package org.starfall.multigateway

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.providers.ProviderModelsPane

class LibraryReorderBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelOrderChangesDuringDragAndPersistsOnlyOnRelease() {
        val order = mutableStateOf(listOf("First", "Second", "Third"))
        val configurations = order.value.associateWith { ModelConfiguration() }
        val saved = mutableListOf<List<String>>()
        compose.setContent { MaterialTheme {
            ProviderModelsPane(order.value, configurations, rememberLazyListState(),
                onMove = { from, to -> order.value = order.value.moved(from, to) },
                onDrop = { saved += order.value }, onEdit = {}, onDelete = {}, onOpenCatalog = {})
        } }
        val source = compose.onNodeWithText("First")
        val from = source.fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithText("Third").fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(Offset(0f, to.center.y - from.center.y), delayMillis = 200)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("First", order.value.last())
            assertTrue(saved.isEmpty())
        }
        source.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(order.value), saved) }
    }
}
