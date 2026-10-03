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
}
