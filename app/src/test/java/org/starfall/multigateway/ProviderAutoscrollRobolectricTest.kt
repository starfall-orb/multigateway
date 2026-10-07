package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ProviderAutoscrollRobolectricTest {
    @Test
    fun testManualScroll() {
        val initial = listOf(LlmProviderInfo("outside", "Outside", ProviderType.OPENAI, baseUrl = "", sortOrder = 0)) +
            (0..35).map { LlmProviderInfo("member-$it", "Member $it", ProviderType.OPENAI,
                baseUrl = "", groupId = "long", sortOrder = it) } +
            LlmProviderInfo("tail", "Tail", ProviderType.OPENAI, baseUrl = "", sortOrder = 2)
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = initial,
                    providerGroups = listOf(ProviderGroup("long", "Long folder", 1)),
                    isGridView = true,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {}
                )
            }
        }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("provider_list")
        val before = surface.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        surface.performTouchInput {
            swipeUp()
        }
        compose.waitForIdle()
        val after = surface.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        println("MANUAL SCROLL: before=$before, after=$after")
        assertTrue("Manual scroll must succeed", after > before)
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testListEdgeTransfer() = checkEdgeTransfer(false)

    @Test
    fun testGridEdgeTransfer() = checkEdgeTransfer(true)

    private fun checkEdgeTransfer(grid: Boolean) {
        val initial = listOf(LlmProviderInfo("outside", "Outside", ProviderType.OPENAI, baseUrl = "", sortOrder = 0)) +
            (0..35).map { LlmProviderInfo("member-$it", "Member $it", ProviderType.OPENAI,
                baseUrl = "", groupId = "long", sortOrder = it) } +
            LlmProviderInfo("tail", "Tail", ProviderType.OPENAI, baseUrl = "", sortOrder = 2)
        val writes = mutableListOf<org.starfall.multigateway.data.model.ProviderPlacement>()
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = initial,
                    providerGroups = listOf(ProviderGroup("long", "Long folder", 1)),
                    isGridView = grid,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {}, onPlaceProvider = {
                        writes += it
                        Result.success(Unit)
                    }
                )
            }
        }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("provider_list")
        val root = surface.fetchSemanticsNode().boundsInRoot
        val sourceNode = compose.onNodeWithTag("provider_outside").fetchSemanticsNode().boundsInRoot
        val source = sourceNode.center - root.topLeft
        val height = root.height

        fun scrollPosition(): Float = surface.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

        val before = scrollPosition()
        println("BEFORE scrollPosition: $before, source=$source, grid=$grid")

        var forward = before
        var reversed = before

        val col1 = root.width * 0.75f
        val targetX = if (grid) col1 else source.x
        val targetY = height - 20f

        // Dispatch each step before advancing frames: performTouchInput batches
        // events until its block returns, so frames inside that block cannot scroll.
        fun holdFinger(milliseconds: Long) {
            surface.performTouchInput { advanceEventTime(milliseconds) }
            compose.mainClock.advanceTimeBy(milliseconds)
            compose.waitForIdle()
        }
        fun moveFinger(point: Offset) {
            surface.performTouchInput {
                advanceEventTime(50)
                moveTo(point)
            }
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
        surface.performTouchInput {
            down(source)
            advanceEventTime(650)
            moveBy(Offset(0f, 1f))
        }
        holdFinger(50)
        compose.onNodeWithTag("dragged_provider").assertExists()
        var currentY = source.y
        while (currentY < targetY) {
            currentY = (currentY + 20f).coerceAtMost(targetY)
            val currentX = source.x + (targetX - source.x) * (currentY - source.y) / (targetY - source.y)
            moveFinger(Offset(currentX, currentY))
        }
        repeat(30) { holdFinger(100) }
        compose.onNodeWithTag("dragged_provider").assertExists()
        forward = scrollPosition()
        val topY = 20f
        while (currentY > topY) {
            currentY = (currentY - 20f).coerceAtLeast(topY)
            val currentX = source.x + (targetX - source.x) * (currentY - source.y) / (targetY - source.y)
            moveFinger(Offset(currentX, currentY))
        }
        repeat(30) { holdFinger(100) }
        reversed = scrollPosition()
        surface.performTouchInput { cancel() }
        compose.waitForIdle()

        println("RESULTS: grid=$grid, before=$before, forward=$forward, reversed=$reversed, writes=$writes")
        assertTrue("Library must scroll through folder members while the finger stays at the edge: before=$before after=$forward", forward > before)
        assertTrue("Autoscroll preview must not persist", writes.isEmpty())
        assertTrue("Reversing at the edge must reverse autoscroll", reversed < forward)
        assertTrue("Cancellation must not save membership or order", writes.isEmpty())
    }
}
