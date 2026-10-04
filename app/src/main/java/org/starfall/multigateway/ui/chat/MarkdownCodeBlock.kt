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

@Composable
internal fun RenderCodeBlock(language: String, code: String, isStreaming: Boolean) {
    val context = LocalContext.current
    var wrapCode by remember(language) { mutableStateOf(true) }
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
                    IconButton(onClick = { wrapCode = !wrapCode }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.WrapText, if (wrapCode) "Disable wrapping" else "Enable wrapping",
                            tint = if (wrapCode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
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
            Box(Modifier.fillMaxWidth().then(if (wrapCode) Modifier else Modifier.horizontalScroll(codeScrollState))) {
                Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
                    softWrap = wrapCode, modifier = Modifier.padding(12.dp).then(if (wrapCode) Modifier.fillMaxWidth() else Modifier)
                        .then(codePresentation.modifier), onTextLayout = codePresentation.onTextLayout, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
