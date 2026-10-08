package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val MaxTableColumnWidth = 260.dp
private val TableCellHorizontalPadding = 10.dp

private class TableRow(val header: Boolean, val cells: List<ASTNode>)

private fun tableRows(node: ASTNode): List<TableRow> = node.children
    .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    .map { row -> TableRow(row.type == GFMElementTypes.HEADER, row.children.filter { it.type == GFMTokenTypes.CELL }) }

private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}

/**
 * Markdown table that never hides content: columns size to their text up to a cap, longer cells wrap,
 * and a table wider than the message scrolls horizontally.
 */
@Composable
internal fun RenderMarkdownTable(model: MarkdownComponentModel) {
    val content = model.content
    val rows = remember(model.node) { tableRows(model.node) }
    if (rows.isEmpty()) return
    val settings = annotatorSettings()
    val preferences = LocalCodeRenderingPreferences.current
    val bodySize = preferences.messageFontSize.sp
    val baseStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = preferences.messageFontFamily.toComposeFontFamily(),
        fontSize = bodySize * 0.92f,
        lineHeight = bodySize * 1.3f,
        color = MaterialTheme.colorScheme.onSurface
    )
    val headerStyle = baseStyle.copy(fontWeight = FontWeight.SemiBold)
    val cells = rows.map { row ->
        row.cells.map { cell -> buildAnnotatedString { buildMarkdownAnnotatedString(content, cell, settings) }.trimmed() }
    }
    val columnCount = cells.maxOf { it.size }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val maxColumnPx = with(density) { (MaxTableColumnWidth - TableCellHorizontalPadding * 2).toPx() }
    val columnWidths = (0 until columnCount).map { column ->
        val natural = cells.indices.maxOf { rowIndex ->
            val text = cells[rowIndex].getOrNull(column) ?: return@maxOf 0f
            val style = if (rows[rowIndex].header) headerStyle else baseStyle
            measurer.measure(text, style, softWrap = false).size.width.toFloat()
        }
        with(density) { natural.coerceIn(24f, maxColumnPx).toDp() } + TableCellHorizontalPadding * 2 + 1.dp
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(8.dp)

    SelectionContainer {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            Column(Modifier.clip(shape).border(1.dp, border, shape)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for (column in 0 until columnCount) {
                            Box(
                                Modifier.width(columnWidths[column]).fillMaxHeight()
                                    .then(if (row.header) Modifier.background(headerBackground) else Modifier)
                                    .border(0.5.dp, border)
                                    .padding(horizontal = TableCellHorizontalPadding, vertical = 8.dp)
                            ) {
                                val text = cells[rowIndex].getOrNull(column)
                                if (text != null) MarkdownClickableText(
                                    text = text,
                                    style = if (row.header) headerStyle else baseStyle,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
