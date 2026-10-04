package org.starfall.multigateway.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Window-relative bounds for large content regions, including multi-window layouts. */
@Composable
fun Modifier.windowHeightIn(minFraction: Float = 0f, maxFraction: Float): Modifier {
    val height = LocalConfiguration.current.screenHeightDp.dp
    return heightIn(min = height * minFraction, max = height * maxFraction)
}

@Composable
fun Modifier.windowHeight(fraction: Float): Modifier =
    height(LocalConfiguration.current.screenHeightDp.dp * fraction)

@Composable
fun Modifier.windowWidth(fraction: Float): Modifier =
    width(LocalConfiguration.current.screenWidthDp.dp * fraction)
