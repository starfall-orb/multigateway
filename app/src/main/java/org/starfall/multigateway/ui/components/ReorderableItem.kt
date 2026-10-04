package org.starfall.multigateway.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

fun Modifier.longPressReorder(
    index: Int,
    itemCount: Int,
    columns: Int = 1,
    minIndex: Int = 0,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onDropAt: ((Offset) -> Unit)? = null,
    onDraggingChanged: (Boolean) -> Unit = {}
): Modifier = composed {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var currentItemPosition by remember { mutableStateOf(Offset.Zero) }
    var currentItemSize by remember { mutableStateOf(IntSize.Zero) }
    var startDragRootPosition by remember { mutableStateOf(Offset.Zero) }

    // Use rememberUpdatedState so changes in index, count or callbacks don't restart pointerInput
    val currentIndexState = rememberUpdatedState(index)
    val itemCountState = rememberUpdatedState(itemCount)
    val columnsState = rememberUpdatedState(columns)
    val minIndexState = rememberUpdatedState(minIndex)
    val onMoveState = rememberUpdatedState(onMove)
    val onDropState = rememberUpdatedState(onDrop)
    val onDropAtState = rememberUpdatedState(onDropAt)
    val onDraggingChangedState = rememberUpdatedState(onDraggingChanged)

    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = spring(),
        label = "dragScale"
    )

    this
        .onGloballyPositioned { coordinates ->
            currentItemPosition = coordinates.positionInRoot()
            currentItemSize = coordinates.size
        }
        .zIndex(if (isDragging) 99f else 1f)
        .graphicsLayer {
            if (isDragging) {
                // When reordering occurs, Compose updates the layout position of the item.
                // By subtracting the layout shift (currentItemPosition - startDragRootPosition),
                // the item stays EXACTLY under the user's finger with zero jump or drift!
                val layoutShiftX = currentItemPosition.x - startDragRootPosition.x
                val layoutShiftY = currentItemPosition.y - startDragRootPosition.y
                translationX = dragOffset.x - layoutShiftX
                translationY = dragOffset.y - layoutShiftY
            } else {
                translationX = 0f
                translationY = 0f
            }
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .pointerInput(Unit) {
            var activeIndex = currentIndexState.value
            var accumulatedX = 0f
            var accumulatedY = 0f
            val threshold = 72.dp.toPx()

            detectDragGesturesAfterLongPress(
                onDragStart = {
                    isDragging = true
                    onDraggingChangedState.value(true)
                    dragOffset = Offset.Zero
                    startDragRootPosition = currentItemPosition
                    activeIndex = currentIndexState.value
                    accumulatedX = 0f
                    accumulatedY = 0f
                },
                onDragCancel = {
                    isDragging = false
                    onDraggingChangedState.value(false)
                    dragOffset = Offset.Zero
                    onDropState.value()
                },
                onDragEnd = {
                    val dropCenter = startDragRootPosition + dragOffset + Offset(
                        currentItemSize.width / 2f,
                        currentItemSize.height / 2f
                    )
                    isDragging = false
                    onDraggingChangedState.value(false)
                    dragOffset = Offset.Zero
                    onDropAtState.value?.invoke(dropCenter)
                    onDropState.value()
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount
                    accumulatedX += dragAmount.x
                    accumulatedY += dragAmount.y

                    val cols = columnsState.value
                    val count = itemCountState.value
                    val horizontal = abs(accumulatedX) > abs(accumulatedY)
                    val delta = when {
                        horizontal && abs(accumulatedX) >= threshold ->
                            if (accumulatedX > 0) 1 else -1
                        !horizontal && abs(accumulatedY) >= threshold ->
                            if (accumulatedY > 0) cols else -cols
                        else -> 0
                    }

                    if (delta != 0) {
                        val currentIdx = activeIndex
                        val target = (currentIdx + delta).coerceIn(minIndexState.value.coerceAtMost(count - 1), count - 1)
                        if (target != currentIdx) {
                            onMoveState.value(currentIdx, target)
                            activeIndex = target
                            if (horizontal) {
                                accumulatedX = 0f
                            } else {
                                accumulatedY = 0f
                            }
                        }
                    }
                }
            )
        }
}

fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}
