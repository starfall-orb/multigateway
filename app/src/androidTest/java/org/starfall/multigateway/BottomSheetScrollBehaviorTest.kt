package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.starfall.multigateway.ui.components.AppBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.DiscoveredModel
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderModelCatalogSheet

class BottomSheetScrollBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun draggingDownAtListStartMovesTheSheet() {
        checkDownwardDrag(initialIndex = 0, expectSheetMovement = true)
    }

    @Test fun theSameDragContinuesIntoTheSheetAfterReachingListStart() {
        checkDownwardDrag(initialIndex = 1, expectSheetMovement = true)
    }

    @Test fun draggingDownInTheMiddleScrollsTheListBeforeMovingTheSheet() {
        checkDownwardDrag(initialIndex = 10, expectSheetMovement = false)
    }

    private fun checkDownwardDrag(initialIndex: Int, expectSheetMovement: Boolean) {
        lateinit var listState: LazyListState
        compose.setContent { MaterialTheme {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            AppBottomSheet(onDismissRequest = {}) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).testTag("edge-list")) {
                    items(60) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val initialTop = sheet.fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("edge-list")
        val distance = if (expectSheetMovement) with(compose.density) { (48 * initialIndex).dp.toPx() } + 100f else 80f
        list.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, distance / 4f), delayMillis = 32) }
        }
        compose.waitForIdle()
        val draggedTop = sheet.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            if (expectSheetMovement) {
                assertTrue("Unused downward drag must move the sheet: before=$initialTop after=$draggedTop list=${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}", draggedTop > initialTop + 20f)
                assertEquals(0, listState.firstVisibleItemIndex)
                assertEquals(0, listState.firstVisibleItemScrollOffset)
            } else {
                assertEquals("List must consume the drag until it reaches its start", initialTop, draggedTop, 1f)
                assertTrue(listState.firstVisibleItemIndex < initialIndex)
            }
        }
        list.performTouchInput { up() }
    }

    @Test fun repeatedDownwardDragsReachTheStartWithoutAReverseDrag() {
        lateinit var listState: LazyListState
        compose.setContent { MaterialTheme {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 59)
            AppBottomSheet(onDismissRequest = {}) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).testTag("edge-list")) {
                    items(60) { Text("Row $it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("edge-list")
        // First exercise the bottom edge; returning toward the start must not
        // need an extra swipe to release a retained Android stretch effect.
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 500) }; compose.waitForIdle() }
        val distance = with(compose.density) { 96.dp.toPx() }
        repeat(40) {
            if (!listState.canScrollBackward) return@repeat
            val before = listState.firstVisibleItemIndex * 48f +
                with(compose.density) { listState.firstVisibleItemScrollOffset.toDp().value }
            list.performTouchInput {
                down(Offset(width / 2f, height * 0.25f))
                repeat(4) { moveBy(Offset(0f, distance / 4f), delayMillis = 80) }
                // Release at rest so a fling does not dismiss the sheet at the start.
                moveBy(Offset.Zero, delayMillis = 200)
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val after = listState.firstVisibleItemIndex * 48f +
                    with(compose.density) { listState.firstVisibleItemScrollOffset.toDp().value }
                assertTrue("Each downward drag must advance toward the actual start", after < before)
            }
        }
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        val sheet = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val before = sheet.fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, 25f), delayMillis = 32) }
        }
        compose.waitForIdle()
        assertTrue("At the start the next drag must immediately move the sheet",
            sheet.fetchSemanticsNode().boundsInRoot.top > before + 20f)
        list.performTouchInput { moveBy(Offset.Zero, delayMillis = 200); up() }
    }

    @Test fun fetchModelsKeepsScrollingTowardTheEndWithoutAReverseSwipe() {
        val models = (0 until 150).map { DiscoveredModel(id = "scroll-model-$it", displayName = "Catalog model $it") }
        compose.setContent { MaterialTheme {
            ProviderModelCatalogSheet(
                provider = LlmProviderInfo("test", "Test", ProviderType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(), onFetchModels = { models }, onCatalogLoaded = {},
                onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog model 0").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("provider-model-catalog-list")
        repeat(40) {
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val position = before.value()
            val couldScroll = position < before.maxValue()
            list.performTouchInput { swipeUp(durationMillis = 180) }
            compose.waitForIdle()
            val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (couldScroll) assertTrue("Forward swipes must keep advancing until the real bottom",
                after.value() > position || after.value() >= after.maxValue())
        }
        compose.onNodeWithText("Catalog model 149").assertIsDisplayed()
        // Touching the bottom must not leave a stretch state that blocks later gestures.
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 180) }; compose.waitForIdle() }
        repeat(40) { list.performTouchInput { swipeDown(durationMillis = 180) }; compose.waitForIdle() }
        compose.onNodeWithText("Catalog model 0").assertIsDisplayed()
    }
}
