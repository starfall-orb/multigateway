package org.starfall.multigateway.ui.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.starfall.multigateway.data.model.*

private const val STREAM_RENDER_INTERVAL_MS = 48L
private const val LARGE_STREAM_RENDER_INTERVAL_MS = 160L
private const val LARGE_STREAM_THRESHOLD_CHARS = 32_000
private const val VERY_LARGE_STREAM_THRESHOLD_CHARS = 128_000
private const val MAX_GENERATION_TEXT_CHARS = 512_000
private const val MAX_GENERATION_SIGNATURE_CHARS = 64_000

/** A single generation owns its conversation snapshot, independently of navigation. */
internal class ChatGeneration(
    private val scope: CoroutineScope,
    private val save: suspend (Conversation) -> Unit,
    private val publish: (Conversation) -> Unit
) {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId = _conversationId.asStateFlow()
    var snapshot: Conversation? = null
        private set
    private var job: Job? = null

    fun start(conversation: Conversation, messageId: String, chunks: Flow<String>): Boolean =
        startEvents(conversation, messageId, chunks.map { GenerationEvent.Text(it) })

    fun startEvents(conversation: Conversation, messageId: String, chunks: Flow<GenerationEvent>): Boolean {
        if (_busy.value) return false
        _busy.value = true
        _error.value = null
        _conversationId.value = conversation.id
        snapshot = conversation
        publish(conversation)
        job = scope.launch(start = CoroutineStart.LAZY) {
            val output = StringBuilder()
            val reasoningOutput = StringBuilder()
            val reasoningSignatureOutput = StringBuilder()
            var activeMessageId = messageId
            var renderJob: Job? = null
            var lastRenderAt = 0L
            var generationInterrupted = false

            fun appendLimited(target: StringBuilder, text: String, limit: Int, label: String) {
                check(target.length.toLong() + text.length <= limit) {
                    "$label exceeds the safe response size limit"
                }
                target.append(text)
            }

            fun appendError(text: String) {
                if (output.length >= MAX_GENERATION_TEXT_CHARS) return
                output.append(text.take(MAX_GENERATION_TEXT_CHARS - output.length))
            }

            fun update() {
                snapshot = updateResponse(
                    snapshot!!,
                    activeMessageId,
                    output.toString(),
                    reasoningOutput.toString().takeIf { it.isNotBlank() },
                    reasoningSignatureOutput.toString().takeIf { it.isNotBlank() }
                )
                publish(snapshot!!)
                lastRenderAt = System.currentTimeMillis()
            }

            fun scheduleUpdate() {
                if (renderJob?.isActive == true) return
                val elapsed = System.currentTimeMillis() - lastRenderAt
                val responseSize = maxOf(output.length, reasoningOutput.length)
                val interval = when {
                    responseSize >= VERY_LARGE_STREAM_THRESHOLD_CHARS -> LARGE_STREAM_RENDER_INTERVAL_MS * 2
                    responseSize >= LARGE_STREAM_THRESHOLD_CHARS -> LARGE_STREAM_RENDER_INTERVAL_MS
                    else -> STREAM_RENDER_INTERVAL_MS
                }
                val waitMs = (interval - elapsed).coerceAtLeast(0L)
                if (waitMs == 0L) {
                    update()
                } else {
                    renderJob = launch {
                        delay(waitMs)
                        update()
                    }
                }
            }

            try {
                save(snapshot!!)
                chunks.collect { chunk ->
                    when (chunk) {
                        is GenerationEvent.Text -> {
                            appendLimited(output, chunk.text, MAX_GENERATION_TEXT_CHARS, "Answer")
                            scheduleUpdate()
                        }
                        is GenerationEvent.Reasoning -> {
                            appendLimited(reasoningOutput, chunk.text, MAX_GENERATION_TEXT_CHARS, "Reasoning")
                            chunk.signature?.let {
                                appendLimited(reasoningSignatureOutput, it, MAX_GENERATION_SIGNATURE_CHARS, "Reasoning signature")
                            }
                            scheduleUpdate()
                        }
                        is GenerationEvent.Tool -> {
                            // Flush pending text before inserting a tool block so its visual anchor is stable.
                            renderJob?.cancel()
                            renderJob = null
                            update()

                            val contentOffset = visibleResponseContent(output.toString()).length
                            snapshot = snapshot!!.copy(messages = snapshot!!.messages.map { message ->
                                if (message.id != activeMessageId) message else message.copy(versions = message.versions.mapIndexed { index, version ->
                                    if (index != message.activeVersionIndex) version else {
                                        val existingIndex = version.toolActivity.indexOfFirst { it.id == chunk.activity.id }
                                        val anchoredActivity = if (existingIndex >= 0) {
                                            val existing = version.toolActivity[existingIndex]
                                            chunk.activity.copy(
                                                contentOffset = existing.contentOffset,
                                                reasoningOffset = existing.reasoningOffset
                                            )
                                        } else {
                                            chunk.activity.copy(
                                                contentOffset = contentOffset,
                                                 reasoningOffset = reasoningOutput.length
                                            )
                                        }
                                        val updatedActivities = version.toolActivity.toMutableList().apply {
                                            if (existingIndex >= 0) set(existingIndex, anchoredActivity) else add(anchoredActivity)
                                        }
                                        version.copy(toolActivity = updatedActivities)
                                    }
                                })
                            })
                            publish(snapshot!!)
                            save(snapshot!!)
                        }
                        is GenerationEvent.UserMessage -> {
                            renderJob?.cancel()
                            renderJob = null
                            update()

                            val previousId = activeMessageId
                            val now = System.currentTimeMillis()
                            val currentAssistant = snapshot!!.messages.firstOrNull { it.id == previousId }
                            val responseVersion = currentAssistant?.activeVersion?.let { version ->
                                MessageVersion(
                                    timestamp = now.toString(),
                                    providerId = version.providerId,
                                    modelId = version.modelId,
                                    modelDisplayName = version.modelDisplayName
                                )
                            } ?: MessageVersion(timestamp = now.toString())
                            val response = StoredMessage(
                                id = java.util.UUID.randomUUID().toString(),
                                role = ChatRole.MODEL,
                                versions = listOf(responseVersion)
                            )
                            activeMessageId = response.id
                            output.setLength(0)
                            reasoningOutput.setLength(0)
                            reasoningSignatureOutput.setLength(0)
                            snapshot = snapshot!!.copy(
                                updatedAt = now,
                                messages = snapshot!!.messages.map { message ->
                                    if (message.id != previousId) message else message.copy(
                                        versions = message.versions.mapIndexed { index, version ->
                                            if (index == message.activeVersionIndex) {
                                                version.copy(processingFinishedAt = now)
                                            } else version
                                        }
                                    )
                                } + chunk.message.copy(isQueued = false) + response
                            )
                            publish(snapshot!!)
                            save(snapshot!!)
                        }
                    }
                }
                renderJob?.cancel()
                renderJob = null
                update()
            } catch (e: CancellationException) {
                generationInterrupted = true
                throw e
            } catch (e: Exception) {
                renderJob?.cancel()
                renderJob = null
                _error.value = e.localizedMessage ?: "Generation failed"
                appendError("\n[Error: ${_error.value}]")
                update()
            } finally {
                renderJob?.cancel()
                try {
                    // A stopped stream must retain its partial response before allowing another send.
                    withContext(NonCancellable) {
                        snapshot = updateResponse(
                            snapshot!!,
                            activeMessageId,
                            output.toString(),
                            reasoningOutput.toString().takeIf { it.isNotBlank() },
                            reasoningSignatureOutput.toString().takeIf { it.isNotBlank() }
                        )
                        snapshot = snapshot!!.copy(messages = snapshot!!.messages.map { message ->
                            if (message.id != activeMessageId) message else message.copy(versions = message.versions.mapIndexed { index, version ->
                                if (index != message.activeVersionIndex) version else version.copy(
                                    generationInterrupted = generationInterrupted,
                                    toolActivity = version.toolActivity.map {
                                        if (it.status == "running") it.copy(status = "cancelled", summary = "Stopped") else it
                                    },
                                    processingFinishedAt = System.currentTimeMillis()
                                )
                            })
                        })
                        publish(snapshot!!)
                        save(snapshot!!)
                    }
                } catch (e: Exception) {
                    _error.value = "Could not save conversation: ${e.localizedMessage}"
                } finally {
                    snapshot = null
                    _conversationId.value = null
                    _busy.value = false
                }
            }
        }
        job!!.start()
        return true
    }

    fun updateConversation(transform: (Conversation) -> Conversation) {
        val current = snapshot ?: return
        val updated = transform(current)
        snapshot = updated
        publish(updated)
        scope.launch { save(updated) }
    }

    fun stop() { job?.cancel() }
    suspend fun stopAndJoin() { job?.cancelAndJoin() }
}
private fun visibleResponseContent(output: String): String {
    val thinking = output.startsWith("<think>")
    val end = output.indexOf("</think>")
    return if (thinking) {
        if (end >= 0) output.substring(end + 8).trimStart() else ""
    } else {
        output
    }
}

private fun visibleReasoningContent(output: String): String? {
    if (!output.startsWith("<think>")) return null
    val end = output.indexOf("</think>")
    return output.substring(7, if (end >= 0) end else output.length).trim()
}
internal fun updateResponse(
    conversation: Conversation,
    messageId: String,
    output: String,
    explicitReasoning: String? = null,
    explicitReasoningSignature: String? = null
): Conversation {
    val reasoning = explicitReasoning ?: visibleReasoningContent(output)
    val content = visibleResponseContent(output)
    return conversation.copy(
        updatedAt = System.currentTimeMillis(),
        messages = conversation.messages.map { message ->
            if (message.id != messageId) message else message.copy(
                versions = message.versions.mapIndexed { index, version ->
                    if (index == message.activeVersionIndex) version.copy(
                        content = content,
                        reasoningContent = reasoning,
                        reasoningSignature = explicitReasoningSignature
                    ) else version
                }
            )
        }
    )
}

/** Regeneration keeps earlier versions and uses only the context before the selected answer. */
internal fun prepareRegeneration(conversation: Conversation, messageId: String,
    version: MessageVersion = MessageVersion(timestamp = System.currentTimeMillis().toString())): Conversation? {
    val index = conversation.messages.indexOfFirst { it.id == messageId && it.role == ChatRole.MODEL }
    if (index < 1 || conversation.messages.take(index).none { it.role == ChatRole.USER }) return null
    val message = conversation.messages[index]
    if (isErrorOnlyResponse(message.activeVersion.content)) return null
    return conversation.copy(messages = conversation.messages.take(index) + message.copy(
        versions = message.versions + version,
        activeVersionIndex = message.versions.size
    ))
}
