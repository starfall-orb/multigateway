package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.data.model.*

data class ContextWindowStatus(val estimatedTokens: Long, val limit: Int) {
    val shouldSuggestSummary get() = estimatedTokens * 100 >= limit.toLong() * 90
    val shouldAutoSummarize get() = estimatedTokens * 100 >= limit.toLong() * 99
    val percentage get() = (estimatedTokens * 100 / limit.coerceAtLeast(1)).coerceAtMost(100).toInt()
}

/** Conservative text estimate; the local counter does not claim tokenizer precision. */
internal fun estimateTextTokens(text: String): Long {
    var wide = 0L
    var otherBytes = 0L
    text.forEach { char ->
        if (char.code in 0x2E80..0x9FFF || char.code in 0xAC00..0xD7AF || char.isSurrogate()) wide++
        else otherBytes += when { char.code < 128 -> 1; char.code < 2048 -> 2; else -> 3 }
    }
    return wide + (otherBytes + 3) / 4
}

internal fun estimateMessageTokens(message: StoredMessage, includeReasoning: Boolean): Long =
    4 + estimateTextTokens(message.content) +
        (if (includeReasoning) estimateTextTokens(message.reasoningContent.orEmpty()) else 0) +
        message.activeVersion.toolActivity.sumOf { estimateTextTokens(it.arguments) + estimateTextTokens(it.response) } +
        message.files.size * 1024L

internal fun contextWindowStatus(conversation: Conversation, prompt: String, config: ModelConfiguration): ContextWindowStatus {
    val effective = effectiveContext(conversation, conversation.messages, prompt)
    val count = estimateTextTokens(effective.systemPrompt) +
        effective.messages.sumOf { estimateMessageTokens(it, config.sendThinkingContent) }
    return ContextWindowStatus(count, config.contextWindowTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS)
}

internal fun chunkSummaryMessages(messages: List<StoredMessage>, tokenLimit: Int): List<List<StoredMessage>> {
    val limit = tokenLimit.coerceAtLeast(16)
    val chunks = mutableListOf<List<StoredMessage>>()
    var current = mutableListOf<StoredMessage>()
    var tokens = 0L
    messages.forEach { message ->
        // Split a long turn as well as splitting between turns, preserving its role.
        val parts = if (estimateMessageTokens(message, true) > limit && message.content.isNotEmpty()) {
            splitSummaryText(message.content, limit - 8).mapIndexed { index, content ->
                message.copy(id = "${message.id}_part_$index", activeVersionIndex = 0,
                    versions = listOf(message.activeVersion.copy(content = content,
                        files = if (index == 0) message.files else emptyList(),
                        toolActivity = if (index == 0) message.activeVersion.toolActivity else emptyList(),
                        reasoningContent = if (index == 0) message.reasoningContent else null)))
            }
        } else listOf(message)
        parts.forEach { part ->
            val cost = estimateMessageTokens(part, true)
            if (current.isNotEmpty() && tokens + cost > limit) {
                chunks += current
                current = mutableListOf()
                tokens = 0
            }
            current += part
            tokens += cost
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

internal data class EffectiveContext(
    val messages: List<StoredMessage>,
    val systemPrompt: String
)

internal fun effectiveContext(
    conversation: Conversation,
    source: List<StoredMessage>,
    baseSystemPrompt: String
): EffectiveContext {
    val summary = conversation.summary ?: return EffectiveContext(source, baseSystemPrompt)
    val cutoff = source.indexOfFirst { it.id == summary.throughMessageId }
    if (cutoff < 0) return EffectiveContext(source, baseSystemPrompt)
    val after = source.drop(cutoff + 1)
    return when (summary.role) {
        SummaryRole.SYSTEM -> EffectiveContext(
            after,
            listOf(baseSystemPrompt, "Conversation summary:\n${summary.content}")
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        )
        SummaryRole.ASSISTANT, SummaryRole.USER -> {
            val role = if (summary.role == SummaryRole.ASSISTANT) ChatRole.MODEL else ChatRole.USER
            val synthetic = StoredMessage(
                id = "summary_${summary.id}",
                role = role,
                versions = listOf(
                    MessageVersion(
                        content = summary.content,
                        timestamp = summary.createdAt.toString()
                    )
                )
            )
            EffectiveContext(listOf(synthetic) + after, baseSystemPrompt)
        }
    }
}

private fun splitSummaryText(text: String, tokenLimit: Int): List<String> {
    val result = mutableListOf<String>()
    var offset = 0
    while (offset < text.length) {
        var low = 1
        var high = text.length - offset
        var length = 1
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (estimateTextTokens(text.substring(offset, offset + middle)) <= tokenLimit) {
                length = middle
                low = middle + 1
            } else high = middle - 1
        }
        if (offset + length < text.length && text[offset + length - 1].isHighSurrogate()) length--
        length = length.coerceAtLeast(1)
        result += text.substring(offset, offset + length)
        offset += length
    }
    return result
}
