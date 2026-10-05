package org.starfall.multigateway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.providers.nearestProviderSlot

class ProviderReorderTest {
    @Test
    fun nearestSlotAllowsReversingWithinSameDragGesture() {
        val slot0 = Rect(0f, 0f, 100f, 100f)
        val slot1 = Rect(0f, 120f, 100f, 220f)

        // A starts in slot 0 and is dragged across B's slot.
        assertEquals(
            "B",
            nearestProviderSlot(
                providerId = "A",
                sectionIds = listOf("A", "B"),
                bounds = mapOf("A" to slot0, "B" to slot1),
                visualCenter = slot1.center,
            )
        )

        // Layout swaps immediately while A remains under the finger.
        val swapped = mapOf("A" to slot1, "B" to slot0)
        assertEquals(
            "A",
            nearestProviderSlot(
                providerId = "A",
                sectionIds = listOf("B", "A"),
                bounds = swapped,
                visualCenter = slot1.center,
            )
        )

        // Without releasing the finger, dragging back selects B's current slot,
        // allowing the order to reverse immediately.
        assertEquals(
            "B",
            nearestProviderSlot(
                providerId = "A",
                sectionIds = listOf("B", "A"),
                bounds = swapped,
                visualCenter = slot0.center,
            )
        )
    }

    @Test
    fun nearestSlotWorksInTwoDimensionalGrid() {
        val bounds = mapOf(
            "A" to Rect(0f, 0f, 100f, 100f),
            "B" to Rect(120f, 0f, 220f, 100f),
            "C" to Rect(0f, 120f, 100f, 220f),
            "D" to Rect(120f, 120f, 220f, 220f),
        )

        assertEquals(
            "D",
            nearestProviderSlot(
                providerId = "A",
                sectionIds = listOf("A", "B", "C", "D"),
                bounds = bounds,
                visualCenter = Offset(175f, 175f),
            )
        )
    }
}
