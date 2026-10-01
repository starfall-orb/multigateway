package org.starfall.multigateway.data.adapter

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import org.starfall.multigateway.data.model.GenerationEvent
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.StoredMessage

/**
 * Boundary for account-backed providers that do not use a normal API key.
 *
 * Keep provider-specific OAuth, token storage and wire behavior behind this
 * interface so removing an adapter only requires removing its registry entry
 * and ProviderType dispatch.
 */
internal interface AccountProviderAdapter {
    val providerType: ProviderType

    suspend fun authorize(provider: LlmProviderInfo): Result<LlmProviderInfo>
    suspend fun fetchModels(provider: LlmProviderInfo): List<String>
    suspend fun testConnection(provider: LlmProviderInfo): Result<String>
    suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo

    suspend fun prepareModelProvider(provider: LlmProviderInfo, modelName: String): LlmProviderInfo =
        prepareAuthenticatedProvider(provider)

    fun normalizeToolRequest(
        sourceProvider: LlmProviderInfo,
        wireProvider: LlmProviderInfo,
        body: JsonObject,
        systemPrompt: String
    ): JsonObject = body

    fun streamEvents(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        maxOutputTokens: Int
    ): Flow<GenerationEvent>

    fun prepareRequestProvider(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo, body: JsonObject): LlmProviderInfo = wireProvider

    fun requestUrl(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo, defaultUrl: String): String = defaultUrl

    fun unwrapResponse(response: JsonObject): JsonObject = response

    fun clearCredentials(providerId: String)
}
