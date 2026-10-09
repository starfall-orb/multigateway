package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.ui.chat.LatexDetector

class LatexDetectorTest {
    @Test fun pricesAreNotDetectedAsMath() {
        assertFalse(LatexDetector.containsLatex("giá \$10 và \$20"))
        assertFalse(LatexDetector.containsLatex("giá 10\$"))
        assertFalse(LatexDetector.shouldRenderLatex("giá \$10 và \$20", "ON"))
    }

    @Test fun mathLookingSingleDollarExpressionsAreDetectedInEveryEnabledMode() {
        assertTrue(LatexDetector.containsLatex("\$x^2\$"))
        assertTrue(LatexDetector.containsLatex("\$snake_case\$"))
        assertTrue(LatexDetector.shouldRenderLatex("\$x^2\$", "ON"))
        assertFalse(LatexDetector.shouldRenderLatex("\$x^2\$", "OFF"))
    }

    @Test fun blockAndEscapedDelimitersNeedAClosingPair() {
        assertTrue(LatexDetector.containsLatex("\$\$\nE=mc^2\n\$\$"))
        assertTrue(LatexDetector.containsLatex("\\[x^2\\]"))
        assertFalse(LatexDetector.containsLatex("\$\$\nE=mc^2"))
        assertFalse(LatexDetector.containsLatex("\\[x^2"))
    }

    @Test fun proseWithInlineFormulaIsDetectedButNotTreatedAsAStandaloneBlock() {
        val prose = "The result is \$x^2\$ for every x."

        assertTrue(LatexDetector.containsLatex(prose))
        assertNull(LatexDetector.standaloneFormula(prose))
        assertEquals("x^2", LatexDetector.standaloneFormula("\$\$x^2\$\$"))
    }
}
