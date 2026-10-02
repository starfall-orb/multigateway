package org.starfall.multigateway.data.service

import com.openai.client.OpenAIClientImpl
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputFile
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.Base64PdfSource
import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import com.google.genai.types.UploadFileConfig
import com.google.genai.types.GetFileConfig
import com.google.genai.types.FileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.starfall.multigateway.data.model.*
import java.time.Duration
import java.util.Base64

/** Each operation owns its client and immutable provider snapshot, including credentials.
 * Switching providers cannot redirect an in-flight request or reuse another provider's key.
 */
internal class OfficialLlmSdk(private val attachments: AttachmentResolver) {
    private inline fun <R> com.openai.client.OpenAIClient.useClient(block: (com.openai.client.OpenAIClient) -> R): R =
        try { block(this) } finally { close() }

    private inline fun <R> com.anthropic.client.AnthropicClient.useClient(block: (com.anthropic.client.AnthropicClient) -> R): R =
        try { block(this) } finally { close() }

    // SDKs wrap an interrupted socket in their own IOException type. Preserve
    // coroutine cancellation instead of displaying it as a generation failure.
    private suspend fun <T> sdkCall(block: () -> T): T = try {
        runInterruptible(block = block)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw e
    }

    private data class InlineAttachment(
        val meta: ResolvedAttachment,
        val bytes: ByteArray,
        val base64: String,
        val dataUrl: String
    )

    private fun inlineAttachment(reference: String): InlineAttachment? {
        val meta = attachments.metadata(reference) ?: return null
        val bytes = attachments.readBytes(reference) ?: return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return InlineAttachment(
            meta = meta,
            bytes = bytes,
            base64 = encoded,
            dataUrl = "data:${meta.mimeType};base64,$encoded"
        )
    }

    private fun openAiChatContent(message: StoredMessage): List<ChatCompletionContentPart> {
        val parts = mutableListOf<ChatCompletionContentPart>()
        if (message.content.isNotBlank()) {
            parts += ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(message.content).build()
            )
        }
        message.files.forEach { reference ->
            val data = inlineAttachment(reference) ?: return@forEach
            if (data.meta.mimeType.startsWith("image/")) {
                val imageUrl = ChatCompletionContentPartImage.ImageUrl.builder()
                    .url(data.dataUrl)
                    .build()
                parts += ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder().imageUrl(imageUrl).build()
                )
            } else {
                val fileObject = ChatCompletionContentPart.File.FileObject.builder()
                    .fileData(data.dataUrl)
                    .filename(data.meta.name)
                    .build()
                parts += ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File.builder().file(fileObject).build()
                )
            }
        }
        if (parts.isEmpty()) {
            parts += ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text("").build()
            )
        }
        return parts
    }

    private fun openAiResponseContent(message: StoredMessage): List<ResponseInputContent> {
        val parts = mutableListOf<ResponseInputContent>()
        if (message.content.isNotBlank()) {
            parts += ResponseInputContent.ofInputText(
                ResponseInputText.builder().text(message.content).build()
            )
        }
        message.files.forEach { reference ->
            val data = inlineAttachment(reference) ?: return@forEach
            if (data.meta.mimeType.startsWith("image/")) {
                parts += ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder().imageUrl(data.dataUrl).build()
                )
            } else {
                parts += ResponseInputContent.ofInputFile(
                    ResponseInputFile.builder()
                        .fileData(data.dataUrl)
                        .filename(data.meta.name)
                        .build()
                )
            }
        }
        if (parts.isEmpty()) {
            parts += ResponseInputContent.ofInputText(
                ResponseInputText.builder().text("").build()
            )
        }
        return parts
    }

    private fun anthropicContent(message: StoredMessage): List<ContentBlockParam> {
        val blocks = mutableListOf<ContentBlockParam>()
        if (message.content.isNotBlank()) blocks += ContentBlockParam.ofText(message.content)
        message.files.forEach { reference ->
            val meta = attachments.metadata(reference) ?: return@forEach
            when {
                meta.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> {
                    val data = inlineAttachment(reference) ?: return@forEach
                    val source = Base64ImageSource.builder()
                        .data(data.base64)
                        .mediaType(Base64ImageSource.MediaType.of(meta.mimeType))
                        .build()
                    blocks += ContentBlockParam.ofImage(
                        ImageBlockParam.builder().source(source).build()
                    )
                }
                meta.mimeType == "application/pdf" -> {
                    val data = inlineAttachment(reference) ?: return@forEach
                    blocks += ContentBlockParam.ofDocument(
                        DocumentBlockParam.builder()
                            .source(Base64PdfSource.builder().data(data.base64).build())
                            .title(meta.name)
                            .build()
                    )
                }
                meta.mimeType.startsWith("text/") || meta.mimeType in setOf(
                    "application/json",
                    "application/xml",
                    "application/javascript"
                ) -> {
                    val bytes = attachments.readBytes(reference) ?: return@forEach
                    blocks += ContentBlockParam.ofDocument(
                        DocumentBlockParam.builder()
                            .textSource(bytes.toString(Charsets.UTF_8))
                            .title(meta.name)
                            .build()
                    )
                }
                else -> Unit
            }
        }
        if (blocks.isEmpty()) blocks += ContentBlockParam.ofText("")
        return blocks
    }

    private suspend fun googleParts(client: Client, message: StoredMessage): List<Part> {
        val parts = mutableListOf<Part>()
        if (message.content.isNotBlank()) parts += Part.fromText(message.content)
        for (reference in message.files) {
            val meta = attachments.metadata(reference) ?: continue
            if (meta.mimeType.startsWith("video/")) {
                val uploadConfig = UploadFileConfig.builder()
                    .mimeType(meta.mimeType)
                    .displayName(meta.name)
                    .build()
                var uploaded = if (meta.sizeBytes > 0) {
                    val input = attachments.open(reference) ?: continue
                    input.use { stream ->
                        sdkCall { client.files.upload(stream, meta.sizeBytes, uploadConfig) }
                    }
                } else {
                    val bytes = attachments.readBytes(reference, 100L * 1024L * 1024L) ?: continue
                    sdkCall { client.files.upload(bytes, uploadConfig) }
                }
                val fileName = uploaded.name().orElse(null)
                if (fileName != null) {
                    var attempts = 0
                    while (
                        uploaded.state().orElse(null)?.knownEnum() != FileState.Known.ACTIVE &&
                        attempts < 120
                    ) {
                        if (uploaded.state().orElse(null)?.knownEnum() == FileState.Known.FAILED) {
                            error("Google failed to process ${meta.name}")
                        }
                        delay(1000)
                        uploaded = sdkCall {
                            client.files.get(fileName, GetFileConfig.builder().build())
                        }
                        attempts++
                    }
                    check(uploaded.state().orElse(null)?.knownEnum() == FileState.Known.ACTIVE) {
                        "Google timed out processing ${meta.name}"
                    }
                }
                val uri = uploaded.uri().orElseThrow {
                    IllegalStateException("Google did not return a file URI for ${meta.name}")
                }
                parts += Part.fromUri(uri, meta.mimeType)
            } else {
                val bytes = attachments.readBytes(reference) ?: continue
                parts += Part.fromBytes(bytes, meta.mimeType)
            }
        }
        if (parts.isEmpty()) parts += Part.fromText("")
        return parts
    }

    private fun baseUrl(provider: LlmProviderInfo): String {
        var base = provider.baseUrl.trim().trimEnd('/')
        when (provider.type) {
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> for (suffix in listOf("/chat/completions", "/responses", "/models")) {
                if (base.endsWith(suffix)) base = base.removeSuffix(suffix)
            }
            // Anthropic SDK adds /v1/messages itself.
            ProviderType.ANTHROPIC -> {
                base = base.removeSuffix("/messages").removeSuffix("/models").removeSuffix("/v1")
            }
            else -> Unit
        }
        return base
    }

    private fun headers(provider: LlmProviderInfo): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val auth = provider.auth
        result.putAll(auth.requestHeaders(provider.type))
        result.putAll(provider.config.headers)
        return result
    }

    private fun openAi(provider: LlmProviderInfo) = OpenAIClientImpl(com.openai.core.ClientOptions.builder().apply {
        val transport = com.openai.client.okhttp.OkHttpClient.builder().timeout(Duration.ofMinutes(2)).build()
        httpClient(object : com.openai.core.http.HttpClient by transport {
            override fun execute(request: com.openai.core.http.HttpRequest,
                                 requestOptions: com.openai.core.RequestOptions): com.openai.core.http.HttpResponse {
                // Interrupting a blocking socket read does not reliably close the socket.
                // The official async transport cancels its OkHttp call when this future is cancelled.
                val future = transport.executeAsync(request, requestOptions)
                try {
                    return future.get()
                } catch (e: InterruptedException) {
                    future.cancel(true)
                    throw e
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause ?: e)
                }
            }
        })
        baseUrl(baseUrl(provider))
        timeout(Duration.ofMinutes(2))
        maxRetries(0)
        // The SDK requires an explicit authentication strategy. Gateways can use
        // headers or query parameters rather than its built-in bearer credential.
        if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) {
            apiKey(provider.auth.token)
            provider.config.headers.forEach { (key, value) -> putHeader(key, value) }
        } else httpRequestAuthenticator(object : com.openai.core.http.HttpRequestAuthenticator {
            override fun authenticate(request: com.openai.core.http.HttpRequest) = request.toBuilder().apply {
                headers(provider).forEach { (key, value) -> putHeader(key, value) }
                if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                    putQueryParam(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
                }
            }.build()
        })
    }.build())

    private fun anthropic(provider: LlmProviderInfo) = AnthropicOkHttpClient.builder().apply {
        baseUrl(baseUrl(provider))
        timeout(Duration.ofMinutes(2))
        maxRetries(0)
        if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) apiKey(provider.auth.token)
        (if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT) provider.config.headers else headers(provider))
            .forEach { (key, value) -> putHeader(key, value) }
        if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
            putQueryParam(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token)
        }
    }.build()

    private fun google(provider: LlmProviderInfo): Client {
        val base = baseUrl(provider).removeSuffix("/models")
        val version = Regex("/(v1(?:beta|alpha)?)$").find(base)?.groupValues?.get(1)
        // Supply auth in the transport so custom headers/query auth work with gateways too.
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
            if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) {
                provider.config.headers.forEach { (key, value) -> request.header(key, value) }
            } else {
                request.removeHeader("x-goog-api-key")
                headers(provider).forEach { (key, value) -> request.header(key, value) }
            }
            if (provider.auth.method == AuthMethod.QUERY_PARAM && provider.auth.token.isNotBlank()) {
                request.url(chain.request().url.newBuilder()
                    .setQueryParameter(provider.auth.key?.takeIf { it.isNotBlank() } ?: "key", provider.auth.token).build())
            }
            chain.proceed(request.build())
        }.build()
        return Client.builder()
            .apiKey(if (provider.auth.method == AuthMethod.PLATFORM_DEFAULT && provider.auth.token.isNotBlank()) provider.auth.token else "gateway-auth-configured-in-transport")
            .httpOptions(HttpOptions.builder()
                .baseUrl(if (version == null) base else base.removeSuffix("/$version"))
                .apiVersion(version ?: "v1beta").timeout(120_000).build())
            .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
            .build()
    }

    suspend fun testConnection(provider: LlmProviderInfo): Result<String> = try {
        runInterruptible<Unit>(Dispatchers.IO) {
            when (provider.type) {
                ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> openAi(provider).useClient { it.models().list() }
                ProviderType.ANTHROPIC -> anthropic(provider).useClient { it.models().list() }
                ProviderType.GOOGLE -> google(provider).use { it.models.list(null).iterator().hasNext() }
                ProviderType.OLLAMA -> error("Use the native Ollama adapter")
                else -> error("Use the registered account-provider adapter")
            }
        }
        Result.success("${provider.type.displayName} connection successful!")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

    fun streamOpenAi(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        reasoningEffort: String? = null,
        sendThinkingContent: Boolean = false
    ): Flow<GenerationEvent> = flow {
        openAi(provider).useClient { client ->
            val params = ChatCompletionCreateParams.builder().model(modelName).maxTokens(maxTokens.toLong())
            if (systemPrompt.isNotBlank()) params.addSystemMessage(systemPrompt)
            messages.forEach { message ->
                if (message.role == ChatRole.MODEL) {
                    val assistant = com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
                        .content(message.content)
                    message.reasoningContent
                        ?.takeIf { sendThinkingContent && it.isNotBlank() }
                        ?.let { reasoning ->
                            assistant.putAdditionalProperty(
                                "reasoning_content",
                                com.openai.core.JsonValue.from(reasoning)
                            )
                        }
                    params.addMessage(assistant.build())
                } else if (message.files.isNotEmpty()) {
                    params.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .contentOfArrayOfContentParts(openAiChatContent(message))
                            .build()
                    )
                } else {
                    params.addUserMessage(message.content)
                }
            }
            temperature?.let { params.temperature(it) }
            topP?.let { params.topP(it) }
            reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                params.putAdditionalBodyProperty("reasoning_effort", com.openai.core.JsonValue.from(effort))
            }

            fun reasoningFrom(properties: Map<String, com.openai.core.JsonValue>): String? =
                runCatching { properties["reasoning_content"]?.convert(String::class.java) }
                    .getOrNull()?.takeIf { it.isNotBlank() }

            if (!provider.config.supportStream) {
                val response = sdkCall { client.chat().completions().create(params.build()) }
                response.choices().firstOrNull()?.message()?.let { message ->
                    reasoningFrom(message._additionalProperties())?.let { emit(GenerationEvent.Reasoning(it)) }
                    message.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                return@useClient
            }

            sdkCall { client.chat().completions().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                while (sdkCall { iterator.hasNext() }) {
                    val delta = iterator.next().choices().firstOrNull()?.delta() ?: continue
                    reasoningFrom(delta._additionalProperties())?.let { emit(GenerationEvent.Reasoning(it)) }
                    delta.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun streamResponses(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        reasoningEffort: String? = null,
        sendThinkingContent: Boolean = false
    ): Flow<GenerationEvent> = flow {
        openAi(provider).useClient { client ->
            val inputItems = buildList {
                messages.forEach { message ->
                    if (message.role == ChatRole.MODEL && sendThinkingContent) {
                        message.reasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                            val content = com.openai.models.responses.ResponseReasoningItem.Content.builder()
                                .text(reasoning)
                                .build()
                            val reasoningItem = com.openai.models.responses.ResponseReasoningItem.builder()
                                .id("rs_${message.id}")
                                .summary(emptyList())
                                .content(listOf(content))
                                .build()
                            add(ResponseInputItem.ofReasoning(reasoningItem))
                        }
                    }
                    val easy = EasyInputMessage.builder()
                        .role(if (message.role == ChatRole.MODEL) EasyInputMessage.Role.ASSISTANT else EasyInputMessage.Role.USER)
                    if (message.role == ChatRole.USER && message.files.isNotEmpty()) {
                        easy.contentOfResponseInputMessageContentList(openAiResponseContent(message))
                    } else {
                        easy.content(message.content)
                    }
                    add(ResponseInputItem.ofEasyInputMessage(easy.build()))
                }
            }
            val params = ResponseCreateParams.builder().model(modelName).maxOutputTokens(maxTokens.toLong()).store(false)
                .inputOfResponse(inputItems)
            if (systemPrompt.isNotBlank()) params.instructions(systemPrompt)
            temperature?.let { params.temperature(it) }
            topP?.let { params.topP(it) }
            reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                params.putAdditionalBodyProperty(
                    "reasoning",
                    com.openai.core.JsonValue.from(mapOf("effort" to effort))
                )
            }
            if (!provider.config.supportStream) {
                val response = sdkCall { client.responses().create(params.build()) }
                check(!response.error().isPresent) { "OpenAI Responses request failed" }
                response.output().forEach { item ->
                    item.reasoning().orElse(null)?.content()?.orElse(emptyList())?.forEach { part ->
                        part.text().takeIf { it.isNotBlank() }?.let { emit(GenerationEvent.Reasoning(it)) }
                    }
                    item.message().orElse(null)?.content()?.forEach { content ->
                        content.outputText().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                    }
                }
                return@useClient
            }
            sdkCall { client.responses().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                var finished = false
                while (sdkCall { iterator.hasNext() }) {
                    val event = iterator.next()
                    check(!event.error().isPresent && !event.failed().isPresent) { "OpenAI Responses stream failed" }
                    event.reasoningTextDelta().orElse(null)?.delta()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Reasoning(it))
                    }
                    event.outputTextDelta().orElse(null)?.delta()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Text(it))
                    }
                    if (event.completed().isPresent || event.incomplete().isPresent) finished = true
                }
                check(finished) { "OpenAI Responses stream ended without a final response" }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun streamAnthropic(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        topK: Int? = null,
        sendThinkingContent: Boolean = false
    ): Flow<GenerationEvent> = flow {
        anthropic(provider).useClient { client ->
            val params = MessageCreateParams.builder().model(modelName).maxTokens(maxTokens.toLong())
            if (systemPrompt.isNotBlank()) params.system(systemPrompt)
            messages.forEach { message ->
                if (message.role == ChatRole.MODEL && sendThinkingContent && !message.reasoningContent.isNullOrBlank()) {
                    val blocks = mutableListOf<com.anthropic.models.messages.ContentBlockParam>()
                    val thinking = com.anthropic.models.messages.ThinkingBlockParam.builder()
                        .thinking(message.reasoningContent!!)
                        .signature(message.reasoningSignature.orEmpty())
                        .build()
                    blocks += com.anthropic.models.messages.ContentBlockParam.ofThinking(thinking)
                    if (message.content.isNotEmpty()) {
                        blocks += com.anthropic.models.messages.ContentBlockParam.ofText(message.content)
                    }
                    params.addMessage(
                        com.anthropic.models.messages.MessageParam.builder()
                            .role(com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(blocks)
                            .build()
                    )
                } else if (message.role == ChatRole.MODEL) {
                    params.addAssistantMessage(message.content)
                } else if (message.files.isNotEmpty()) {
                    params.addMessage(
                        com.anthropic.models.messages.MessageParam.builder()
                            .role(com.anthropic.models.messages.MessageParam.Role.USER)
                            .contentOfBlockParams(anthropicContent(message))
                            .build()
                    )
                } else {
                    params.addUserMessage(message.content)
                }
            }
            @Suppress("DEPRECATION")
            run {
                temperature?.let { params.temperature(it) }
                topP?.let { params.topP(it) }
                topK?.let { params.topK(it.toLong()) }
            }

            if (!provider.config.supportStream) {
                sdkCall { client.messages().create(params.build()) }.content().forEach { block ->
                    block.thinking().orElse(null)?.let { thinking ->
                        emit(GenerationEvent.Reasoning(thinking.thinking(), thinking.signature()))
                    }
                    block.text().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
                return@useClient
            }

            sdkCall { client.messages().createStreaming(params.build()) }.use { response ->
                val iterator = response.stream().iterator()
                while (sdkCall { iterator.hasNext() }) {
                    val delta = iterator.next().contentBlockDelta().orElse(null)?.delta() ?: continue
                    delta.thinking().orElse(null)?.thinking()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Reasoning(it))
                    }
                    delta.signature().orElse(null)?.signature()?.takeIf { it.isNotEmpty() }?.let {
                        emit(GenerationEvent.Reasoning("", it))
                    }
                    delta.text().orElse(null)?.text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun streamGoogle(
        provider: LlmProviderInfo,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int,
        topK: Int? = null,
        sendThinkingContent: Boolean = false
    ): Flow<GenerationEvent> = flow {
        google(provider).use { client ->
            val contents = mutableListOf<Content>()
            for (message in messages) {
                contents += Content.builder()
                    .role(if (message.role == ChatRole.MODEL) "model" else "user")
                    .parts(googleParts(client, message))
                    .build()
            }
            val config = GenerateContentConfig.builder().maxOutputTokens(maxTokens)
            if (systemPrompt.isNotBlank()) config.systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
            temperature?.let { config.temperature(it.toFloat()) }
            topP?.let { config.topP(it.toFloat()) }
            topK?.let { config.topK(it.toFloat()) }
            if (!provider.config.supportStream) {
                sdkCall { client.models.generateContent(modelName.removePrefix("models/"), contents, config.build()) }
                    .text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                return@use
            }
            sdkCall { client.models.generateContentStream(modelName.removePrefix("models/"), contents, config.build()) }.use { response ->
                val iterator = response.iterator()
                while (sdkCall { iterator.hasNext() }) {
                    iterator.next().text()?.takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}