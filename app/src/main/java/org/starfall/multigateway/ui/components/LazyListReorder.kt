package org.starfall.multigateway.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

internal data class ReorderRow(val index: Int, val offset: Int, val size: Int)

internal fun reorderTarget(rows: List<ReorderRow>, index: Int, center: Float): Int {
    val current = rows.firstOrNull { it.index == index } ?: return index
    val originalCenter = current.offset + current.size / 2f
    return if (center > originalCenter) {
        rows.filter { it.index > index && center > it.offset + it.size / 2f }
            .maxOfOrNull { it.index } ?: index
    } else {
        rows.filter { it.index < index && center < it.offset + it.size / 2f }
            .minOfOrNull { it.index } ?: index
    }
}

/** Container-owned gesture: moving a keyed row cannot cancel its pointer handler. */
internal class LazyListReorderState(
    val listState: LazyListState,
    private val onMove: (Int, Int) -> Unit,
    private val onDrop: () -> Unit
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var startOffset = 0f
    private var distance by mutableFloatStateOf(0f)
    var pointerY by mutableFloatStateOf(0f)
        private set
    private var pendingIndex: Int? = null

    private val draggedItem get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey }
    val translation get() = draggedItem?.let { startOffset + distance - it.offset } ?: 0f

    fun start(y: Float) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return
        draggedKey = item.key
        startOffset = item.offset.toFloat()
        distance = 0f
        pointerY = y
        pendingIndex = null
    }

    fun drag(deltaY: Float) {
        if (draggedKey == null) return
        distance += deltaY
        pointerY += deltaY
        moveIfNeeded()
    }

    fun moveIfNeeded() {
        val item = draggedItem ?: return
        // Wait for LazyColumn to apply the previous move before issuing another one.
        if (pendingIndex != null && item.index != pendingIndex) return
        pendingIndex = null
        val rows = listState.layoutInfo.visibleItemsInfo.map { ReorderRow(it.index, it.offset, it.size) }
        val target = reorderTarget(rows, item.index, startOffset + distance + item.size / 2f)
        if (target != item.index) {
            pendingIndex = target
            onMove(item.index, target)
        }
    }

    fun finish() {
        val wasDragging = draggedKey != null
        draggedKey = null
        distance = 0f
        pendingIndex = null
        if (wasDragging) onDrop()
    }
}

@Composable
internal fun rememberLazyListReorderState(
    listState: LazyListState,
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit
): LazyListReorderState {
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val state = remember(listState) { LazyListReorderState(listState, { from, to -> move(from, to) }, { drop() }) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 720.dp.toPx() }
    LaunchedEffect(state.draggedKey) {
        if (state.draggedKey == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (state.draggedKey != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val layout = listState.layoutInfo
            val top = layout.viewportStartOffset + layout.beforeContentPadding
            val bottom = layout.viewportEndOffset - layout.afterContentPadding
            val intensity = when {
                state.pointerY < top + edge -> -((top + edge - state.pointerY) / edge).coerceIn(0f, 1f)
                state.pointerY > bottom - edge -> ((state.pointerY - bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (abs(intensity) > 0f) listState.scrollBy(intensity * maxSpeed * seconds)
            state.moveIfNeeded()
        }
    }
    return state
}

internal fun Modifier.reorderGestures(state: LazyListReorderState) = pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(it.y) },
        onDragEnd = state::finish,
        onDragCancel = state::finish,
        onDrag = { change, amount ->
            if (state.draggedKey != null) {
                change.consume()
                state.drag(amount.y)
            }
        }
    )
}

internal fun Modifier.reorderItem(state: LazyListReorderState, key: Any): Modifier =
    zIndex(if (state.draggedKey == key) 1f else 0f).graphicsLayer {
        translationY = if (state.draggedKey == key) state.translation else 0f
        shadowElevation = if (state.draggedKey == key) 8.dp.toPx() else 0f
    }
