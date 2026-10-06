package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import org.intellij.markdown.ast.getTextInNode

/** Library primitives with app-owned code toolbar, error/LaTeX UI and streaming fades. */
@Composable
fun MarkdownRenderer(content: String, modifier: Modifier = Modifier, isStreaming: Boolean = false, latexMode: String = "AUTO") {
    if (content.isBlank()) return
    val latestContent by rememberUpdatedState(content)
    var parsed by remember { mutableStateOf<State>(State.Loading()) }
    LaunchedEffect(Unit) {
        // Finish each off-main parse and retain its result while the next chunk is
        // parsed. Fast token updates must not cancel parsing or flash a blank UI.
        snapshotFlow { latestContent }.conflate().collect { input ->
            parsed = parseLibraryMarkdown(input)
        }
    }
    val type = MaterialTheme.typography
    Markdown(
        state = parsed,
        modifier = modifier.fillMaxWidth().then(if (isStreaming) Modifier else Modifier.animateContentSize()),
        colors = markdownColor(),
        typography = markdownTypography(
            h1 = type.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary),
            h2 = type.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
            h3 = type.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            h4 = type.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
            h5 = type.bodyLarge.copy(fontWeight = FontWeight.Bold),
            h6 = type.bodyLarge.copy(fontWeight = FontWeight.Bold),
            paragraph = type.bodyLarge.copy(lineHeight = 22.sp),
            link = type.bodyLarge.copy(color = MaterialTheme.colorScheme.primary)
        ),
        padding = markdownPadding(block = 8.dp),
        components = markdownComponents(
            codeFence = { model ->
                val fence = markdownFence(model.content, model.node)
                RenderCodeBlock(fence.language, fence.code,
                    isStreaming && !fence.closed && model.node.endOffset == model.content.length)
            },
            codeBlock = { model -> RenderCodeBlock("", model.node.getTextInNode(model.content).toString().trimEnd(), false) },
            paragraph = { model -> RenderParagraph(model, latexMode) }
        ),
        error = { Text(content, style = type.bodyLarge) }
    )
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

@Composable
private fun RenderParagraph(model: MarkdownComponentModel, latexMode: String) {
    val text = model.node.getTextInNode(model.content).toString()
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
            val settings = annotatorSettings()
            val annotated = buildAnnotatedString {
                buildMarkdownAnnotatedString(model.content, model.node, settings)
            }
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
