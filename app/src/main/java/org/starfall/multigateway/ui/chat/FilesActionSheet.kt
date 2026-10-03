package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesActionSheet(
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onTakePhoto: () -> Unit,
    onCreateImage: () -> Unit,
    onCreateVideo: () -> Unit,
    onOpenConversationSummary: () -> Unit,
    onOpenTools: () -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AddActionTile(
                    icon = Icons.Outlined.Image,
                    label = "Photos",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onPickImage()
                        onDismiss()
                    }
                )
                AddActionTile(
                    icon = Icons.Outlined.CameraAlt,
                    label = "Camera",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onTakePhoto()
                        onDismiss()
                    }
                )
                AddActionTile(
                    icon = Icons.Outlined.Description,
                    label = "Files",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onPickDocument()
                        onDismiss()
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            AddActionRow(
                icon = Icons.Outlined.Image,
                title = "Image",
                subtitle = "Send directly to an image generation model",
                onClick = { onCreateImage(); onDismiss() }
            )
            AddActionRow(
                icon = Icons.Outlined.Videocam,
                title = "Video",
                subtitle = "Send directly to a video generation model",
                onClick = { onCreateVideo(); onDismiss() }
            )
            AddActionRow(
                icon = Icons.Outlined.Summarize,
                title = "Conversation Summary",
                subtitle = "Compress earlier context into a summary",
                onClick = {
                    onOpenConversationSummary()
                    onDismiss()
                }
            )
            AddActionRow(
                icon = Icons.Outlined.Extension,
                title = "MCP & tools",
                subtitle = "Choose tools available to the model",
                onClick = {
                    onOpenTools()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun AddActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AddActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
