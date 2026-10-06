package org.starfall.multigateway.ui.chat

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import org.starfall.multigateway.R

/** Follow measured new lines; tokens that stay on the same line do not restart scrolling. */
@Composable
internal fun rememberLiveThinkingScrollState(active: Boolean): ScrollState {
    val state = rememberScrollState()
    LaunchedEffect(active, state) {
        if (active) snapshotFlow { state.maxValue }.collectLatest { target ->
            // The scroll range is unknown until the first measurement.
            if (target in 1 until Int.MAX_VALUE) {
                state.animateScrollTo(target, animationSpec = tween(240, easing = LinearOutSlowInEasing))
            }
        }
    }
    return state
}

@Composable
internal fun LiveThinkingBlock(reasoning: String, active: Boolean) {
    val scrollState = rememberLiveThinkingScrollState(active)
    val density = LocalDensity.current
    val previewHeight = with(density) { 48.sp.toDp() }
    val fadeHeight = with(density) { 16.sp.toPx() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (active) R.string.thinking_streaming else R.string.processed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            if (active) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        val fadingEdges = if (!active) Modifier else Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height > 0f && scrollState.maxValue in 1 until Int.MAX_VALUE) {
                    val edge = fadeHeight.coerceAtMost(size.height / 3f).coerceAtLeast(1f)
                    val topAlpha = 1f - (scrollState.value / edge).coerceIn(0f, 1f)
                    val bottomAlpha = 1f - ((scrollState.maxValue - scrollState.value) / edge).coerceIn(0f, 1f)
                    val mask = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = topAlpha),
                        (edge / size.height) to Color.Black,
                        (1f - edge / size.height) to Color.Black,
                        1f to Color.Black.copy(alpha = bottomAlpha)
                    )
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
        Box(
            Modifier.fillMaxWidth().heightIn(max = previewHeight)
                .testTag("live-thinking-preview")
                .then(fadingEdges).verticalScroll(scrollState)
        ) {
            CompositionLocalProvider(LocalStreamingTextFade provides active) {
                val presentation = rememberStreamingTextPresentation(reasoning)
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = presentation.modifier,
                    onTextLayout = presentation.onTextLayout
                )
            }
        }
    }
}
