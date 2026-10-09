package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.ui.chat.MathBlock
import org.starfall.multigateway.ui.chat.MathPiece
import org.starfall.multigateway.ui.chat.MAX_INLINE_FORMULAS
import org.starfall.multigateway.ui.chat.MathSplitter
import org.starfall.multigateway.ui.chat.groupMathBlocks
import org.starfall.multigateway.ui.chat.mathPlaceholder
import org.starfall.multigateway.ui.chat.placeholderMarkdown

class MathSplitterTest {
    private fun split(text: String, mode: String = "AUTO") = MathSplitter.split(text, mode)
    private fun text(value: String) = MathPiece.Text(value)
    private fun inline(formula: String, source: String = "\$$formula\$") = MathPiece.Math(formula, false, source)
    private fun display(formula: String, source: String) = MathPiece.Math(formula, true, source)

    @Test fun plainTextIsOneTextPiece() {
        assertEquals(listOf(text("Không có công thức nào ở đây.")), split("Không có công thức nào ở đây."))
        assertEquals(emptyList<MathPiece>(), split(""))
    }

    @Test fun offModeNeverSplits() {
        assertEquals(listOf(text("a \$x^2\$ b")), split("a \$x^2\$ b", "OFF"))
        assertEquals(listOf(text("a \$x^2\$ b")), split("a \$x^2\$ b", "off"))
    }

    @Test fun inlineFormulaInsideVietnameseProse() {
        assertEquals(
            listOf(text("Giá trị của "), inline("x_1"), text(" là "), inline("\\frac{1}{2}"), text(".")),
            split("Giá trị của \$x_1\$ là \$\\frac{1}{2}\$.")
        )
    }

    @Test fun onAndAutoSplitTheSame() {
        val text = "The result is \$x^2\$ for every x."
        assertEquals(split(text, "AUTO"), split(text, "ON"))
        assertEquals(3, split(text, "ON").size)
    }

    @Test fun displayDelimitersAreDisplayMath() {
        assertEquals(listOf(display("E=mc^2", "\$\$\nE=mc^2\n\$\$")), split("\$\$\nE=mc^2\n\$\$"))
        assertEquals(listOf(display("x^2", "\\[x^2\\]")), split("\\[x^2\\]"))
        assertEquals(
            listOf(text("a "), display("y", "\\[ y \\]"), text(" b")),
            split("a \\[ y \\] b")
        )
    }

    @Test fun parenthesisDelimitersAreInline() {
        assertEquals(
            listOf(text("Let "), MathPiece.Math("n", false, "\\(n\\)"), text(" be even")),
            split("Let \\(n\\) be even")
        )
    }

    @Test fun pricesAreNotMath() {
        assertEquals(listOf(text("giá \$10 và \$20")), split("giá \$10 và \$20"))
        assertEquals(listOf(text("from \$10.50 - \$19.99 each")), split("from \$10.50 - \$19.99 each"))
        assertEquals(listOf(text("giá 10\$")), split("giá 10\$"))
    }

    @Test fun priceBeforeARealFormulaStillFindsTheFormula() {
        assertEquals(
            listOf(text("costs \$5 and "), inline("x^2")),
            split("costs \$5 and \$x^2\$")
        )
    }

    @Test fun escapedDollarsAreText() {
        assertEquals(listOf(text("pay \\\$5 and \\\$x^2\\\$")), split("pay \\\$5 and \\\$x^2\\\$"))
        assertEquals(listOf(text("\\\\ then ")) + inline("x^2"), split("\\\\ then \$x^2\$"))
    }

    @Test fun codeSpansAreNeverMath() {
        assertEquals(listOf(text("run `echo \$x^2\$` now")), split("run `echo \$x^2\$` now"))
        assertEquals(
            listOf(text("`a` and "), inline("x^2")),
            split("`a` and \$x^2\$")
        )
        assertEquals(listOf(text("``a ` \$x^2\$ b``")), split("``a ` \$x^2\$ b``"))
    }

    @Test fun anUnclosedDelimiterStaysText() {
        // A reply that is still streaming must not flash half a formula.
        listOf("\$\$\nE=mc^2", "\\[x^2", "\\(x", "\$x^2", "a \$\$ b", "\$\$\$").forEach { open ->
            assertEquals("not math: $open", listOf(text(open)), split(open))
        }
    }

    @Test fun inlineDollarFormulaNeverSpansALine() {
        assertEquals(listOf(text("\$a^2\nb^2\$")), split("\$a^2\nb^2\$"))
    }

    @Test fun displayDollarFormulaMaySpanLines() {
        val source = "\$\$\n\\begin{aligned}\na &= b \\\\\n  &= c\n\\end{aligned}\n\$\$"
        val pieces = split(source)
        assertEquals(1, pieces.size)
        val math = pieces.single() as MathPiece.Math
        assertTrue(math.display)
        assertEquals(source, math.source)
        assertTrue(math.formula.startsWith("\\begin{aligned}"))
        assertTrue(math.formula.endsWith("\\end{aligned}"))
    }

    @Test fun sourceKeepsTheDelimitersForFallback() {
        val math = split("see \$x^2\$ now")[1] as MathPiece.Math
        assertEquals("\$x^2\$", math.source)
        assertEquals("x^2", math.formula)
        val spaced = split("see \$ x^2 \$ now")[1] as MathPiece.Math
        assertEquals("\$ x^2 \$", spaced.source)
        assertEquals("x^2", spaced.formula)
    }

    @Test fun piecesRebuildTheOriginalParagraph() {
        val original = "A \$a_1\$, then \$\$\nb\n\$\$ and \\(c\\) \\[d\\]. Cost \$5 \\\$ `\$e^2\$`"
        val rebuilt = split(original).joinToString("") {
            when (it) {
                is MathPiece.Text -> it.text
                is MathPiece.Math -> it.source
            }
        }
        assertEquals(original, rebuilt)
    }

    @Test fun displayFormulasSplitTheFlow() {
        val blocks = groupMathBlocks(split("Before \$a^2\$ text\n\$\$\nb^2\n\$\$\nafter \$c^2\$."))
        assertEquals(3, blocks.size)
        val first = blocks[0] as MathBlock.Flow
        assertEquals(listOf(text("Before "), inline("a^2"), text(" text")), first.pieces)
        assertEquals("b^2", (blocks[1] as MathBlock.Display).math.formula)
        val last = blocks[2] as MathBlock.Flow
        assertEquals(listOf(text("after "), inline("c^2"), text(".")), last.pieces)
    }

    @Test fun whitespaceAroundADisplayFormulaIsDropped() {
        val blocks = groupMathBlocks(split("\n\$\$x^2\$\$\n"))
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MathBlock.Display)
    }

    @Test fun aLoneInlineFormulaStaysInTheFlow() {
        val blocks = groupMathBlocks(split("\$x^2\$"))
        assertEquals(listOf<MathBlock>(MathBlock.Flow(listOf(inline("x^2")))), blocks)
    }

    @Test fun placeholdersReplaceFormulasSoMarkdownCannotMangleThem() {
        val pieces = split("**Kết quả: \$a_1 + b_2\$** và \$x^2\$.")
        assertEquals(
            "**Kết quả: ${mathPlaceholder(0)}** và ${mathPlaceholder(1)}.",
            placeholderMarkdown(pieces)
        )
    }

    @Test fun literalPrivateUseCharactersAreNeutralised() {
        val pieces = split("icon ${mathPlaceholder(0)} and \$x^2\$")
        assertEquals("icon \uFFFD and ${mathPlaceholder(0)}", placeholderMarkdown(pieces))
    }

    @Test fun formulasPastTheLimitStayAsSource() {
        val text = (0..MAX_INLINE_FORMULAS).joinToString(" ") { "\$x^$it\$" }
        val markdown = placeholderMarkdown(split(text))
        assertEquals(MAX_INLINE_FORMULAS, markdown.count { it.code in 0xE000..0xF8FF })
        assertTrue(markdown.endsWith("\$x^$MAX_INLINE_FORMULAS\$"))
    }
}
