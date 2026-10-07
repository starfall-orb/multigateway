package org.starfall.multigateway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.asAndroidPath
import org.starfall.multigateway.ui.providers.packedGroupPath
import org.starfall.multigateway.ui.providers.PackedGridCell
import org.starfall.multigateway.ui.providers.packedGroupRegions

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackedProviderGridTest {
    private fun bounds(cells: List<PackedGridCell>) = cells.mapIndexed { index, cell ->
        val left = (index % 2) * 112f
        val top = (index / 2) * 176f
        cell.key to Rect(left, top, left + 100f, top + 164f)
    }.toMap()

    @Test fun frameRendersAContinuousSteppedShapeAndLeavesRootCardsOutside() {
        val cells = listOf(PackedGridCell("before", null), PackedGridCell("folder", "g"),
            PackedGridCell("a", "g"), PackedGridCell("b", "g"), PackedGridCell("c", "g"), PackedGridCell("after", null))
        val positions = bounds(cells)
        val parts = packedGroupRegions(cells, positions).getValue("g")
        val path = packedGroupPath(parts, positions.values.toSet(), Offset.Zero, 20f)
        val bitmap = android.graphics.Bitmap.createBitmap(212, 516, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawPath(path.asAndroidPath(), android.graphics.Paint().apply { color = android.graphics.Color.GRAY })
        for (key in listOf("folder", "a", "b", "c")) {
            val point = positions.getValue(key).center
            assertEquals(android.graphics.Color.GRAY, bitmap.getPixel(point.x.toInt(), point.y.toInt()))
        }
        for (key in listOf("before", "after")) {
            val point = positions.getValue(key).center
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(point.x.toInt(), point.y.toInt()))
        }
        assertEquals(android.graphics.Color.GRAY, bitmap.getPixel(106, 258))
        assertEquals(android.graphics.Color.GRAY, bitmap.getPixel(162, 170))
        bitmap.recycle()
    }

    @Test fun folderStartsInLastColumnAndNextRootItemFillsItsLastRow() {
        val cells = listOf(PackedGridCell("before", null), PackedGridCell("folder", "g"),
            PackedGridCell("a", "g"), PackedGridCell("b", "g"), PackedGridCell("c", "g"), PackedGridCell("after", null))
        val positions = bounds(cells)
        val regions = packedGroupRegions(cells, positions).getValue("g")
        assertEquals(positions.getValue("before").top, positions.getValue("folder").top, 0f)
        assertEquals(positions.getValue("c").top, positions.getValue("after").top, 0f)
        assertFalse(regions.any { it.contains(positions.getValue("before").center) })
        assertFalse(regions.any { it.contains(positions.getValue("after").center) })
        for (key in listOf("folder", "a", "b", "c")) assertTrue(regions.any { it.contains(positions.getValue(key).center) })
        assertTrue(regions.any { it.contains(Offset(106f, 258f)) }) // horizontal seam
        assertTrue(regions.any { it.contains(Offset(162f, 170f)) }) // vertical seam
    }

    @Test fun diagonalFolderCellsDoNotCreateAnOutsideDropTarget() {
        val cells = listOf(PackedGridCell("root", null), PackedGridCell("folder", "g"), PackedGridCell("a", "g"))
        val regions = packedGroupRegions(cells, bounds(cells)).getValue("g")
        assertEquals(4, regions.size)
        assertTrue(regions.any { it.contains(Offset(112f, 164f)) })
        assertFalse(regions.any { it.contains(Offset(50f, 82f)) })
        assertFalse(regions.any { it.contains(Offset(162f, 258f)) })
    }

    @Test fun differentFoldersAndOffscreenCellsDoNotCreateFalseDropTargets() {
        val cells = listOf(PackedGridCell("g1", "first"), PackedGridCell("g2", "second"),
            PackedGridCell("p2", "second"), PackedGridCell("offscreen", "second"))
        val positions = bounds(cells) - "offscreen"
        val regions = packedGroupRegions(cells, positions)
        assertEquals(1, regions.getValue("first").size)
        assertFalse(regions.getValue("first").any { it.contains(positions.getValue("g2").center) })
        assertEquals(3, regions.getValue("second").size)
    }
    @org.junit.Test fun draggedFolderRetainsItsHandleAndRestoresAllMembersAfterCancel() {
        val cells = listOf(
            org.starfall.multigateway.ui.providers.PackedGridCell("group_g", "g"),
            org.starfall.multigateway.ui.providers.PackedGridCell("provider_a", "g"),
            org.starfall.multigateway.ui.providers.PackedGridCell("provider_b", "g"),
            org.starfall.multigateway.ui.providers.PackedGridCell("provider_tail", null))
        val heading = org.starfall.multigateway.ui.providers.providerDragCells(cells, false, "heading_g")
        org.junit.Assert.assertEquals(listOf("root:start", "heading_g", "provider_tail", "root:end"), heading.map { it.key })
        val tile = org.starfall.multigateway.ui.providers.providerDragCells(cells, true, "group_g")
        org.junit.Assert.assertEquals(listOf("root:start", "group_g", "provider_tail", "root:end"), tile.map { it.key })
        val restored = org.starfall.multigateway.ui.providers.providerDragCells(cells, false, null)
        org.junit.Assert.assertEquals(listOf("root:start", "heading_g", "group_g", "provider_a", "provider_b", "provider_tail", "root:end"), restored.map { it.key })
        org.junit.Assert.assertEquals(restored, org.starfall.multigateway.ui.providers.providerDragCells(cells, false, "provider_a"))
    }

}
