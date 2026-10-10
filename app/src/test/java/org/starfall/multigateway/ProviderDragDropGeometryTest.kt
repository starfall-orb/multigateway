package org.starfall.multigateway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.providers.DragTile
import org.starfall.multigateway.ui.providers.autoScrollDelta
import org.starfall.multigateway.ui.providers.hitTile
import org.starfall.multigateway.ui.providers.isFolderDropZone

class ProviderDragDropGeometryTest {
    private val tile = Rect(0f, 0f, 100f, 200f)

    @Test fun middleOfAFolderTileIsTheDropZoneAndTheEdgeBandIsNot() {
        assertTrue(isFolderDropZone(tile, Offset(50f, 100f)))
        assertTrue(isFolderDropZone(tile, Offset(30f, 60f)))
        assertFalse(isFolderDropZone(tile, Offset(5f, 100f)))
        assertFalse(isFolderDropZone(tile, Offset(50f, 10f)))
        assertFalse(isFolderDropZone(tile, Offset(95f, 195f)))
    }

    @Test fun hitTileFindsTheTileUnderThePointer() {
        val tiles = listOf(DragTile("a", Rect(0f, 0f, 50f, 50f)), DragTile("b", Rect(50f, 0f, 100f, 50f)))
        assertEquals("b", hitTile(tiles, Offset(60f, 10f))?.key)
        assertNull(hitTile(tiles, Offset(60f, 80f)))
    }

    @Test fun autoScrollOnlyNearTheEdgesAndSpeedsUpTowardsThem() {
        assertEquals(0f, autoScrollDelta(500f, 1000f, 100f, 10f), 0f)
        assertTrue(autoScrollDelta(20f, 1000f, 100f, 10f) < 0f)
        assertTrue(autoScrollDelta(980f, 1000f, 100f, 10f) > 0f)
        assertTrue(autoScrollDelta(980f, 1000f, 100f, 10f) > autoScrollDelta(920f, 1000f, 100f, 10f))
        assertEquals(10f, autoScrollDelta(2000f, 1000f, 100f, 10f), 0f)
        assertEquals(0f, autoScrollDelta(0f, 0f, 100f, 10f), 0f)
    }
}
