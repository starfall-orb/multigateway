package org.starfall.multigateway.data.adapter.antigravity

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.*
import java.util.UUID

internal class AntigravityAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ProviderType.ANTIGRAVITY,
    "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com",
    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
    "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs",
    51121, "/oauth-callback", "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"
) {
    private val metadata = obj("ideType" to str("ANTIGRAVITY"), "platform" to str("MACOS"), "pluginType" to str("GEMINI"))
    private fun wire(provider: LlmProviderInfo, token: AccountTokenState) = provider.copy(type = ProviderType.GOOGLE,
        auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
        config = provider.config.copy(headers = provider.config.headers + mapOf(
            "User-Agent" to "antigravity/1.23.2 windows/amd64",
            "X-Goog-Api-Client" to "google-cloud-sdk vscode_cloudshelleditor/0.1", "Client-Metadata" to metadata.toString())))

    private val projectLock = Mutex()
    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo = projectLock.withLock {
        var token = ensureToken(provider)
        if (token.projectId.isNullOrBlank()) {
            val authenticated = wire(provider, token)
            val bases = if (provider.baseUrl.trimEnd('/') == ProviderType.ANTIGRAVITY.defaultBaseUrl)
                listOf("https://cloudcode-pa.googleapis.com",
                    "https://daily-cloudcode-pa.sandbox.googleapis.com",
                    "https://autopush-cloudcode-pa.sandbox.googleapis.com",
                    "https://daily-cloudcode-pa.googleapis.com")
                else listOf(provider.baseUrl.trimEnd('/'))
            fun project(value: JsonElement?): String? = when (value) {
                is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> value.text("id").takeIf(String::isNotBlank)
                else -> null
            }
            var projectId: String? = null
            var lastFailure: Exception? = null
            for (base in bases) {
                try {
                    val loaded = http.post("$base/v1internal:loadCodeAssist", obj("metadata" to metadata), authenticated)
                    projectId = project(loaded["cloudaicompanionProject"])
                    if (projectId == null) {
                        val tier = (loaded["allowedTiers"] as? JsonArray).orEmpty().map { it.jsonObject }
                            .firstOrNull { (it["isDefault"] as? JsonPrimitive)?.booleanOrNull == true }
                            ?.text("id")?.takeIf(String::isNotBlank) ?: "legacy-tier"
                        for (attempt in 0 until 10) {
                            val onboard = http.post("$base/v1internal:onboardUser",
                                obj("tierId" to str(tier), "metadata" to metadata), authenticated)
                            if ((onboard["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                                projectId = project((onboard["response"] as? JsonObject)?.get("cloudaicompanionProject"))
                                break
                            }
                            delay(2000)
                        }
                    }
                    if (!projectId.isNullOrBlank()) break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { lastFailure = e }
            }
            check(!projectId.isNullOrBlank()) {
                "Antigravity account has no Code Assist project: ${lastFailure?.message.orEmpty()}"
            }
            token = token.copy(projectId = projectId)
            store.save(provider.id, token)
        }
        wire(provider, token)
    }
    override suspend fun fetchModels(provider: LlmProviderInfo): List<String> {
        prepareAuthenticatedProvider(provider)
        return AntigravityModels.available
    }
    override fun normalizeToolRequest(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo,
        body: JsonObject, systemPrompt: String): JsonObject {
        val project = store.load(sourceProvider.id)?.projectId ?: error("Antigravity project is missing")
        val model = body.text("model")
        require(model.isNotBlank()) { "Antigravity request needs a model" }
        val request = body.toMutableMap().apply { remove("model") }
        val contents = request["contents"] as? JsonArray
        if (contents != null) request["contents"] = JsonArray(contents.map { content ->
            val message = content.jsonObject
            val parts = (message["parts"] as? JsonArray).orEmpty().map { part ->
                val value = part.jsonObject
                if (value["functionCall"] != null && value["thoughtSignature"] == null)
                    JsonObject(value + ("thoughtSignature" to str("skip_thought_signature_validator"))) else value
            }
            JsonObject(message + ("parts" to JsonArray(parts)))
        })
        val config = (request["generationConfig"] as? JsonObject) ?: obj()
        if (model.contains("claude") && sourceProvider.config.modelConfigs[model]?.supportsThinking != false) {
            val maxTokens = (config["maxOutputTokens"] as? JsonPrimitive)?.intOrNull ?: sourceProvider.config.maxTokens
            if (maxTokens > 1024) request["generationConfig"] = JsonObject(config + ("thinkingConfig" to obj(
                "includeThoughts" to JsonPrimitive(true), "thinkingBudget" to JsonPrimitive(minOf(1024, maxTokens - 1)))))
        }
        return obj("project" to str(project), "model" to str(AntigravityModels.resolve(model, sourceProvider.config.modelConfigs[model]?.reasoningEffort)), "requestId" to str("agent-${UUID.randomUUID()}"),
            "userAgent" to str("antigravity"), "requestType" to str("agent"),
            "request" to JsonObject(request))
    }
    override fun requestUrl(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo, defaultUrl: String) =
        sourceProvider.baseUrl.trimEnd('/') + "/v1internal:generateContent"
    override fun unwrapResponse(response: JsonObject): JsonObject = (response["response"] as? JsonObject) ?: response
}
