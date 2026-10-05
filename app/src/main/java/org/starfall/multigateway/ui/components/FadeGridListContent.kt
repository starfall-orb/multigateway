package org.starfall.multigateway.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val FadeOutDurationMillis = 120
private const val FadeInDurationMillis = 140

/**
 * Switches between list and grid as two independent static layouts.
 *
 * The old layout fully fades out before the new layout starts fading in. No position, size,
 * placement, or slide animation is used for the view-mode transition.
 */
@Composable
fun FadeGridListContent(
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (isGrid: Boolean) -> Unit
) {
    AnimatedContent(
        targetState = isGrid,
        modifier = modifier,
        transitionSpec = {
            fadeIn(
                animationSpec = tween(
                    durationMillis = FadeInDurationMillis,
                    delayMillis = FadeOutDurationMillis
                )
            ) togetherWith fadeOut(
                animationSpec = tween(durationMillis = FadeOutDurationMillis)
            )
        },
        label = "gridListFade"
    ) { grid ->
        content(grid)
    }
}
