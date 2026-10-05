package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog
import org.starfall.multigateway.ui.components.windowHeightIn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.ConversationSummary
import org.starfall.multigateway.data.model.ConversationSummaryProgress
import org.starfall.multigateway.data.model.SummaryRole

@Composable
fun ConversationSummaryDivider(
    summary: ConversationSummary?,
    progress: ConversationSummaryProgress?,
    onOpenSummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = progress != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (!running && summary != null) Modifier.clickable(onClick = onOpenSummary) else Modifier),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Outlined.Summarize,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = progress?.label ?: "Summary Conversation",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSummaryDialog(
    summary: ConversationSummary,
    onRoleChange: (SummaryRole) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Summary Conversation") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowHeightIn(maxFraction = 0.75f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    summary.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider()
                Text("Send summary to AI as", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val roles = listOf(
                        SummaryRole.SYSTEM to "System",
                        SummaryRole.ASSISTANT to "Assistant",
                        SummaryRole.USER to "User"
                    )
                    roles.forEachIndexed { index, (role, label) ->
                        SegmentedButton(
                            selected = summary.role == role,
                            onClick = { onRoleChange(role) },
                            shape = SegmentedButtonDefaults.itemShape(index, roles.size),
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Delete summary")
            }
        }
    )
}
