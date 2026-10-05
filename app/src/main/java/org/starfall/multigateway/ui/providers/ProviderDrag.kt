package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Keeps the gesture and layout slot in the original folder while the screen draws a lifted copy.
 * A child translation or zIndex cannot escape the folder Surface's clipping boundary.
 */
internal fun Modifier.providerDrag(
    providerId: String,
    onBoundsChanged: (Rect?) -> Unit,
    onDrag: (Rect, Offset) -> Unit,
    onDrop: (Offset) -> Unit,
    onCancel: () -> Unit
): Modifier = composed {
    var layoutBounds by remember { mutableStateOf(Rect.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val boundsChanged by rememberUpdatedState(onBoundsChanged)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)

    DisposableEffect(providerId) {
        val disposeBounds = onBoundsChanged
        onDispose { disposeBounds(null) }
    }

    this
        .onGloballyPositioned { coordinates ->
            layoutBounds = coordinates.boundsInRoot()
            // Keep publishing the current layout slot while dragging. The floating copy
            // moves independently, so reorder hit-testing can compare the dragged
            // card center against the live slot centers and reverse direction immediately.
            boundsChanged(layoutBounds)
        }
        .graphicsLayer {
            alpha = if (dragging) 0f else 1f
        }
        .pointerInput(providerId) {
            var startBounds = Rect.Zero
            var pointer = Offset.Zero

            detectDragGesturesAfterLongPress(
                onDragStart = { touch ->
                    startBounds = layoutBounds
                    dragOffset = Offset.Zero
                    pointer = layoutBounds.topLeft + touch
                    dragging = true
                    drag(startBounds, pointer)
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset += amount
                    pointer += amount
                    drag(startBounds.translate(dragOffset), pointer)
                },
                onDragEnd = {
                    dragging = false
                    dragOffset = Offset.Zero
                    boundsChanged(layoutBounds)
                    drop(pointer)
                },
                onDragCancel = {
                    dragging = false
                    dragOffset = Offset.Zero
                    boundsChanged(layoutBounds)
                    cancel()
                }
            )
        }
}
