package org.starfall.multigateway.ui.components

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.ui.theme.modalScrimColor
import kotlin.math.roundToInt

/**
 * Keep unused list movement at both ends inside the list. A fling must not transfer
 * to the sheet's drag/settle animation after the list has reached an edge.
 * Fling deltas remain unconsumed so the list can detect its real edge and stop;
 * only the leftover velocity is absorbed afterwards.
 */
internal fun bottomSheetListScrollBoundary(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

/** Surface includes the navbar; content avoids it. Dragging keeps any height in bounds. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBoundary = remember { bottomSheetListScrollBoundary() }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val entrance = remember { Animatable(0f) }
    var dismissing by remember { mutableStateOf(false) }
    var requestedHeight by remember { mutableStateOf<Float?>(null) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    fun dismiss() {
        if (!dismissing) {
            dismissing = true
            scope.launch {
                entrance.animateTo(0f, tween(150))
                currentDismiss()
            }
        }
    }
    val resizeLabel = stringResource(R.string.sheet_resize)
    val dismissLabel = stringResource(R.string.sheet_dismiss)
    val scrimColor = modalScrimColor(BottomSheetDefaults.ScrimColor)

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ConfigureBottomSheetDialogWindow()
        BoxWithConstraints(Modifier.fillMaxSize().testTag("bottom-sheet-window")) {
            val fullHeight = constraints.maxHeight
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = entrance.value }
                .background(scrimColor).clickable(onClickLabel = dismissLabel, onClick = ::dismiss))
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
                    // Consume the keyboard here so content does not pad for it a second time.
                    .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
            ) {
                val bounds = bottomSheetHeightBounds(fullHeight, constraints.maxHeight)
                val drag = rememberDraggableState { delta ->
                    requestedHeight = bounds.resizedHeight(requestedHeight ?: measuredHeight.toFloat(), delta)
                }
                    Surface(
                    modifier = modifier.align(Alignment.BottomCenter).widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                        // Keep the sheet itself above the system navigation bar. The dialog
                        // is edge-to-edge, so padding only the content still lets the surface
                        // and its last rows extend into the navbar on gesture/navigation-bar
                        // configurations.
                        .navigationBarsPadding()
                        .fillMaxWidth().testTag("app-bottom-sheet")
                        .onSizeChanged { measuredHeight = it.height }
                        .graphicsLayer { translationY = size.height * (1f - entrance.value) }
                        .semantics { paneTitle = resizeLabel }
                        .draggable(drag, Orientation.Vertical, enabled = !dismissing),
                    shape = shape, color = containerColor,
                    tonalElevation = BottomSheetDefaults.Elevation
                ) {
                    // Measure once with a finite ceiling. Unforced content reports its natural
                    // height; resizable list/scroll viewports shrink to the user's chosen height.
                    Layout(content = {
                        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                            Column(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier.fillMaxWidth().height(48.dp).testTag("bottom-sheet-drag-handle")
                                        .semantics {
                                            contentDescription = resizeLabel
                                            stateDescription = "${(measuredHeight * 100f / fullHeight.coerceAtLeast(1)).roundToInt()}%"
                                            val range = (bounds.maximum - bounds.minimum).coerceAtLeast(1).toFloat()
                                            progressBarRangeInfo = ProgressBarRangeInfo(
                                                ((measuredHeight - bounds.minimum) / range).coerceIn(0f, 1f), 0f..1f
                                            )
                                            setProgress { fraction ->
                                                requestedHeight = bounds.minimum + range * fraction.coerceIn(0f, 1f)
                                                true
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) { BottomSheetDefaults.DragHandle() }
                                Column(
                                    Modifier.fillMaxWidth().nestedScroll(scrollBoundary)
                                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                                    content = content
                                )
                            }
                        }
                    }) { measurables, constraints ->
                        val ceiling = bounds.heightFor(bounds.maximum, requestedHeight)
                        val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = ceiling))
                        val height = bounds.heightFor(placeable.height, requestedHeight)
                        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureBottomSheetDialogWindow() {
    val view = LocalView.current
    var current: View? = view
    var dialogWindow: android.view.Window? = null

    while (current != null && dialogWindow == null) {
        if (current is DialogWindowProvider) {
            dialogWindow = current.window
        } else {
            current = current.parent as? View
        }
    }

    val window = dialogWindow ?: return

    DisposableEffect(window) {
        val oldDimAmount = window.attributes.dimAmount
        val oldSoftInputMode = window.attributes.softInputMode
        val oldAnimations = window.attributes.windowAnimations
        val oldNavigationBarColor = window.navigationBarColor
        val oldStatusBarColor = window.statusBarColor
        val oldNavigationContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                null
            }
        val oldStatusContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced
            } else {
                null
            }

        // The app owns the scrim and keyboard padding in this edge-to-edge dialog.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        window.navigationBarColor = Color.Transparent.toArgb()
        window.statusBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        onDispose {
            window.setDimAmount(oldDimAmount)
            window.setSoftInputMode(oldSoftInputMode)
            window.setWindowAnimations(oldAnimations)
            window.navigationBarColor = oldNavigationBarColor
            window.statusBarColor = oldStatusBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                oldNavigationContrast?.let { window.isNavigationBarContrastEnforced = it }
                oldStatusContrast?.let { window.isStatusBarContrastEnforced = it }
            }
        }
    }
}
