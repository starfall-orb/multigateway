package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.drawer.lastModelIcon

class ConversationModelIconTest {
    private val providers = listOf(
        LlmProviderInfo("first", "First", ProviderType.OPENAI, baseUrl = "", config = ProviderConfiguration(modelConfigs = mapOf(
            "old" to ModelConfiguration(icon = "old.png"), "same-id" to ModelConfiguration(icon = "first.png")))),
        LlmProviderInfo("last", "Last", ProviderType.OPENAI, baseUrl = "", config = ProviderConfiguration(modelConfigs = mapOf(
            "new" to ModelConfiguration(icon = "new.png", displayName = "Latest Model"),
            "same-id" to ModelConfiguration(icon = "last.png"))))
    ).associateBy { it.id }
    private fun reply(id: String, provider: String, model: String) = StoredMessage(id, ChatRole.MODEL,
        listOf(MessageVersion(providerId = provider, modelId = model)))
    private fun chat(messages: List<StoredMessage>) = Conversation("chat", "Title", 0, 0, messages,
        providerId = "first", modelId = "old")

    @Test fun usesLastModelReplyDespiteTrailingUserMessageAndOldConversationSelection() {
        val conversation = chat(listOf(reply("a", "first", "old"), reply("b", "last", "new"),
            StoredMessage("user", ChatRole.USER, listOf(MessageVersion(modelId = "old")))))
        val icon = conversation.lastModelIcon(providers)!!
        assertEquals("new.png", icon.image)
        assertEquals("Latest Model", icon.name)
    }

    @Test fun selectedRegenerationVersionDeterminesTheIcon() {
        val response = StoredMessage("answer", ChatRole.MODEL, listOf(
            MessageVersion(providerId = "first", modelId = "old"),
            MessageVersion(providerId = "last", modelId = "new")), activeVersionIndex = 1)
        assertEquals("new.png", chat(listOf(response)).lastModelIcon(providers)!!.image)
        assertEquals("old.png", chat(listOf(response.copy(activeVersionIndex = 0))).lastModelIcon(providers)!!.image)
    }

    @Test fun identicalModelIdsResolveToTheirOwnProviderIcon() {
        val icon = chat(listOf(reply("answer", "last", "same-id"))).lastModelIcon(providers)!!
        assertEquals("last.png", icon.image)
    }

    @Test fun removedProviderKeepsSavedModelNameForAutomaticLogoMatching() {
        val response = StoredMessage("answer", ChatRole.MODEL, listOf(
            MessageVersion(providerId = "removed", modelId = "same-id", modelDisplayName = "Saved Model")))
        val icon = chat(listOf(response)).lastModelIcon(providers)!!
        assertNull(icon.image)
        assertEquals("Saved Model", icon.name)
    }

    @Test fun legacyChatsUseConversationModelAndChatsWithoutModelsKeepTheirFallback() {
        assertEquals("old.png", chat(listOf(StoredMessage("legacy", ChatRole.MODEL))).lastModelIcon(providers)!!.image)
        assertNull(Conversation("new", "New Chat", 0, 0).lastModelIcon(providers))
    }
}
