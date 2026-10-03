package org.starfall.multigateway.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Shared edge-to-edge modal sheet.
 *
 * The sheet surface may extend through the navigation-bar area at the bottom, but a fully
 * expanded sheet is capped below the status bar / display cutout at the top. Material3's
 * default ModalBottomSheet insets add safeDrawing.bottom, which visually lifts sheet content
 * above the navigation bar; override them here so every sheet follows the app's edge-to-edge
 * layout consistently.
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
    val topInset = WindowInsets.statusBars.union(WindowInsets.displayCutout)
        .asPaddingValues().calculateTopPadding()
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
        modifier = modifier.heightIn(max = maxHeight),
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        content = content
    )
}
