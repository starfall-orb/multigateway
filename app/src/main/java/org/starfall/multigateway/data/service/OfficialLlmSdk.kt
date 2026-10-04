package org.starfall.multigateway.data.service

import com.openai.client.OpenAIClientImpl
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.starfall.multigateway.data.model.*
import java.time.Duration

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

    private val content = SdkAttachmentContent(attachments)
    private fun openAiChatContent(message: StoredMessage) = content.openAiChatContent(message)
    private fun openAiResponseContent(message: StoredMessage) = content.openAiResponseContent(message)
    private fun anthropicContent(message: StoredMessage) = content.anthropicContent(message)
    private suspend fun googleParts(client: Client, message: StoredMessage) = content.googleParts(client, message)

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
            val modelConfig = provider.config.modelConfigs[modelName]
            if (modelConfig?.reasoningDisabled == true) {
                params.putAdditionalBodyProperty("thinking", com.anthropic.core.JsonValue.from(mapOf("type" to "disabled")))
            } else if (modelConfig?.reasoningEffort != null && maxTokens > 1024) {
                params.putAdditionalBodyProperty("thinking", com.anthropic.core.JsonValue.from(
                    mapOf("type" to "enabled", "budget_tokens" to modelConfig.reasoningBudget(maxTokens))))
            }
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
            provider.config.modelConfigs[modelName]?.let { modelConfig ->
                if (modelConfig.reasoningDisabled) {
                    config.thinkingConfig(com.google.genai.types.ThinkingConfig.builder().thinkingBudget(0).includeThoughts(false).build())
                } else modelConfig.reasoningEffort?.let {
                    config.thinkingConfig(com.google.genai.types.ThinkingConfig.builder()
                        .thinkingLevel(if (it == "xhigh") "high" else it).includeThoughts(true).build())
                }
            }
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
