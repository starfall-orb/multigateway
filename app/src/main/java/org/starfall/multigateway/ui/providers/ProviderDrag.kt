package org.starfall.multigateway.ui.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drags the actual provider card instead of hiding it and drawing a detached overlay copy.
 *
 * The card stays in layout so surrounding items can take its slot when the backing order changes,
 * while the visual translation compensates for those layout moves to keep the card under the finger.
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
    var dragStartTopLeft by remember { mutableStateOf(Offset.Zero) }

    val boundsChanged by rememberUpdatedState(onBoundsChanged)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)

    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.04f else 1f,
        animationSpec = spring(),
        label = "providerDragScale"
    )

    DisposableEffect(providerId) {
        val disposeBounds = onBoundsChanged
        onDispose { disposeBounds(null) }
    }

    this
        .onGloballyPositioned { coordinates ->
            layoutBounds = coordinates.boundsInRoot()
            // Keep publishing the current layout slot while dragging. The visual card is
            // translated independently, so reorder hit-testing can compare the dragged
            // card center against the live slot centers and reverse direction immediately.
            boundsChanged(layoutBounds)
        }
        .zIndex(if (dragging) 100f else 0f)
        .graphicsLayer {
            if (dragging) {
                val layoutShift = layoutBounds.topLeft - dragStartTopLeft
                translationX = dragOffset.x - layoutShift.x
                translationY = dragOffset.y - layoutShift.y
                shadowElevation = 10.dp.toPx()
            } else {
                translationX = 0f
                translationY = 0f
                shadowElevation = 0f
            }
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(providerId) {
            var startBounds = Rect.Zero
            var pointer = Offset.Zero

            detectDragGesturesAfterLongPress(
                onDragStart = { touch ->
                    startBounds = layoutBounds
                    dragStartTopLeft = layoutBounds.topLeft
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
