package org.starfall.multigateway

import android.content.ComponentCallbacks2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.mikepenz.markdown.model.State
import org.starfall.multigateway.ui.chat.AssetKey
import org.starfall.multigateway.ui.chat.ChatOpenCache
import org.starfall.multigateway.ui.chat.ChatRenderCaches
import org.starfall.multigateway.ui.chat.ChatSettleTracker
import org.starfall.multigateway.ui.chat.MarkdownParseCache
import org.starfall.multigateway.ui.chat.SoftLruCache
import com.mikepenz.markdown.model.parseMarkdown

@RunWith(RobolectricTestRunner::class)
class ChatRenderCachesTest {
    @Before fun cold() = ChatRenderCaches.clearAll()

    private fun parse(text: String): State = runBlocking { parseMarkdown(text) }

    @Test fun lruDropsTheLeastRecentlyUsedEntryWhenOverWeight() {
        val cache = SoftLruCache<String, String>(maxWeight = 10)
        cache.put("a", "A", 4)
        cache.put("b", "B", 4)
        assertNotNull(cache.get("a"))          // "a" is now the most recent
        cache.put("c", "C", 4)                 // 12 > 10: "b" goes
        assertEquals("A", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("C", cache.get("c"))
    }

    @Test fun trimmingKeepsAFractionAndZeroEmptiesEverything() {
        val cache = SoftLruCache<Int, Int>(maxWeight = 10)
        (1..10).forEach { cache.put(it, it, 1) }
        cache.trimToFraction(0.5f)
        assertFalse(cache.contains(1))
        assertTrue(cache.contains(10))
        cache.trimToFraction(0f)
        assertFalse(cache.contains(10))
    }

    @Test fun parsedMarkdownIsReusedUntilMemoryRunsLow() {
        val text = "# Title\n\nSome **bold** text."
        MarkdownParseCache.put(text, parse(text))
        assertTrue(MarkdownParseCache.contains(text))
        // Going to the background is normal and keeps the cache.
        ChatRenderCaches.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(MarkdownParseCache.contains(text))
        ChatRenderCaches.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertFalse(MarkdownParseCache.contains(text))
    }

    @Test fun aParseOfDifferentTextIsNeverStoredUnderTheWrongKey() {
        MarkdownParseCache.put("other", parse("text"))
        assertNull(MarkdownParseCache.get("other"))
    }

    @Test fun aChatIsWarmOnlyWhileItsAssetsSignatureAndEnvironmentMatch() {
        val text = "Answer"
        MarkdownParseCache.put(text, parse(text))
        ChatOpenCache.remember("chat", signature = 7, environment = 1, assets = listOf(AssetKey.Markdown(text)))
        assertTrue(ChatOpenCache.isWarm("chat", 7, 1))
        assertFalse("changed messages", ChatOpenCache.isWarm("chat", 8, 1))
        assertFalse("changed font/theme/width", ChatOpenCache.isWarm("chat", 7, 2))
        assertFalse("never opened", ChatOpenCache.isWarm("other", 7, 1))
        MarkdownParseCache.get(text)
        ChatRenderCaches.clearAll()
        assertFalse("everything was evicted", ChatOpenCache.isWarm("chat", 7, 1))
    }

    @Test fun aChatWhoseParsedContentWasEvictedIsNotWarm() {
        val text = "Answer"
        ChatOpenCache.remember("chat", 1, 1, listOf(AssetKey.Markdown(text)))
        assertFalse(ChatOpenCache.isWarm("chat", 1, 1))
    }

    @Test fun trackerCountsPendingWorkAndRetainsAssetsOnce() {
        val tracker = ChatSettleTracker()
        tracker.beginPending(); tracker.beginPending()
        assertEquals(2, tracker.pending)
        tracker.endPending(); tracker.endPending(); tracker.endPending()
        assertEquals("never negative", 0, tracker.pending)

        val key = AssetKey.Markdown("x")
        tracker.retain(key); tracker.retain(key)
        assertEquals(listOf(key), tracker.readyAssets())
        tracker.release(key)
        assertEquals(listOf(key), tracker.readyAssets())
        tracker.release(key)
        assertTrue(tracker.readyAssets().isEmpty())
    }
}
