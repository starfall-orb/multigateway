package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.local.preferences.WordWrapMode
import org.starfall.multigateway.ui.preview.CodePreviewActivity
import org.starfall.multigateway.ui.preview.canPreviewCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal val LocalCodeRenderingPreferences = staticCompositionLocalOf { AppPreferences() }

/** Width of the text area, excluding code block padding. Null means no wrapping. */
internal fun codeWrapWidth(mode: WordWrapMode, viewport: Float, column: Float): Float? = when (mode) {
    WordWrapMode.OFF -> null
    WordWrapMode.VIEWPORT -> viewport
    WordWrapMode.COLUMN -> column
    WordWrapMode.BOUNDED -> minOf(viewport, column)
}

@Composable
internal fun RenderCodeBlock(language: String, code: String, isStreaming: Boolean) {
    val context = LocalContext.current
    val preferences = LocalCodeRenderingPreferences.current
    var sessionWrap by remember(language) { mutableStateOf(false) }
    val mode = if (preferences.wordWrapMode == WordWrapMode.OFF && sessionWrap) WordWrapMode.VIEWPORT else preferences.wordWrapMode
    val wrapCode = mode != WordWrapMode.OFF
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val characterWidth = remember(textStyle, density) { (measurer.measure("0".repeat(100), textStyle, softWrap = false).size.width / 100f).coerceAtLeast(1f) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted by produceState(AnnotatedString(code), code, language, dark) {
        value = withContext(Dispatchers.Default) { highlightedCode(language, code, dark) }
    }
    var copied by remember { mutableStateOf(false) }
    val codeScrollState = rememberScrollState()
    val codePresentation = rememberStreamingTextPresentation(code)
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (isStreaming) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (preferences.wordWrapMode == WordWrapMode.OFF) {
                        IconButton(onClick = { sessionWrap = !sessionWrap }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.WrapText, stringResource(if (sessionWrap) R.string.code_wrap_disable else R.string.code_wrap_enable),
                                tint = if (sessionWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (preferences.codePreviewEnabled && canPreviewCode(language)) {
                        IconButton(onClick = { CodePreviewActivity.open(context, language, code) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Visibility, stringResource(R.string.preview_code),
                                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                        copied = true
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, "Copy code",
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewport = with(density) { (maxWidth - 24.dp).coerceAtLeast(1.dp).toPx() }
                val target = codeWrapWidth(mode, viewport, (characterWidth * preferences.wordWrapColumn).coerceAtMost(200_000f))
                val bodyModifier = if (target == null) Modifier else Modifier.width(with(density) { target.toDp() } + 24.dp)
                Box(Modifier.fillMaxWidth().then(if (mode == WordWrapMode.OFF || mode == WordWrapMode.COLUMN) Modifier.horizontalScroll(codeScrollState) else Modifier)) {
                    Text(highlighted, style = textStyle,
                        softWrap = wrapCode, modifier = bodyModifier.padding(12.dp).then(codePresentation.modifier),
                        onTextLayout = codePresentation.onTextLayout, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
