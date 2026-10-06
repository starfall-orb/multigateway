package org.starfall.multigateway.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.ui.components.MediaPreviewDialog
import org.starfall.multigateway.ui.components.ChatFilePreview
import org.starfall.multigateway.ui.components.mediaThumbnail

private data class AttachmentPreviewData(
    val name: String,
    val mimeType: String,
    val bitmap: ImageBitmap? = null,
    val durationMs: Long? = null
)

@Composable
fun AttachmentStrip(
    references: List<String>,
    removable: Boolean,
    onRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    inlinePreview: Boolean = false
) {
    if (references.isEmpty()) return
    if (inlinePreview && !removable) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            references.forEach { reference -> key(reference) {
                AttachmentTile(reference, false, {}, false, reference in selectedImageAttachments,
                    onToggleChatImage, inlinePreview = true)
            } }
        }
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 0.dp)
    ) {
        items(references, key = { it }) { reference ->
            AttachmentTile(
                reference = reference,
                removable = removable,
                onRemove = { onRemove(reference) },
                compact = compact,
                selectedForChat = reference in selectedImageAttachments,
                onToggleChatImage = onToggleChatImage
            )
        }
    }
}

@Composable
private fun AttachmentTile(
    reference: String,
    removable: Boolean,
    onRemove: () -> Unit,
    compact: Boolean,
    selectedForChat: Boolean,
    onToggleChatImage: ((String) -> Unit)?,
    inlinePreview: Boolean = false
) {
    val context = LocalContext.current
    var showPreview by remember(reference) { mutableStateOf(false) }
    val data by produceState<AttachmentPreviewData?>(initialValue = null, reference) {
        value = withContext(Dispatchers.IO) { loadPreview(context, reference) }
    }
    val width = if (compact) 116.dp else 132.dp
    val height = if (compact) 72.dp else 92.dp
    val shape = RoundedCornerShape(if (compact) 26.dp else 18.dp)
    if (inlinePreview && data != null) {
        val preview = data!!
        ChatFilePreview(reference, preview.name, preview.mimeType, preview.bitmap, false,
            selectedForChat = selectedForChat, onToggleAttachment = onToggleChatImage, onOpen = { showPreview = true })
        if (showPreview) MediaPreviewDialog(reference, preview.name, preview.mimeType) { showPreview = false }
        return
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .testTag("attachment-tile_$reference")
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = data != null) { showPreview = true }
    ) {
        val preview = data
        if (preview?.bitmap != null) {
            Image(
                bitmap = preview.bitmap,
                contentDescription = preview.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (preview?.mimeType?.startsWith("audio/") == true) Icons.Outlined.Audiotrack
                    else Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    preview?.name ?: Uri.parse(reference).lastPathSegment.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (preview?.mimeType?.startsWith("video/") == true || preview?.mimeType?.startsWith("audio/") == true) {
            Surface(modifier = Modifier.align(Alignment.Center), shape = CircleShape,
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)) {
                Icon(Icons.Default.PlayArrow, "Play media", Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
        preview?.durationMs?.let { duration ->
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
            ) {
                Text(
                    formatDuration(duration),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }

        if (removable) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
        } else if (data?.mimeType?.startsWith("image/") == true && onToggleChatImage != null) {
            FilledTonalIconToggleButton(
                checked = selectedForChat,
                onCheckedChange = { onToggleChatImage(reference) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                )
            ) {
                Icon(if (selectedForChat) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    if (selectedForChat) "Remove image from next message" else "Attach image to next message")
            }
        }
    }
    val preview = data
    if (showPreview && preview != null) {
        MediaPreviewDialog(reference, preview.name, preview.mimeType) { showPreview = false }
    }
}

private suspend fun loadPreview(context: Context, reference: String): AttachmentPreviewData {
    val uri = Uri.parse(reference)
    val metadata = runCatching { AttachmentResolver(context).metadata(reference) }.getOrNull()
    val name = metadata?.name ?: uri.lastPathSegment ?: "Attachment"
    val mime = metadata?.mimeType.orEmpty()
    val bitmap = mediaThumbnail(context, reference, mime, 480)
    var duration: Long? = null
    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            if (uri.scheme == "content") retriever.setDataSource(context, uri)
            else retriever.setDataSource(uri.path ?: reference)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
        runCatching { retriever.release() }
    }

    return AttachmentPreviewData(name, mime, bitmap, duration)
}

private fun formatDuration(durationMs: Long): String {
    val total = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "%02d:%02d".format(minutes, seconds)
}
