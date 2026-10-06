package org.starfall.multigateway.data.adapter.common

import java.util.Base64
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.adapter.AccountProviderAdapter
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.*

/** Shared PKCE and encrypted persistence; protocol details stay in each adapter. */
internal abstract class OAuthAccountAdapter(
    context: Context,
    private val attachments: AttachmentResolver,
    final override val providerType: ProviderType,
    private val clientId: String,
    private val authorizationUrl: String,
    private val tokenUrl: String,
    private val scope: String,
    private val callbackPort: Int,
    private val callbackPath: String,
    private val clientSecret: String? = null,
    private val jsonTokens: Boolean = false,
    private val callbackHost: String = "localhost"
) : AccountProviderAdapter {
    protected val appContext = context.applicationContext
    protected val http = ToolHttp()
    protected val store = AccountTokenStore(context, providerType.name.lowercase())
    private val refreshLock = Mutex()
    private val redirectUri get() = "http://$callbackHost:$callbackPort$callbackPath"
    protected val marker get() = "account:${providerType.name.lowercase()}:v1"

    protected suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    protected open suspend fun openBrowser(url: String) = withContext(Dispatchers.Main) {
        OAuthBrowser.open(appContext, url)
    }

    override suspend fun authorize(provider: LlmProviderInfo): Result<LlmProviderInfo> = result {
        require(provider.type == providerType)
        require(clientId.isNotBlank() && (clientSecret == null || clientSecret.isNotBlank())) {
            "${providerType.displayName} OAuth client configuration is missing from this build."
        }
        val extra = if (jsonTokens) mapOf("code" to "true")
            else mapOf("access_type" to "offline", "prompt" to "consent")
        val request = AppAuthTransactions.authorization(authorizationUrl, tokenUrl, clientId, redirectUri, scope, extra)
        OAuthCallbackService.keepAlive(appContext) {
            val code = awaitCode(request.toUri().toString(), request.state!!)
            val token = enrichToken(exchange(mapOf("grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to redirectUri, "code_verifier" to request.codeVerifier!!, "state" to request.state!!)))
            withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, token) }
            authorized(provider, token)
        }
    }

    protected fun authorized(provider: LlmProviderInfo, token: AccountTokenState) = provider.copy(
        auth = Authorization(AuthMethod.OAUTH, key = token.email, value = marker, oauthAccountId = provider.auth.oauthAccountId))

    protected open suspend fun enrichToken(token: AccountTokenState): AccountTokenState = token

    protected suspend fun ensureToken(provider: LlmProviderInfo): AccountTokenState = refreshLock.withLock {
        require(provider.type == providerType && provider.auth.method == AuthMethod.OAUTH && provider.auth.value == marker) {
            "${providerType.displayName} is not authorized. Sign in from Provider settings."
        }
        val current = store.load(provider.oauthCredentialId) ?: error("Credentials are missing. Sign in again.")
        if (current.expiresAt != null && current.expiresAt <= System.currentTimeMillis() + 60_000) {
            require(current.refreshToken.isNotBlank()) { "Authorization expired. Sign in again." }
            exchange(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken), current)
                .also { store.save(provider.oauthCredentialId, it) }
        } else current
    }

    private suspend fun exchange(fields: Map<String, String>, previous: AccountTokenState? = null): AccountTokenState {
        val payload = AppAuthTransactions.exchange(http, AppAuthTransactions.token(tokenUrl, clientId, fields),
            AppAuthTransactions.authentication(if (clientSecret == null) "none" else "client_secret_post", clientSecret),
            jsonBody = jsonTokens)
        return accountTokenFromResponse(payload, previous)
    }

    private suspend fun awaitCode(url: String, state: String): String =
        awaitOAuthAuthorizationCode(redirectUri, url, state, ::openBrowser)

    override suspend fun testConnection(provider: LlmProviderInfo): Result<String> = result {
        check(fetchModels(provider).isNotEmpty()) { "Provider returned no models" }
        "${providerType.displayName} connection is ready."
    }
    override fun clearCredentials(providerId: String) = store.delete(providerId)

    override fun streamEvents(provider: LlmProviderInfo, modelName: String, messages: List<StoredMessage>,
        systemPrompt: String, maxOutputTokens: Int): Flow<GenerationEvent> = channelFlow {
        val wire = prepareModelProvider(provider, modelName)
        val body = chatBody(wire.type, modelName, messages, systemPrompt, maxOutputTokens, provider.config.modelConfigs[modelName] ?: ModelConfiguration())
        val base = providerBase(wire)
        val url = when (wire.type) {
            ProviderType.ANTHROPIC -> base.removeSuffix("/v1") + "/v1/messages"
            ProviderType.GOOGLE -> "$base/models/$modelName:generateContent"
            ProviderType.OPENAI_RESPONSES -> "$base/responses"
            else -> "$base/chat/completions"
        }
        val response = http.modelResponse(requestUrl(provider, wire, url),
            normalizeToolRequest(provider, wire, body, systemPrompt), prepareRequestProvider(provider, wire, body), provider.streamEnabledFor(modelName),
            { send(GenerationEvent.Text(it)) }, { send(GenerationEvent.Reasoning(it)) }, ::unwrapResponse)
        if (provider.streamEnabledFor(modelName) && wire.type == ProviderType.ANTHROPIC) {
            (response["content"] as? JsonArray).orEmpty().forEach {
                val part = it.jsonObject
                part.text("signature").takeIf(String::isNotBlank)?.let { signature ->
                    send(GenerationEvent.Reasoning("", signature))
                }
            }
        }
        if (!provider.streamEnabledFor(modelName)) {
            when (wire.type) {
                ProviderType.ANTHROPIC -> (response["content"] as? JsonArray).orEmpty().forEach {
                    val part = it.jsonObject
                    if (part.text("type") == "thinking") send(GenerationEvent.Reasoning(part.text("thinking"), part.text("signature").takeIf(String::isNotBlank)))
                    else if (part.text("type") == "text") send(GenerationEvent.Text(part.text("text")))
                }
                ProviderType.GOOGLE -> (response["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty().forEach {
                        val part = it.jsonObject
                        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) send(GenerationEvent.Reasoning(part.text("text")))
                        else send(GenerationEvent.Text(part.text("text")))
                    }
                ProviderType.OPENAI_RESPONSES -> (response["output"] as? JsonArray).orEmpty().forEach { item ->
                    if (item.jsonObject.text("type") == "reasoning")
                        (item.jsonObject["summary"] as? JsonArray).orEmpty().forEach {
                            send(GenerationEvent.Reasoning(it.jsonObject.text("text")))
                        }
                    (item.jsonObject["content"] as? JsonArray).orEmpty().forEach { part ->
                        if (part.jsonObject.text("type") == "output_text") send(GenerationEvent.Text(part.jsonObject.text("text")))
                    }
                }
                else -> (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.let {
                    if (it.text("reasoning_content").isNotEmpty()) send(GenerationEvent.Reasoning(it.text("reasoning_content")))
                    send(GenerationEvent.Text(it.text("content")))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun chatBody(type: ProviderType, model: String, messages: List<StoredMessage>, prompt: String, maxTokens: Int, config: ModelConfiguration): JsonObject {
        fun parts(message: StoredMessage): JsonArray = buildJsonArray {
            if (message.content.isNotBlank()) add(if (type == ProviderType.ANTHROPIC) obj("type" to str("text"), "text" to str(message.content)) else obj("text" to str(message.content)))
            message.files.forEach { reference ->
                val meta = attachments.metadata(reference) ?: error("Attachment metadata is missing")
                val bytes = attachments.readBytes(reference) ?: error("Attachment data is missing")
                val encoded = Base64.getEncoder().encodeToString(bytes)
                when (type) {
                    ProviderType.GOOGLE -> add(obj("inlineData" to obj("mimeType" to str(meta.mimeType), "data" to str(encoded))))
                    ProviderType.ANTHROPIC -> {
                        require(meta.mimeType.startsWith("image/") || meta.mimeType == "application/pdf") { "Unsupported Claude attachment" }
                        add(obj("type" to str(if (meta.mimeType == "application/pdf") "document" else "image"),
                            "source" to obj("type" to str("base64"), "media_type" to str(meta.mimeType), "data" to str(encoded))))
                    }
                    else -> { require(meta.mimeType.startsWith("image/")) { "Unsupported Copilot attachment" }
                        add(obj("type" to str("image_url"), "image_url" to obj("url" to str("data:${meta.mimeType};base64,$encoded")))) }
                }
            }
        }
        val body = buildJsonObject {
            if (type == ProviderType.GOOGLE) {
                put("model", model)
                put("systemInstruction", obj("parts" to JsonArray(listOf(obj("text" to str(prompt))))))
                put("contents", JsonArray(messages.filter { it.role != ChatRole.SYSTEM }.map {
                    obj("role" to str(if (it.role == ChatRole.MODEL) "model" else "user"), "parts" to parts(it)) }))
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", maxTokens); config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("topP", it) }; config.topK?.let { put("topK", it) }
                    config.googleThinkingConfig()?.let { put("thinkingConfig", it) }
                })
            } else {
                put("model", model); put("max_tokens", maxTokens)
                if (type != ProviderType.ANTHROPIC || (!config.supportsThinking || config.reasoningEffort == null || config.reasoningDisabled) || maxTokens <= 1024) {
                    config.temperature?.let { put("temperature", it) }; config.topP?.let { put("top_p", it) }
                }
                if (type == ProviderType.ANTHROPIC) config.topK?.let { put("top_k", it) }
                if (type == ProviderType.ANTHROPIC) {
                    put("system", prompt)
                    if (config.reasoningDisabled) put("thinking", obj("type" to str("disabled")))
                    if (config.supportsThinking && config.reasoningEffort != null && !config.reasoningDisabled && maxTokens > 1024) {
                        put("thinking", obj("type" to str("enabled"), "budget_tokens" to JsonPrimitive(config.reasoningBudget(maxTokens))))
                    }
                }
                if (type == ProviderType.OPENAI && config.supportsThinking)
                    config.reasoningEffort?.let { put("reasoning_effort", it) }
                val native = messages.filter { it.role != ChatRole.SYSTEM }.map {
                    val content = if (type == ProviderType.ANTHROPIC) JsonArray(
                        (if (it.role == ChatRole.MODEL && config.sendThinkingContent &&
                            !it.reasoningContent.isNullOrBlank() && !it.reasoningSignature.isNullOrBlank())
                            listOf(obj("type" to str("thinking"), "thinking" to str(it.reasoningContent!!),
                                "signature" to str(it.reasoningSignature!!))) else emptyList()) + parts(it)
                    ) else buildJsonArray {
                        add(obj("type" to str("text"), "text" to str(it.content))); parts(it).filter { p -> p.jsonObject["type"] != null }.forEach { add(it) }
                    }
                    obj("role" to str(if (it.role == ChatRole.MODEL) "assistant" else "user"), "content" to content)
                }
                put("messages", JsonArray((if (type != ProviderType.ANTHROPIC && prompt.isNotBlank()) listOf(obj("role" to str("system"), "content" to str(prompt))) else emptyList()) + native))
            }
        }
        return if (type == ProviderType.OPENAI_RESPONSES) JsonObject(body.filterKeys { it != "messages" && it != "max_tokens" } +
            mapOf("input" to JsonArray(body.getValue("messages").jsonArray.map { message ->
                val item = message.jsonObject
                val content = item["content"]
                JsonObject(item + ("content" to if (content is JsonArray) JsonArray(content.map { part ->
                    val value = part.jsonObject
                    if (value.text("type") == "image_url") obj("type" to str("input_image"), "image_url" to value.getValue("image_url").jsonObject.getValue("url"))
                    else obj("type" to str(if (item.text("role") == "assistant") "output_text" else "input_text"), "text" to value.getValue("text"))
                }) else content!!))
            }), "max_output_tokens" to JsonPrimitive(maxTokens)) +
            (if (config.supportsThinking && config.reasoningEffort != null)
                mapOf("reasoning" to obj("effort" to str(config.reasoningEffort))) else emptyMap())) else body
    }


}
