package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.components.moved

class ModelReorderTest {
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
