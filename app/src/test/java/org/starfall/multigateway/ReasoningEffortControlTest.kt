package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.ui.chat.reasoningEffortEnabled
import org.starfall.multigateway.ui.chat.reasoningEffortIndex

class ReasoningEffortControlTest {
    @Test fun disabledValuesAreRecognized() {
        assertFalse(reasoningEffortEnabled("none"))
        assertFalse(reasoningEffortEnabled(" OFF "))
        assertTrue(reasoningEffortEnabled(null))
        assertTrue(reasoningEffortEnabled("medium"))
    }

    @Test fun sliderIndexMapsToKnownEffortAndDefaultsSafely() {
        assertEquals(0, reasoningEffortIndex("none"))
        assertEquals(0, reasoningEffortIndex("off"))
        assertEquals(1, reasoningEffortIndex(null))
        assertEquals(2, reasoningEffortIndex("LOW"))
        assertEquals(5, reasoningEffortIndex("xhigh"))
        assertEquals(1, reasoningEffortIndex("unknown"))
    }
}
