package org.starfall.multigateway

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.entities.McpServerEntity
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.LlmRepository
import org.starfall.multigateway.data.repository.McpRepository
import org.starfall.multigateway.data.service.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntityIconTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun importedImageRemainsAvailableWithoutOriginalAndIsBounded() {
        val source = File.createTempFile("source-icon", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        val id = IconStore(context).importImage(Uri.fromFile(source))
        source.delete()
        val loaded = IconStore(context).load(id)!!
        assertTrue(loaded.width <= 256)
        assertTrue(loaded.height <= 256)
        assertEquals(2f, loaded.width.toFloat() / loaded.height, 0.05f)
        loaded.recycle()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun invalidImagesAreRejectedAndTemporaryCopiesRemoved() {
        val source = File.createTempFile("invalid-icon", ".txt", context.cacheDir).apply { writeText("not an image") }
        try {
            assertThrows(Exception::class.java) { IconStore(context).importImage(Uri.fromFile(source)) }
            assertFalse(File(context.filesDir, "entity-icons").listFiles().orEmpty().any { it.extension == "tmp" })
            assertNull(IconStore(context).load("../source.png"))
        } finally { source.delete() }
    }

    @Test fun iconsSurviveRepositoryReloadAndRemovalPreservesSettings() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val provider = LlmProviderInfo("p", "Provider", ProviderType.OPENAI,
                baseUrl = "https://example.test/v1", icon = "icon-a.png", config = ProviderConfiguration(
                    modelConfigs = mapOf("model" to ModelConfiguration(icon = "icon-b.png", temperature = 0.4))))
            LlmRepository(db, LlmService(context)).saveProvider(provider)
            val llm = LlmRepository(db, LlmService(context))
            assertEquals(provider, llm.getProviderById("p"))
            val removed = provider.copy(icon = null, config = provider.config.copy(
                modelConfigs = mapOf("model" to provider.config.modelConfigs.getValue("model").copy(icon = null))))
            llm.saveProvider(removed)
            assertEquals(removed, llm.getProviderById("p"))

            val server = McpInfo("s", "Server", url = "https://example.test/mcp",
                headers = mapOf("X-Test" to "yes"), auth = McpAuthorization(McpAuthMethod.BEARER_TOKEN, value = "secret"),
                cachedTools = emptyList(), sortOrder = 3, icon = "icon-c.png")
            McpRepository(db, McpService()).saveServer(server)
            val mcp = McpRepository(db, McpService())
            assertEquals(server, mcp.getById("s"))
            mcp.saveServer(server.copy(icon = null))
            assertEquals(server.copy(icon = null), mcp.getById("s"))
        } finally { db.close() }
    }

    @Test fun namedIconsApplyToFutureEntitiesAcrossProvidersAndRestarts() = runBlocking {
        installTestAndroidKeyStore()
        context.getSharedPreferences("named-entity-icons", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val source = LlmProviderInfo("first", " My Provider ", ProviderType.OPENAI,
                baseUrl = "https://example.test", icon = "icon-a.png",
                config = ProviderConfiguration(modelConfigs = mapOf("m" to ModelConfiguration(displayName = "My Model", icon = "icon-b.png"))))
            LlmRepository(db, LlmService(context), IconStore(context)).saveProvider(source)
            LlmRepository(db, LlmService(context), IconStore(context)).deleteProvider(source.id)
            val repository = LlmRepository(db, LlmService(context), IconStore(context))
            val future = source.copy(id = "later", name = "my provider", icon = null,
                config = ProviderConfiguration(modelConfigs = mapOf("another-id" to ModelConfiguration(displayName = " my model "))))
            repository.saveProvider(future)
            val restored = repository.getProviderById("later")!!
            assertNull(restored.icon)
            assertEquals("icon-a.png", IconStore(context).find(restored.name))
            assertNull(restored.config.modelConfigs.getValue("another-id").icon)
            assertEquals("icon-b.png", IconStore(context).find("my model", model = true))
            assertEquals("icon-a.png", IconStore(context).find("my provider"))
            val mcp = McpRepository(db, McpService(), IconStore(context))
            mcp.saveServer(McpInfo("s", "Tool Server", icon = "icon-c.png"))
            mcp.deleteServer("s")
            McpRepository(db, McpService(), IconStore(context)).saveServer(McpInfo("s2", " tool server "))
            assertNull(mcp.getById("s2")!!.icon)
            assertEquals("icon-c.png", IconStore(context).find(mcp.getById("s2")!!.name))
            mcp.saveServer(mcp.getById("s2")!!.copy(icon = null))
            mcp.saveServer(McpInfo("s3", "tool server"))
            assertNull(mcp.getById("s3")!!.icon)
            assertEquals("icon-c.png", IconStore(context).find("tool server"))
        } finally { db.close() }
    }

    @Test fun sharedRegexMatchingPrefersModelSuffixThenPrefixAndIgnoresVariant() {
        val store = IconStore(context)
        store.saveRules(listOf(
            IconRule(pattern = "vendor", image = "icon-a.png"),
            IconRule(pattern = "claude", image = "icon-b.png"),
            IconRule(pattern = "gemini|google", image = "icon-c.png")))
        assertEquals("icon-b.png", store.find("vendor/claude-sonnet-4", model = true))
        assertEquals("icon-a.png", store.find("vendor/unknown-v2", model = true))
        assertEquals("icon-c.png", store.find("GEMINI-3-flash", model = true))
        assertEquals("icon-c.png", store.find(" Google "))
        assertEquals(listOf("claude", "vendor"), iconMatchNames("vendor/claude-sonnet", true))
        assertNull(store.find("somethingelse", model = true))
        assertThrows(IllegalArgumentException::class.java) { store.saveRules(listOf(IconRule(pattern = "[", image = "icon-d.png"))) }
        store.saveRules(store.rules().map { if (it.pattern == "claude") it.copy(image = "icon-d.png") else it })
        assertEquals("icon-d.png", IconStore(context).find("claude-haiku", true))
        store.saveRules(emptyList())
        assertNull(store.find("claude-haiku", true))
    }

    @Test fun legacyRecordsWithoutIconsStillLoad() = runBlocking {
        assertNull(kotlinx.serialization.json.Json.decodeFromString<ModelConfiguration>("{}").icon)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.mcpServerDao().insertOrUpdate(McpServerEntity(
                id = "old", name = "Old", protocol = McpProtocol.SSE.name, url = "https://example.test/sse",
                headersJson = """{"headers":{"X-Test":"yes"},"auth":{"method":"NONE"}}""",
                cachedToolsJson = null, sortOrder = 0))
            val restored = McpRepository(db, McpService()).getById("old")!!
            assertNull(restored.icon)
            assertEquals(mapOf("X-Test" to "yes"), restored.headers)
        } finally { db.close() }
    }

    @Test fun pruneRemovesOnlyUnreferencedImportedFiles() {
        context.getSharedPreferences("named-entity-icons", Context.MODE_PRIVATE).edit().clear().commit()
        val directory = File(context.filesDir, "entity-icons").apply {
            mkdirs()
            listFiles().orEmpty().forEach { it.delete() }
        }
        val ruleIcon = "icon-11111111-1111-1111-1111-111111111111.png"
        val entityIcon = "icon-22222222-2222-2222-2222-222222222222.png"
        val orphanIcon = "icon-33333333-3333-3333-3333-333333333333.png"
        File(directory, ruleIcon).writeBytes(byteArrayOf(1))
        File(directory, entityIcon).writeBytes(byteArrayOf(2))
        File(directory, orphanIcon).writeBytes(byteArrayOf(3))
        File(directory, "import-stale.tmp").writeBytes(byteArrayOf(4))

        val store = IconStore(context)
        store.saveRules(listOf(IconRule(pattern = "provider", image = ruleIcon)))
        store.prune(listOf(entityIcon))

        assertTrue(File(directory, ruleIcon).exists())
        assertTrue(File(directory, entityIcon).exists())
        assertFalse(File(directory, orphanIcon).exists())
        assertFalse(File(directory, "import-stale.tmp").exists())
    }

}
