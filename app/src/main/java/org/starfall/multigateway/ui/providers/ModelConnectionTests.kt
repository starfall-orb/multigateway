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

    fun test(provider: LlmProviderInfo, modelId: String) {
        if (!scope.isActive || batchRunning.value || running[modelId] == true) return
        running[modelId] = true
        results.remove(modelId)
        scope.launch {
            try {
                results[modelId] = manualSemaphore.withPermit {
                    connectSafely(provider, modelId)
                }
            } finally {
                running[modelId] = false
            }
        }
    }

    fun testAll(provider: LlmProviderInfo, modelIds: Collection<String>) {
        if (!scope.isActive || batchRunning.value || running.values.any { it }) return
        val ids = modelIds.distinct()
        if (ids.isEmpty()) return

        batchRunning.value = true
        ids.forEach(results::remove)
        scope.launch {
            try {
                for (modelId in ids) {
                    if (!isActive) break
                    running[modelId] = true
                    try {
                        // Intentionally one request at a time to avoid provider rate-limit bursts.
                        results[modelId] = connectSafely(provider, modelId)
                    } finally {
                        running[modelId] = false
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
            running.remove(it)
            results.remove(it)
        }
    }
}
