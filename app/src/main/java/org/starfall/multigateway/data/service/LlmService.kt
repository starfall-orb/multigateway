package org.starfall.multigateway.data.service
import org.starfall.multigateway.data.local.preferences.ModelConfigurationMemory

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.adapter.AccountProviderAdapterRegistry
import org.starfall.multigateway.data.model.*
import java.util.Base64

class LlmService(context: Context) {
    internal val modelConfigurationMemory = ModelConfigurationMemory(context)
    private val appContext = context.applicationContext

    internal suspend fun <T> withOAuthSession(block: suspend () -> T): T =
        org.starfall.multigateway.data.adapter.common.OAuthCallbackService.keepAlive(appContext, block)

    private val attachments = AttachmentResolver(context)
    private val sdk = OfficialLlmSdk(attachments)
    private val accountAdapters = AccountProviderAdapterRegistry(context, attachments)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    internal suspend fun toolAttachments(
        message: StoredMessage,
        files: org.starfall.multigateway.data.tools.ToolFiles,
        imported: MutableMap<String, String>
    ): JsonArray = JsonArray(message.files.map { reference ->
        val meta = attachments.metadata(reference) ?: error("Attachment metadata is missing")
        val bytes = attachments.readBytes(reference) ?: error("Attachment data is missing")
        val uri = imported[reference] ?: ("tool-file:" + files.save(bytes.inputStream(), meta.mimeType)).also {
            imported[reference] = it
        }
        buildJsonObject {
            put("uri", uri)
            put("mimeType", meta.mimeType)
            put("data", Base64.getEncoder().encodeToString(bytes))
        }
    })

    /** Trusted user selections only; model-supplied references use ToolFiles instead. */
    internal suspend fun importToolAttachments(references: List<String>, files: org.starfall.multigateway.data.tools.ToolFiles, maxBytes: Long): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            references.map { reference ->
                val meta = attachments.metadata(reference) ?: error("Attachment metadata is missing")
                require(meta.sizeBytes <= maxBytes) { "Reference image is too large for the selected generation API." }
                val input = attachments.open(reference) ?: error("Attachment data is missing")
                "tool-file:" + files.save(input, meta.mimeType, maxBytes)
            }
        }

    fun resolveOllamaChatUrl(baseUrl: String): String {
        var clean = baseUrl.trim().trimEnd('/')
        if (clean.endsWith("/tags")) {
            clean = clean.removeSuffix("/tags").trimEnd('/')
        }
        if (clean.endsWith("/chat")) {
            clean = clean.removeSuffix("/chat").trimEnd('/')
        }
        if (clean.endsWith("/generate")) {
            clean = clean.removeSuffix("/generate").trimEnd('/')
        }
        return if (clean.endsWith("/api")) "$clean/chat" else "$clean/api/chat"
    }

    fun resolveOllamaTagsUrl(baseUrl: String): String {
        var clean = baseUrl.trim().trimEnd('/')
        if (clean.endsWith("/tags")) {
            return clean
        }
        if (clean.endsWith("/chat")) {
            clean = clean.removeSuffix("/chat").trimEnd('/')
        }
        if (clean.endsWith("/generate")) {
            clean = clean.removeSuffix("/generate").trimEnd('/')
        }
        return if (clean.endsWith("/api")) "$clean/tags" else "$clean/api/tags"
    }

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> =
        accountAdapters.get(provider.type)?.authorize(provider)
            ?: Result.failure(IllegalArgumentException("${provider.type.displayName} does not use account authorization."))

    suspend fun prepareAccountProvider(provider: LlmProviderInfo, modelName: String? = null): LlmProviderInfo =
        accountAdapters.get(provider.type)?.let { adapter ->
            if (modelName == null) adapter.prepareAuthenticatedProvider(provider) else adapter.prepareModelProvider(provider, modelName)
        }
            ?: provider

    fun normalizeAccountToolRequest(
        sourceProvider: LlmProviderInfo,
        wireProvider: LlmProviderInfo,
        body: JsonObject,
        systemPrompt: String
    ): JsonObject =
        accountAdapters.get(sourceProvider.type)
            ?.normalizeToolRequest(sourceProvider, wireProvider, body, systemPrompt)
            ?: body

    fun prepareAccountRequest(source: LlmProviderInfo, wire: LlmProviderInfo, body: JsonObject): LlmProviderInfo =
        accountAdapters.get(source.type)?.prepareRequestProvider(source, wire, body) ?: wire

    fun accountRequestUrl(source: LlmProviderInfo, wire: LlmProviderInfo, url: String): String =
        accountAdapters.get(source.type)?.requestUrl(source, wire, url) ?: url

    fun unwrapAccountResponse(source: LlmProviderInfo, response: JsonObject): JsonObject =
        accountAdapters.get(source.type)?.unwrapResponse(response) ?: response

    fun clearAccountCredentials(type: ProviderType, providerId: String) {
        accountAdapters.clearCredentials(type, providerId)
    }

    suspend fun testConnection(provider: LlmProviderInfo): Result<String> {
        accountAdapters.get(provider.type)?.let { return it.testConnection(provider) }
        return try {
            when (provider.type) {
                ProviderType.OLLAMA -> {
                    val tagsUrl = resolveOllamaTagsUrl(provider.baseUrl)
                    val response = httpClient.get(tagsUrl) {
                        applyAuth(provider)
                    }
                    if (response.status.isSuccess()) {
                        val body = response.bodyAsText()
                        val parsed = json.parseToJsonElement(body).jsonObject
                        val models = parsed["models"]?.jsonArray?.mapNotNull {
                            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        } ?: emptyList()
                        if (models.isNotEmpty()) {
                            Result.success("Connected! Found ${models.size} models:\n${models.take(4).joinToString(", ")}${if (models.size > 4) "..." else ""}")
                        } else {
                            Result.success("Connected to Ollama! (0 models installed)")
                        }
                    } else {
                        Result.failure(Exception("HTTP ${response.status.value}: ${response.status.description}"))
                    }
                }
                else -> sdk.testConnection(provider)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testModel(provider: LlmProviderInfo, modelId: String): Result<String> {
        return try {
            require(modelId.isNotBlank()) { "Model ID is empty" }
            val modelConfig = provider.config.modelConfigs[modelId] ?: ModelConfiguration()
            require(modelConfig.modelType == ModelType.TEXT_GENERATION) {
                "${modelConfig.modelType.displayName} models cannot be tested with a text request"
            }
            val testProvider = provider.copy(config = provider.config.copy(
                supportStream = false,
                maxTokens = minOf(provider.config.maxTokens, 64),
                modelConfigs = provider.config.modelConfigs + (modelId to modelConfig.copy(supportStream = false))
            ))
            val message = StoredMessage(
                id = "connection-test",
                role = ChatRole.USER,
                versions = listOf(MessageVersion(content = "Reply with OK."))
            )
            val outputBuilder = StringBuilder()
            streamContent(testProvider, modelId, listOf(message)).collect { chunk ->
                if (outputBuilder.length < 500) {
                    outputBuilder.append(chunk.take(500 - outputBuilder.length))
                }
            }
            val output = outputBuilder.toString().trim()
            if (output.startsWith("[Error:") || output.startsWith(" [Error:")) {
                Result.failure(Exception(output.trim()))
            } else {
                Result.success(if (output.isBlank()) "Model request completed successfully" else output)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchProviderModels(provider: LlmProviderInfo): List<String> =
        fetchProviderModelCatalog(provider).map { it.id }

    suspend fun fetchProviderModelCatalog(provider: LlmProviderInfo): List<DiscoveredModel> {
        accountAdapters.get(provider.type)?.let { return it.fetchModels(provider).map { id -> DiscoveredModel(id) } }

        var base = provider.baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses", "/messages", "/models", "/chat", "/tags", "/generate")) {
            base = base.removeSuffix(suffix)
        }
        val endpoint = provider.config.customListModelsUrl?.takeIf { it.isNotBlank() } ?: when (provider.type) {
            ProviderType.OLLAMA -> base.removeSuffix("/api").removeSuffix("/v1") + "/v1/models"
            ProviderType.ANTHROPIC -> base.removeSuffix("/v1") + "/v1/models"
            ProviderType.GOOGLE -> if (Regex("/v1(?:beta|alpha)?$").containsMatchIn(base)) "$base/models" else "$base/v1beta/models"
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> "$base/models"
            else -> error("No model discovery endpoint is registered for ${provider.type.displayName}.")
        }
        val models = linkedMapOf<String, DiscoveredModel>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val response = httpClient.get(endpoint) {
                val auth = provider.auth
                auth.requestHeaders(provider.type).forEach { (key, value) -> header(key, value) }
                if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
                    parameter(auth.key?.takeIf { it.isNotBlank() } ?: "key", auth.token)
                }
                if (provider.type == ProviderType.ANTHROPIC) header("anthropic-version", "2023-06-01")
                provider.config.headers.forEach { (name, value) -> headers.remove(name); header(name, value) }
                cursor?.let { parameter(if (provider.type == ProviderType.GOOGLE) "pageToken" else "after_id", it) }
            }
            check(response.status.isSuccess()) { "Unable to load models: HTTP ${response.status.value}" }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val entries = (body["data"] ?: body["models"]) as? JsonArray
                ?: error("The models endpoint returned an unsupported response.")
            entries.forEach { entry ->
                val item = entry as? JsonObject ?: return@forEach
                val id = (item["id"] ?: item["name"])?.jsonPrimitive?.contentOrNull
                id?.takeIf { it.isNotBlank() }?.let {
                    val modelId = if (provider.type == ProviderType.GOOGLE) it.removePrefix("models/") else it
                    models[modelId] = discoveredModel(modelId, item)
                }
            }
            cursor = when {
                provider.type == ProviderType.GOOGLE -> body["nextPageToken"]?.jsonPrimitive?.contentOrNull
                provider.type == ProviderType.ANTHROPIC && body["has_more"]?.jsonPrimitive?.booleanOrNull == true ->
                    body["last_id"]?.jsonPrimitive?.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
            check(cursor == null || seenCursors.add(cursor!!)) { "The models endpoint repeated a page." }
        } while (cursor != null)
        return models.values.toList()
    }

    suspend fun fetchOllamaModels(baseUrl: String): List<String> {
        return try {
            val tagsUrl = resolveOllamaTagsUrl(baseUrl)
            val response = httpClient.get(tagsUrl)
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["models"]?.jsonArray?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun filterAttachmentsForProvider(
        provider: LlmProviderInfo,
        modelConfig: ModelConfiguration,
        messages: List<StoredMessage>
    ): List<StoredMessage> = messages.map { message ->
        if (message.files.isEmpty()) return@map message
        val allowedFiles = message.files.filter { reference ->
            val meta = attachments.metadata(reference) ?: return@filter false
            when {
                accountAdapters.get(provider.type) != null -> when {
                    meta.isImage -> modelConfig.supportsVision
                    else -> true
                }
                provider.type == ProviderType.GOOGLE -> when {
                    meta.isImage -> modelConfig.supportsVision
                    meta.isVideo -> modelConfig.supportsVideoInput
                    else -> true
                }
                provider.type == ProviderType.OPENAI || provider.type == ProviderType.OPENAI_RESPONSES -> when {
                    meta.isImage -> modelConfig.supportsVision
                    else -> true
                }
                provider.type == ProviderType.ANTHROPIC -> when {
                    meta.isImage -> modelConfig.supportsVision
                    meta.isPdf || meta.isText -> true
                    else -> false
                }
                provider.type == ProviderType.OLLAMA -> meta.isImage && modelConfig.supportsVision
                else -> false
            }
        }
        if (allowedFiles == message.files) return@map message
        val versions = message.versions.toMutableList()
        if (versions.isNotEmpty()) {
            val index = message.activeVersionIndex.coerceIn(0, versions.lastIndex)
            versions[index] = versions[index].copy(files = allowedFiles)
        }
        message.copy(versions = versions)
    }

    fun streamEvents(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String = "",
        maxOutputTokens: Int? = null
    ): Flow<GenerationEvent> = flow {
        val modelConfig = provider.config.modelConfigs[modelName] ?: ModelConfiguration()
        val requestProvider = provider.copy(config = provider.config.copy(supportStream = provider.streamEnabledFor(modelName)))
        val requestMessages = filterAttachmentsForProvider(requestProvider, modelConfig, messages)
        val temperature = modelConfig.temperature
        val topP = modelConfig.topP
        val topK = modelConfig.topK
        val maxTokens = maxOutputTokens
            ?.coerceAtLeast(1)
            ?.let { minOf(it, provider.config.maxTokens) }
            ?: provider.config.maxTokens
        accountAdapters.get(provider.type)?.let { adapter ->
            emitAll(adapter.streamEvents(
                requestProvider, modelName, requestMessages, systemPrompt, maxTokens
            ))
            return@flow
        }
        when (provider.type) {
            ProviderType.OPENAI -> emitAll(
                sdk.streamOpenAi(
                    requestProvider, modelName, requestMessages, systemPrompt,
                    temperature, topP, maxTokens, modelConfig.reasoningEffort, modelConfig.sendThinkingContent
                )
            )
            ProviderType.OPENAI_RESPONSES -> emitAll(
                sdk.streamResponses(
                    requestProvider, modelName, requestMessages, systemPrompt,
                    temperature, topP, maxTokens, modelConfig.reasoningEffort, modelConfig.sendThinkingContent
                )
            )
            ProviderType.ANTHROPIC -> emitAll(
                sdk.streamAnthropic(
                    requestProvider, modelName, requestMessages, systemPrompt,
                    temperature, topP, maxTokens, topK, modelConfig.sendThinkingContent
                )
            )
            ProviderType.GOOGLE -> emitAll(
                sdk.streamGoogle(
                    requestProvider, modelName, requestMessages, systemPrompt,
                    temperature, topP, maxTokens, topK, modelConfig.sendThinkingContent
                )
            )
            ProviderType.OLLAMA -> streamOllama(
                requestProvider, modelName, requestMessages, systemPrompt,
                temperature, topP, maxTokens, topK
            ).collect { emit(GenerationEvent.Text(it)) }
            else -> error("No request adapter is registered for ${provider.type.displayName}.")
        }
    }

    suspend fun streamContent(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String = "",
        maxOutputTokens: Int? = null
    ): Flow<String> = streamEvents(provider, modelName, messages, systemPrompt, maxOutputTokens)
        .transform { event ->
            if (event is GenerationEvent.Text) emit(event.text)
        }

    private fun streamOllama(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        topK: Int?
    ): Flow<String> = flow {
        val url = resolveOllamaChatUrl(provider.baseUrl)

        val ollamaMessages = mutableListOf<JsonObject>()
        if (systemPrompt.isNotBlank()) {
            ollamaMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        for (message in messages) {
            ollamaMessages.add(buildJsonObject {
                put("role", if (message.role == ChatRole.MODEL) "assistant" else "user")
                put("content", message.content)
                val images = message.files.mapNotNull { reference ->
                    val meta = attachments.metadata(reference) ?: return@mapNotNull null
                    if (!meta.isImage) return@mapNotNull null
                    attachments.readBytes(reference)?.let { Base64.getEncoder().encodeToString(it) }
                }
                if (images.isNotEmpty()) {
                    put("images", JsonArray(images.map(::JsonPrimitive)))
                }
            })
        }

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("messages", JsonArray(ollamaMessages))
            put("stream", provider.config.supportStream)
            put("options", buildJsonObject {
                put("num_predict", maxTokens)
                temperature?.let { put("temperature", it) }
                topP?.let { put("top_p", it) }
                topK?.let { put("top_k", it) }
            })
        }

        try {
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                applyAuth(provider)
                setBody(requestBody.toString())
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.isNotBlank()) {
                    try {
                        val parsed = json.parseToJsonElement(line).jsonObject
                        val content = parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!content.isNullOrEmpty()) emit(content)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            emit(" [Error: ${e.localizedMessage ?: "Network error"}]")
        }
    }

    private fun HttpRequestBuilder.applyAuth(provider: LlmProviderInfo) {
        provider.auth.requestHeaders(provider.type).forEach { (key, value) -> header(key, value) }
        if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
            parameter(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
        }
        provider.config.headers.forEach { (k, v) ->
            header(k, v)
        }
    }
}
