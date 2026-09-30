package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.chat.MarkdownBlock
import org.starfall.multigateway.ui.chat.parseMarkdown

class MarkdownParserTest {
    @Test(timeout = 1000)
    fun incompleteAndLiteralHeadingsRemainParagraphs() {
        listOf("#", "##", "######", "# ", "#hashtag", "####### title").forEach { text ->
            assertEquals(listOf(MarkdownBlock.Paragraph(text.trim())), parseMarkdown(text))
        }
    }

    @Test(timeout = 1000)
    fun everyStreamingPrefixOfHeadingTerminates() {
        val response = "Introduction\n\n## Heading\nSome text\n#hashtag"
        for (length in 1..response.length) {
            parseMarkdown(response.take(length))
        }
    }

    @Test(timeout = 1000)
    fun completeHeadingStillSeparatesParagraphs() {
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Introduction"),
                MarkdownBlock.Heading(2, "Heading"),
                MarkdownBlock.Paragraph("#hashtag")
            ),
            parseMarkdown("Introduction\n## Heading\n#hashtag")
        )
    }
}
