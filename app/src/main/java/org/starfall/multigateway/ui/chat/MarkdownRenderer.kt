package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect

/**
 * High-fidelity Markdown Composable for displaying AI chat responses
 * including syntax-styled code blocks, tables, task lists, blockquotes,
 * headings, and rich inline text formatting.
 */
@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    latexMode: String = "AUTO"
) {
    if (content.isBlank()) return

    val blocks = remember(content) { parseMarkdown(content) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isStreaming) Modifier else Modifier.animateContentSize()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            key(index, block.javaClass.simpleName) {
                when (block) {
                    is MarkdownBlock.CodeBlock -> {
                        RenderCodeBlock(
                            language = block.language,
                            code = block.code,
                            isStreaming = isStreaming && index == blocks.lastIndex && !block.isClosed
                        )
                    }
                    is MarkdownBlock.Heading -> {
                        RenderHeading(block.level, block.text)
                    }
                    is MarkdownBlock.BlockQuote -> {
                        RenderBlockQuote(block.text)
                    }
                    is MarkdownBlock.UnorderedList -> {
                        RenderUnorderedList(block.items)
                    }
                    is MarkdownBlock.OrderedList -> {
                        RenderOrderedList(block.items)
                    }
                    is MarkdownBlock.TaskList -> {
                        RenderTaskList(block.items)
                    }
                    is MarkdownBlock.Table -> {
                        RenderTable(block.headers, block.rows)
                    }
                    is MarkdownBlock.HorizontalRule -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp
                        )
                    }
                    is MarkdownBlock.Paragraph -> {
                        RenderParagraph(block.text, latexMode)
                    }
                }
            }
        }
    }
}

/**
 * Animated streaming version of markdown content.
 */
@Composable
fun StreamingMarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = true
) {
    val latestContent by rememberUpdatedState(content)
    var displayContent by remember { mutableStateOf(content) }
    var finishingFade by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming, if (isStreaming) null else content) {
        if (isStreaming) {
            finishingFade = true
            snapshotFlow { latestContent }.conflate().collect {
                // Render a complete received chunk, rather than reparsing every animated character.
                delay(64)
                displayContent = latestContent
            }
        } else {
            // Flush the last chunk and let its fade finish before switching to static rendering.
            displayContent = latestContent
            if (finishingFade) {
                delay(STREAM_FADE_DURATION_MS + 32)
                finishingFade = false
            }
        }
    }
    CompositionLocalProvider(LocalStreamingTextFade provides (isStreaming || finishingFade)) {
        MarkdownRenderer(
            content = displayContent,
            modifier = modifier,
            isStreaming = isStreaming
        )
    }
}

// ----------------------------------------------------------------------------
// Block Renderers
// ----------------------------------------------------------------------------

@Composable
private fun RenderHeading(level: Int, text: String) {
    val annotated = rememberMarkdownAnnotatedString(text)
    val (style, color, topPad) = when (level) {
        1 -> Triple(
            MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            MaterialTheme.colorScheme.primary,
            8.dp
        )
        2 -> Triple(
            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
            MaterialTheme.colorScheme.onSurface,
            6.dp
        )
        3 -> Triple(
            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            MaterialTheme.colorScheme.onSurface,
            4.dp
        )
        4 -> Triple(
            MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
            MaterialTheme.colorScheme.onSurface,
            2.dp
        )
        else -> Triple(
            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            MaterialTheme.colorScheme.onSurface,
            2.dp
        )
    }

    Column(modifier = Modifier.padding(top = topPad, bottom = 2.dp)) {
        MarkdownClickableText(
            text = annotated,
            style = style.copy(color = color)
        )
        if (level <= 2) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 1.dp
            )
        }
    }
}

@Composable
private fun RenderBlockQuote(text: String) {
    val annotated = rememberMarkdownAnnotatedString(text)
    Surface(
        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(IntrinsicSize.Min)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            MarkdownClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = FontStyle.Italic,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun RenderUnorderedList(items: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val annotated = rememberMarkdownAnnotatedString(item)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, start = 4.dp, end = 10.dp)
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                MarkdownClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RenderOrderedList(items: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (number, item) ->
            val annotated = rememberMarkdownAnnotatedString(item)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "$number.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .widthIn(min = 22.dp)
                        .padding(end = 6.dp)
                )
                MarkdownClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RenderTaskList(items: List<Pair<Boolean, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (isChecked, item) ->
            val annotated = rememberMarkdownAnnotatedString(item)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isChecked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (isChecked) "Checked" else "Unchecked",
                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp)
                )
                MarkdownClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 22.sp,
                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RenderTable(headers: List<String>, rows: List<List<String>>) {
    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
    ) {
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            Column(modifier = Modifier.padding(2.dp)) {
                // Headers
                if (headers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        headers.forEach { header ->
                            val annotated = rememberMarkdownAnnotatedString(header)
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 90.dp, max = 220.dp)
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                MarkdownClickableText(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 1.dp
                    )
                }

                // Rows
                rows.forEachIndexed { rowIndex, row ->
                    val bgColor = if (rowIndex % 2 == 0) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    }
                    Row(
                        modifier = Modifier
                            .background(bgColor)
                            .padding(vertical = 6.dp)
                    ) {
                        row.forEachIndexed { colIndex, cell ->
                            val annotated = rememberMarkdownAnnotatedString(cell)
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 90.dp, max = 220.dp)
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                MarkdownClickableText(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                    if (rowIndex < rows.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderParagraph(text: String, latexMode: String = "AUTO") {
    val context = LocalContext.current
    val presentation = rememberStreamingTextPresentation(text)
    val isError = remember(text) { isErrorText(text) }
    val isLatex = remember(text, latexMode) { LatexDetector.shouldRenderLatex(text, latexMode) }

    when {
        isError -> {
            val errorColor = MaterialTheme.colorScheme.error
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                border = androidx.compose.foundation.BorderStroke(1.dp, errorColor.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = text,
                                modifier = presentation.modifier,
                                onTextLayout = presentation.onTextLayout,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 22.sp,
                                    color = errorColor,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Error Message", text))
                            Toast.makeText(context, "Copied error text", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy Error",
                            tint = errorColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        isLatex -> {
            RenderLatexBlock(text)
        }
        else -> {
            val annotated = rememberMarkdownAnnotatedString(text)
            androidx.compose.foundation.text.selection.SelectionContainer {
                MarkdownClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun isErrorText(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("[Error:", ignoreCase = true) ||
           trimmed.startsWith("Error:", ignoreCase = true) ||
           trimmed.startsWith("API Error", ignoreCase = true) ||
           trimmed.startsWith("Exception:", ignoreCase = true) ||
           trimmed.startsWith("Failed:", ignoreCase = true) ||
           trimmed.contains("[Error:", ignoreCase = true)
}

// ----------------------------------------------------------------------------
// Inline Parser & Clickable Text
// ----------------------------------------------------------------------------

@Composable
private fun MarkdownClickableText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val presentation = rememberStreamingTextPresentation(text.text)
    val mergedStyle = style.copy(
        color = if (style.color != Color.Unspecified) style.color else MaterialTheme.colorScheme.onSurface
    )

    ClickableText(
        text = text,
        style = mergedStyle,
        modifier = modifier.then(presentation.modifier),
        onTextLayout = presentation.onTextLayout,
        onClick = { offset ->
            text.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        uriHandler.openUri(annotation.item)
                    } catch (_: Exception) {
                        // Ignore malformed URIs safely
                    }
                }
        }
    )
}

@Composable
fun rememberMarkdownAnnotatedString(text: String): AnnotatedString {
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val textColor = MaterialTheme.colorScheme.onSurface

    return remember(text, primaryColor, codeBg, textColor) {
        buildMarkdownAnnotatedString(text, primaryColor, codeBg, textColor)
    }
}

fun buildMarkdownAnnotatedString(
    text: String,
    primaryColor: Color,
    codeBackgroundColor: Color,
    textColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        // Regex pattern for all inline tokens
        // 1: Inline Code `code`
        // 2, 3: Link [text](url)
        // 4: Bold-Italic ***text***
        // 5: Bold **text**
        // 6: Bold __text__
        // 7: Italic *text*
        // 8: Italic _text_
        // 9: Strikethrough ~~text~~
        val pattern = Regex(
            "`([^`\n]+)`|" +
            "\\[([^\\]]+)\\]\\(([^)\\s]+)\\)|" +
            "\\*\\*\\*([^*]+)\\*\\*\\*|" +
            "\\*\\*([^*]+)\\*\\*|" +
            "__([^_]+)__|" +
            "\\*([^*]+)\\*|" +
            "_([^_]+)_|" +
            "~~([^~]+)~~"
        )

        var lastIndex = 0
        pattern.findAll(text).forEach { match ->
            val start = match.range.first
            if (start > lastIndex) {
                append(text.substring(lastIndex, start))
            }

            when {
                // Inline Code
                match.groups[1] != null -> {
                    val code = match.groups[1]!!.value
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackgroundColor,
                            color = primaryColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    )
                    append(" $code ")
                    pop()
                }

                // Link
                match.groups[2] != null && match.groups[3] != null -> {
                    val linkText = match.groups[2]!!.value
                    val linkUrl = match.groups[3]!!.value
                    pushStringAnnotation(tag = "URL", annotation = linkUrl)
                    pushStyle(
                        SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    append(linkText)
                    pop()
                    pop()
                }

                // Bold Italic
                match.groups[4] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(match.groups[4]!!.value)
                    pop()
                }

                // Bold (**)
                match.groups[5] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groups[5]!!.value)
                    pop()
                }

                // Bold (__)
                match.groups[6] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groups[6]!!.value)
                    pop()
                }

                // Italic (*)
                match.groups[7] != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(match.groups[7]!!.value)
                    pop()
                }

                // Italic (_)
                match.groups[8] != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(match.groups[8]!!.value)
                    pop()
                }

                // Strikethrough
                match.groups[9] != null -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(match.groups[9]!!.value)
                    pop()
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

// ----------------------------------------------------------------------------
// Markdown AST / Block Models
// ----------------------------------------------------------------------------
