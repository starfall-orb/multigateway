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

        try { java.io.File("/tmp/scroll_state.txt").writeText("0,0") } catch (e: Exception) {}
        fun scrollPosition(): Float {
            return try {
                val text = java.io.File("/tmp/scroll_state.txt").readText().trim()
                val parts = text.split(",")
                val idx = parts[0].toFloat()
                val off = parts[1].toFloat()
                idx * 1000000f + off
            } catch (e: Exception) {
                0f
            }
        }

        val before = scrollPosition()
        println("BEFORE scrollPosition: $before, source=$source, grid=$grid")

        var forward = before
        var reversed = before

        val col0 = root.width * 0.25f
        val col1 = root.width * 0.75f
        val startX = if (grid) col0 else source.x
        val targetX = if (grid) col1 else source.x
        val targetY = height - 20f

        surface.performTouchInput {
            down(source)
            advanceEventTime(650)
            compose.mainClock.advanceTimeBy(650)
            compose.waitForIdle()

            var currentY = source.y
            while (currentY < targetY) {
                currentY = (currentY + 20f).coerceAtMost(targetY)
                val currentX = source.x + (targetX - source.x) * (currentY - source.y) / (targetY - source.y)
                moveTo(Offset(currentX, currentY))
                advanceEventTime(50)
                compose.mainClock.advanceTimeBy(50)
                compose.waitForIdle()
            }

            repeat(30) {
                advanceEventTime(100)
                compose.mainClock.advanceTimeBy(100)
                compose.waitForIdle()
            }
            forward = scrollPosition()

            val topY = 20f
            while (currentY > topY) {
                currentY = (currentY - 20f).coerceAtLeast(topY)
                val currentX = source.x + (targetX - source.x) * (currentY - source.y) / (targetY - source.y)
                moveTo(Offset(currentX, currentY))
                advanceEventTime(50)
                compose.mainClock.advanceTimeBy(50)
                compose.waitForIdle()
            }

            repeat(30) {
                advanceEventTime(100)
                compose.mainClock.advanceTimeBy(100)
                compose.waitForIdle()
            }
            reversed = scrollPosition()
            cancel()
        }
        compose.waitForIdle()

        println("RESULTS: grid=$grid, before=$before, forward=$forward, reversed=$reversed, writes=$writes")
        assertTrue("Library must scroll through folder members while the finger stays at the edge: before=$before after=$forward", forward > before)
        assertTrue("Autoscroll preview must not persist", writes.isEmpty())
        assertTrue("Reversing at the edge must reverse autoscroll", reversed < forward)
        assertTrue("Cancellation must not save membership or order", writes.isEmpty())
    }
}
