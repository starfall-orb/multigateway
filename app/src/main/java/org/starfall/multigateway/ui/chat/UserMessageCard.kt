package org.starfall.multigateway.ui.chat
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.StoredMessage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageCard(
    message: StoredMessage,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSwitchVersion: (Int) -> Unit,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    onResend: (() -> Unit)? = null,
    resendEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * 0.85f),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .combinedClickable(
                        onClick = { if (message.isQueued) showMenu = true },
                        onLongClick = { showMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (message.files.isNotEmpty()) {
                        AttachmentStrip(
                            references = message.files,
                            removable = false,
                            modifier = Modifier.padding(bottom = 8.dp),
                            compact = false,
                            selectedImageAttachments = selectedImageAttachments,
                            onToggleChatImage = onToggleChatImage
                        )
                    }

                    // Text Content
                    val messageText: @Composable () -> Unit = {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (message.isQueued) messageText() else SelectionContainer { messageText() }

                    // Version switcher if multiple versions exist
                    if (!message.isQueued && message.versions.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    if (message.activeVersionIndex > 0) {
                                        onSwitchVersion(message.activeVersionIndex - 1)
                                    }
                                },
                                enabled = message.activeVersionIndex > 0,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev Version", modifier = Modifier.size(14.dp))
                            }
                            Text(
                                text = "${message.activeVersionIndex + 1}/${message.versions.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (message.activeVersionIndex < message.versions.size - 1) {
                                        onSwitchVersion(message.activeVersionIndex + 1)
                                    }
                                },
                                enabled = message.activeVersionIndex < message.versions.size - 1,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Version", modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Dropdown Context Menu
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (!message.isQueued) DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Copied", message.content))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onEdit(message.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (message.isQueued) "Cancel" else "Delete") },
                            leadingIcon = { Icon(if (message.isQueued) Icons.Outlined.Close else Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onDelete(message.id)
                            }
                        )
                    }
                }
            }
            if (onResend != null) {
                IconButton(onClick = onResend, enabled = resendEnabled, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(org.starfall.multigateway.R.string.resend_message),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
