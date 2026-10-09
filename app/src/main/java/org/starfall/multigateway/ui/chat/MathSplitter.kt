package org.starfall.multigateway.ui.chat

/** A paragraph is cut into Markdown text and math so each part is laid out by the renderer that suits it. */
internal sealed interface MathPiece {
    /** Markdown source that contains no math. */
    data class Text(val text: String) : MathPiece

    /**
     * [formula] is the TeX between the delimiters and [source] the original text including them; the source is
     * what the reader sees when the formula cannot be laid out.
     */
    data class Math(val formula: String, val display: Boolean, val source: String) : MathPiece
}

/** What the paragraph renderer draws: running text with inline formulas, or one standalone formula. */
internal sealed interface MathBlock {
    /** Text and inline [MathPiece.Math] only. */
    data class Flow(val pieces: List<MathPiece>) : MathBlock

    data class Display(val math: MathPiece.Math) : MathBlock
}

/**
 * Finds `$$…$$`, `\[…\]`, `\(…\)` and `$…$` in a paragraph. Anything that is not clearly closed stays text, which
 * matters while a reply is still streaming. Single dollars reuse [LatexDetector]'s price and "looks like math"
 * checks, so "$10 and $20" is never mistaken for a formula.
 */
internal object MathSplitter {
    fun split(text: String, mode: String): List<MathPiece> {
        if (text.isEmpty()) return emptyList()
        if (mode.equals("OFF", ignoreCase = true)) return listOf(MathPiece.Text(text))

        val pieces = ArrayList<MathPiece>()
        var textStart = 0
        var i = 0

        fun accept(math: MathPiece.Math, start: Int, end: Int) {
            if (start > textStart) pieces.add(MathPiece.Text(text.substring(textStart, start)))
            pieces.add(math)
            textStart = end
            i = end
        }

        while (i < text.length) {
            when (text[i]) {
                '\\' -> {
                    val end = matchBackslashDelimited(text, i)
                    if (end != null) accept(end.first, i, end.second)
                    // Otherwise this is an escaped character (\$ or \\) or a lone backslash: never a delimiter.
                    else i += 2
                }
                '`' -> i = skipCodeSpan(text, i)
                '$' -> {
                    val match = matchDollar(text, i)
                    if (match != null) accept(match.first, i, match.second)
                    else i += if (text.startsWith("$$", i)) 2 else 1
                }
                else -> i++
            }
        }
        if (text.length > textStart) pieces.add(MathPiece.Text(text.substring(textStart)))
        return pieces
    }

    /** `\( … \)` is inline and `\[ … \]` display. Returns the math and the index just past its closing delimiter. */
    private fun matchBackslashDelimited(text: String, start: Int): Pair<MathPiece.Math, Int>? {
        val (close, display) = when (text.getOrNull(start + 1)) {
            '(' -> "\\)" to false
            '[' -> "\\]" to true
            else -> return null
        }
        val end = text.indexOf(close, start + 2)
        if (end < 0) return null
        val formula = text.substring(start + 2, end).trim()
        if (formula.isEmpty()) return null
        val after = end + close.length
        return MathPiece.Math(formula, display, text.substring(start, after)) to after
    }

    private fun matchDollar(text: String, start: Int): Pair<MathPiece.Math, Int>? {
        if (text.startsWith("$$", start)) {
            val end = text.indexOf("$$", start + 2)
            if (end < 0) return null
            val formula = text.substring(start + 2, end).trim()
            if (formula.isEmpty()) return null
            val after = end + 2
            return MathPiece.Math(formula, true, text.substring(start, after)) to after
        }
        val close = findInlineClose(text, start + 1)
        if (close < 0) return null
        val formula = text.substring(start + 1, close).trim()
        if (formula.isEmpty()) return null
        if (!LatexDetector.looksLikeMath(formula)) return null
        if (LatexDetector.looksLikeUsdRange(formula, text.substring(close + 1))) return null
        return MathPiece.Math(formula, false, text.substring(start, close + 1)) to (close + 1)
    }

    /** Index of the `$` closing an inline formula that opened just before [from], or -1. */
    private fun findInlineClose(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            when (text[j]) {
                '\\' -> j += 2
                '\n' -> return -1
                '$' -> return if (text.getOrNull(j + 1) == '$') -1 else j
                else -> j++
            }
        }
        return -1
    }

    /** Index after the code span starting at [start]; its content is code, never math. */
    private fun skipCodeSpan(text: String, start: Int): Int {
        var ticks = 0
        while (start + ticks < text.length && text[start + ticks] == '`') ticks++
        var j = start + ticks
        while (j < text.length) {
            if (text[j] != '`') {
                j++
                continue
            }
            var run = 0
            while (j + run < text.length && text[j + run] == '`') run++
            if (run == ticks) return j + run
            j += run
        }
        return start + ticks // never closed: the backticks are literal
    }
}

/** Display formulas become their own blocks; everything between them flows as one text block. */
internal fun groupMathBlocks(pieces: List<MathPiece>): List<MathBlock> {
    val blocks = ArrayList<MathBlock>()
    var flow = ArrayList<MathPiece>()

    fun flush() {
        val trimmed = trimFlow(flow)
        if (trimmed.isNotEmpty()) blocks.add(MathBlock.Flow(trimmed))
        flow = ArrayList()
    }

    for (piece in pieces) {
        if (piece is MathPiece.Math && piece.display) {
            flush()
            blocks.add(MathBlock.Display(piece))
        } else {
            flow.add(piece)
        }
    }
    flush()
    return blocks
}

private fun trimFlow(flow: List<MathPiece>): List<MathPiece> {
    val out = ArrayList(flow)
    while (out.isNotEmpty()) {
        val first = out.first() as? MathPiece.Text ?: break
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) out.removeAt(0) else { out[0] = MathPiece.Text(trimmed); break }
    }
    while (out.isNotEmpty()) {
        val last = out.last() as? MathPiece.Text ?: break
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) out.removeAt(out.lastIndex) else { out[out.lastIndex] = MathPiece.Text(trimmed); break }
    }
    return out
}

/** Inline formulas are swapped for one private-use character each, starting here, while Markdown is parsed. */
internal const val MATH_PLACEHOLDER_BASE = 0xE000

/** Formulas past this many in one text block stay as plain source text. */
internal const val MAX_INLINE_FORMULAS = 512

private val PRIVATE_USE_CHARS = Regex("[\uE000-\uF8FF]")

internal fun mathPlaceholder(index: Int): Char = (MATH_PLACEHOLDER_BASE + index).toChar()

/**
 * The text of a flow with every inline formula replaced by [mathPlaceholder]. Taking the formulas out first
 * stops Markdown from reading the `_` and `*` inside them as emphasis, and lets emphasis wrap a formula
 * ("**result: $x$**"). A formula is a single non-space character to the parser, like a word.
 */
internal fun placeholderMarkdown(pieces: List<MathPiece>): String = buildString {
    var index = 0
    for (piece in pieces) {
        when (piece) {
            // A literal private-use character in the reply must not be mistaken for a placeholder.
            is MathPiece.Text -> append(piece.text.replace(PRIVATE_USE_CHARS, "\uFFFD"))
            is MathPiece.Math -> if (index < MAX_INLINE_FORMULAS) {
                append(mathPlaceholder(index))
                index++
            } else {
                append(piece.source)
            }
        }
    }
}
