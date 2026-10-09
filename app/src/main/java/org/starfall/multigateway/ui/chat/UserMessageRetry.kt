package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.MessageVersion
import org.starfall.multigateway.data.model.StoredMessage

internal fun isErrorOnlyResponse(content: String): Boolean {
    val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.isEmpty()) return false
    return lines.all { line ->
        line.startsWith("[Error:", ignoreCase = true) && line.endsWith("]") ||
            line.startsWith("Error:", ignoreCase = true) ||
            line.startsWith("API Error", ignoreCase = true) ||
            line.startsWith("Exception:", ignoreCase = true) ||
            line.startsWith("Failed:", ignoreCase = true)
    }
}

internal fun unansweredUserMessageIds(messages: List<StoredMessage>): Set<String> =
    messages.asReversed().takeWhile { it.role != ChatRole.MODEL }
        .filter { it.role == ChatRole.USER && !it.isQueued }.mapTo(mutableSetOf()) { it.id }
        .apply {
            if (messages.lastOrNull()?.let {
                    it.role == ChatRole.MODEL &&
                        (it.activeVersion.generationInterrupted || isErrorOnlyResponse(it.content))
                } != true) return@apply
            messages.dropLast(1).lastOrNull { it.role == ChatRole.USER && !it.isQueued }
                ?.let { add(it.id) }
        }

/** Insert the answer beside its question without duplicating it or deleting later messages. */
internal fun prepareUserMessageRetry(
    conversation: Conversation,
    messageId: String,
    responseId: String,
    version: MessageVersion
): Conversation? {
    if (messageId !in unansweredUserMessageIds(conversation.messages)) return null
    val index = conversation.messages.indexOfFirst { it.id == messageId }
    val stoppedResponse = conversation.messages.getOrNull(index + 1)?.takeIf {
        it.role == ChatRole.MODEL &&
            (it.activeVersion.generationInterrupted || isErrorOnlyResponse(it.content))
    }
    if (stoppedResponse != null) {
        val retriedResponse = if (isErrorOnlyResponse(stoppedResponse.content)) {
            val versions = stoppedResponse.versions.toMutableList()
            if (versions.isEmpty()) {
                stoppedResponse.copy(versions = listOf(version), activeVersionIndex = 0)
            } else {
                val activeIndex = stoppedResponse.activeVersionIndex.coerceIn(versions.indices)
                versions[activeIndex] = version
                stoppedResponse.copy(versions = versions, activeVersionIndex = activeIndex)
            }
        } else {
            stoppedResponse.copy(
                versions = stoppedResponse.versions + version,
                activeVersionIndex = stoppedResponse.versions.size
            )
        }
        return conversation.copy(
            messages = conversation.messages.map { if (it.id == stoppedResponse.id) retriedResponse else it },
            summary = conversation.summary?.takeUnless { summary ->
                conversation.messages.indexOfFirst { it.id == summary.throughMessageId } >= index
            },
            updatedAt = System.currentTimeMillis()
        )
    }
    val response = StoredMessage(responseId, ChatRole.MODEL, listOf(version))
    return conversation.copy(
        messages = conversation.messages.take(index + 1) + response + conversation.messages.drop(index + 1),
        summary = conversation.summary?.takeUnless { summary ->
            conversation.messages.indexOfFirst { it.id == summary.throughMessageId } >= index
        },
        updatedAt = System.currentTimeMillis()
    )
}
