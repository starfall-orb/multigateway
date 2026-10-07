package org.starfall.multigateway.data.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.*
import java.util.UUID

/** Native structured tool calls, bounded rounds, no executable text extracted from answers. */
class ToolChat(private val http: ToolHttp, private val mcp: McpService, private val llm: LlmService) {
    fun generateMedia(
        provider: LlmProviderInfo,
        model: String,
        kind: ModelType,
        prompt: String,
        imageOptions: JsonObject = obj(),
        attachments: List<String> = emptyList(),
        videoOptions: JsonObject = obj()
    ): Flow<GenerationEvent> = flow {
        require(kind == ModelType.IMAGE_GENERATION || kind == ModelType.VIDEO_GENERATION)
        val name = if (kind == ModelType.IMAGE_GENERATION) "generate_image" else "generate_video"
        var activity = ToolActivity(UUID.randomUUID().toString(), name,
            arguments = obj("prompt" to str(prompt)).toString())
        emit(GenerationEvent.Tool(activity))
        try {
            require(kind != ModelType.VIDEO_GENERATION || attachments.size <= 1) {
                "Video generation accepts one reference image. Remove extra attachments."
            }
            require(attachments.size <= 16) { "Image generation accepts at most 16 reference images." }
            val maxBytes = if (kind == ModelType.IMAGE_GENERATION && provider.type.isOpenAi && !isAgnesProvider(provider)) 50L * 1024 * 1024 - 1 else MAX_MEDIA_INPUT_BYTES
            val inputs = llm.importToolAttachments(attachments, http.requireFiles(), maxBytes)
            if (inputs.isNotEmpty()) {
                activity = activity.copy(arguments = buildJsonObject {
                    put("prompt", prompt)
                    if (kind == ModelType.VIDEO_GENERATION) put("input_image", inputs.single())
                    else put("input_images", JsonArray(inputs.map(::str)))
                }.toString())
                emit(GenerationEvent.Tool(activity))
            }
            val result = SystemMediaTools(http).generate(name, provider, model, prompt, imageOptions,
                inputImage = if (kind == ModelType.VIDEO_GENERATION) inputs.singleOrNull() else null,
                inputImages = if (kind == ModelType.IMAGE_GENERATION) inputs else emptyList(),
                videoOptions = videoOptions)
            val summary = summarizeToolResult(result, http.requireFiles())
            emit(GenerationEvent.Tool(activity.copy(status = "success", summary = summary.preview,
                files = summary.files, response = summary.content.toString())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = obj(
                "isError" to JsonPrimitive(true),
                "status" to str("error"),
                "error" to str(e.message.orEmpty().take(500))
            )
            emit(GenerationEvent.Tool(activity.copy(
                status = "error",
                summary = e.message.orEmpty().take(500),
                response = error.toString()
            )))
            throw e
        }
    }

    suspend fun completeText(
        provider: LlmProviderInfo,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        maxOutputTokens: Int? = null
    ): String {
        val output = StringBuilder()
        llm.streamContent(provider, model, messages, prompt, maxOutputTokens).collect { output.append(it) }
        return output.toString()
    }

    fun generate(
        provider: LlmProviderInfo,
        model: String,
        messages: List<StoredMessage>,
        prompt: String,
        servers: List<McpInfo>,
        providers: List<LlmProviderInfo>,
        settings: () -> ToolSettings
    ): Flow<GenerationEvent> = channelFlow {
        val sessions = mutableMapOf<String, McpSession>()
        val tools = mutableListOf<ToolDefinition>()
        try {
            servers
                .filter { settings().quickMcp[it.id] != false }
                .forEach { server ->
                    val activity = ToolActivity(UUID.randomUUID().toString(), "${server.name}: connect")
                    send(GenerationEvent.Tool(activity))
                    try {
                        val session = mcp.session(server)
                        sessions[server.id] = session
                        tools += session.tools().filter {
                            globalMcpToolEnabled(settings(), server.id, it.originalName)
                        }
                        send(GenerationEvent.Tool(activity.copy(status = "success", summary = "Tools ready")))
                    } catch (e: CancellationException) {
                        send(GenerationEvent.Tool(activity.copy(status = "cancelled")))
                        throw e
                    } catch (e: Exception) {
                        send(GenerationEvent.Tool(activity.copy(status = "error", summary = e.message.orEmpty().take(500))))
                    }
                }

            listOf("generate_image", "generate_video").forEach { name ->
                val config = settings().system[name]
                if (config?.enabled == true) {
                    tools += ToolDefinition(
                        name = name,
                        description = if (name == "generate_image") {
                            "Generate or edit an image from a detailed prompt and optional attached/reference images. The app displays the saved image."
                        } else {
                            "Generate a video from a detailed prompt. The app displays the saved video."
                        },
                        schema = mediaToolSchema(name)
                    )
                }
            }

            if (tools.isEmpty()) {
                llm.streamEvents(provider, model, messages, prompt).collect { send(it) }
                return@channelFlow
            }

            http.requireFiles()
            val history = mutableListOf<JsonObject>()
            if (provider.type != ProviderType.GOOGLE && prompt.isNotBlank()) {
                history += obj("role" to str("system"), "content" to str(prompt))
            }
            val sendThinkingContent = provider.config.modelConfigs[model]?.sendThinkingContent == true
            val importedAttachments = mutableMapOf<String, String>()
            messages.forEach { message ->
                val toolSummary = message.activeVersion.toolActivity
                    .filter { it.status != "running" }
                    .joinToString("\n", prefix = "\n") {
                        val references = it.files.filter { name -> http.requireFiles().resolve(name) != null }
                            .joinToString(", ") { name -> "tool-file:$name" }
                        "Tool ${it.name}: ${it.status}. ${it.summary.take(2000)}" +
                            if (references.isEmpty()) "" else " Reusable files: $references"
                    }
                val attachments = if (message.files.isEmpty()) JsonArray(emptyList()) else
                    llm.toolAttachments(message, http.requireFiles(), importedAttachments)
                val baseMessage = obj(
                    "role" to str(if (message.role == ChatRole.MODEL) "assistant" else "user"),
                    "content" to str(message.content + toolSummary + toolAttachmentReferences(attachments))
                )
                var wireMessage = if (message.files.isEmpty()) baseMessage else
                    JsonObject(baseMessage + ("_attachments" to attachments))
                val reasoning = message.reasoningContent?.takeIf {
                    sendThinkingContent && message.role == ChatRole.MODEL && it.isNotBlank()
                }
                if (reasoning != null) {
                    wireMessage = JsonObject(wireMessage + ("reasoning_content" to str(reasoning)))
                    message.reasoningSignature?.takeIf { it.isNotBlank() }?.let { signature ->
                        wireMessage = JsonObject(wireMessage + ("reasoning_signature" to str(signature)))
                    }
                }
                history += wireMessage
            }

            val budget = ToolBudget()
            val fileSender = SendFileTool(http)
            var fileDeliveryAvailable = false
            repeat(12) {
                currentCoroutineContext().ensureActive()
                val allowed = tools.filter { tool ->
                    if (tool.serverId == null) {
                        if (tool.name == SEND_FILE_TOOL_NAME) fileDeliveryAvailable
                        else settings().system[tool.name]?.enabled == true
                    } else {
                        settings().quickMcp[tool.serverId] != false &&
                            globalMcpToolEnabled(settings(), tool.serverId, tool.originalName)
                    }
                }

                val streaming = provider.streamEnabledFor(model)
                val turn = request(
                    provider,
                    model,
                    history,
                    allowed,
                    prompt,
                    onText = { send(GenerationEvent.Text(it)) },
                    onReasoning = { send(GenerationEvent.Reasoning(it)) }
                )
                val reasoningSignature = turn.text("reasoning_signature").takeIf { it.isNotBlank() }
                if (!streaming) {
                    turn.text("reasoning_content").takeIf { it.isNotBlank() }?.let {
                        send(GenerationEvent.Reasoning(it, reasoningSignature))
                    }
                    turn.text("content").takeIf { it.isNotBlank() }?.let {
                        send(GenerationEvent.Text(it))
                    }
                } else if (reasoningSignature != null) {
                    send(GenerationEvent.Reasoning("", reasoningSignature))
                }

                val calls = turn["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
                if (calls.isEmpty()) return@channelFlow
                check(calls.size <= 16) { "Too many tool calls in a single response" }
                history += turn

                calls.forEach { value ->
                    val call = value.jsonObject
                    val function = call.requireObject("function")
                    val name = function.text("name")
                    val tool = allowed.find { it.name == name }
                    val rawArguments = when (val raw = function["arguments"]) {
                        null, JsonNull -> ""
                        is JsonPrimitive -> raw.content
                        else -> raw.toString()
                    }
                    val activity = ToolActivity(
                        id = UUID.randomUUID().toString(),
                        name = tool?.let { definition ->
                            servers.find { it.id == definition.serverId }
                                ?.let { "${it.name} / ${definition.originalName}" }
                                ?: definition.originalName
                        } ?: name,
                        arguments = rawArguments
                    )
                    send(GenerationEvent.Tool(activity))

                    var result: JsonObject
                    try {
                        require(tool != null) { "Tool is not enabled" }
                        budget.consume(if (tool.serverId == null) name else null)
                        val args = toolArguments(function["arguments"])
                        result = if (tool.serverId != null) {
                            check(
                                settings().quickMcp[tool.serverId] != false &&
                                    globalMcpToolEnabled(settings(), tool.serverId, tool.originalName)
                            ) { "MCP tool disabled" }
                            (sessions[tool.serverId] ?: error("MCP session unavailable"))
                                .call(tool.originalName, args)
                        } else if (name == SEND_FILE_TOOL_NAME) {
                            fileSender.execute(args)
                        } else {
                            val cfg = settings().system[name] ?: error("System tool is not configured")
                            check(cfg.enabled) { "System tool disabled" }
                            val mediaProvider = providers.find { it.id == cfg.providerId }
                                ?: error("Select a provider for this system tool")
                            val kind = if (name == "generate_image") {
                                ModelType.IMAGE_GENERATION
                            } else {
                                ModelType.VIDEO_GENERATION
                            }
                            check(
                                mediaProvider.config.modelConfigs[cfg.modelId]?.modelType == kind &&
                                    mediaProvider.config.modelIds?.contains(cfg.modelId) != false
                            ) { "Select an available media model in System tools" }
                            SystemMediaTools(http).generate(
                                name,
                                mediaProvider,
                                cfg.modelId,
                                args.text("prompt"),
                                cfg.imageOptions,
                                mediaInputImageArgument(args),
                                mediaInputImagesArgument(args),
                                videoOptions = cfg.videoOptions,
                                inputImageUrl = mediaInputImageUrlArgument(args)
                            )
                        }

                        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                        val contentApi = servers.find { it.id == tool.serverId }?.isContentApiPreset() == true
                        val contentApiMedia = if (contentApi) resolveContentApiMedia(result, http) else null
                        if (contentApiMedia != null) result = contentApiMedia.content
                        val summary = summarizeToolResult(result, http.requireFiles())
                        result = summary.content
                        if (tool.serverId != null) {
                            val availableFiles = (summary.files.filterNot { it == summary.responseFile } + contentApiMedia?.files.orEmpty()).distinct()
                            result = fileDeliveryResult(result, availableFiles)
                        }
                        send(
                            GenerationEvent.Tool(
                                activity.copy(
                                    status = if (isError) "error" else "success",
                                    summary = when {
                                        name == SEND_FILE_TOOL_NAME && tool.serverId == null -> "File sent to chat."
                                        contentApiMedia?.files?.isNotEmpty() == true -> "Prepared ${contentApiMedia.files.size} media item(s). Use send_file to send them to chat."
                                        else -> summary.preview
                                    },
                                    files = when {
                                        tool.serverId != null -> listOf(summary.responseFile)
                                        name == SEND_FILE_TOOL_NAME -> listOf(result.text("uri").removePrefix("tool-file:"))
                                        else -> summary.files
                                    },
                                    inlineMedia = tool.serverId == null && name == SEND_FILE_TOOL_NAME,
                                    responseFile = summary.responseFile,
                                    response = result.toString()
                                )
                            )
                        )
                    } catch (e: CancellationException) {
                        send(GenerationEvent.Tool(activity.copy(status = "cancelled", summary = "Stopped")))
                        throw e
                    } catch (e: Exception) {
                        result = obj(
                            "isError" to JsonPrimitive(true),
                            "status" to str("error"),
                            "error" to str(e.message.orEmpty().take(500))
                        )
                        send(
                            GenerationEvent.Tool(
                                activity.copy(
                                    status = "error",
                                    summary = e.message.orEmpty().take(500),
                                    response = result.toString()
                                )
                            )
                        )
                    }

                    if (tool?.serverId != null) {
                        if (!fileDeliveryAvailable) tools += sendFileDefinition
                        fileDeliveryAvailable = true
                    }
                    history += obj(
                        "role" to str("tool"),
                        "tool_call_id" to str(call.text("id")),
                        "name" to str(name),
                        "content" to str(result.toString())
                    )
                }
            }
            error("Stopped after 12 tool rounds. Send another message to continue.")
        } finally {
            sessions.values.forEach { it.close() }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun request(
        p: LlmProviderInfo,
        model: String,
        history: List<JsonObject>,
        tools: List<ToolDefinition>,
        prompt: String,
        onText: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit
    ): JsonObject {
        val defs = JsonArray(tools.map { obj("type" to str("function"),"function" to obj("name" to str(it.name),"description" to str(it.description),"parameters" to it.schema)) })
        val wireProvider = llm.prepareAccountProvider(p, model)
        val base = providerBase(wireProvider)
        val config = p.config.modelConfigs[model] ?: ModelConfiguration()
        suspend fun modelResponse(url: String, body: JsonObject, provider: LlmProviderInfo,
            stream: Boolean, text: suspend (String) -> Unit, reasoning: suspend (String) -> Unit = {}): JsonObject {
            val requestBody = if (p.type == ProviderType.ANTIGRAVITY) JsonObject(body + ("model" to str(model))) else body
            return http.modelResponse(llm.accountRequestUrl(p, provider, url),
                llm.normalizeAccountToolRequest(p, provider, requestBody, prompt), llm.prepareAccountRequest(p, provider, requestBody), stream, text, reasoning,
                { llm.unwrapAccountResponse(p, it) })
        }

        return when(wireProvider.type) {
            ProviderType.OPENAI_RESPONSES -> {
                val sendReasoning = config.sendThinkingContent
                val isDeepSeek = wireProvider.baseUrl.contains("deepseek.com", ignoreCase = true) || model.startsWith("deepseek-", ignoreCase = true)
                val input = history.flatMap { message ->
                    when {
                        message["responsesOutput"] is JsonArray -> {
                            val items = message.requireArray("responsesOutput").toList()
                            if (sendReasoning) items else items.filterNot { it.requireObject().text("type") == "reasoning" }
                        }
                        message.text("role") == "tool" -> listOf(obj(
                            "type" to str("function_call_output"), "call_id" to str(message.text("tool_call_id")),
                            "output" to str(message.text("content"))
                        ))
                        message.text("role") == "assistant" -> buildList {
                            message.text("reasoning_content").takeIf { sendReasoning && it.isNotBlank() }?.let { reasoning ->
                                add(obj(
                                    "type" to str("reasoning"),
                                    "content" to JsonArray(listOf(obj("type" to str("reasoning_text"), "text" to str(reasoning))))
                                ))
                            }
                            add(obj("role" to str("assistant"), "content" to str(message.text("content"))))
                        }
                        else -> listOf(JsonObject(message.filterKeys {
                            it != "reasoning_content" && it != "reasoning_signature" && it != "_attachments"
                        } + ("content" to toolMessageContent(message, ProviderType.OPENAI_RESPONSES))))
                    }
                }
                val requestBody = buildJsonObject {
                    put("model", model); put("input", JsonArray(input)); put("store", false)
                    if (!isDeepSeek) put("include", JsonArray(listOf(str("reasoning.encrypted_content"))))
                    put("max_output_tokens", p.config.maxTokens)
                    config.temperature?.let { put("temperature", it) }
                    config.topP?.let { put("top_p", it) }
                    config.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { effort ->
                        put("reasoning", buildJsonObject { put("effort", effort) })
                    }
                    if (tools.isNotEmpty()) put("tools", JsonArray(tools.map {
                        obj("type" to str("function"), "name" to str(it.name), "description" to str(it.description),
                            "parameters" to it.schema, "strict" to JsonPrimitive(false))
                    }))
                }
                val response = modelResponse(
                    "$base/responses",
                    requestBody,
                    wireProvider,
                    p.streamEnabledFor(model),
                    onText,
                    onReasoning
                )
                providerTurn(wireProvider.type, response)
            }
            ProviderType.OPENAI, ProviderType.OLLAMA -> {
                val ollama = wireProvider.type == ProviderType.OLLAMA
                val sendReasoning = config.sendThinkingContent && !ollama
                val wireHistory = history.map { message ->
                    var clean = JsonObject(message.filterKeys { key ->
                        key != "_attachments" && key != "responsesOutput" && key != "reasoning_signature" && (sendReasoning || key != "reasoning_content")
                    } + ("content" to toolMessageContent(message, wireProvider.type)))
                    if (ollama && message["_attachments"] is JsonArray) {
                        val images = message.getValue("_attachments").jsonArray.map {
                            val file = it.jsonObject
                            require(file.text("mimeType").startsWith("image/")) { "Unsupported Ollama attachment" }
                            file.getValue("data")
                        }
                        clean = JsonObject(clean + ("images" to JsonArray(images)))
                    }
                    when (message.text("role")) {
                        "tool" -> if (ollama) JsonObject(clean + ("tool_name" to str(message.text("name")))) else clean
                        "assistant" -> {
                            val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { c ->
                                val call = c.requireObject()
                                val f = call.requireObject("function")
                                val rawArgs = f["arguments"]
                                val args = if (ollama) {
                                    rawArgs as? JsonObject ?: Json.parseToJsonElement(f.text("arguments"))
                                } else {
                                    str(if (rawArgs is JsonPrimitive) rawArgs.content else rawArgs?.toString() ?: "{}")
                                }
                                if (ollama) obj("function" to JsonObject(f + ("arguments" to args)))
                                else obj(
                                    "id" to str(call.text("id")),
                                    "type" to str("function"),
                                    "function" to JsonObject(f + ("arguments" to args))
                                )
                            }
                            JsonObject(clean + ("tool_calls" to JsonArray(calls)))
                        }
                        else -> clean
                    }
                }
                val request = buildJsonObject {
                    put("model", model); put("messages", JsonArray(wireHistory)); put("stream", false)
                    if (tools.isNotEmpty()) put("tools", defs)
                    if (ollama) put("options", buildJsonObject {
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.topK?.let { put("top_k", it) }
                        put("num_predict", p.config.maxTokens)
                    }) else {
                        put("max_tokens", p.config.maxTokens)
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            put("reasoning_effort", it)
                        }
                    }
                }
                val response = modelResponse(
                    if (ollama) base.removeSuffix("/api") + "/api/chat" else "$base/chat/completions",
                    request, wireProvider, p.streamEnabledFor(model), onText, onReasoning
                )
                providerTurn(wireProvider.type, response)
            }
            ProviderType.ANTHROPIC -> {
                val native = mutableListOf<JsonObject>()
                history.filter { it.text("role") != "system" }.forEach { message ->
                    val role = message.text("role")
                    val blocks = mutableListOf<JsonElement>()
                    if (role == "tool") {
                        blocks += obj(
                            "type" to str("tool_result"),
                            "tool_use_id" to str(message.text("tool_call_id")),
                            "content" to str(message.text("content"))
                        )
                    } else {
                        if (role == "assistant" && config.sendThinkingContent) {
                            message.text("reasoning_content").takeIf { it.isNotBlank() }?.let { reasoning ->
                                val signature = message.text("reasoning_signature")
                                blocks += buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", reasoning)
                                    if (signature.isNotBlank()) put("signature", signature)
                                }
                            }
                        }
                        blocks += toolAttachmentParts(message, ProviderType.ANTHROPIC)
                        if (message.text("content").isNotEmpty()) {
                            blocks += obj("type" to str("text"), "text" to str(message.text("content")))
                        }
                        (message["tool_calls"] as? JsonArray).orEmpty().forEach { c ->
                            val call = c.jsonObject
                            val f = call.requireObject("function")
                            blocks += obj(
                                "type" to str("tool_use"),
                                "id" to str(call.text("id")),
                                "name" to str(f.text("name")),
                                "input" to (f["arguments"] as? JsonObject ?: Json.parseToJsonElement(f.text("arguments")))
                            )
                        }
                    }
                    val targetRole = if (role == "assistant") "assistant" else "user"
                    if (native.lastOrNull()?.text("role") == targetRole) {
                        val last = native.removeAt(native.lastIndex)
                        native += obj("role" to str(targetRole), "content" to JsonArray(last.requireArray("content") + blocks))
                    } else {
                        native += obj("role" to str(targetRole), "content" to JsonArray(blocks))
                    }
                }
                val response = modelResponse(base.removeSuffix("/v1") + "/v1/messages", buildJsonObject {
                    put("model", model); put("max_tokens", p.config.maxTokens); put("system", prompt); put("messages", JsonArray(native))
                    config.claudeThinkingParameters(model, p.config.maxTokens).forEach { (key, value) -> put(key, value) }
                    if (tools.isNotEmpty()) put("tools", JsonArray(tools.map {
                        obj("name" to str(it.name), "description" to str(it.description), "input_schema" to it.schema)
                    }))
                    if (config.claudeAllowsSampling(model, p.config.maxTokens)) {
                        config.temperature?.let { put("temperature", it) }
                        config.topP?.let { put("top_p", it) }
                        config.topK?.let { put("top_k", it) }
                    }
                }, wireProvider, p.streamEnabledFor(model), onText, onReasoning)
                providerTurn(wireProvider.type, response)
            }
            ProviderType.GOOGLE -> {
                val native=history.map { message ->
                    val role=message.text("role"); val parts=mutableListOf<JsonElement>()
                    if(role=="tool") parts += obj("functionResponse" to obj("name" to str(message.text("name")),"response" to obj("result" to str(message.text("content")))))
                    else {
                        parts += toolAttachmentParts(message, ProviderType.GOOGLE)
                        if(message.text("content").isNotBlank()) parts += obj("text" to str(message.text("content")))
                        (message["tool_calls"] as? JsonArray).orEmpty().forEach { c -> val f=c.requireObject().requireObject("function")
                            parts += (c.jsonObject["googlePart"] ?: obj("functionCall" to obj("name" to str(f.text("name")),"args" to (f["arguments"] as? JsonObject ?: Json.parseToJsonElement(f.text("arguments")))))) }
                    }
                    obj("role" to str(if(role=="assistant") "model" else "user"),"parts" to JsonArray(parts))
                }
                val root=if(Regex("/v1(?:beta|alpha)?$").containsMatchIn(base)) base else "$base/v1beta"
                val response=modelResponse("$root/models/$model:generateContent",buildJsonObject {
                    put("contents",JsonArray(native)); put("systemInstruction",obj("parts" to JsonArray(listOf(obj("text" to str(prompt))))))
                    if(tools.isNotEmpty()) put("tools",JsonArray(listOf(obj("functionDeclarations" to JsonArray(tools.map { obj("name" to str(it.name),"description" to str(it.description),"parameters" to it.schema) })))))
                    put("generationConfig",buildJsonObject { put("maxOutputTokens",p.config.maxTokens); config.temperature?.let { put("temperature",it) }; config.topP?.let { put("topP",it) }; config.topK?.let { put("topK",it) }; config.googleThinkingConfig()?.let { put("thinkingConfig", it) } })
                },wireProvider,p.streamEnabledFor(model),onText,onReasoning)
                providerTurn(wireProvider.type, response)
            }
            else -> error("Unsupported provider protocol: ${wireProvider.type.displayName}")
        }
    }
}
