package org.starfall.multigateway

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.chat.buildMarkdownAnnotatedString

class MarkdownInlineParserTest {
    @Test fun linksRetainCorrectAnnotationOffsetsAfterFormattingIsRemoved() {
        val parsed = buildMarkdownAnnotatedString("**See** [docs](https://example.com) and `x`.", Color.Blue, Color.Gray, Color.Black)
        assertEquals("See docs and  x .", parsed.text)
        val link = parsed.getStringAnnotations("URL", 0, parsed.length).single()
        assertEquals("https://example.com", link.item)
        assertEquals("docs", parsed.text.substring(link.start, link.end))
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun mixedFormattingAndUnfinishedStreamingTokensRemainReadable() {
        val parsed = buildMarkdownAnnotatedString("***both*** _italic_ ~~gone~~ [unfinished", Color.Blue, Color.Gray, Color.Black)
        assertEquals("both italic gone [unfinished", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(parsed.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }
}
