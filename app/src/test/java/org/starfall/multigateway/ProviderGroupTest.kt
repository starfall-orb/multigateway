package org.starfall.multigateway

import android.content.Context
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.local.db.SecretCipher
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
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
import org.starfall.multigateway.ui.providers.newProviderId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderGroupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun repositoryOmitsSmallContextOverridesAndPersistsRawModelJson() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            val raw = Json.parseToJsonElement("""{"id":"small","context_window":32000,"vendor":{"extra":true}}""").jsonObject
            repo.saveProvider(LlmProviderInfo("json-test", "Provider", ProviderType.OPENAI, baseUrl = "",
                config = ProviderConfiguration(modelConfigs = mapOf(
                    "small" to ModelConfiguration(contextWindowTokens = 32000, modelJson = raw),
                    "default" to ModelConfiguration(contextWindowTokens = 128000),
                    "large" to ModelConfiguration(contextWindowTokens = 128001)
                ))))
            val entity = db.llmProviderDao().getProviderById("json-test")!!
            val stored = Json.parseToJsonElement(SecretCipher.decrypt(entity.configJson)).jsonObject.getValue("modelConfigs").jsonObject
            assertFalse(stored.getValue("small").jsonObject.containsKey("contextWindowTokens"))
            assertFalse(stored.getValue("default").jsonObject.containsKey("contextWindowTokens"))
            assertEquals(128001, stored.getValue("large").jsonObject.getValue("contextWindowTokens").jsonPrimitive.int)
            val reopened = LlmRepository(db, LlmService(context)).getProviderById("json-test")!!
            assertEquals(raw, reopened.config.modelConfigs.getValue("small").modelJson)
            assertEquals(DEFAULT_CONTEXT_WINDOW_TOKENS, reopened.config.modelConfigs.getValue("small").contextWindowTokens)
        } finally { db.close() }
    }

    @Test fun dragPlacementPersistsMembershipAndExactOrdersTogether() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            repo.saveGroup(ProviderGroup("g", "Folder", 1))
            for ((id, group) in listOf("outside" to null, "a" to "g", "b" to "g", "tail" to null))
                repo.saveProvider(LlmProviderInfo(id, id, ProviderType.OPENAI, baseUrl = "", groupId = group))
            val root = listOf(ProviderRootOrderItem("g", true), ProviderRootOrderItem("tail", false))
            repo.placeProvider(ProviderPlacement("outside", "g", root, mapOf("g" to listOf("a", "outside", "b"))))
            assertEquals("g", repo.getProviderById("outside")!!.groupId)
            val visible = repo.allProviders.first()
            assertEquals(listOf("a", "outside", "b"), visible.filter { it.groupId == "g" }.map { it.id })
            val persisted = LlmRepository(db, LlmService(context))
            assertEquals(listOf("a", "outside", "b"), persisted.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            repo.placeProvider(ProviderPlacement("a", null,
                listOf(ProviderRootOrderItem("a", false)) + root, mapOf("g" to listOf("outside", "b"))))
            assertNull(repo.getProviderById("a")!!.groupId)
            assertEquals(0, repo.getProviderById("a")!!.sortOrder)
            assertEquals(1, repo.allGroups.first().first().sortOrder)
            assertEquals(2, repo.getProviderById("tail")!!.sortOrder)
            assertEquals(listOf("outside", "b"), repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            val before = repo.allProviders.first()
            repo.placeProvider(ProviderPlacement("outside", "missing", emptyList(), emptyMap()))
            assertEquals(before, repo.allProviders.first())
        } finally { db.close() }
    }

    @Test
    fun repositoryPersistsMembershipAndDeletingGroupUngroupsProviders() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            val group = ProviderGroup("g1", "Work", 0, "icon-11111111-1111-1111-1111-111111111111.png")
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
    fun newAndMovedProvidersAppendInCreationOrderAndStayThereAfterReload() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            repo.saveGroup(ProviderGroup("g", "Work", 0))
            fun provider(id: String, group: String? = "g") = LlmProviderInfo(id, id, ProviderType.OPENAI,
                baseUrl = "https://example.test/v1", groupId = group)
            repo.saveProvider(provider("z-first"))
            repo.saveProvider(provider("a-second"))
            repo.saveProvider(provider("0-moved", null))
            val expected = listOf("z-first", "a-second", "0-moved")
            val emissions = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
            val collector = launch(Dispatchers.Unconfined) {
                repo.allProviders.collect { providers ->
                    val members = providers.filter { it.groupId == "g" }.map { it.id }
                    if ("0-moved" in members) emissions.add(members)
                }
            }
            repo.moveProviderToGroup("0-moved", "g")
            collector.cancelAndJoin()
            assertTrue(emissions.isNotEmpty())
            emissions.forEach { assertEquals(expected, it) }
            repo.saveProvider(provider("0-new"))
            val finalOrder = expected + "0-new"
            assertEquals(finalOrder, repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            repo.moveProviderToGroup("a-second", "g")
            assertEquals(finalOrder, repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            val reloaded = LlmRepository(db, LlmService(context)).allProviders.first()
            assertEquals(finalOrder, reloaded.filter { it.groupId == "g" }.map { it.id })
            assertEquals(listOf(0, 1, 2, 3), reloaded.filter { it.groupId == "g" }.map { it.sortOrder })
        } finally { db.close() }
    }

    @Test
    fun legacyMaximumRanksAreCompactedWithoutOverflowWhenMovingIntoFolder() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            repo.saveGroup(ProviderGroup("g", "Work", 0))
            for (id in listOf("z-legacy", "a-legacy")) {
                db.llmProviderDao().insertOrUpdate(org.starfall.multigateway.data.local.db.entities.LlmProviderEntity(
                    id, id, ProviderType.OPENAI.name, "https://example.test/v1", "{}", "{}", null, Int.MAX_VALUE, "g"))
            }
            repo.saveProvider(LlmProviderInfo("moving", "Moving", ProviderType.OPENAI, baseUrl = "https://example.test/v1"))
            repo.moveProviderToGroup("moving", "g")
            val members = LlmRepository(db, LlmService(context)).allProviders.first().filter { it.groupId == "g" }
            assertEquals(listOf("a-legacy", "z-legacy", "moving"), members.map { it.id })
            assertEquals(listOf(0, 1, 2), members.map { it.sortOrder })
            repo.moveProviderToGroup("moving", null)
            assertTrue(repo.getProviderById("moving")!!.sortOrder > repo.allGroups.first().single().sortOrder)
        } finally { db.close() }
    }

    @Test
    fun pickerTabsFilterFoldersAndProviderCollapseButSearchExpandsMatches() {
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
            collapsedGroupIds = setOf(group.id),
            selectedFolderId = group.id
        )
        assertTrue(groupCollapsed.none { it is ModelPickerItem.Group })
        assertTrue(groupCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertEquals(listOf("alpha", "beta"), groupCollapsed.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
        assertFalse(groupCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == root.id })

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
        assertTrue(searched.none { it is ModelPickerItem.Group })
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


    @Test
    fun creatingMultipleProvidersUsesDistinctPersistentIds() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = LlmRepository(db, LlmService(context))
            val firstId = newProviderId()
            val secondId = newProviderId()
            assertNotEquals(firstId, secondId)
            assertTrue(firstId.startsWith("custom_"))
            assertTrue(secondId.startsWith("custom_"))

            repo.saveProvider(
                LlmProviderInfo(
                    id = firstId,
                    name = "First",
                    type = ProviderType.OPENAI,
                    baseUrl = "https://first.example/v1"
                )
            )
            repo.saveProvider(
                LlmProviderInfo(
                    id = secondId,
                    name = "Second",
                    type = ProviderType.OPENAI,
                    baseUrl = "https://second.example/v1"
                )
            )

            assertEquals(
                setOf(firstId, secondId),
                repo.allProviders.first().map { it.id }.toSet()
            )
        } finally {
            db.close()
        }
    }

}
