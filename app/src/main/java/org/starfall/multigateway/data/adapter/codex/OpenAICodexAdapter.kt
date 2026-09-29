package org.starfall.multigateway.data.adapter.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starfall.multigateway.data.adapter.AccountProviderAdapter
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.Authorization
import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.data.model.GenerationEvent
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.StoredMessage
import org.starfall.multigateway.data.service.AttachmentResolver

/**
 * Self-contained ChatGPT Codex account adapter.
 *
 * This module owns OAuth, refresh-token rotation, encrypted token persistence,
 * request normalization and Codex wire headers. Core code only dispatches
 * ProviderType.OPENAI_CODEX here.
 */
internal class OpenAICodexAdapter(
    context: Context,
    private val attachments: AttachmentResolver
) : AccountProviderAdapter {
    override val providerType: ProviderType = ProviderType.OPENAI_CODEX

    private val appContext = context.applicationContext
    private val tokenStore = CodexTokenStore(appContext)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = HttpClient(CIO)

    override suspend fun authorize(provider: LlmProviderInfo): Result<LlmProviderInfo> = runCatching {
        require(provider.type == ProviderType.OPENAI_CODEX) { "Provider is not OpenAI Codex." }

        val verifierBytes = ByteArray(96).also(SecureRandom()::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        )
        val state = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        val authorizationUrl = Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", AUTH_SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "login")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .build()
            .toString()

        val code = awaitAuthorizationCode(authorizationUrl, state)
        val exchanged = exchangeAuthorizationCode(code, verifier)
        tokenStore.save(provider.id, exchanged)

        provider.copy(
            name = provider.name.ifBlank { ProviderType.OPENAI_CODEX.defaultName },
            baseUrl = provider.baseUrl.ifBlank { ProviderType.OPENAI_CODEX.defaultBaseUrl },
            auth = Authorization(
                method = AuthMethod.OAUTH,
                key = exchanged.email ?: exchanged.accountId,
                value = AUTH_MARKER
            )
        )
    }

    override suspend fun fetchModels(provider: LlmProviderInfo): List<String> {
        ensureToken(provider)
        return SUPPORTED_MODELS
    }

    override suspend fun testConnection(provider: LlmProviderInfo): Result<String> = runCatching {
        val token = ensureToken(provider)
        val identity = token.email ?: token.accountId
        if (identity.isNullOrBlank()) "OpenAI Codex authorization is ready."
        else "OpenAI Codex authorized as $identity."
    }

    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo {
        val token = ensureToken(provider)
        val sessionId = UUID.randomUUID().toString()
        val headers = linkedMapOf(
            "User-Agent" to CODEX_USER_AGENT,
            "Connection" to "Keep-Alive",
            "Originator" to "codex-tui",
            "Session_id" to sessionId,
            "Conversation_id" to sessionId
        )
        token.accountId?.takeIf { it.isNotBlank() }?.let {
            headers["Chatgpt-Account-Id"] = it
        }
        headers.putAll(provider.config.headers)

        return provider.copy(
            type = ProviderType.OPENAI_RESPONSES,
            baseUrl = provider.baseUrl.ifBlank { ProviderType.OPENAI_CODEX.defaultBaseUrl },
            auth = Authorization(AuthMethod.BEARER_TOKEN, value = token.accessToken),
            config = provider.config.copy(headers = headers)
        )
    }

    override fun normalizeToolRequest(
        sourceProvider: LlmProviderInfo,
        wireProvider: LlmProviderInfo,
        body: JsonObject,
        systemPrompt: String
    ): JsonObject {
        val sessionId = wireProvider.config.headers["Session_id"] ?: UUID.randomUUID().toString()
        val values = body.toMutableMap()
        listOf(
            "user",
            "metadata",
            "prompt_cache_retention",
            "safety_identifier",
            "stream_options",
            "max_output_tokens",
            "max_completion_tokens",
            "temperature",
            "top_p",
            "frequency_penalty",
            "presence_penalty",
            "previous_response_id"
        ).forEach(values::remove)
        values["instructions"] = JsonPrimitive(
            systemPrompt.takeIf { it.isNotBlank() }
                ?: "You are a coding assistant. Follow the user's request accurately and concisely."
        )
        (values["input"] as? JsonArray)?.let { input ->
            values["input"] = JsonArray(input.filterNot { item ->
                (item as? JsonObject)?.get("role")?.jsonPrimitive?.contentOrNull == "system"
            })
        }
        values["store"] = JsonPrimitive(false)
        values["prompt_cache_key"] = JsonPrimitive(sessionId)

        val include = (values["include"] as? JsonArray).orEmpty().toMutableList()
        if (include.none { it.jsonPrimitive.contentOrNull == "reasoning.encrypted_content" }) {
            include += JsonPrimitive("reasoning.encrypted_content")
        }
        values["include"] = JsonArray(include)

        (values["reasoning"] as? JsonObject)?.let { reasoning ->
            if (reasoning["summary"] == null) {
                values["reasoning"] = JsonObject(reasoning + ("summary" to JsonPrimitive("auto")))
            }
        }
        return JsonObject(values)
    }

    override fun streamEvents(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        maxOutputTokens: Int
    ): Flow<GenerationEvent> = flow {
        val token = ensureToken(provider)
        val sessionId = UUID.randomUUID().toString()
        val modelConfig = provider.config.modelConfigs[modelName]
        val request = buildRequest(
            modelName = modelName,
            messages = messages,
            systemPrompt = systemPrompt,
            reasoningEffort = modelConfig?.reasoningEffort,
            stream = provider.config.supportStream,
            sessionId = sessionId
        )

        val response = http.post(resolveEndpoint(provider.baseUrl)) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${token.accessToken}")
            header("User-Agent", CODEX_USER_AGENT)
            header("Connection", "Keep-Alive")
            header("Originator", "codex-tui")
            header("Session_id", sessionId)
            header("Conversation_id", sessionId)
            token.accountId?.takeIf { it.isNotBlank() }?.let {
                header("Chatgpt-Account-Id", it)
            }
            provider.config.headers.forEach { (name, value) -> header(name, value) }
            setBody(request.toString())
        }

        if (!response.status.isSuccess()) {
            error("OpenAI Codex request failed: HTTP ${response.status.value}: ${response.bodyAsText().take(2048)}")
        }

        if (!provider.config.supportStream) {
            emitFinalResponse(json.parseToJsonElement(response.bodyAsText()).jsonObject) { emit(it) }
            return@flow
        }

        val channel = response.bodyAsChannel()
        val eventData = StringBuilder()
        while (!channel.isClosedForRead) {
            currentCoroutineContext().ensureActive()
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data:")) {
                eventData.append(line.substringAfter("data:").trimStart()).append('\n')
            }
            if (line.isBlank() && eventData.isNotEmpty()) {
                val data = eventData.toString().trim()
                eventData.setLength(0)
                if (data == "[DONE]") break
                val event = json.parseToJsonElement(data).jsonObject
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "response.output_text.delta" -> event["delta"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(GenerationEvent.Text(it)) }

                    "response.reasoning_text.delta",
                    "response.reasoning_summary_text.delta" -> event["delta"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(GenerationEvent.Reasoning(it)) }

                    "response.failed", "error" -> error(
                        "OpenAI Codex stream failed: ${event["error"] ?: event}"
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun clearCredentials(providerId: String) {
        tokenStore.delete(providerId)
    }

    private suspend fun ensureToken(provider: LlmProviderInfo): CodexTokenState {
        require(provider.auth.method == AuthMethod.OAUTH && provider.auth.value == AUTH_MARKER) {
            "OpenAI Codex is not authorized. Open OAuth in the browser from Provider settings."
        }
        val current = tokenStore.load(provider.id)
            ?: error("OpenAI Codex credentials are missing. Sign in again.")

        val expiresAt = current.expiresAt
        if (expiresAt == null || expiresAt > System.currentTimeMillis() + REFRESH_BUFFER_MS) {
            return current
        }

        val refreshed = refresh(current)
        tokenStore.save(provider.id, refreshed)
        return refreshed
    }

    private suspend fun exchangeAuthorizationCode(code: String, verifier: String): CodexTokenState {
        val response = http.submitForm(
            url = "$ISSUER/oauth/token",
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", CLIENT_ID)
                append("code", code)
                append("redirect_uri", REDIRECT_URI)
                append("code_verifier", verifier)
            }
        )
        val raw = response.bodyAsText()
        check(response.status.isSuccess()) {
            "OpenAI Codex token exchange failed: HTTP ${response.status.value}: ${raw.take(2048)}"
        }
        return tokenStateFromResponse(raw, previousRefreshToken = null)
    }

    private suspend fun refresh(current: CodexTokenState): CodexTokenState {
        val response = http.submitForm(
            url = "$ISSUER/oauth/token",
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", CLIENT_ID)
                append("refresh_token", current.refreshToken)
                append("scope", REFRESH_SCOPE)
            }
        )
        val raw = response.bodyAsText()
        check(response.status.isSuccess()) {
            "OpenAI Codex token refresh failed: HTTP ${response.status.value}: ${raw.take(2048)}"
        }
        val next = tokenStateFromResponse(raw, previousRefreshToken = current.refreshToken)
        return next.copy(
            accountId = next.accountId ?: current.accountId,
            email = next.email ?: current.email
        )
    }

    private fun tokenStateFromResponse(
        raw: String,
        previousRefreshToken: String?
    ): CodexTokenState {
        val payload = json.parseToJsonElement(raw).jsonObject
        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("OpenAI Codex token response did not include an access token.")
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: previousRefreshToken
            ?: error("OpenAI Codex token response did not include a refresh token.")
        val tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "Bearer"
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val idToken = payload["id_token"]?.jsonPrimitive?.contentOrNull
        val idClaims = parseJwtClaims(idToken)
        val accessClaims = parseJwtClaims(accessToken)
        val accountId = idClaims?.let(::extractAccountId) ?: accessClaims?.let(::extractAccountId)
        val email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: accessClaims?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val jwtExpiresAt = (idClaims ?: accessClaims)
            ?.get("exp")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
            ?.times(1000L)

        return CodexTokenState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000L } ?: jwtExpiresAt,
            accountId = accountId,
            email = email
        )
    }

    private fun parseJwtClaims(token: String?): JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(parts[1])
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private fun extractAccountId(claims: JsonObject): String? {
        claims["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val namespaced = claims["https://api.openai.com/auth"] as? JsonObject
        namespaced?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return (claims["organizations"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun awaitAuthorizationCode(
        authorizationUrl: String,
        expectedState: String
    ): String = withContext(Dispatchers.IO) {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), CALLBACK_PORT))
            server.soTimeout = 1000

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }

            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }

                socket.use {
                    it.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    val firstLine = reader.readLine().orEmpty()
                    val target = firstLine.split(' ').getOrNull(1).orEmpty()
                    val callback = runCatching { Uri.parse("http://localhost$target") }.getOrNull()

                    val callbackState = callback?.getQueryParameter("state")
                    val error = callback?.getQueryParameter("error")
                    val errorDescription = callback?.getQueryParameter("error_description")
                    val code = callback?.getQueryParameter("code")

                    when {
                        !error.isNullOrBlank() -> {
                            sendBrowserResponse(it, false)
                            error(errorDescription ?: error)
                        }
                        callbackState != expectedState -> {
                            sendBrowserResponse(it, false)
                            error("OpenAI Codex OAuth state did not match.")
                        }
                        code.isNullOrBlank() -> {
                            sendBrowserResponse(it, false)
                            error("OpenAI Codex OAuth callback did not include an authorization code.")
                        }
                        else -> {
                            sendBrowserResponse(it, true)
                            return@withContext code
                        }
                    }
                }
            }
            error("OpenAI Codex authorization timed out.")
        }
    }

    private fun sendBrowserResponse(socket: java.net.Socket, success: Boolean) {
        val message = if (success) {
            "Authorization complete. You can return to MultiGateway."
        } else {
            "Authorization failed. Return to MultiGateway and try again."
        }
        val body = "<!doctype html><html><body><h3>$message</h3></body></html>"
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)).use { writer ->
            writer.write("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\n")
            writer.write("Content-Type: text/html; charset=utf-8\r\n")
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private fun buildRequest(
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        reasoningEffort: String?,
        stream: Boolean,
        sessionId: String
    ): JsonObject = buildJsonObject {
        put("model", modelName)
        put(
            "instructions",
            systemPrompt.takeIf { it.isNotBlank() }
                ?: "You are a coding assistant. Follow the user's request accurately and concisely."
        )
        put("input", buildInput(messages))
        put("store", false)
        put("stream", stream)
        put("prompt_cache_key", sessionId)
        put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
        put(
            "reasoning",
            buildJsonObject {
                put("effort", reasoningEffort?.takeIf { it.isNotBlank() } ?: "high")
                put("summary", "auto")
            }
        )
    }

    private fun buildInput(messages: List<StoredMessage>): JsonArray = buildJsonArray {
        messages.forEach { message ->
            val role = if (message.role == ChatRole.MODEL) "assistant" else "user"
            add(
                buildJsonObject {
                    put("role", role)
                    put("content", buildJsonArray {
                        if (message.content.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", if (role == "assistant") "output_text" else "input_text")
                                put("text", message.content)
                            })
                        }
                        if (role == "user") {
                            message.files.forEach fileLoop@{ reference ->
                                val metadata = attachments.metadata(reference) ?: return@fileLoop
                                if (!metadata.isImage) return@fileLoop
                                val bytes = attachments.readBytes(reference) ?: return@fileLoop
                                val data = Base64.getEncoder().encodeToString(bytes)
                                add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:${metadata.mimeType};base64,$data")
                                })
                            }
                        }
                    })
                }
            )
        }
    }

    private suspend fun emitFinalResponse(
        response: JsonObject,
        emit: suspend (GenerationEvent) -> Unit
    ) {
        val responseError = response["error"]
        if (responseError != null && responseError !is JsonNull) {
            error("OpenAI Codex response failed: $responseError")
        }

        (response["output"] as? JsonArray).orEmpty().forEach outputLoop@{ itemElement ->
            val item = itemElement as? JsonObject ?: return@outputLoop
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> (item["content"] as? JsonArray).orEmpty().forEach contentLoop@{ contentElement ->
                    val part = contentElement as? JsonObject ?: return@contentLoop
                    if (part["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        part["text"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(GenerationEvent.Text(it)) }
                    }
                }
                "reasoning" -> {
                    val parts = (item["summary"] as? JsonArray).orEmpty() +
                        (item["content"] as? JsonArray).orEmpty()
                    parts.forEach summaryLoop@{ summaryElement ->
                        val summary = summaryElement as? JsonObject ?: return@summaryLoop
                        summary["text"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(GenerationEvent.Reasoning(it)) }
                    }
                }
            }
        }
    }

    private fun resolveEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isBlank()) return DEFAULT_ENDPOINT
        return when {
            clean.endsWith("/backend-api/codex/responses") -> clean
            clean.endsWith("/backend-api/codex") -> "$clean/responses"
            clean.endsWith("/responses") -> clean
            else -> DEFAULT_ENDPOINT
        }
    }

    companion object {
        const val AUTH_MARKER = "openai_codex_oauth"
        const val DEFAULT_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"

        val SUPPORTED_MODELS = listOf(
            "gpt-6-astra",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.2",
            "gpt-5.4-mini",
            "gpt-5.3-codex",
            "gpt-5.3-codex-spark"
        )

        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val ISSUER = "https://auth.openai.com"
        private const val CALLBACK_PORT = 1455
        private const val REDIRECT_URI = "http://localhost:1455/auth/callback"
        private const val AUTH_SCOPE = "openid email profile offline_access"
        private const val REFRESH_SCOPE = "openid profile email"
        private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
        private const val CODEX_USER_AGENT =
            "codex-tui/0.135.0 (Mac OS 26.5.0; arm64) iTerm.app/3.6.10 (codex-tui; 0.135.0)"
    }
}
