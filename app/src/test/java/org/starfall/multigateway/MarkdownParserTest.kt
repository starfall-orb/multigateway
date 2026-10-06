package org.starfall.multigateway

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.chat.markdownFence
import org.starfall.multigateway.ui.chat.parseLibraryMarkdown

class MarkdownParserTest {
    private fun ASTNode.descendants(): List<ASTNode> = listOf(this) + children.flatMap { it.descendants() }

    @Test(timeout = 10000) fun everyStreamingPrefixProducesAParseResult() = runBlocking {
        val response = "Introduction\n\n## Heading\n- [x] Task\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```kotlin\n  val x = 1\n```\n[link](https://example.com)"
        for (length in 1..response.length) assertTrue(parseLibraryMarkdown(response.take(length)) is State.Success)
    }

    @Test fun incompleteFencesKeepCodeWhitespaceAndRemainStreaming() = runBlocking {
        for (ending in listOf("", "\n```")) {
            val text = "```kotlin\n  val x = 1\n    println(x)$ending"
            val state = parseLibraryMarkdown(text) as State.Success
            val node = state.node.descendants().first { it.type == MarkdownElementTypes.CODE_FENCE }
            val fence = markdownFence(text, node)
            assertEquals("kotlin", fence.language)
            assertEquals("  val x = 1\n    println(x)", fence.code)
            assertEquals(ending.isNotEmpty(), fence.closed)
        }
    }

    @Test fun nestedListsTablesAndReferenceLinksAreRecognized() = runBlocking {
        val state = parseLibraryMarkdown("- Outer\n  - Inner\n\n| A | B |\n|---|---|\n| x | y |\n\n[docs][ref]\n\n[ref]: https://example.com") as State.Success
        val nodes = state.node.descendants()
        assertEquals(2, nodes.count { it.type == MarkdownElementTypes.UNORDERED_LIST })
        assertTrue(nodes.any { it.type == GFMElementTypes.TABLE })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.FULL_REFERENCE_LINK })
    }
}
