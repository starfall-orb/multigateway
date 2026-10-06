package org.starfall.multigateway.ui.chat

import com.mikepenz.markdown.model.Input
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal suspend fun parseLibraryMarkdown(content: String): State {
    val flavour = GFMFlavourDescriptor()
    return MarkdownState(Input(content, true, flavour, MarkdownParser(flavour), ReferenceLinkHandlerImpl())).parse()
}

internal data class MarkdownFence(val language: String, val code: String, val closed: Boolean)

/** Use the library's token boundaries; preserve whitespace for copying and code preview. */
internal fun markdownFence(content: String, node: ASTNode): MarkdownFence {
    val tokens = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    val code = if (tokens.isEmpty()) "" else content.substring(tokens.first().startOffset, tokens.last().endOffset)
    return MarkdownFence(
        node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(content)?.toString().orEmpty(),
        code,
        node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    )
}
