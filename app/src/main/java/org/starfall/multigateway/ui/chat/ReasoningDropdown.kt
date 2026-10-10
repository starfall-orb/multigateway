package org.starfall.multigateway.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.R

@Composable
fun ReasoningDropdown(
    reasoning: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = stringResource(R.string.thinking),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) stringResource(R.string.thinking_streaming) else stringResource(R.string.thought_process),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            CollapsibleThinkingText(
                reasoning = reasoning,
                expanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
            )
        }
    }
}

internal sealed interface ProcessingDropdownItem {
    data class Thinking(val reasoning: String) : ProcessingDropdownItem
    data class Tool(val activity: org.starfall.multigateway.data.model.ToolActivity) : ProcessingDropdownItem
}

@Composable
internal fun ProcessingDropdown(
    items: List<ProcessingDropdownItem>,
    durationMillis: Long?,
    startNumber: Int,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val runningTools = items.filterIsInstance<ProcessingDropdownItem.Tool>()
        .map { it.activity }.filter { it.status == "running" && !it.name.endsWith(": connect") && it.name != "send_file" }
    val visibleItems = items.filterNot {
        it is ProcessingDropdownItem.Tool &&
            (it.activity.name.endsWith(": connect") || it.activity.status == "running" || it.activity.name == "send_file")
    }
    if (visibleItems.isEmpty()) {
        Box(modifier) { ToolActivityCards(runningTools) }
        return
    }

    val onlyThinking = visibleItems.all { it is ProcessingDropdownItem.Thinking }
    val onlyTools = visibleItems.all { it is ProcessingDropdownItem.Tool }
    val processingLabel = when {
        onlyThinking && durationMillis != null ->
            stringResource(R.string.reasoning_completed_in, formatProcessingDuration(durationMillis))
        onlyThinking -> stringResource(R.string.reasoning_completed)
        onlyTools && durationMillis != null ->
            stringResource(R.string.tool_executed_in, formatProcessingDuration(durationMillis))
        onlyTools -> stringResource(R.string.tool_executed)
        durationMillis != null ->
            stringResource(R.string.processed_in, formatProcessingDuration(durationMillis))
        else -> stringResource(R.string.processed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = processingLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                visibleItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Surface(
                                modifier = Modifier.size(24.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = (startNumber + index).toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (item) {
                                is ProcessingDropdownItem.Thinking -> {
                                    var thinkingExpanded by remember(item.reasoning) { mutableStateOf(false) }
                                    CollapsibleThinkingText(
                                        reasoning = item.reasoning,
                                        expanded = thinkingExpanded,
                                        onToggle = { thinkingExpanded = !thinkingExpanded },
                                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp)
                                    )
                                }
                                is ProcessingDropdownItem.Tool -> {
                                    ToolActivityCards(listOf(item.activity))
                                }
                            }
                        }
                    }
                    if (index < visibleItems.lastIndex) {
                        Box(
                            modifier = Modifier
                                .padding(start = 11.5.dp)
                                .width(1.dp)
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
        }
        ToolActivityCards(runningTools)
    }
}
@Composable
private fun CollapsibleThinkingText(
    reasoning: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preferences = LocalCodeRenderingPreferences.current
    val bodySize = preferences.messageFontSize.sp
    val messageFont = preferences.messageFontFamily.toComposeFontFamily()
    val bodyLineHeight = bodySize * 1.375f
    Text(
        text = reasoning,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = messageFont,
            fontSize = bodySize,
            lineHeight = bodyLineHeight
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = if (expanded) Int.MAX_VALUE else 5,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    )
}

private fun formatProcessingDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) + 500L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
