package org.starfall.multigateway.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ProviderType(val displayName: String, val defaultName: String, val defaultBaseUrl: String) {
    @SerialName("openai")
    OPENAI("Chat Completions", "OpenAI", "https://api.openai.com/v1"),
    @SerialName("openai_responses")
    OPENAI_RESPONSES("OpenAI Responses", "OpenAI Responses", "https://api.openai.com/v1"),
    @SerialName("openai_codex")
    OPENAI_CODEX("OpenAI Codex", "OpenAI Codex", "https://chatgpt.com/backend-api/codex/responses"),
    @SerialName("google")
    GOOGLE("Google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
    @SerialName("anthropic")
    ANTHROPIC("Anthropic", "Anthropic", "https://api.anthropic.com/v1"),
    @SerialName("ollama")
    OLLAMA("Ollama", "Ollama", "https://ollama.com/api");

    val isOpenAi: Boolean get() = this == OPENAI || this == OPENAI_RESPONSES
}

/** Update each default field independently; custom provider details survive type changes. */
fun LlmProviderInfo.withType(newType: ProviderType): LlmProviderInfo = copy(
    type = newType,
    name = if (name == type.defaultName) newType.defaultName else name,
    baseUrl = if (baseUrl.trimEnd('/') == type.defaultBaseUrl) newType.defaultBaseUrl else baseUrl
)

enum class AuthMethod {
    @SerialName("query_param")
    QUERY_PARAM,
    @SerialName("bearer_token")
    BEARER_TOKEN,
    @SerialName("custom_header")
    CUSTOM_HEADER,
    @SerialName("oauth")
    OAUTH,
    @SerialName("other")
    OTHER
}

@Serializable
data class Authorization(
    val method: AuthMethod = AuthMethod.BEARER_TOKEN,
    val key: String? = null,
    val value: String? = null
) {
    // OAuth stores only an opaque adapter marker here; real tokens never enter provider config.
    val token: String
        get() = if (method == AuthMethod.OAUTH) "" else value?.takeIf { it.isNotBlank() } ?: key.orEmpty()
}

fun ProviderType.defaultAuthorization(): Authorization = when (this) {
    ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES, ProviderType.ANTHROPIC ->
        Authorization(method = AuthMethod.BEARER_TOKEN, value = "")

    ProviderType.GOOGLE ->
        Authorization(method = AuthMethod.QUERY_PARAM, key = "key", value = "")

    ProviderType.OPENAI_CODEX ->
        Authorization(method = AuthMethod.OAUTH, value = "")

    ProviderType.OLLAMA ->
        Authorization(method = AuthMethod.OTHER, value = "")
}

@Serializable
data class ProviderConfiguration(
    val httpProxy: Map<String, String> = emptyMap(),
    val socksProxy: Map<String, String> = emptyMap(),
    val supportStream: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val responsesApi: Boolean = false,
    val customListModelsUrl: String? = null,
    val maxTokens: Int = 4000,
    // Model IDs are scoped to this provider, including custom gateway models.
    val modelConfigs: Map<String, ModelConfiguration> = emptyMap(),
    // null preserves legacy discovery; an empty list explicitly selects no models.
    val modelIds: List<String>? = null
)


/** Ordering never removes a selected model or replaces its configuration. */
internal fun ProviderConfiguration.reorderedModels(requestedIds: List<String>): ProviderConfiguration {
    val existingIds = modelIds ?: modelConfigs.keys.toList()
    val existingSet = existingIds.toSet()
    val preferred = requestedIds.filter { it in existingSet }.distinct()
    val preferredSet = preferred.toSet()
    val ordered = preferred + existingIds.filterNot { it in preferredSet }
    val configOrder = ordered.filter { it in modelConfigs } + modelConfigs.keys.filterNot { it in ordered }
    return copy(modelIds = ordered, modelConfigs = configOrder.associateWith { modelConfigs.getValue(it) })
}

@Serializable
data class LlmProviderInfo(
    val id: String,
    val name: String,
    val type: ProviderType,
    val auth: Authorization = Authorization(),
    val icon: String? = null,
    val baseUrl: String,
    val config: ProviderConfiguration = ProviderConfiguration(),
    val sortOrder: Int = Int.MAX_VALUE
)

@Serializable
data class Capabilities(
    val text: Boolean = true,
    val image: Boolean = false,
    val video: Boolean = false,
    val embed: Boolean = false,
    val audio: Boolean = false,
    val others: String? = null
)

@Serializable
data class LlmModel(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val icon: String? = null,
    @SerialName("provider_id") val providerId: String,
    @SerialName("input_capabilities") val inputCapabilities: Capabilities = Capabilities(),
    @SerialName("output_capabilities") val outputCapabilities: Capabilities = Capabilities()
)

@Serializable
data class LlmProviderModels(
    val id: String,
    val models: List<LlmModel> = emptyList()
)

@Serializable
enum class ModelType(val displayName: String) {
    TEXT_GENERATION("Text generation"),
    IMAGE_GENERATION("Image generation"),
    VIDEO_GENERATION("Video generation"),
    EMBEDDING("Embedding"),
    RERANKING("Reranking"),
    SPEECH_TO_TEXT("Speech to text"),
    TEXT_TO_SPEECH("Text to speech")
}

@Serializable
data class ModelConfiguration(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    // null inherits the provider setting; false is an explicit override.
    val supportStream: Boolean? = null,
    val displayName: String = "",
    val modelType: ModelType = ModelType.TEXT_GENERATION,
    // Image input capability. Kept as supportsVision for backward compatibility with saved configs.
    val supportsVision: Boolean = true,
    val supportsVideoInput: Boolean = false,
    val supportsAudioInput: Boolean = false,
    val supportsThinking: Boolean = true,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val supportsToolCalls: Boolean = true,
    @SerialName("send_thinking_content") val sendThinkingContent: Boolean = false
)

fun LlmProviderInfo.streamEnabledFor(modelId: String): Boolean =
    config.modelConfigs[modelId]?.supportStream ?: config.supportStream
