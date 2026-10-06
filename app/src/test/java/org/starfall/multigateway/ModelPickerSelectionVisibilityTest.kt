package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.*

class ModelPickerSelectionVisibilityTest {
    private val group = ProviderGroup("folder", "Work")
    private val selected = LlmProviderInfo("selected", "Selected provider", ProviderType.OPENAI,
        baseUrl = "", groupId = group.id,
        config = ProviderConfiguration(modelIds = listOf("alpha", "beta")))
    private val other = selected.copy(id = "other")

    @Test fun selectedModelRetainsItsProviderAndFolderAtEveryCollapseCombination() {
        for (folderCollapsed in listOf(false, true)) {
            for (providerCollapsed in listOf(false, true)) {
                val items = computeModelPickerItems(listOf(selected, other), listOf(group), emptyMap(),
                    selected.id, "alpha",
                    collapsedGroupIds = if (folderCollapsed) setOf(group.id) else emptySet(),
                    collapsedProviderIds = if (providerCollapsed) setOf(selected.id) else emptySet())
                assertEquals(group, (items[0] as ModelPickerItem.Group).group)
                val provider = items[1] as ModelPickerItem.Provider
                val model = items[2] as ModelPickerItem.Model
                assertEquals(selected.id, provider.provider.id)
                assertEquals(group.id, provider.provider.groupId)
                assertEquals(1, provider.depth)
                assertTrue(model.isSelected)
                assertEquals("alpha", model.modelId)
                assertEquals(2, model.depth)
                if (folderCollapsed || providerCollapsed) {
                    assertEquals(listOf("alpha"), items.filterIsInstance<ModelPickerItem.Model>()
                        .filter { it.provider.id == selected.id }.map { it.modelId })
                }
                if (folderCollapsed) assertEquals(3, items.size)
            }
        }
    }

    @Test fun collapsedRootProviderRetainsOnlySelectedModelAndExpansionRestoresOthers() {
        val root = selected.copy(groupId = null)
        val items = computeModelPickerItems(listOf(root), emptyList(), emptyMap(), root.id, "beta",
            collapsedProviderIds = setOf(root.id))
        assertEquals(0, (items[0] as ModelPickerItem.Provider).depth)
        assertEquals("beta", (items[1] as ModelPickerItem.Model).modelId)
        assertEquals(1, (items[1] as ModelPickerItem.Model).depth)
        assertEquals(2, items.size)
        val expanded = computeModelPickerItems(listOf(root), emptyList(), emptyMap(), root.id, "beta")
        assertEquals(listOf("alpha", "beta"), expanded.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }

    @Test fun offIsSeparateFromDefaultAndDoesNotRemoveThinkingCapability() {
        assertFalse(reasoningEffortEnabled("none"))
        assertFalse(reasoningEffortEnabled(" OFF "))
        assertTrue(reasoningEffortEnabled(null))
        assertEquals("none", reasoningEfforts.first())
        assertEquals("Off", reasoningEffortLabels.first())
        assertTrue(reasoningEfforts.contains(null))
        assertEquals(1, reasoningEffortIndex(null))
        val model = ModelConfiguration(supportsThinking = true, reasoningEffort = "high")
        assertTrue(model.withConversationReasoning("none").supportsThinking)
        assertEquals("high", model.reasoningEffort)
        assertEquals(model, model.withConversationReasoning(null))
    }
}
