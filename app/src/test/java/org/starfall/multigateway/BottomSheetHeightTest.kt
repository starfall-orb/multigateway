package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.components.bottomSheetHeightBounds

class BottomSheetHeightTest {
    @Test fun openingFitsContentWithinOneThirdAndTheExistingSafeMaximum() {
        val bounds = bottomSheetHeightBounds(windowHeight = 1200, availableHeight = 1160)
        assertEquals(400, bounds.minimum)
        assertEquals(1160, bounds.maximum)
        assertEquals(400, bounds.heightFor(180, null))
        assertEquals(680, bounds.heightFor(680, null))
        assertEquals(1160, bounds.heightFor(2200, null))
    }

    @Test fun resizingKeepsArbitraryIntermediateHeightsInsteadOfSnapping() {
        val bounds = bottomSheetHeightBounds(1200, 1160)
        val first = bounds.resizedHeight(1160f, 237.5f)
        assertEquals(922.5f, first, 0f)
        val second = bounds.resizedHeight(first, 114.25f)
        assertEquals(808.25f, second, 0f)
        assertEquals(808, bounds.heightFor(2000, second))
        assertEquals(400f, bounds.resizedHeight(second, 10000f), 0f)
        assertEquals(1160f, bounds.resizedHeight(second, -10000f), 0f)
    }

    @Test fun contentLoadingAutoGrowsUntilTheUserChoosesAHeight() {
        val bounds = bottomSheetHeightBounds(1200, 1160)
        assertEquals(400, bounds.heightFor(150, null))
        assertEquals(900, bounds.heightFor(900, null))
        val requested = bounds.resizedHeight(900f, 120f)
        assertEquals(780, bounds.heightFor(2000, requested))
        assertEquals(780, bounds.heightFor(180, requested))
    }

    @Test fun keyboardAndShortWindowsNeverMakeTheBoundsInvalid() {
        val withKeyboard = bottomSheetHeightBounds(1200, 250)
        assertEquals(250, withKeyboard.minimum)
        assertEquals(250, withKeyboard.maximum)
        assertEquals(250, withKeyboard.heightFor(1800, 780f))
        val afterKeyboard = bottomSheetHeightBounds(1200, 1160)
        assertEquals(780, afterKeyboard.heightFor(1800, 780f))
        assertEquals(1, bottomSheetHeightBounds(0, 0).heightFor(0, null))
    }
}
