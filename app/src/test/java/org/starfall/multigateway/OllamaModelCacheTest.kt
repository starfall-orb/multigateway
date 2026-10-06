package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.service.LlmService
import org.starfall.multigateway.data.service.OllamaModelCache

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OllamaModelCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun reopeningUsesPersistedModelsWithoutAnotherNetworkRequest() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"models":[{"name":"local-model"}]}"""))
            val url = server.url("/").toString()
            assertEquals(listOf("local-model"), LlmService(context).fetchOllamaModels(url))
            assertEquals(listOf("local-model"), LlmService(context).fetchOllamaModels(url))
            assertEquals(1, server.requestCount)
            assertEquals(listOf("local-model"), OllamaModelCache(context).read(url)?.models)
        } finally { server.shutdown() }
    }

    @Test fun staleModelsSurviveOfflineRefreshAndEndpointsStaySeparate() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val url = server.url("/").toString()
            val cache = OllamaModelCache(context, now = { 0 })
            cache.write(url, listOf("previous-model"))
            assertFalse(OllamaModelCache(context).isFresh(cache.read(url)!!))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(listOf("previous-model"), LlmService(context).fetchOllamaModels(url))
            assertNull(cache.read(server.url("/other/").toString()))
        } finally { server.shutdown() }
    }

    @Test fun successfulEmptyDiscoveryClearsRemovedModels() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val url = server.url("/").toString()
            OllamaModelCache(context, now = { 0 }).write(url, listOf("removed-model"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"models":[]}"""))
            assertTrue(LlmService(context).fetchOllamaModels(url).isEmpty())
            assertTrue(OllamaModelCache(context).read(url)!!.models.isEmpty())
        } finally { server.shutdown() }
    }
}
