package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.MessageVersion
import org.starfall.multigateway.data.model.StoredMessage

internal fun unansweredUserMessageIds(messages: List<StoredMessage>): Set<String> =
    messages.asReversed().takeWhile { it.role != ChatRole.MODEL }
        .filter { it.role == ChatRole.USER && !it.isQueued }.mapTo(mutableSetOf()) { it.id }

/** Insert the answer beside its question without duplicating it or deleting later messages. */
internal fun prepareUserMessageRetry(
    conversation: Conversation,
    messageId: String,
    responseId: String,
    version: MessageVersion
): Conversation? {
    if (messageId !in unansweredUserMessageIds(conversation.messages)) return null
    val index = conversation.messages.indexOfFirst { it.id == messageId }
    val response = StoredMessage(responseId, ChatRole.MODEL, listOf(version))
    return conversation.copy(
        messages = conversation.messages.take(index + 1) + response + conversation.messages.drop(index + 1),
        summary = conversation.summary?.takeUnless { summary ->
            conversation.messages.indexOfFirst { it.id == summary.throughMessageId } >= index
        },
        updatedAt = System.currentTimeMillis()
    )
}
