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

/** The screen draws the dragged card above folder surfaces so it isn't clipped by its folder. */
internal fun Modifier.providerDrag(
    providerId: String,
    onBoundsChanged: (Rect?) -> Unit,
    onDrag: (Rect, Offset) -> Unit,
    onDrop: (Offset) -> Unit,
    onCancel: () -> Unit
): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val boundsChanged by rememberUpdatedState(onBoundsChanged)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(providerId) {
        val disposeBounds = onBoundsChanged
        onDispose { disposeBounds(null) }
    }
    onGloballyPositioned {
        bounds = it.boundsInRoot()
        boundsChanged(bounds)
    }.graphicsLayer {
        alpha = if (dragging) 0f else 1f
    }.pointerInput(providerId) {
        var startBounds = Rect.Zero
        var pointer = Offset.Zero
        var distance = Offset.Zero
        detectDragGesturesAfterLongPress(
            onDragStart = {
                startBounds = bounds
                pointer = bounds.topLeft + it
                distance = Offset.Zero
                dragging = true
                drag(startBounds, pointer)
            },
            onDrag = { change, amount ->
                change.consume()
                pointer += amount
                distance += amount
                drag(startBounds.translate(distance), pointer)
            },
            onDragEnd = {
                dragging = false
                drop(pointer)
            },
            onDragCancel = {
                dragging = false
                cancel()
            }
        )
    }
}
