package org.starfall.multigateway.ui.components

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.starfall.multigateway.ui.theme.modalScrimColor

/**
 * Keeps unused downward list scroll/fling velocity from being handed to a modal sheet. Without
 * this boundary, a fast gesture toward the first item can continue into the sheet and dismiss it.
 */
internal fun bottomSheetListScrollBoundary(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = Offset(0f, available.y.coerceAtLeast(0f))

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtLeast(0f))
}

/**
 * Shared modal bottom sheet with exactly two inset rules:
 *
 * 1. The sheet surface is edge-to-edge at the bottom, including behind the navigation bar.
 * 2. Interactive sheet content keeps the navigation-bar safe area, while the expanded sheet
 *    surface never rises above the bottom edge of the status bar / display cutout.
 *
 * Do not add navigationBarsPadding/safeDrawing padding around AppBottomSheet itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit
) {
    val hostView = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val statusTopPx = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
    val statusTop = with(density) { statusTopPx.toDp() }
    var dragHandleHeight by remember(density) { mutableStateOf(0.dp) }

    val windowHeightPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
    } else {
        hostView.rootView.height
    }
    val windowHeight = if (windowHeightPx > 0) {
        with(density) { windowHeightPx.toDp() }
    } else {
        configuration.screenHeightDp.dp
    }
    val maxSheetHeight = (windowHeight - statusTop).coerceAtLeast(1.dp)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // Keep the surface's constraints at the full dialog height: Material3 uses them
        // to calculate its bottom anchor. A heightIn here also raises that anchor.
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        scrimColor = modalScrimColor(BottomSheetDefaults.ScrimColor),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.onSizeChanged {
                    dragHandleHeight = with(density) { it.height.toDp() }
                }
            )
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        ConfigureBottomSheetDialogWindow()
        // Material3's dialog pads its outer layout for the IME. Match that reduced
        // anchor space while keeping the expanded surface below the status bar.
        val keyboardHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The handle and this content together fit below the status bar.
                .heightIn(max = (maxSheetHeight - keyboardHeight - dragHandleHeight).coerceAtLeast(0.dp))
                // Only content avoids system bars; the surface still covers the navbar.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                ),
            content = content
        )
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

        // This is the only window-layout override we need.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.navigationBarColor = Color.Transparent.toArgb()
        window.statusBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        onDispose {
            window.navigationBarColor = oldNavigationBarColor
            window.statusBarColor = oldStatusBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                oldNavigationContrast?.let { window.isNavigationBarContrastEnforced = it }
                oldStatusContrast?.let { window.isStatusBarContrastEnforced = it }
            }
        }
    }
}
