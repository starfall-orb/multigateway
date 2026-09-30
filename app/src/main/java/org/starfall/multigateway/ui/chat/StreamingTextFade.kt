package org.starfall.multigateway.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult

internal val LocalStreamingTextFade = compositionLocalOf { false }
internal const val STREAM_FADE_DURATION_MS = 240L

internal data class StreamingFadeRange(val start: Int, val end: Int, val startedAt: Long)

/** Keep each chunk's original start time when a newer chunk arrives. */
internal class StreamingFadeTracker {
    private var previousText = ""
    private var ranges = emptyList<StreamingFadeRange>()

    fun update(text: String, now: Long): List<StreamingFadeRange> {
        val commonLength = previousText.commonPrefixWith(text).length
        ranges = ranges.mapNotNull { range ->
            val end = minOf(range.end, commonLength)
            if (range.start < end && now - range.startedAt < STREAM_FADE_DURATION_MS) {
                range.copy(end = end)
            } else null
        }
        if (commonLength < text.length) {
            ranges = ranges + StreamingFadeRange(commonLength, text.length, now)
        }
        previousText = text
        return ranges
    }
}

internal data class StreamingTextPresentation(
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit
)

@Composable
internal fun rememberStreamingTextPresentation(text: String): StreamingTextPresentation {
    val enabled = LocalStreamingTextFade.current
    if (!enabled) return StreamingTextPresentation(Modifier, {})

    val tracker = remember { StreamingFadeTracker() }
    val ranges = remember(text) { tracker.update(text, SystemClock.uptimeMillis()) }
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val frameTime = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(ranges) {
        val finishAt = ranges.maxOfOrNull { it.startedAt + STREAM_FADE_DURATION_MS } ?: return@LaunchedEffect
        do {
            withFrameNanos { frameTime.longValue = SystemClock.uptimeMillis() }
        } while (frameTime.longValue < finishAt)
    }

    val modifier = Modifier.drawWithCache {
        val layout = layoutResult.value
        val paths = if (layout?.layoutInput?.text?.text == text) {
            ranges.map { range -> range to layout.getPathForRange(range.start, range.end) }
        } else emptyList()
        val paint = Paint()
        onDrawWithContent {
            val now = frameTime.longValue
            val active = paths.filter { (range, _) -> now - range.startedAt < STREAM_FADE_DURATION_MS }
            if (active.isEmpty()) {
                drawContent()
            } else {
                val fadingPath = Path().apply { active.forEach { (_, path) -> addPath(path) } }
                clipPath(fadingPath, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                active.forEach { (range, path) ->
                    val progress = ((now - range.startedAt).toFloat() / STREAM_FADE_DURATION_MS).coerceIn(0f, 1f)
                    paint.alpha = FastOutSlowInEasing.transform(progress)
                    clipPath(path) {
                        drawContext.canvas.saveLayer(path.getBounds(), paint)
                        this@onDrawWithContent.drawContent()
                        drawContext.canvas.restore()
                    }
                }
            }
        }
    }
    return StreamingTextPresentation(modifier) { layoutResult.value = it }
}
