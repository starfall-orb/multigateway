package org.starfall.multigateway.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MediaViewerToolbar(
    onClose: () -> Unit,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            onSave?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Download, "Save media") } }
            onShare?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Share, "Share media") } }
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Delete, "Delete media", tint = MaterialTheme.colorScheme.error) } }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close viewer") }
        }
    }
}
