package org.starfall.multigateway.ui.providers

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.starfall.multigateway.data.model.LlmProviderInfo

internal class ModelConnectionTests(
    private val scope: CoroutineScope,
    private val connect: suspend (LlmProviderInfo, String) -> Result<String>
) {
    val running = mutableStateMapOf<String, Boolean>()
    val results = mutableStateMapOf<String, Result<String>>()
    private val semaphore = Semaphore(4)

    fun test(provider: LlmProviderInfo, modelId: String) {
        if (!scope.isActive || running[modelId] == true) return
        running[modelId] = true
        results.remove(modelId)
        scope.launch {
            try {
                results[modelId] = semaphore.withPermit {
                    try { connect(provider, modelId)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { Result.failure(e) }
                }
            } finally { running[modelId] = false }
        }
    }

    fun forget(ids: Collection<String>) {
        ids.forEach { running.remove(it); results.remove(it) }
    }
}
