package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.prepareUserMessageRetry
import org.starfall.multigateway.ui.chat.unansweredUserMessageIds

class UserMessageRetryTest {
    private fun user(id: String) = StoredMessage(id, ChatRole.USER,
        listOf(MessageVersion(content = id, files = listOf("content://attachment/$id"))))
    private fun conversation(messages: List<StoredMessage>) = Conversation("chat", "Chat", 1, 1, messages)

    @Test fun onlyUnansweredSavedUserMessagesHaveResendButtons() {
        val messages = listOf(user("answered"), StoredMessage("answer", ChatRole.MODEL),
            user("pending1"), user("pending2"), user("queued").copy(isQueued = true))
        assertEquals(setOf("pending1", "pending2"), unansweredUserMessageIds(messages))
    }

    @Test fun retryInsertsAnAnswerAndPreservesQuestionAttachmentsAndLaterMessages() {
        val original = conversation(listOf(user("first"), user("later")))
        val version = MessageVersion(providerId = "provider", modelId = "model")
        val retried = prepareUserMessageRetry(original, "first", "reply", version)!!
        assertEquals(listOf("first", "reply", "later"), retried.messages.map { it.id })
        assertEquals(original.messages.first(), retried.messages.first())
        assertEquals(original.messages.last(), retried.messages.last())
        assertEquals(ChatRole.MODEL, retried.messages[1].role)
        assertEquals(version, retried.messages[1].activeVersion)
        assertEquals(setOf("later"), unansweredUserMessageIds(retried.messages))
        assertNull(prepareUserMessageRetry(retried, "first", "duplicate", version))
    }

    @Test fun answeredQueuedAndMissingMessagesCannotBeRetried() {
        val original = conversation(listOf(user("answered"), StoredMessage("answer", ChatRole.MODEL),
            user("queued").copy(isQueued = true)))
        for (id in listOf("answered", "answer", "queued", "missing")) {
            assertNull(prepareUserMessageRetry(original, id, "reply", MessageVersion()))
        }
    }

    @Test fun retryInvalidatesOnlySummariesCoveringTheInsertedResponse() {
        val original = conversation(listOf(user("earlier"), user("target"), user("later")))
        val summary = ConversationSummary("summary", "Summary", "later")
        assertNull(prepareUserMessageRetry(original.copy(summary = summary), "target", "reply", MessageVersion())!!.summary)
        val earlierSummary = summary.copy(throughMessageId = "earlier")
        assertEquals(earlierSummary, prepareUserMessageRetry(original.copy(summary = earlierSummary),
            "target", "reply", MessageVersion())!!.summary)
    }
}
