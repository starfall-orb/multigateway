package org.starfall.multigateway.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Bounds the sheet surface itself, so full-height content cannot cover the status bar. */
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
    } else view.rootView.height
    val windowHeight = if (windowPixels > 0) with(LocalDensity.current) { windowPixels.toDp() }
        else configuration.screenHeightDp.dp
    val maxHeight = (windowHeight - topInset).coerceAtLeast(1.dp)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.heightIn(max = maxHeight),
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        content = content
    )
}
