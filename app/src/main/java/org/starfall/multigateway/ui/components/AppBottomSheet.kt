package org.starfall.multigateway.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Shared edge-to-edge modal sheet.
 *
 * Material3 positions ModalBottomSheet against the usable content area on some 3-button
 * navigation devices even though its dialog window is edge-to-edge. That makes a full-height
 * sheet stop above the navigation bar and consequently pushes its top behind the status bar.
 *
 * We compensate the sheet surface by the navigation-bar inset so its visual bottom reaches the
 * real window bottom. The maximum surface height remains fullWindow - statusBar, therefore the
 * expanded top edge lands immediately below the status bar / display cutout.
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
    val topInset = WindowInsets.statusBars
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
        .calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val view = LocalView.current
    val windowPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
    } else {
        view.rootView.height
    }
    val windowHeight = if (windowPixels > 0) {
        with(LocalDensity.current) { windowPixels.toDp() }
    } else {
        configuration.screenHeightDp.dp
    }
    val maxHeight = (windowHeight - topInset).coerceAtLeast(1.dp)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .heightIn(max = maxHeight)
            .offset(y = bottomInset),
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        BottomSheetDialogSystemBars()
        content()
    }
}

@Composable
private fun BottomSheetDialogSystemBars() {
    val view = LocalView.current
    var currentView: android.view.View? = view
    var dialogWindow: android.view.Window? = null
    while (currentView != null && dialogWindow == null) {
        if (currentView is DialogWindowProvider) {
            dialogWindow = currentView.window
        } else {
            currentView = currentView.parent as? android.view.View
        }
    }
    val window = dialogWindow ?: return

    DisposableEffect(window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val oldNavigationBarColor = window.navigationBarColor
        val oldStatusBarColor = window.statusBarColor
        val oldNavigationContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else null
        val oldStatusContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else null

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
