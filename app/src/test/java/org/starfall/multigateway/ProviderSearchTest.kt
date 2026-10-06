package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.*

class ProviderSearchTest {
    private val provider = LlmProviderInfo("p", "Ollama Cloud", ProviderType.OPENAI,
        baseUrl = "https://api.example.com", groupId = "folder",
        config = ProviderConfiguration(modelIds = listOf("qwen-3", "custom"), modelConfigs = mapOf(
            "custom" to ModelConfiguration(displayName = "My Vision Model"),
            "removed" to ModelConfiguration(displayName = "Old Model"))))

    @Test fun nameSearchIgnoresUrlsModelsAndFolderNamesAndWorksForAllMemberships() {
        val fields = setOf(ProviderSearchField.PROVIDER_NAME)
        for (group in listOf(null, "folder")) {
            val item = provider.copy(groupId = group)
            assertTrue(matchesProviderSearch(item, "  OLLAMA  ", fields))
            assertFalse(matchesProviderSearch(item, "api.example", fields))
            assertFalse(matchesProviderSearch(item, "qwen", fields))
        }
        assertFalse(matchesFolderSearch("Ollama folder", "ollama", fields))
    }

    @Test fun modelSearchMatchesIdsAndDisplayNamesButExcludesRemovedModels() {
        val fields = setOf(ProviderSearchField.MODEL_NAME)
        assertTrue(matchesProviderSearch(provider, "QWEN", fields))
        assertTrue(matchesProviderSearch(provider, "vision", fields))
        assertFalse(matchesProviderSearch(provider, "Ollama", fields))
        assertFalse(matchesProviderSearch(provider, "Old Model", fields))
        assertFalse(matchesProviderSearch(provider.copy(config = provider.config.copy(modelIds = emptyList())), "vision", fields))
        assertTrue(matchesProviderSearch(provider.copy(config = provider.config.copy(modelIds = null)), "Old Model", fields))
    }

    @Test fun selectedFieldsCombineAndFolderSearchIsExplicit() {
        val fields = setOf(ProviderSearchField.FOLDER_NAME, ProviderSearchField.MODEL_NAME)
        assertTrue(matchesFolderSearch("My Folder", "folder", fields))
        assertTrue(matchesProviderSearch(provider, "qwen", fields))
        assertFalse(matchesProviderSearch(provider, "Ollama", fields))
        assertTrue(matchesProviderSearch(provider, "  ", fields))
    }
}
