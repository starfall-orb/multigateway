package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.ui.chat.StreamingFadeRange
import org.starfall.multigateway.ui.chat.StreamingFadeTracker

class StreamingFadeTrackerTest {
    @Test
    fun appendedChunkDoesNotRestartPreviousFade() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello", 0)
        assertEquals(
            listOf(StreamingFadeRange(0, 5, 0), StreamingFadeRange(5, 11, 64)),
            tracker.update("Hello world", 64)
        )
        assertEquals(listOf(StreamingFadeRange(11, 12, 400)), tracker.update("Hello world!", 400))
    }

    @Test
    fun markdownFormattingRewritePreservesUnchangedPrefix() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello **wor", 0)
        assertEquals(
            listOf(StreamingFadeRange(0, 6, 0), StreamingFadeRange(6, 11, 64)),
            tracker.update("Hello world", 64)
        )
    }

    @Test
    fun shortenedAndEmptyTextNeverLeaveOutOfBoundsRanges() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello world", 0)
        assertEquals(listOf(StreamingFadeRange(0, 5, 0)), tracker.update("Hello", 64))
        assertTrue(tracker.update("", 128).isEmpty())
    }

    @Test
    fun finishedChunksAreDiscardedWithoutNewAnimations() {
        val tracker = StreamingFadeTracker()
        tracker.update("Hello", 0)
        assertTrue(tracker.update("Hello", 300).isEmpty())
    }
}
