package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
            .animateContentSize(),
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
    modifier: Modifier = Modifier
) {
    val visibleLength = remember { Animatable(0f) }
    LaunchedEffect(content.length) {
        val target = content.length.toFloat()
        if (target < visibleLength.value) {
            visibleLength.snapTo(target)
        } else if (target > visibleLength.value) {
            val delta = target - visibleLength.value
            visibleLength.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = (delta * 6f).toInt().coerceIn(30, 160),
                    easing = LinearEasing
                )
            )
        }
    }
    val end = visibleLength.value.toInt().coerceIn(0, content.length)
    val displayContent = if (end > 0) content.substring(0, end) else ""

    if (displayContent.isNotEmpty()) {
        MarkdownRenderer(
            content = displayContent,
            modifier = modifier,
            isStreaming = true
        )
    }
}

// ----------------------------------------------------------------------------
// Block Renderers
// ----------------------------------------------------------------------------

@Composable
private fun RenderCodeBlock(
    language: String,
    code: String,
    isStreaming: Boolean
) {
    val context = LocalContext.current
    var wrapCode by remember(language, code) { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val codeScrollState = rememberScrollState()

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val displayLang = language.ifBlank { "code" }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Column {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = displayLang,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    if (isStreaming) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { wrapCode = !wrapCode },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.WrapText,
                            contentDescription = if (wrapCode) "Disable wrapping" else "Enable wrapping",
                            tint = if (wrapCode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                            copied = true
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Code Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrapCode) Modifier else Modifier.horizontalScroll(codeScrollState))
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    softWrap = wrapCode,
                    modifier = Modifier
                        .padding(12.dp)
                        .then(if (wrapCode) Modifier.fillMaxWidth() else Modifier),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

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

@Composable
private fun RenderLatexBlock(text: String) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "∑",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LaTeX Equation",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LaTeX Formula", text))
                        Toast.makeText(context, "Formula copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Formula",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
    val mergedStyle = style.copy(
        color = if (style.color != Color.Unspecified) style.color else MaterialTheme.colorScheme.onSurface
    )

    ClickableText(
        text = text,
        style = mergedStyle,
        modifier = modifier,
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

sealed interface MarkdownBlock {
    data class CodeBlock(val language: String, val code: String, val isClosed: Boolean = true) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class UnorderedList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<Pair<String, String>>) : MarkdownBlock
    data class TaskList(val items: List<Pair<Boolean, String>>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

// ----------------------------------------------------------------------------
// Block Parser
// ----------------------------------------------------------------------------

fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. Fenced Code Block
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            val fence = if (line.trimStart().startsWith("```")) "```" else "~~~"
            val lang = line.trimStart().removePrefix(fence).trim()
            val codeLines = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                val codeLine = lines[i]
                if (codeLine.trimStart().startsWith(fence)) {
                    closed = true
                    i++
                    break
                } else {
                    codeLines.add(codeLine)
                    i++
                }
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n"), closed))
            continue
        }

        // 2. Horizontal Rule (---, ***, ___)
        val trimmed = line.trim()
        if (trimmed.length >= 3 && (trimmed.all { it == '-' } || trimmed.all { it == '*' } || trimmed.all { it == '_' })) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // 3. Heading (# .. ######)
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // 4. Blockquote (> ...)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.BlockQuote(quoteLines.joinToString("\n")))
            continue
        }

        // 5. Task List (- [ ] or - [x])
        if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") ||
            trimmed.startsWith("* [ ] ") || trimmed.startsWith("* [x] ") || trimmed.startsWith("* [X] ")
        ) {
            val taskItems = mutableListOf<Pair<Boolean, String>>()
            while (i < lines.size) {
                val current = lines[i].trim()
                val isCheck = when {
                    current.startsWith("- [x] ", ignoreCase = true) || current.startsWith("* [x] ", ignoreCase = true) -> true
                    current.startsWith("- [ ] ") || current.startsWith("* [ ] ") -> false
                    else -> null
                }
                if (isCheck != null) {
                    val itemText = current.substring(6).trim()
                    taskItems.add(Pair(isCheck, itemText))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.TaskList(taskItems))
            continue
        }

        // 6. Unordered List (- , * , + )
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val listItems = mutableListOf<String>()
            while (i < lines.size) {
                val current = lines[i].trim()
                if (current.startsWith("- ") || current.startsWith("* ") || current.startsWith("+ ")) {
                    listItems.add(current.substring(2).trim())
                    i++
                } else if (current.isNotBlank() && (lines[i].startsWith("  ") || lines[i].startsWith("\t")) && listItems.isNotEmpty()) {
                    // Append continuation line to previous item
                    val last = listItems.removeAt(listItems.lastIndex)
                    listItems.add("$last\n${current.trim()}")
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.UnorderedList(listItems))
            continue
        }

        // 7. Ordered List (1. , 2. )
        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
        if (orderedMatch != null) {
            val orderedItems = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val current = lines[i].trim()
                val match = Regex("^(\\d+)\\.\\s+(.*)$").find(current)
                if (match != null) {
                    orderedItems.add(Pair(match.groupValues[1], match.groupValues[2].trim()))
                    i++
                } else if (current.isNotBlank() && (lines[i].startsWith("  ") || lines[i].startsWith("\t")) && orderedItems.isNotEmpty()) {
                    val last = orderedItems.removeAt(orderedItems.lastIndex)
                    orderedItems.add(Pair(last.first, "${last.second}\n${current.trim()}"))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.OrderedList(orderedItems))
            continue
        }

        // 8. Table (| Col 1 | Col 2 |)
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().contains("---")) {
            val headers = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            i += 2 // skip header and divider
            val rows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rowLine = lines[i].trim()
                if (rowLine.startsWith("|") && rowLine.endsWith("|")) {
                    val rowCells = rowLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    rows.add(rowCells)
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.Table(headers, rows))
            continue
        }

        // 9. Blank line -> skip
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 10. Paragraph (accumulate consecutive non-empty non-special lines)
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val current = lines[i]
            val currentTrim = current.trim()
            if (currentTrim.isEmpty()) {
                i++
                break
            }
            if (currentTrim.startsWith("```") || currentTrim.startsWith("~~~") ||
                currentTrim.startsWith("#") || currentTrim.startsWith(">") ||
                currentTrim.startsWith("- ") || currentTrim.startsWith("* ") || currentTrim.startsWith("+ ") ||
                Regex("^(\\d+)\\.\\s+").containsMatchIn(currentTrim) ||
                (currentTrim.startsWith("|") && currentTrim.endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().contains("---")) ||
                (currentTrim.length >= 3 && (currentTrim.all { it == '-' } || currentTrim.all { it == '*' } || currentTrim.all { it == '_' }))
            ) {
                break
            }
            paragraphLines.add(currentTrim)
            i++
        }

        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
        }
    }

    return blocks
}
