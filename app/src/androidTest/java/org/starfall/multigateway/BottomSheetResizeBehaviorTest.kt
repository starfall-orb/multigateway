package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.ui.components.AppBottomSheet

class BottomSheetResizeBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortSheetOpensAtOneThirdAndKeepsTheLastItemVisible() {
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent { MaterialTheme {
            AppBottomSheet(onDismissRequest = {}) {
                Text("Last item", Modifier.fillMaxWidth().padding(16.dp))
            }
        } }
        val fullHeight = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.height
        val sheet = compose.onNodeWithTag("app-bottom-sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(fullHeight / 3f, sheet.height, 2f)
        compose.onNodeWithText("Last item").assertIsDisplayed()
    }

    @Test fun aFewLazyItemsUseContentHeightAndManyItemsUseTheMaximum() {
        compose.setContent { MaterialTheme {
            AppBottomSheet(onDismissRequest = {}) {
                Column {
                    Text("List header", Modifier.padding(16.dp))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items((1..6).toList()) { Text("Item $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        } }
        val fullHeight = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.height
        val height = compose.onNodeWithTag("app-bottom-sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue(height >= fullHeight / 3f - 2)
        assertTrue(height < fullHeight - 24)
        compose.onNodeWithText("Item 6").assertIsDisplayed()
    }

    @Test fun draggingKeepsTwoDifferentIntermediateHeightsAndClampsAtOneThird() {
        var dismissed = false
        compose.setContent { MaterialTheme {
            AppBottomSheet(onDismissRequest = { dismissed = true }) {
                Box(Modifier.fillMaxWidth().fillMaxHeight())
            }
        } }
        val sheet = compose.onNodeWithTag("app-bottom-sheet")
        val handle = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val window = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot
        val initial = sheet.fetchSemanticsNode().boundsInRoot.height
        handle.performTouchInput { swipe(center, center + Offset(0f, window.height * 0.16f), durationMillis = 400) }
        compose.waitForIdle()
        val first = sheet.fetchSemanticsNode().boundsInRoot.height
        assertTrue(first < initial - window.height * 0.1f)
        assertTrue(first > window.height / 3f + 20)
        handle.performTouchInput { swipe(center, center + Offset(0f, window.height * 0.1f), durationMillis = 400) }
        compose.waitForIdle()
        val second = sheet.fetchSemanticsNode().boundsInRoot.height
        assertTrue(second < first - window.height * 0.06f)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(second, sheet.fetchSemanticsNode().boundsInRoot.height, 1f)
        val start = handle.fetchSemanticsNode().boundsInRoot.center.y
        handle.performTouchInput { swipe(center, center + Offset(0f, window.bottom - start - 2f), durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(window.height / 3f, sheet.fetchSemanticsNode().boundsInRoot.height, 2f)
        assertFalse(dismissed)
    }
}
