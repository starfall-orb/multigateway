package org.starfall.multigateway.ui.providers

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.starfall.multigateway.data.model.LlmProviderInfo

internal class ModelConnectionTests(
    private val scope: CoroutineScope,
    private val connect: suspend (LlmProviderInfo, String) -> Result<String>
) {
    val running = mutableStateMapOf<String, Boolean>()
    val results = mutableStateMapOf<String, Result<String>>()
    val batchRunning = mutableStateOf(false)

    // Manual per-model tests may run in parallel, but Test All is deliberately serialized.
    private val manualSemaphore = Semaphore(4)
    private val revisions = mutableMapOf<String, Long>()
    private fun revision(id: String): Long = revisions[id] ?: 0L

    fun test(provider: LlmProviderInfo, modelId: String) {
        if (!scope.isActive || batchRunning.value || running[modelId] == true) return
        running[modelId] = true
        val runRevision = revision(modelId)
        results.remove(modelId)
        scope.launch {
            try {
                val result = manualSemaphore.withPermit {
                    connectSafely(provider, modelId)
                }
                if (revision(modelId) == runRevision) results[modelId] = result
            } finally {
                if (revision(modelId) == runRevision) running[modelId] = false
            }
        }
    }

    fun testAll(provider: LlmProviderInfo, modelIds: Collection<String>) {
        if (!scope.isActive || batchRunning.value || running.values.any { it }) return
        val ids = modelIds.distinct()
        val batchRevisions = ids.associateWith(::revision)
        if (ids.isEmpty()) return

        batchRunning.value = true
        ids.forEach(results::remove)
        scope.launch {
            try {
                for (modelId in ids) {
                    if (!isActive) break
                    if (revision(modelId) != batchRevisions.getValue(modelId)) continue
                    running[modelId] = true
                    try {
                        // Intentionally one request at a time to avoid provider rate-limit bursts.
                        val result = connectSafely(provider, modelId)
                        if (revision(modelId) == batchRevisions.getValue(modelId)) results[modelId] = result
                    } finally {
                        if (revision(modelId) == batchRevisions.getValue(modelId)) running[modelId] = false
                    }
                }
            } finally {
                batchRunning.value = false
            }
        }
    }

    private suspend fun connectSafely(provider: LlmProviderInfo, modelId: String): Result<String> =
        try {
            connect(provider, modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    fun forget(ids: Collection<String>) {
        ids.forEach {
            revisions[it] = revision(it) + 1
            running.remove(it)
            results.remove(it)
        }
    }
}
