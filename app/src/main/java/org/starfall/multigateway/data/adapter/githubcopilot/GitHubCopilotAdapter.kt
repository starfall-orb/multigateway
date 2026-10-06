package org.starfall.multigateway.data.adapter.githubcopilot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.text
import org.starfall.multigateway.data.service.discoveredModel

internal class GitHubCopilotAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ProviderType.GITHUB_COPILOT, CLIENT_ID, "", "", "read:user", 0, ""
) {
    override suspend fun authorize(provider: LlmProviderInfo): Result<LlmProviderInfo> = result {
        require(provider.type == providerType)
        OAuthCallbackService.keepAlive(appContext) {
            val device = http.post("https://github.com/login/device/code", buildJsonObject {
                put("client_id", CLIENT_ID); put("scope", "read:user")
            })
            val code = device.text("user_code")
            val deviceCode = device.text("device_code")
            require(code.isNotBlank() && deviceCode.isNotBlank()) { "Invalid GitHub device response" }
            withContext(Dispatchers.Main) {
                (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("GitHub authorization code", code))
                Toast.makeText(appContext, "Enter $code in GitHub (copied to clipboard)", Toast.LENGTH_LONG).show()
            }
            openBrowser(device.text("verification_uri_complete").ifBlank { device.text("verification_uri") })
            var interval = ((device["interval"] as? JsonPrimitive)?.longOrNull ?: 5).coerceAtLeast(1)
            val deadline = System.currentTimeMillis() +
                ((device["expires_in"] as? JsonPrimitive)?.longOrNull ?: 900).coerceIn(1, 900) * 1000
            var token: AccountTokenState? = null
            while (System.currentTimeMillis() < deadline) {
                delay(minOf(interval * 1000 + 1000, (deadline - System.currentTimeMillis()).coerceAtLeast(0)))
                if (System.currentTimeMillis() >= deadline) break
                // GitHub reports pending/slow_down in JSON with HTTP 200.
                val response = http.json(http.request("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json").post(okhttp3.FormBody.Builder()
                        .add("client_id", CLIENT_ID).add("device_code", deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()).build(), allowOAuthError = true)
                val access = response.text("access_token")
                if (access.isNotBlank()) { token = AccountTokenState(access, ""); break }
                when (response.text("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval = maxOf(interval + 5, (response["interval"] as? JsonPrimitive)?.longOrNull ?: 0)
                    else -> error("GitHub authorization failed: ${response.text("error")}")
                }
            }
            val authorizedToken = token ?: error("GitHub authorization timed out")
            withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, authorizedToken) }
            authorized(provider, authorizedToken)
        }
    }
    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo {
        val token = ensureToken(provider)
        return provider.copy(type = ProviderType.OPENAI,
            auth = Authorization(AuthMethod.BEARER_TOKEN, value = token.accessToken),
            config = provider.config.copy(headers = provider.config.headers + mapOf(
                "User-Agent" to "opencode/1.2.27", "Openai-Intent" to "conversation-edits",
                "X-GitHub-Api-Version" to "2026-06-01", "x-initiator" to "user")))
    }
    override suspend fun prepareModelProvider(provider: LlmProviderInfo, modelName: String): LlmProviderInfo {
        // Resolve against the current account's live catalog; never reuse another account's routing.
        val catalog = fetchModelCatalog(provider)
        val model = catalog.firstOrNull { it.id == modelName }
            ?: error("Copilot model '$modelName' is unavailable for the selected account or disabled by its policy. Refresh models and select an available model.")
        val supported = (model.metadata["supported_endpoints"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val wire = prepareAuthenticatedProvider(provider)
        val type = copilotProtocol(modelName, supported)
        return wire.copy(type = type,
            auth = if (type == ProviderType.ANTHROPIC) Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${wire.auth.token}") else wire.auth,
            config = wire.config.copy(headers = wire.config.headers + mapOf("anthropic-beta" to "interleaved-thinking-2025-05-14")))
    }

    override suspend fun fetchModels(provider: LlmProviderInfo): List<String> = fetchModelCatalog(provider).map { it.id }

    override suspend fun fetchModelCatalog(provider: LlmProviderInfo): List<DiscoveredModel> {
        val wire = prepareAuthenticatedProvider(provider)
        val response = http.json(http.request(wire.baseUrl.trimEnd('/') + "/models", wire).get().build())
        val models = response["data"] as? JsonArray ?: error("Copilot returned an unsupported model catalog")
        return models.mapNotNull { (it as? JsonObject)?.let(::copilotDiscoveredModel) }.distinctBy { it.id }
    }
    override fun normalizeToolRequest(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo,
        body: JsonObject, systemPrompt: String): JsonObject {
        val values = body.toMutableMap()
        if (wireProvider.type == ProviderType.OPENAI_RESPONSES) {
            values["instructions"] = JsonPrimitive(systemPrompt)
            values["store"] = JsonPrimitive(false)
            (values["input"] as? JsonArray)?.let { input ->
                values["input"] = JsonArray(input.filterNot { (it as? JsonObject)?.text("role") == "system" })
            }
        }
        // Copilot's GPT routes manage their output-token limit, as in the reference client.
        if (body.text("model").startsWith("gpt-")) {
            values.remove("max_tokens"); values.remove("max_output_tokens")
        }
        return JsonObject(values)
    }

    override fun prepareRequestProvider(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo, body: JsonObject): LlmProviderInfo {
        val messages = (body["messages"] ?: body["input"]) as? JsonArray
        val last = messages?.lastOrNull() as? JsonObject
        val role = last?.text("role")
        val toolResult = (last?.get("content") as? JsonArray).orEmpty()
            .any { (it as? JsonObject)?.text("type") == "tool_result" }
        val initiator = if (last == null || ((role == "user" || role == "system") && !toolResult)) "user" else "agent"
        fun hasImage(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.text("type") in listOf("image", "image_url", "input_image") || value.values.any(::hasImage)
            is JsonArray -> value.any(::hasImage)
            else -> false
        }
        val headers = wireProvider.config.headers + mapOf("x-initiator" to initiator) +
            if (hasImage(body)) mapOf("Copilot-Vision-Request" to "true") else emptyMap()
        return wireProvider.copy(config = wireProvider.config.copy(headers = headers))
    }

    private companion object { const val CLIENT_ID = "Ov23li8tweQw6odWQebz" }
}


internal fun copilotProtocol(id: String, supported: List<String>): ProviderType = when {
    "/v1/messages" in supported || (supported.isEmpty() && id.contains("claude")) -> ProviderType.ANTHROPIC
    ("/responses" in supported && "/chat/completions" !in supported) ||
        (supported.isEmpty() && Regex("^gpt-(\\d+)(?:[.-]|$)").find(id)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 5 } == true &&
            !id.startsWith("gpt-5-mini")) -> ProviderType.OPENAI_RESPONSES
    else -> ProviderType.OPENAI
}

internal fun copilotDiscoveredModel(item: JsonObject): DiscoveredModel? {
    val id = item.text("id").takeIf(String::isNotBlank) ?: return null
    val policy = item["policy"] as? JsonObject
    if (policy?.text("state")?.lowercase() in listOf("disabled", "blocked", "unavailable")) return null
    if ((item["model_picker_enabled"] as? JsonPrimitive)?.booleanOrNull == false) return null
    val capabilities = item["capabilities"] as? JsonObject
    if (capabilities?.text("type")?.lowercase() in listOf("embeddings", "embedding")) return null
    return discoveredModel(id, item)
}
