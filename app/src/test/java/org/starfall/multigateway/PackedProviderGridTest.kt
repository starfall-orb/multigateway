package org.starfall.multigateway

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.providers.PackedGridCell
import org.starfall.multigateway.ui.providers.packedGroupBlockBounds
import org.starfall.multigateway.ui.providers.providerDragCells

class PackedProviderGridTest {
    private val folder = listOf(
        PackedGridCell("group_g", "g"),
        PackedGridCell("provider_a", "g"),
        PackedGridCell("provider_b", "g"),
        PackedGridCell("provider_c", "g")
    )
    private val tail = PackedGridCell("provider_tail", null)

    @Test fun evenFolderHeaderStaysBeforeTheMembersBlock() {
        val cells = providerDragCells(listOf(PackedGridCell("provider_before", null)) + folder + tail, true, null)
        assertEquals(listOf("root:start", "provider_before", "group_g", "folder-start_g",
            "provider_a", "provider_b", "provider_c", "folder-end_g", "provider_tail", "root:end"), cells.map { it.key })
        assertNull(cells.first { it.key == "group_g" }.groupId)
        assertEquals(listOf("folder-start_g", "provider_a", "provider_b", "provider_c", "folder-end_g"),
            cells.filter { it.groupId == "g" }.map { it.key })
    }

    @Test fun oddFolderHeaderIsPartOfTheSameRectangularBlock() {
        val before = listOf(PackedGridCell("provider_one", null), PackedGridCell("provider_two", null))
        val cells = providerDragCells(before + folder + tail, true, null)
        assertEquals(listOf("root:start", "provider_one", "provider_two", "folder-start_g", "group_g",
            "provider_a", "provider_b", "provider_c", "folder-end_g", "provider_tail", "root:end"), cells.map { it.key })
        assertEquals("g", cells.first { it.key == "group_g" }.groupId)
    }

    @Test fun nextFolderStartsInANewRowAfterAnIncompleteFolder() {
        val cells = providerDragCells(folder.dropLast(1) + PackedGridCell("group_h", "h"), true, null)
        assertEquals(listOf("root:start", "folder-start_g", "group_g", "provider_a", "provider_b",
            "folder-end_g", "folder-start_h", "group_h", "folder-end_h", "root:end"), cells.map { it.key })
        assertEquals("h", cells.first { it.key == "group_h" }.groupId)
    }

    @Test fun closedFoldersRemainOrdinaryGridTiles() {
        val cells = listOf(PackedGridCell("group_g", null), tail)
        assertEquals(listOf(PackedGridCell("root:start", null)) + cells + PackedGridCell("root:end", null),
            providerDragCells(cells, true, null))
    }

    @Test fun draggedFolderRetainsItsHandleAndRestoresTheBlockAfterCancel() {
        val cells = folder + tail
        val heading = providerDragCells(cells, false, "heading_g")
        assertEquals(listOf("root:start", "folder-start_g", "heading_g", "folder-end_g", "provider_tail", "root:end"),
            heading.map { it.key })
        val tile = providerDragCells(cells, true, "group_g")
        assertEquals(listOf("root:start", "group_g", "provider_tail", "root:end"), tile.map { it.key })
        val restored = providerDragCells(cells, false, null)
        assertEquals(listOf("root:start", "folder-start_g", "heading_g", "group_g", "provider_a", "provider_b",
            "provider_c", "folder-end_g", "provider_tail", "root:end"), restored.map { it.key })
        assertEquals(restored, providerDragCells(cells, false, "provider_a"))
    }

    @Test fun containerReservesTheEmptyPartnerColumnAndExcludesOtherTiles() {
        val cells = providerDragCells(listOf(PackedGridCell("provider_before", null)) + folder + tail, true, null)
        val slots = mapOf(
            "provider_before" to Rect(0f, 24f, 100f, 188f),
            "group_g" to Rect(100f, 24f, 200f, 188f),
            "folder-start_g" to Rect(0f, 188f, 200f, 200f),
            "provider_a" to Rect(0f, 200f, 100f, 364f),
            "provider_b" to Rect(100f, 200f, 200f, 364f),
            "provider_c" to Rect(0f, 364f, 100f, 528f),
            "folder-end_g" to Rect(0f, 528f, 200f, 540f),
            "provider_tail" to Rect(0f, 540f, 100f, 704f)
        )
        val blocks = packedGroupBlockBounds(cells, slots, Size(200f, 800f), inset = 6f)
        assertEquals(mapOf("g" to Rect(1f, 194f, 199f, 534f)), blocks)
        val block = blocks.getValue("g")
        assertTrue(block.contains(slots.getValue("provider_c").center.copy(x = 150f)))
        for (key in listOf("provider_before", "group_g", "provider_tail")) {
            assertFalse("$key must remain outside the folder", block.contains(slots.getValue(key).center))
        }
    }

    @Test fun scrollingDoesNotDrawFalseRoundedEndsInsideALongFolder() {
        val cells = providerDragCells(folder, true, null)
        val slots = mapOf("provider_a" to Rect(100f, -64f, 200f, 100f),
            "provider_b" to Rect(0f, 100f, 100f, 264f))
        val block = packedGroupBlockBounds(cells, slots, Size(200f, 200f), inset = 6f).getValue("g")
        assertTrue(block.top < 0f)
        assertTrue(block.bottom > 200f)
        assertTrue(packedGroupBlockBounds(cells, emptyMap(), Size(200f, 200f), inset = 6f).isEmpty())
    }
}
