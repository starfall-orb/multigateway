package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/** A drag-and-drop tile as laid out in the coordinate space of the drag surface. */
internal data class DragTile(val key: String, val rect: Rect)

internal fun hitTile(tiles: List<DragTile>, point: Offset): DragTile? =
    tiles.firstOrNull { it.rect.contains(point) }

/** Fraction of a folder tile, measured from each edge, that still counts as "reorder next to it". */
private const val FolderEdgeBand = 0.22f

/**
 * The middle of a folder tile means "put the provider into this folder". The outer band stays a
 * normal reorder target so a provider can still be placed before or after a folder.
 */
internal fun isFolderDropZone(tile: Rect, point: Offset): Boolean {
    val insetX = tile.width * FolderEdgeBand
    val insetY = tile.height * FolderEdgeBand
    return Rect(tile.left + insetX, tile.top + insetY, tile.right - insetX, tile.bottom - insetY).contains(point)
}

/**
 * Signed scroll amount (px) for a pointer held near the top or bottom of a scrolling surface.
 * Negative scrolls towards the start. Speed grows linearly the closer the pointer gets to the edge.
 */
internal fun autoScrollDelta(pointerY: Float, viewportHeight: Float, zone: Float, maxStep: Float): Float {
    if (viewportHeight <= 0f || zone <= 0f) return 0f
    return when {
        pointerY < zone -> -maxStep * ((zone - pointerY) / zone).coerceIn(0f, 1f)
        pointerY > viewportHeight - zone -> maxStep * ((pointerY - (viewportHeight - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

internal fun LazyGridState.dragTiles(): List<DragTile> = layoutInfo.visibleItemsInfo.mapNotNull { item ->
    val key = item.key as? String ?: return@mapNotNull null
    DragTile(
        key,
        Rect(
            item.offset.x.toFloat(), item.offset.y.toFloat(),
            (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()
        )
    )
}

internal val LazyGridState.viewportHeightPx: Float get() = layoutInfo.viewportSize.height.toFloat()

internal fun IntSize.isEmpty() = width <= 0 || height <= 0

/**
 * One long-press drag gesture for the whole screen.
 *
 * The gesture lives on a stable ancestor and runs in the Initial pass. That matters for two
 * reasons: the dragged tile may leave composition (a folder member dragged out of the open
 * folder), and once the long press fired every later event is consumed before the tiles see
 * them, so letting go over a folder never triggers that folder's click.
 *
 * [onPickUp] is asked at touch-down time whether a draggable tile sits under the finger; the
 * gesture only arms when it answers true, so scrolling and taps keep working everywhere else.
 */
@Composable
internal fun Modifier.providerDragGesture(
    enabled: Boolean,
    canPickUp: (Offset) -> Boolean,
    onPickUp: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit
): Modifier {
    if (!enabled) return this
    val pickable by rememberUpdatedState(canPickUp)
    val pickedUp by rememberUpdatedState(onPickUp)
    val moved by rememberUpdatedState(onMove)
    val dropped by rememberUpdatedState(onDrop)
    val cancelled by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!pickable(down.position)) return@awaitEachGesture
            var abandoned = false
            val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        abandoned = true
                        break
                    }
                }
            } == null
            if (abandoned || !timedOut) return@awaitEachGesture

            pickedUp(down.position)
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@awaitEachGesture
                    val wasConsumed = change.isConsumed
                    change.consume()
                    if (!change.pressed) {
                        if (event.type == PointerEventType.Release && !wasConsumed) {
                            moved(change.position)
                            dropped()
                        } else cancelled()
                        completed = true
                        return@awaitEachGesture
                    }
                    moved(change.position)
                }
            } finally {
                // Includes system cancellation and disposal of the gesture modifier.
                if (!completed) cancelled()
            }
        }
    }
}
