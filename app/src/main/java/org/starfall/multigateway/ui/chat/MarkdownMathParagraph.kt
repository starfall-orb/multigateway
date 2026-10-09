package org.starfall.multigateway.ui.chat

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A paragraph that contains math. Inline formulas are laid out inside the running text, so they wrap and
 * select with it; display formulas (`$$…$$`, `\[…\]`) get their own block between the surrounding text.
 */
@Composable
internal fun RenderMathParagraph(pieces: List<MathPiece>, style: TextStyle, modifier: Modifier = Modifier) {
    val blocks = remember(pieces) { groupMathBlocks(pieces) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MathBlock.Display -> RenderLatexBlock(source = block.math.source, formula = block.math.formula)
                is MathBlock.Flow -> InlineMathFlow(block.pieces, style)
            }
        }
    }
}

@Composable
private fun InlineMathFlow(pieces: List<MathPiece>, style: TextStyle) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val textColor = if (style.color.isSpecified) style.color else colors.onSurface
    val textSize = if (style.fontSize.isSpecified) style.fontSize else 16.sp
    val textSizePx = with(density) { textSize.toPx().coerceAtLeast(1f) }
    val isDarkTheme = isSystemInDarkTheme()

    val maths = remember(pieces) { pieces.filterIsInstance<MathPiece.Math>() }
    val keys = remember(maths, textSizePx, textColor, isDarkTheme) {
        maths.map { latexCacheKey(it.formula, textSizePx, textColor.toArgb(), isDarkTheme) }
    }

    // Formulas already in the cache show on the first frame. The rest are laid out off the main thread and the
    // text shows their source until they are ready, so streaming never waits on JLatexMath.
    var built by remember { mutableStateOf<Map<LatexCacheKey, Drawable?>>(emptyMap()) }
    LaunchedEffect(keys) {
        val missing = keys.filter { it !in built && LatexDrawables.peek(it) == null }.distinct()
        if (missing.isNotEmpty()) {
            val results = withContext(Dispatchers.Default) {
                missing.associateWith { LatexDrawables.buildOrNull(it) }
            }
            built = built + results
        }
    }
    // A null entry is a formula that is not ready yet, or one that could not be laid out; both show as source.
    val drawables = keys.map { built[it] ?: LatexDrawables.peek(it) }

    val primary = colors.primary
    val codeBackground = colors.surfaceContainerHighest
    val fontFamily = style.fontFamily ?: FontFamily.Default
    val markdown = remember(pieces) { placeholderMarkdown(pieces) }
    val styled = remember(markdown, primary, codeBackground, textColor, fontFamily, textSize) {
        buildMarkdownAnnotatedString(markdown, primary, codeBackground, textColor, fontFamily, textSize)
    }
    val text = remember(styled, maths, drawables) { weaveInlineMath(styled, maths, drawables) }

    val inlineContent = remember(drawables, maths, density) {
        buildMap<String, InlineTextContent> {
            drawables.forEachIndexed { index, drawable ->
                if (drawable == null || index >= MAX_INLINE_FORMULAS) return@forEachIndexed
                val width = with(density) { drawable.intrinsicWidth.coerceAtLeast(1).toSp() }
                val height = with(density) { drawable.intrinsicHeight.coerceAtLeast(1).toSp() }
                put(
                    inlineMathId(index),
                    InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
                        Image(
                            painter = remember(drawable) { LatexDrawablePainter(drawable) },
                            contentDescription = maths[index].source,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                )
            }
        }
    }

    // A fixed line height would clip a tall formula such as a fraction, so grow it to fit the tallest one.
    val tallestFormulaPx = drawables.maxOfOrNull { it?.intrinsicHeight ?: 0 } ?: 0
    val flowStyle = remember(style, tallestFormulaPx, density) {
        val neededPx = tallestFormulaPx * 1.1f
        if (style.lineHeight.isSp && neededPx > with(density) { style.lineHeight.toPx() }) {
            style.copy(lineHeight = with(density) { neededPx.toSp() })
        } else {
            style
        }
    }

    SelectionContainer {
        MarkdownClickableText(
            text = text,
            style = flowStyle,
            modifier = Modifier.fillMaxWidth(),
            inlineContent = inlineContent
        )
    }
}

private fun inlineMathId(index: Int): String = "latex-$index"

/**
 * Replaces each [mathPlaceholder] in the styled text with its laid-out formula, or with the formula's source when
 * it has none, keeping every span the Markdown parser produced around it. The placeholder is also the inline
 * content's alternate text, so line breaking treats a formula like a word and never splits it from a comma.
 */
private fun weaveInlineMath(
    styled: AnnotatedString,
    maths: List<MathPiece.Math>,
    drawables: List<Drawable?>
): AnnotatedString = buildAnnotatedString {
    val plain = styled.text
    val known = minOf(maths.size, MAX_INLINE_FORMULAS)
    var copiedUpTo = 0
    for (position in plain.indices) {
        val index = plain[position].code - MATH_PLACEHOLDER_BASE
        if (index !in 0 until known) continue
        append(styled.subSequence(copiedUpTo, position))
        if (drawables[index] != null) {
            appendInlineContent(inlineMathId(index), mathPlaceholder(index).toString())
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append(maths[index].source) }
        }
        copiedUpTo = position + 1
    }
    append(styled.subSequence(copiedUpTo, plain.length))
}
