package org.starfall.multigateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.LlmRepository
import org.starfall.multigateway.data.service.LlmService
import org.starfall.multigateway.ui.chat.ModelPickerItem
import org.starfall.multigateway.ui.chat.computeModelPickerItems

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderGroupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun repositoryPersistsMembershipAndDeletingGroupUngroupsProviders() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            val group = ProviderGroup("g1", "Work", 0)
            repo.saveGroup(group)
            repo.saveProvider(
                LlmProviderInfo(
                    id = "p1",
                    name = "Provider",
                    type = ProviderType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    groupId = group.id
                )
            )

            assertEquals(listOf(group), repo.allGroups.first())
            assertEquals(group.id, repo.getProviderById("p1")?.groupId)

            repo.deleteGroup(group.id)

            assertTrue(repo.allGroups.first().isEmpty())
            assertNull(repo.getProviderById("p1")?.groupId)
        } finally {
            db.close()
        }
    }

    @Test
    fun pickerTreeRespectsGroupAndProviderCollapseButSearchExpandsMatches() {
        val group = ProviderGroup("g1", "Work", 0)
        val grouped = LlmProviderInfo(
            id = "p1",
            name = "Primary",
            type = ProviderType.OPENAI,
            baseUrl = "https://example.test/v1",
            groupId = group.id,
            config = ProviderConfiguration(
                modelIds = listOf("alpha", "beta"),
                modelConfigs = mapOf("alpha" to ModelConfiguration(), "beta" to ModelConfiguration())
            )
        )
        val root = LlmProviderInfo(
            id = "p2",
            name = "Root",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://example.test/v1",
            config = ProviderConfiguration(
                modelIds = listOf("gamma"),
                modelConfigs = mapOf("gamma" to ModelConfiguration())
            )
        )

        val groupCollapsed = computeModelPickerItems(
            providers = listOf(grouped, root),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            collapsedGroupIds = setOf(group.id)
        )
        assertTrue(groupCollapsed.any { it is ModelPickerItem.Group && it.group.id == group.id })
        assertFalse(groupCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertTrue(groupCollapsed.any { it is ModelPickerItem.Model && it.modelId == "gamma" })

        val providerCollapsed = computeModelPickerItems(
            providers = listOf(grouped),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            collapsedProviderIds = setOf(grouped.id)
        )
        assertTrue(providerCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertFalse(providerCollapsed.any { it is ModelPickerItem.Model })

        val searched = computeModelPickerItems(
            providers = listOf(grouped),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            query = "beta",
            collapsedGroupIds = setOf(group.id),
            collapsedProviderIds = setOf(grouped.id)
        )
        assertTrue(searched.any { it is ModelPickerItem.Group && it.group.id == group.id })
        assertTrue(searched.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertEquals(listOf("beta"), searched.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }

    @Test
    fun rootOrderInterleavesGroupsAndUngroupedProvidersAndMovePersistsMembership() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            val g1 = ProviderGroup("g1", "One", 0)
            val g2 = ProviderGroup("g2", "Two", 1)
            repo.saveGroup(g1)
            repo.saveGroup(g2)
            repo.saveProvider(
                LlmProviderInfo(
                    id = "root",
                    name = "Root",
                    type = ProviderType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    sortOrder = 2
                )
            )
            repo.saveProvider(
                LlmProviderInfo(
                    id = "child",
                    name = "Child",
                    type = ProviderType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    sortOrder = 0,
                    groupId = g1.id
                )
            )

            repo.reorderRootItems(
                listOf(
                    ProviderRootOrderItem("root", isGroup = false),
                    ProviderRootOrderItem("g2", isGroup = true),
                    ProviderRootOrderItem("g1", isGroup = true)
                )
            )

            assertEquals(0, repo.getProviderById("root")?.sortOrder)
            assertEquals(
                mapOf("g1" to 2, "g2" to 1),
                repo.allGroups.first().associate { it.id to it.sortOrder }
            )

            repo.moveProviderToGroup("root", "g1")
            val moved = repo.getProviderById("root")
            assertEquals("g1", moved?.groupId)
            assertTrue((moved?.sortOrder ?: -1) > (repo.getProviderById("child")?.sortOrder ?: Int.MAX_VALUE))

            repo.moveProviderToGroup("root", null)
            assertNull(repo.getProviderById("root")?.groupId)
            assertTrue((repo.getProviderById("root")?.sortOrder ?: -1) > repo.allGroups.first().maxOf { it.sortOrder })
        } finally {
            db.close()
        }
    }

}
