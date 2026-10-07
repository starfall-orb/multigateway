package org.starfall.multigateway.ui.chat

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

internal suspend fun parseLibraryMarkdown(content: String): State {
    return parseMarkdown(content)
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
