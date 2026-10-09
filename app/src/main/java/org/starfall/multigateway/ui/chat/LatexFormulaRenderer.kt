package org.starfall.multigateway.ui.chat

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.roundToInt

/**
 * Renders one formula with JLatexMath and keeps the source visible while the
 * drawable is being built. The source is also the fallback for unsupported
 * commands and malformed formulas.
 */
@Composable
internal fun LatexFormulaRenderer(
    formula: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    displayMode: Boolean = true,
    modifier: Modifier = Modifier
) {
    val color = if (textStyle.color.isSpecified) textStyle.color else MaterialTheme.colorScheme.onSurface
    val textSize = if (textStyle.fontSize.isSpecified) textStyle.fontSize else 16.sp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textSizePx = with(density) { textSize.toPx().coerceAtLeast(1f) }
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val cacheKey = latexCacheKey(formula, textSizePx, color.toArgb(), isDarkTheme)
    val result by produceState<LatexRenderResult>(
        initialValue = LatexRenderResult.Loading,
        key1 = cacheKey
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                LatexRenderResult.Ready(LatexDrawables.getOrCreate(cacheKey))
            } catch (error: Throwable) {
                android.util.Log.w("LatexRenderer", "Could not render formula: $formula", error)
                LatexRenderResult.Fallback(formula, error)
            }
        }
    }

    when (val renderResult = result) {
        LatexRenderResult.Loading -> LatexFallbackText(formula, textStyle, modifier)
        is LatexRenderResult.Fallback -> LatexFallbackText(renderResult.source, textStyle, modifier)
        is LatexRenderResult.Ready -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = if (displayMode) Alignment.Center else Alignment.CenterStart
            ) {
                Image(
                    painter = rememberLatexDrawablePainter(renderResult.drawable),
                    contentDescription = "Rendered LaTeX formula"
                )
            }
        }
    }
}

@Composable
private fun LatexFallbackText(source: String, style: TextStyle, modifier: Modifier) {
    SelectionContainer {
        androidx.compose.material3.Text(
            text = latexFallbackSource(source),
            modifier = modifier,
            style = style.copy(fontFamily = FontFamily.Serif)
        )
    }
}

private sealed interface LatexRenderResult {
    data object Loading : LatexRenderResult
    data class Ready(val drawable: Drawable) : LatexRenderResult
    data class Fallback(val source: String, val error: Throwable) : LatexRenderResult
}

internal data class LatexCacheKey(
    val formula: String,
    val textSizePx: Int,
    val colorArgb: Int,
    val isDarkTheme: Boolean
)

internal fun latexCacheKey(
    formula: String,
    textSizePx: Float,
    colorArgb: Int,
    isDarkTheme: Boolean
): LatexCacheKey = LatexCacheKey(
    formula = formula,
    textSizePx = textSizePx.roundToInt().coerceAtLeast(1),
    colorArgb = colorArgb,
    isDarkTheme = isDarkTheme
)

/** The raw source is intentionally preserved when a formula cannot be parsed. */
internal fun latexFallbackSource(source: String): String = source

internal object LatexDrawables {
    private const val MAX_ENTRIES = 96
    private val cache = object : LruCache<LatexCacheKey, Drawable>(MAX_ENTRIES) {
        override fun sizeOf(key: LatexCacheKey, value: Drawable): Int = 1
    }

    @Synchronized
    fun peek(key: LatexCacheKey): Drawable? = cache.get(key)

    @Synchronized
    fun getOrCreate(key: LatexCacheKey): Drawable {
        cache.get(key)?.let { return it }
        val drawable = JLatexMathDrawable
            .builder(key.formula)
            .textSize(key.textSizePx.toFloat())
            .color(key.colorArgb)
            .build()
        cache.put(key, drawable)
        return drawable
    }

    /** Null entries are formulas that could not be laid out (they stay as their source text). */
    fun peekAll(keys: List<LatexCacheKey>): List<Drawable?> = keys.map { peek(it) }

    fun buildOrNull(key: LatexCacheKey): Drawable? = try {
        getOrCreate(key)
    } catch (error: Throwable) {
        android.util.Log.w("LatexRenderer", "Could not render formula: ${key.formula}", error)
        null
    }

    fun buildAll(keys: List<LatexCacheKey>): List<Drawable?> = keys.map { buildOrNull(it) }
}

@Composable
private fun rememberLatexDrawablePainter(drawable: Drawable): Painter {
    return androidx.compose.runtime.remember(drawable) { LatexDrawablePainter(drawable) }
}

internal class LatexDrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize = Size(
        drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
        drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
    )

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
    }
}
