package org.starfall.multigateway.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
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

/** Let downward motion at the list start reach Material; contain overscroll at the list end. */
internal fun bottomSheetListScrollBoundary(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f) Offset(0f, available.y) else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

/** Material owns the dialog, scrim, accessibility, insets and show/hide lifecycle. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    dragHandleHeight: Dp = 48.dp,
    dragHandleWidth: Dp = 32.dp,
    /** When set, the sheet is this fraction of the available height regardless of content; null wraps content. */
    fixedHeightFraction: Float? = null,
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
                    // Android stretch overscroll can consume edge deltas before the
                    // sheet receives them and retain a stretch between gestures.
                    // Give the list and sheet a single, continuous scroll handoff.
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        Column(Modifier.fillMaxWidth().nestedScroll(scrollBoundary), content = content)
                    }
                }
            }) { measurables, constraints ->
                val ceiling = bounds.heightFor(bounds.maximum, requestedHeight)
                // Fixed mode is content-independent: provider/model expansion and folder-tab
                // changes cannot resize the sheet. requestedHeight changes only through the resize handle.
                val fixedHeight = fixedHeightFraction?.let { fraction ->
                    val initialHeight = (bounds.maximum * fraction.coerceIn(0f, 1f)).roundToInt()
                    bounds.heightFor(initialHeight, requestedHeight)
                }
                val placeable = measurables.single().measure(
                    constraints.copy(minHeight = fixedHeight ?: 0, maxHeight = fixedHeight ?: ceiling)
                )
                layout(placeable.width, fixedHeight ?: bounds.heightFor(placeable.height, requestedHeight)) { placeable.placeRelative(0, 0) }
            }
        }
    }
}
