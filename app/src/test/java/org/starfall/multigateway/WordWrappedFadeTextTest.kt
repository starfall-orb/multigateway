package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.components.wrapAtWhitespace

class WordWrappedFadeTextTest {
    @Test fun shortNamesStayOnOneLine() {
        assertEquals("My Provider", wrapAtWhitespace("My Provider", 15) { it.length })
    }

    @Test fun longWordsAreNeverSplit() {
        val name = "averylongprovidernamewithoutspaces"
        assertEquals(name, wrapAtWhitespace(name, 8) { it.length })
        assertEquals("$name\nOther", wrapAtWhitespace("$name Other", 8) { it.length })
    }

    @Test fun whitespaceNamesWrapOnlyAtWordBoundariesAndKeepTheRemainderOnLineTwo() {
        assertEquals("My\nProvider With Several Words", wrapAtWhitespace("My Provider With Several Words", 8) { it.length })
        assertEquals("A\nverylongtoken Other", wrapAtWhitespace("A verylongtoken Other", 8) { it.length })
    }

    @Test fun unicodeAndWhitespaceAreHandledWithoutSplittingWords() {
        assertEquals("Nhà\ncungcấpkhôngkhoảngtrắng", wrapAtWhitespace("  Nhà\t cungcấpkhôngkhoảngtrắng  ", 8) { it.length })
        assertEquals("", wrapAtWhitespace("   ", 8) { it.length })
    }
}
