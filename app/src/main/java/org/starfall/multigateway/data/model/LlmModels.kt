package org.starfall.multigateway.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ProviderType(val displayName: String, val defaultName: String, val defaultBaseUrl: String) {
    @SerialName("openai")
    OPENAI("OpenAI Chat Completions", "", ""),
    @SerialName("openai_responses")
    OPENAI_RESPONSES("OpenAI Responses API", "OpenAI Responses", "https://api.openai.com/v1"),
    @SerialName("anthropic")
    ANTHROPIC("Anthropic Messages API", "Anthropic", "https://api.anthropic.com/v1"),
    @SerialName("google")
    GOOGLE("Google Gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
    @SerialName("ollama")
    OLLAMA("Ollama Cloud", "Ollama Cloud", "https://ollama.com/api"),
    @SerialName("antigravity")
    ANTIGRAVITY("Antigravity", "Antigravity", "https://daily-cloudcode-pa.sandbox.googleapis.com"),
    @SerialName("github_copilot")
    GITHUB_COPILOT("GitHub Copilot", "GitHub Copilot", "https://api.githubcopilot.com"),
    @SerialName("openai_codex")
    OPENAI_CODEX("OpenAI Codex", "OpenAI Codex", "https://chatgpt.com/backend-api/codex/responses"),
    @SerialName("claude_code")
    CLAUDE_CODE("Claude Code", "Claude Code", "https://api.anthropic.com/v1");

    val isAccountProvider: Boolean get() = this in setOf(OPENAI_CODEX, CLAUDE_CODE, ANTIGRAVITY, GITHUB_COPILOT)

    val isOpenAi: Boolean get() = this == OPENAI || this == OPENAI_RESPONSES
}

/** Update each default field independently; custom provider details survive type changes. */
fun LlmProviderInfo.withType(newType: ProviderType): LlmProviderInfo = copy(
    type = newType,
    name = if (name == type.defaultName) newType.defaultName else name,
    baseUrl = if (baseUrl.trimEnd('/') == type.defaultBaseUrl) newType.defaultBaseUrl else baseUrl
)

enum class AuthMethod {
    @SerialName("platform_default")
    PLATFORM_DEFAULT,
    @SerialName("none")
    NONE,
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
    val method: AuthMethod = AuthMethod.PLATFORM_DEFAULT,
    val key: String? = null,
    val value: String? = null
) {
    // OAuth stores only an opaque adapter marker here; real tokens never enter provider config.
    val token: String
        get() = when {
            method in listOf(AuthMethod.OAUTH, AuthMethod.NONE) -> ""
            value != null -> value
            method in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM) -> ""
            else -> key.orEmpty() // Legacy API keys were stored in this field.
        }
}

fun ProviderType.defaultAuthorization(): Authorization = when (this) {
    ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES, ProviderType.ANTHROPIC, ProviderType.GOOGLE ->
        Authorization(method = AuthMethod.PLATFORM_DEFAULT, value = "")

    ProviderType.OPENAI_CODEX, ProviderType.CLAUDE_CODE, ProviderType.ANTIGRAVITY, ProviderType.GITHUB_COPILOT ->
        Authorization(method = AuthMethod.OAUTH, value = "")

    ProviderType.OLLAMA ->
        Authorization(method = AuthMethod.BEARER_TOKEN, key = "Authorization", value = "")
}

/** Preserve serialized legacy methods while presenting the unified choices. */
fun Authorization.editMethod(): AuthMethod = when (method) {
    AuthMethod.CUSTOM_HEADER -> AuthMethod.BEARER_TOKEN
    AuthMethod.OTHER -> if (token.isBlank()) AuthMethod.NONE else AuthMethod.BEARER_TOKEN
    else -> method
}

fun bearerHeaderValue(headerName: String, value: String): String {
    val trimmed = value.trim()
    return if (headerName.equals("Authorization", ignoreCase = true) &&
        trimmed.isNotEmpty() && !Regex("^Bearer(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    ) "Bearer $trimmed" else trimmed
}

/** Empty credentials omit authentication; the remote endpoint decides whether it is required. */
fun Authorization.requestHeaders(type: ProviderType): Map<String, String> = when (method) {
    AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER -> {
        val name = key?.trim()?.takeIf { it.isNotBlank() } ?: "Authorization"
        token.takeIf { it.isNotBlank() }?.let { mapOf(name to bearerHeaderValue(name, it)) } ?: emptyMap()
    }
    AuthMethod.PLATFORM_DEFAULT, AuthMethod.OTHER -> token.takeIf { it.isNotBlank() }?.let {
        when (type) {
            ProviderType.GOOGLE -> mapOf("x-goog-api-key" to it)
            ProviderType.ANTHROPIC -> mapOf("x-api-key" to it)
            else -> mapOf("Authorization" to bearerHeaderValue("Authorization", it))
        }
    } ?: emptyMap()
    AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE -> emptyMap()
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
    val sortOrder: Int = Int.MAX_VALUE,
    val groupId: String? = null
)

data class ProviderGroup(
    val id: String,
    val name: String,
    val sortOrder: Int = Int.MAX_VALUE
)


data class ProviderRootOrderItem(
    val id: String,
    val isGroup: Boolean
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
    @SerialName("send_thinking_content") val sendThinkingContent: Boolean = false,
    val icon: String? = null
)

fun LlmProviderInfo.streamEnabledFor(modelId: String): Boolean =
    config.modelConfigs[modelId]?.supportStream ?: config.supportStream

/** null lets the endpoint choose; "none" explicitly disables reasoning. */
val ModelConfiguration.reasoningDisabled: Boolean
    get() = reasoningEffort in listOf("none", "off")

fun ModelConfiguration.withConversationReasoning(effort: String?): ModelConfiguration =
    if (effort.isNullOrBlank()) this else copy(reasoningEffort = if (effort == "off") "none" else effort)


// Keep Claude budgets inside its output token limit.
fun ModelConfiguration.reasoningBudget(maxTokens: Int): Int =
    minOf(when (reasoningEffort) { "low" -> 1024; "medium" -> 2048; "high" -> 4096; "xhigh" -> 8192; else -> 1024 }, maxTokens - 1)

fun ModelConfiguration.googleThinkingConfig(): kotlinx.serialization.json.JsonObject? =
    when {
        reasoningDisabled -> kotlinx.serialization.json.buildJsonObject {
            put("thinkingBudget", kotlinx.serialization.json.JsonPrimitive(0))
            put("includeThoughts", kotlinx.serialization.json.JsonPrimitive(false))
        }
        reasoningEffort != null -> kotlinx.serialization.json.buildJsonObject {
            put("thinkingLevel", kotlinx.serialization.json.JsonPrimitive(if (reasoningEffort == "xhigh") "high" else reasoningEffort))
            put("includeThoughts", kotlinx.serialization.json.JsonPrimitive(true))
        }
        else -> null
    }
