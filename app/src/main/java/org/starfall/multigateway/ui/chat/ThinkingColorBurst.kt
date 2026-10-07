package org.starfall.multigateway.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** A short, interruptible bloom; idle controls never keep an animation running. */
@Stable
internal class ThinkingColorBurst(private val scope: CoroutineScope) {
    val progress = Animatable(1f)
    var origin by mutableStateOf(Offset.Zero)
        private set
    var color by mutableStateOf(Color.Transparent)
        private set
    private var job: Job? = null

    fun start(origin: Offset, color: Color) {
        job?.cancel()
        this.origin = origin
        this.color = color
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
}

@Composable
internal fun rememberThinkingColorBurst(): ThinkingColorBurst {
    val scope = rememberCoroutineScope()
    return remember(scope) { ThinkingColorBurst(scope) }
}

/** Reveal the new gradient outward from the thumb before leaving its settled fill. */
internal fun DrawScope.revealThinkingColor(burst: ThinkingColorBurst, draw: DrawScope.() -> Unit) {
    val progress = burst.progress.value
    if (progress >= 1f) {
        draw()
        return
    }
    val radius = 14.dp.toPx() + hypot(size.width, size.height) * progress
    clipPath(Path().apply { addOval(Rect(burst.origin, radius)) }) { draw() }
}

internal fun DrawScope.drawThinkingColorBurst(burst: ThinkingColorBurst) {
    val progress = burst.progress.value
    if (progress >= 1f) return
    val radius = 14.dp.toPx() + hypot(size.width, size.height) * progress
    val opacity = (1f - progress) * (1f - progress)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.42f * opacity),
                burst.color.copy(alpha = 0.55f * opacity), Color.Transparent),
            center = burst.origin,
            radius = radius
        ),
        radius = radius,
        center = burst.origin
    )
}
