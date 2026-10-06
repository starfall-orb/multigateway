package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.*

class ChatModelPickerFilterTest {
    private val configs = ModelType.entries.associate { it.name to ModelConfiguration(modelType = it, displayName = "Shared Name") }
    private val mixed = LlmProviderInfo("mixed", "Mixed", ProviderType.OPENAI, baseUrl = "", groupId = "g",
        config = ProviderConfiguration(modelIds = configs.keys.toList(), modelConfigs = configs))
    private val mediaOnly = mixed.copy(id = "media", name = "Media", groupId = null,
        config = ProviderConfiguration(modelIds = listOf("image"), modelConfigs = mapOf("image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))))

    @Test fun chatFilterKeepsOnlyChatModelsAndRemovesEmptyProvidersEvenWhenSearching() {
        for (query in listOf("", "Shared", "Mixed", "Work")) {
            val rows = computeModelPickerItems(listOf(mixed, mediaOnly), listOf(ProviderGroup("g", "Work")), emptyMap(),
                selectedProviderId = "mixed", selectedModelId = ModelType.IMAGE_GENERATION.name,
                query = query, modelFilter = chatModelPickerFilter)
            assertEquals(listOf(ModelType.TEXT_GENERATION.name), rows.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
            assertFalse(rows.filterIsInstance<ModelPickerItem.Model>().any { it.isSelected })
            assertEquals(listOf("mixed"), rows.filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id })
            assertEquals(1, rows.filterIsInstance<ModelPickerItem.Provider>().single().modelCount)
        }
    }

    @Test fun legacyUnconfiguredModelsAreChatAndSelectedMediaModelsRemainFiltered() {
        val legacy = mixed.copy(groupId = null, config = ProviderConfiguration(modelConfigs = mapOf(
            "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))))
        val rows = computeModelPickerItems(listOf(legacy), emptyList(), mapOf("mixed" to listOf("chat", "image")),
            "mixed", "image", modelFilter = chatModelPickerFilter)
        assertEquals(listOf("chat"), rows.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }
}
