package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

@Composable
internal fun MarkdownClickableText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val presentation = rememberStreamingTextPresentation(text.text)
    val mergedStyle = style.copy(color = if (style.color != Color.Unspecified) style.color else MaterialTheme.colorScheme.onSurface)
    ClickableText(text = text, style = mergedStyle, modifier = modifier.then(presentation.modifier),
        onTextLayout = presentation.onTextLayout, onClick = { offset ->
            text.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()?.let {
                try { uriHandler.openUri(it.item) } catch (_: Exception) { }
            }
        })
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

private val inlinePattern = Regex(
    "`([^`\n]+)`|" +
        "\\[([^\\]]+)\\]\\(([^)\\s]+)\\)|" +
        "\\*\\*\\*([^*]+)\\*\\*\\*|" +
        "\\*\\*([^*]+)\\*\\*|" +
        "__([^_]+)__|" +
        "\\*([^*]+)\\*|" +
        "_([^_]+)_|" +
        "~~([^~]+)~~"
)

fun buildMarkdownAnnotatedString(text: String, primaryColor: Color, codeBackgroundColor: Color, textColor: Color): AnnotatedString =
    buildAnnotatedString {
        var lastIndex = 0
        inlinePattern.findAll(text).forEach { match ->
            val start = match.range.first
            if (start > lastIndex) append(text.substring(lastIndex, start))
            when {
                match.groups[1] != null -> {
                    val code = match.groups[1]!!.value
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackgroundColor,
                        color = primaryColor, fontWeight = FontWeight.Medium, fontSize = 13.sp))
                    append(" $code ")
                    pop()
                }
                match.groups[2] != null && match.groups[3] != null -> {
                    pushStringAnnotation(tag = "URL", annotation = match.groups[3]!!.value)
                    pushStyle(SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold))
                    append(match.groups[2]!!.value)
                    pop()
                    pop()
                }
                match.groups[4] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(match.groups[4]!!.value)
                    pop()
                }
                match.groups[5] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groups[5]!!.value)
                    pop()
                }
                match.groups[6] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groups[6]!!.value)
                    pop()
                }
                match.groups[7] != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(match.groups[7]!!.value)
                    pop()
                }
                match.groups[8] != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(match.groups[8]!!.value)
                    pop()
                }
                match.groups[9] != null -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(match.groups[9]!!.value)
                    pop()
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) append(text.substring(lastIndex))
    }
