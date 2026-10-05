package org.starfall.multigateway.ui.configuration

import kotlinx.coroutines.*
import org.starfall.multigateway.data.model.LlmProviderInfo

/** An OAuth request belongs to the ViewModel, so browser/activity transitions cannot discard it. */
internal class OAuthAuthorizations(
    private val scope: CoroutineScope,
    private val authorizeAccount: suspend (LlmProviderInfo) -> Result<LlmProviderInfo>
) {
    private val active = mutableMapOf<String, Deferred<Result<LlmProviderInfo>>>()

    private fun key(provider: LlmProviderInfo) = "${provider.id}:${provider.type}"

    suspend fun authorize(provider: LlmProviderInfo): Result<LlmProviderInfo> {
        val key = key(provider)
        val pending = synchronized(active) {
            active[key] ?: scope.async(start = CoroutineStart.LAZY) {
                authorizeAccount(provider)
            }.also { request ->
                active[key] = request
                request.invokeOnCompletion {
                    synchronized(active) { active.remove(key, request) }
                }
            }
        }
        return pending.await()
    }
    fun cancel(provider: LlmProviderInfo) {
        val request = synchronized(active) { active[key(provider)] }
        request?.cancel(CancellationException("OAuth authorization cancelled by user"))
    }

}
