package org.starfall.multigateway.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Wrap only at whitespace; keep any remaining text together on the final visible line. */
internal fun wrapAtWhitespace(text: String, width: Int, measureWidth: (String) -> Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 2) return words.firstOrNull().orEmpty()
    var firstLine = words.first()
    for (index in 1 until words.size) {
        val candidate = "$firstLine ${words[index]}"
        if (measureWidth(candidate) > width) {
            return firstLine + "\n" + words.drop(index).joinToString(" ")
        }
        firstLine = candidate
    }
    return firstLine
}

/** Two lines at most, with unbroken words clipped and faded rather than split or ellipsized. */
@Composable
internal fun WordWrappedFadeText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BoxWithConstraints(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        val wrapped = wrapAtWhitespace(text, constraints.maxWidth) {
            measurer.measure(it, style = style, softWrap = false, maxLines = 1).size.width
        }
        Text(
            text = wrapped,
            style = style,
            color = color,
            softWrap = false,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val result = layout ?: return@drawWithContent
                    val fadeWidth = minOf(20.dp.toPx(), size.width / 2f)
                    if (fadeWidth <= 0f) return@drawWithContent
                    val left = if (rtl) 0f else size.width - fadeWidth
                    val mask = Brush.horizontalGradient(
                        colors = if (rtl) listOf(Color.Transparent, Color.Black)
                            else listOf(Color.Black, Color.Transparent),
                        startX = left,
                        endX = left + fadeWidth
                    )
                    for (line in 0 until result.lineCount) {
                        if (result.getLineRight(line) <= size.width && result.getLineLeft(line) >= 0f) continue
                        val top = result.getLineTop(line).coerceIn(0f, size.height)
                        val bottom = result.getLineBottom(line).coerceIn(top, size.height)
                        drawRect(mask, Offset(left, top), Size(fadeWidth, bottom - top), blendMode = BlendMode.DstIn)
                    }
                }
        )
    }
}
