package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.components.ReorderRow
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.components.reorderTarget

class ModelReorderTest {
    private val rows = listOf(
        ReorderRow(0, 16, 72),
        ReorderRow(1, 96, 112),
        ReorderRow(2, 216, 88),
        ReorderRow(3, 312, 72)
    )

    @Test
    fun movesOnlyAfterCrossingActualNeighborCenter() {
        assertEquals(0, reorderTarget(rows, 0, 140f))
        assertEquals(1, reorderTarget(rows, 0, 153f))
        assertEquals(2, reorderTarget(rows, 0, 261f))
        assertEquals(3, reorderTarget(rows, 0, 349f))
    }

    @Test
    fun upwardDragUsesVariableHeightRows() {
        assertEquals(3, reorderTarget(rows, 3, 270f))
        assertEquals(2, reorderTarget(rows, 3, 259f))
        assertEquals(1, reorderTarget(rows, 3, 151f))
        assertEquals(0, reorderTarget(rows, 3, 51f))
    }

    @Test
    fun visibleWindowKeepsAbsoluteListIndices() {
        val visible = listOf(ReorderRow(6, -24, 72), ReorderRow(7, 56, 112), ReorderRow(8, 176, 88))
        assertEquals(8, reorderTarget(visible, 6, 221f))
        assertEquals(6, reorderTarget(visible, 8, 11f))
    }

    @Test
    fun everyMovePreservesAllModelIdsAndConfigurations() {
        val configs = linkedMapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        for (from in configs.keys.indices) {
            for (to in configs.keys.indices) {
                val order = configs.keys.toList().moved(from, to)
                assertEquals(configs.size, order.size)
                assertEquals(configs.keys, order.toSet())
                assertEquals(configs.keys.elementAt(from), order[to])
                assertEquals(configs, order.associateWith { configs.getValue(it) })
            }
        }
    }
}
