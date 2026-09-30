package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*

class ModelReorderPersistenceTest {
    private val configs = linkedMapOf(
        "a" to ModelConfiguration(displayName = "A"),
        "b" to ModelConfiguration(displayName = "B"),
        "c" to ModelConfiguration(displayName = "C")
    )

    @Test
    fun staleDragSnapshotCannotDeleteNewlyAddedModel() {
        val stored = ProviderConfiguration(modelConfigs = configs, modelIds = listOf("a", "b", "c"))
        val reordered = stored.reorderedModels(listOf("b", "a"))
        assertEquals(listOf("b", "a", "c"), reordered.modelIds)
        assertEquals(configs, reordered.modelConfigs)
    }

    @Test
    fun emptyOrDuplicateDragOrderCannotRemoveAnyModel() {
        val stored = ProviderConfiguration(modelConfigs = configs)
        for (order in listOf(emptyList(), listOf("b", "b"))) {
            val reordered = stored.reorderedModels(order)
            assertEquals(configs.keys, reordered.modelIds!!.toSet())
            assertEquals(configs.size, reordered.modelIds!!.size)
            assertEquals(configs, reordered.modelConfigs)
        }
    }

    @Test
    fun staleDragCannotRestoreExplicitlyDeletedModel() {
        val stored = ProviderConfiguration(modelConfigs = configs - "b", modelIds = listOf("a", "c"))
        val reordered = stored.reorderedModels(listOf("b", "c", "a"))
        assertEquals(listOf("c", "a"), reordered.modelIds)
        assertEquals(configs - "b", reordered.modelConfigs)
    }

    @Test
    fun reorderRetainsUnselectedConfigurationsAndModelsWithoutConfiguration() {
        val stored = ProviderConfiguration(modelConfigs = configs, modelIds = listOf("a", "custom"))
        val reordered = stored.reorderedModels(listOf("custom", "a"))
        assertEquals(listOf("custom", "a"), reordered.modelIds)
        assertEquals(configs, reordered.modelConfigs)
        assertTrue(stored.copy(modelIds = emptyList()).reorderedModels(listOf("a")).modelIds!!.isEmpty())
    }
}
