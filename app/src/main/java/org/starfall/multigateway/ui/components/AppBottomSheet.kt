package org.starfall.multigateway.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R
import org.starfall.multigateway.ui.theme.modalScrimColor
import kotlin.math.roundToInt

/** List-edge gestures resize through the handle, rather than dismissing the sheet. */
internal fun bottomSheetListScrollBoundary(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

/** Material owns the dialog, scrim, accessibility, insets and show/hide lifecycle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dragHandleHeight: Dp = 48.dp,
    dragHandleWidth: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollBoundary = remember { bottomSheetListScrollBoundary() }
    val windowHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    var requestedHeight by remember { mutableStateOf<Float?>(null) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    val resizeLabel = stringResource(R.string.sheet_resize)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.testTag("app-bottom-sheet"),
        shape = shape,
        containerColor = containerColor,
        scrimColor = modalScrimColor(BottomSheetDefaults.ScrimColor),
        dragHandle = null,
        contentWindowInsets = { WindowInsets.safeDrawing }
    ) {
        // FlexibleBottomSheet snaps to segmented sizes. Retain the app's free-form
        // content sizing in this small wrapper, without owning a dialog/window.
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("bottom-sheet-window")) {
            val bounds = bottomSheetHeightBounds(windowHeight, constraints.maxHeight)
            val drag = rememberDraggableState { delta ->
                requestedHeight = bounds.resizedHeight(requestedHeight ?: measuredHeight.toFloat(), delta)
            }
            Layout(content = {
                Column(Modifier.fillMaxWidth().onSizeChanged { measuredHeight = it.height }) {
                    Box(Modifier.fillMaxWidth().height(dragHandleHeight).testTag("bottom-sheet-drag-handle")
                        .draggable(drag, Orientation.Vertical)
                        .semantics {
                            contentDescription = resizeLabel
                            val range = (bounds.maximum - bounds.minimum).coerceAtLeast(1).toFloat()
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                ((measuredHeight - bounds.minimum) / range).coerceIn(0f, 1f), 0f..1f)
                            stateDescription = "${(measuredHeight * 100f / windowHeight.coerceAtLeast(1)).roundToInt()}%"
                            setProgress { fraction ->
                                requestedHeight = bounds.minimum + range * fraction.coerceIn(0f, 1f)
                                true
                            }
                        }, contentAlignment = Alignment.Center
                    ) { BottomSheetDefaults.DragHandle(Modifier.width(dragHandleWidth)) }
                    Column(Modifier.fillMaxWidth().nestedScroll(scrollBoundary), content = content)
                }
            }) { measurables, constraints ->
                val ceiling = bounds.heightFor(bounds.maximum, requestedHeight)
                val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = ceiling))
                layout(placeable.width, bounds.heightFor(placeable.height, requestedHeight)) { placeable.placeRelative(0, 0) }
            }
        }
    }
}
