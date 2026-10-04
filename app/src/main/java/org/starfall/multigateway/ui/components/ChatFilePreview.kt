package org.starfall.multigateway.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.AttachmentResolver

@Composable
internal fun BoundedMediaFrame(ratio: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.5f), contentAlignment = Alignment.Center) {
        val safeRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val frameHeight = minOf(maxWidth / safeRatio, maxHeight)
        Box(Modifier.fillMaxWidth().height(frameHeight).clip(RoundedCornerShape(16.dp)).background(Color.Black),
            contentAlignment = Alignment.Center, content = content)
    }
}

/** One presentation for attachments, generated media and files explicitly sent by tools. */
@Composable
fun ChatFilePreview(
    reference: String,
    name: String,
    mime: String,
    thumbnail: ImageBitmap?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    selectedForChat: Boolean = false,
    onToggleAttachment: ((String) -> Unit)? = null,
    onOpen: () -> Unit
) {
    val sharedSelection = LocalChatAttachmentSelection.current
    val toggle = onToggleAttachment ?: sharedSelection?.let { it::toggle }
    val selected = if (onToggleAttachment == null && sharedSelection != null) reference in sharedSelection.attachments.value else selectedForChat
    val actions: @Composable BoxScope.() -> Unit = {
        FileCornerActions(reference, name, mime, selected, toggle, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
    when {
        mime.startsWith("image/") -> BoundedMediaFrame(thumbnail?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f, modifier) {
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
                thumbnail?.let { Image(it, "Generated image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                else if (thumbnail == null) Text("Image preview unavailable", color = Color.White)
            }
            actions()
        }
        mime.startsWith("video/") -> InlineVideoPreview(reference, thumbnail, modifier, overlay = actions)
        else -> Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 48.dp, bottom = 10.dp)) {
                    if (mime.startsWith("audio/")) InlineAudioPreview(reference)
                    else Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.InsertDriveFile, null)
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                actions()
            }
        }
    }
}

@Composable
private fun FileCornerActions(reference: String, name: String, mime: String, selected: Boolean,
    onToggle: ((String) -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(reference) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            downloading = true
            try {
                withContext(Dispatchers.IO) {
                    val input = AttachmentResolver(context).open(reference) ?: error("File unavailable")
                    input.use { source ->
                        (context.contentResolver.openOutputStream(uri) ?: error("Cannot save file")).use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                Toast.makeText(context, R.string.file_download_saved, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { Toast.makeText(context, R.string.file_download_failed, Toast.LENGTH_LONG).show()
            } finally { downloading = false }
        }
    }
    val image = mime.startsWith("image/")
    val selectionLabel = if (image) {
        if (selected) "Remove image from next message" else "Attach image to next message"
    } else stringResource(if (selected) R.string.file_remove_next_message else R.string.file_attach_next_message)
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.62f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !downloading, onClick = { save.launch(name) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Download, stringResource(R.string.file_download), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Checkbox(selected, onCheckedChange = { onToggle?.invoke(reference) }, enabled = onToggle != null,
                modifier = Modifier.size(40.dp).semantics { contentDescription = selectionLabel },
                colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.White,
                    checkmarkColor = Color.Black, disabledUncheckedColor = Color.Gray))
        }
    }
}
