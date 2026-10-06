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
import org.starfall.multigateway.data.service.discoveredModel
import org.starfall.multigateway.data.tools.*
import java.util.UUID

internal class AntigravityAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ProviderType.ANTIGRAVITY,
    "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com",
    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
    "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs",
    51121, "/oauth-callback", "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",
    callbackHost = "127.0.0.1"
) {
    private val metadata = obj("ideType" to str("ANTIGRAVITY"), "platform" to str("PLATFORM_UNSPECIFIED"), "pluginType" to str("GEMINI"))
    private fun wire(provider: LlmProviderInfo, token: AccountTokenState) = provider.copy(type = ProviderType.GOOGLE,
        auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
        config = provider.config.copy(headers = provider.config.headers + mapOf(
            "User-Agent" to "antigravity/1.23.2 windows/amd64",
            "X-Goog-Api-Client" to "google-cloud-sdk vscode_cloudshelleditor/0.1", "Client-Metadata" to metadata.toString())))

    private val projectLock = Mutex()
    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo =
        wire(provider, ensureToken(provider))

    private fun bases(provider: LlmProviderInfo): List<String> =
        if (provider.baseUrl.trimEnd('/') == ProviderType.ANTIGRAVITY.defaultBaseUrl)
            listOf("https://cloudcode-pa.googleapis.com", "https://daily-cloudcode-pa.sandbox.googleapis.com",
                "https://autopush-cloudcode-pa.sandbox.googleapis.com", "https://daily-cloudcode-pa.googleapis.com")
        else listOf(provider.baseUrl.trimEnd('/'))

    private fun project(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
        is JsonObject -> value.text("id").takeIf(String::isNotBlank)
        else -> null
    }

    private suspend fun ensureProject(provider: LlmProviderInfo): AccountTokenState = projectLock.withLock {
        var token = ensureToken(provider)
        if (!token.projectId.isNullOrBlank()) return@withLock token
        val authenticated = wire(provider, token)
        var lastFailure: Exception? = null
        for (base in bases(provider)) {
            try {
                val loaded = http.post("$base/v1internal:loadCodeAssist", obj("metadata" to metadata), authenticated)
                check(loaded["projectValidationError"] == null || loaded["projectValidationError"] == JsonNull) {
                    "Antigravity rejected the project returned by Code Assist"
                }
                var projectId = project(loaded["cloudaicompanionProject"])
                if (projectId == null) {
                    val tier = (loaded["allowedTiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        .firstOrNull { (it["isDefault"] as? JsonPrimitive)?.booleanOrNull == true }
                        ?.text("id")?.takeIf(String::isNotBlank) ?: "legacy-tier"
                    val body = obj("tierId" to str(tier), "metadata" to metadata)
                    var operation = http.post("$base/v1internal:onboardUser", body, authenticated)
                    for (attempt in 0 until 10) {
                        if ((operation["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                            projectId = project((operation["response"] as? JsonObject)?.get("cloudaicompanionProject"))
                            break
                        }
                        if (attempt == 9) break
                        delay(2000)
                        val name = operation.text("name").trimStart('/').removePrefix("v1internal/")
                        operation = if (name.isNotBlank()) {
                            http.json(http.request("$base/v1internal/$name", authenticated).get().build())
                        } else http.post("$base/v1internal:onboardUser", body, authenticated)
                    }
                }
                if (!projectId.isNullOrBlank()) {
                    token = token.copy(projectId = projectId, codeAssistBaseUrl = base)
                    store.save(provider.oauthCredentialId, token)
                    return@withLock token
                }
                lastFailure = IllegalStateException("Code Assist did not return a provisioned project")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastFailure = e }
        }
        error("Could not resolve the Antigravity project for this account: ${lastFailure?.message.orEmpty()}")
    }

    override suspend fun prepareModelProvider(provider: LlmProviderInfo, modelName: String): LlmProviderInfo =
        wire(provider, ensureProject(provider))

    override suspend fun fetchModels(provider: LlmProviderInfo): List<String> = fetchModelCatalog(provider).map { it.id }

    override suspend fun fetchModelCatalog(provider: LlmProviderInfo): List<DiscoveredModel> {
        val token = ensureProject(provider)
        val authenticated = wire(provider, token)
        val candidates = (listOfNotNull(token.codeAssistBaseUrl?.takeIf { it in bases(provider) }) + bases(provider)).distinct()
        var lastFailure: Exception? = null
        for (base in candidates) {
            try {
                val response = http.post("$base/v1internal:fetchAvailableModels", obj("project" to str(token.projectId!!)), authenticated)
                val models = response["models"] as? JsonObject ?: error("Antigravity returned an unsupported model catalog")
                val catalog = models.mapNotNull { (id, value) ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    if (id.isBlank() || (item["isInternal"] as? JsonPrimitive)?.booleanOrNull == true ||
                        (item["modelPickerEnabled"] as? JsonPrimitive)?.booleanOrNull == false ||
                        (item["userFacing"] as? JsonPrimitive)?.booleanOrNull == false ||
                        item.text("visibility").lowercase() in listOf("internal", "hidden")) null
                    else discoveredModel(id, item)
                }
                // Keep the generation route on the endpoint that returned this account's catalog.
                if (token.codeAssistBaseUrl != base) projectLock.withLock {
                    val current = store.load(provider.oauthCredentialId)
                    if (current?.projectId == token.projectId) store.save(provider.oauthCredentialId, current.copy(codeAssistBaseUrl = base))
                }
                return catalog
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastFailure = e }
        }
        error("Could not load the Antigravity model catalog for this account/project: ${lastFailure?.message.orEmpty()}")
    }
    override suspend fun testConnection(provider: LlmProviderInfo): Result<String> = result {
        ensureToken(provider)
        "Antigravity authorization is ready."
    }
    override fun normalizeToolRequest(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo,
        body: JsonObject, systemPrompt: String): JsonObject {
        val project = store.load(sourceProvider.oauthCredentialId)?.projectId ?: error("Antigravity project is missing")
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
        val modelConfig = sourceProvider.config.modelConfigs[model]
        if (model.contains("claude") && modelConfig?.supportsThinking != false &&
            modelConfig?.reasoningDisabled != true && modelConfig?.reasoningEffort != null) {
            val maxTokens = (config["maxOutputTokens"] as? JsonPrimitive)?.intOrNull ?: sourceProvider.config.maxTokens
            if (maxTokens > 1024) request["generationConfig"] = JsonObject(config + ("thinkingConfig" to obj(
                "includeThoughts" to JsonPrimitive(true), "thinkingBudget" to JsonPrimitive(modelConfig.reasoningBudget(maxTokens)))))
        }
        return obj("project" to str(project), "model" to str((modelConfig?.modelJson?.get("model") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: if (modelConfig?.modelJson?.isNotEmpty() == true) model else AntigravityModels.resolve(model, modelConfig?.reasoningEffort)), "requestId" to str("agent-${UUID.randomUUID()}"),
            "userAgent" to str("antigravity"), "requestType" to str("agent"),
            "request" to JsonObject(request))
    }
    override fun requestUrl(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo, defaultUrl: String) =
        (store.load(sourceProvider.oauthCredentialId)?.codeAssistBaseUrl?.takeIf { it in bases(sourceProvider) }
            ?: sourceProvider.baseUrl.trimEnd('/')) + "/v1internal:generateContent"
    override fun unwrapResponse(response: JsonObject): JsonObject = (response["response"] as? JsonObject) ?: response
}
