package org.starfall.multigateway.ui.chat

import androidx.compose.material3.Text
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString as libraryAnnotatedString
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
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun MarkdownClickableText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val presentation = rememberStreamingTextPresentation(text.text)
    val mergedStyle = style.copy(color = if (style.color != Color.Unspecified) style.color else MaterialTheme.colorScheme.onSurface)
    Text(text = text, style = mergedStyle, modifier = modifier.then(presentation.modifier),
        onTextLayout = presentation.onTextLayout)
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

/** Delegate inline recognition and nested formatting to the same GFM parser as blocks. */
@Suppress("DEPRECATION")
fun buildMarkdownAnnotatedString(text: String, primaryColor: Color, codeBackgroundColor: Color, textColor: Color): AnnotatedString =
    buildMarkdownAnnotatedString(text, primaryColor, codeBackgroundColor, textColor, FontFamily.Default, TextUnit.Unspecified)

@Suppress("DEPRECATION")
fun buildMarkdownAnnotatedString(text: String, primaryColor: Color, codeBackgroundColor: Color, textColor: Color,
    fontFamily: FontFamily, fontSize: TextUnit): AnnotatedString =
    text.libraryAnnotatedString(
        style = TextStyle(color = textColor, fontFamily = fontFamily, fontSize = fontSize),
        linkTextSpanStyle = SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold),
        codeSpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackgroundColor,
            color = primaryColor, fontWeight = FontWeight.Medium,
            fontSize = if (fontSize == TextUnit.Unspecified) 13.sp else fontSize)
    )
