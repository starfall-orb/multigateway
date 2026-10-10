package org.starfall.multigateway.ui.chat

import android.content.ComponentCallbacks2
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.State
import java.lang.ref.SoftReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RAM that only exists to make reopening a chat faster. Everything registered here is low priority:
 * the garbage collector may drop the values at any time (they are held by [SoftReference]) and the app
 * empties the caches itself when Android reports memory pressure ([onTrimMemory]).
 */
internal interface EvictableCache {
    /** Keep at most [fraction] (0..1) of the capacity; 0 empties the cache. */
    fun trimToFraction(fraction: Float)
}

internal object ChatRenderCaches {
    private val caches = CopyOnWriteArrayList<EvictableCache>()

    fun register(cache: EvictableCache) {
        caches.addIfAbsent(cache)
    }

    fun clearAll() = caches.forEach { it.trimToFraction(0f) }

    /**
     * Called from [android.content.ComponentCallbacks2.onTrimMemory]. Going to the background
     * ([ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]) is normal and keeps the cache; any real pressure
     * (running low/critical, or the process being a candidate to be killed) empties it.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val fraction = when {
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> return
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 0f
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> 0.5f
            else -> return
        }
        caches.forEach { it.trimToFraction(fraction) }
    }

    /** True when [key] can be drawn on the first frame without waiting for any background work. */
    fun isReady(key: AssetKey): Boolean = when (key) {
        is AssetKey.Markdown -> MarkdownParseCache.contains(key.content)
        is AssetKey.Latex -> LatexDrawables.peek(key.key) != null
        is AssetKey.Attachment -> AttachmentPreviewCache.contains(key.reference)
    }
}

/**
 * LRU cache bounded by a caller-defined weight whose values are only softly reachable, so it never
 * competes with the rest of the app for memory.
 */
internal class SoftLruCache<K : Any, V : Any>(maxWeight: Int) : EvictableCache {
    private class Entry<V : Any>(value: V, val weight: Int) {
        val ref = SoftReference(value)
    }

    private val lru = object : LruCache<K, Entry<V>>(maxWeight) {
        // Fixed when the entry is stored: LruCache requires sizeOf to stay constant, and a soft
        // reference may be cleared at any time.
        override fun sizeOf(key: K, value: Entry<V>): Int = value.weight
    }

    init {
        ChatRenderCaches.register(this)
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = lru.get(key) ?: return null
        return entry.ref.get() ?: run {
            lru.remove(key)
            null
        }
    }

    @Synchronized
    fun contains(key: K): Boolean = get(key) != null

    @Synchronized
    fun put(key: K, value: V, weight: Int) {
        lru.put(key, Entry(value, weight.coerceAtLeast(1)))
    }

    @Synchronized
    override fun trimToFraction(fraction: Float) {
        if (fraction <= 0f) lru.evictAll() else lru.trimToSize((lru.maxSize() * fraction).toInt())
    }
}

/** Parsed Markdown by source text, so a reopened answer is laid out at its final height on the first frame. */
internal object MarkdownParseCache {
    // Weighed in source characters; the parsed tree is several times larger.
    private const val MAX_CHARS = 600_000
    private val cache = SoftLruCache<String, State.Success>(MAX_CHARS)

    fun get(content: String): State.Success? = cache.get(content)

    fun contains(content: String): Boolean = cache.contains(content)

    fun put(content: String, state: State) {
        if (state is State.Success && state.content == content) cache.put(content, state, content.length)
    }
}

/** One thing a message needs from background work before it has its final size. */
internal sealed interface AssetKey {
    data class Markdown(val content: String) : AssetKey
    data class Latex(val key: LatexCacheKey) : AssetKey
    data class Attachment(val reference: String) : AssetKey
}

/**
 * Remembers which conversations were last seen settled at their newest message, together with the
 * assets that were on screen. Reopening one of them skips the loading veil when every one of those
 * assets is still cached and nothing that affects layout has changed.
 */
internal object ChatOpenCache {
    private const val MAX_CONVERSATIONS = 24

    private class Entry(val signature: Int, val environment: Int, val assets: List<AssetKey>)

    private val cache = SoftLruCache<String, Entry>(MAX_CONVERSATIONS)

    fun isWarm(conversationId: String, signature: Int, environment: Int): Boolean {
        val entry = cache.get(conversationId) ?: return false
        return entry.signature == signature &&
            entry.environment == environment &&
            entry.assets.all(ChatRenderCaches::isReady)
    }

    fun remember(conversationId: String, signature: Int, environment: Int, assets: List<AssetKey>) {
        cache.put(conversationId, Entry(signature, environment, assets), 1)
    }
}

/**
 * Counts the background work (Markdown parsing, formulas, previews) of the items on screen and keeps
 * the assets that are ready, so the chat can tell when its layout has stopped changing.
 */
@Stable
internal class ChatSettleTracker {
    var pending by mutableIntStateOf(0)
        private set

    private val retained = LinkedHashMap<AssetKey, Int>()

    fun beginPending() {
        pending += 1
    }

    fun endPending() {
        pending = (pending - 1).coerceAtLeast(0)
    }

    fun retain(key: AssetKey) {
        retained[key] = (retained[key] ?: 0) + 1
    }

    fun release(key: AssetKey) {
        val count = (retained[key] ?: return) - 1
        if (count <= 0) retained.remove(key) else retained[key] = count
    }

    fun readyAssets(): List<AssetKey> = retained.keys.toList()
}

internal val LocalChatSettleTracker = staticCompositionLocalOf<ChatSettleTracker?> { null }

/** While [pending] is true the surrounding chat is told that this item's size is not final yet. */
@Composable
internal fun TrackPendingWork(pending: Boolean) {
    val tracker = LocalChatSettleTracker.current ?: return
    if (pending) {
        DisposableEffect(tracker) {
            tracker.beginPending()
            onDispose { tracker.endPending() }
        }
    }
}

/** Reports assets that are on screen in their final form, for [ChatOpenCache]. */
@Composable
internal fun TrackReadyAssets(assets: List<AssetKey>) {
    val tracker = LocalChatSettleTracker.current ?: return
    if (assets.isEmpty()) return
    DisposableEffect(tracker, assets) {
        assets.forEach(tracker::retain)
        onDispose { assets.forEach(tracker::release) }
    }
}
