package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.data.model.MessageVersion
import org.starfall.multigateway.data.model.StoredMessage
import org.starfall.multigateway.data.model.ToolActivity
import org.starfall.multigateway.data.tools.ToolFiles
import org.starfall.multigateway.ui.tools.MediaFileCard

@Composable
fun AssistantMessageCard(
    message: StoredMessage,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRead: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMoreMenu by remember { mutableStateOf(false) }
    val activeVersion = message.activeVersion
    val processingDurationMillis = activeVersion.processingFinishedAt?.let { finishedAt ->
        activeVersion.timestamp.toLongOrNull()?.let { startedAt -> (finishedAt - startedAt).coerceAtLeast(0L) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isStreaming && message.content.isBlank()) {
            StreamingProcessingPreview(activeVersion)
        } else {
            CompletedAssistantContent(
                version = activeVersion,
                processingDurationMillis = processingDurationMillis.takeUnless { isStreaming },
                animateStreamingContent = isStreaming
            )
        }

        val generatedFiles = activeVersion.toolActivity.flatMap { it.files }
            .map { it.removePrefix("tool-file:") }.distinct()
        if (generatedFiles.isNotEmpty()) {
            val store = remember(context) { ToolFiles(context) }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                generatedFiles.forEach { name -> key(name) { MediaFileCard(store, name) } }
            }
        }
        if (activeVersion.files.isNotEmpty()) {
            AttachmentStrip(activeVersion.files, removable = false, modifier = Modifier.padding(top = 8.dp))
        }

        if (isStreaming) {
            Row(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isStreaming && message.versions.size > 1) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (message.activeVersionIndex > 0) {
                            onSwitchVersion(message.activeVersionIndex - 1)
                        }
                    },
                    enabled = message.activeVersionIndex > 0,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous version",
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "${message.activeVersionIndex + 1}/${message.versions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        if (message.activeVersionIndex < message.versions.size - 1) {
                            onSwitchVersion(message.activeVersionIndex + 1)
                        }
                    },
                    enabled = message.activeVersionIndex < message.versions.size - 1,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next version",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (!isStreaming && message.content.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MessageActionButton(onClick = onRegenerate, contentDescription = "Regenerate") {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message.content)
                        }
                        context.startActivity(Intent.createChooser(share, "Share response"))
                    },
                    contentDescription = "Share"
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                MessageActionButton(onClick = onCopy, contentDescription = "Copy") {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                }

                Box {
                    MessageActionButton(
                        onClick = { showMoreMenu = true },
                        contentDescription = "More"
                    ) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onDelete()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                MessageActionButton(onClick = onRead, contentDescription = "Read aloud") {
                    Icon(Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private sealed interface ProcessingTimelineItem {
    val contentOffset: Int

    data class Thinking(
        override val contentOffset: Int,
        val reasoning: String
    ) : ProcessingTimelineItem

    data class Tool(val activity: ToolActivity) : ProcessingTimelineItem {
        override val contentOffset: Int get() = activity.contentOffset
    }
}

private data class NumberedProcessingItem(
    val number: Int,
    val item: ProcessingTimelineItem
)

private fun buildProcessingTimeline(version: MessageVersion): List<ProcessingTimelineItem> {
    val activities = version.toolActivity.filterNot { it.name.endsWith(": connect") }
    val reasoning = version.reasoningContent.orEmpty()

    if (activities.isNotEmpty() && activities.all { it.reasoningOffset == null }) {
        return buildList {
            if (reasoning.isNotBlank()) add(ProcessingTimelineItem.Thinking(0, reasoning))
            activities.forEach { add(ProcessingTimelineItem.Tool(it)) }
        }
    }

    val timeline = mutableListOf<ProcessingTimelineItem>()
    var reasoningCursor = 0
    activities.forEach { activity ->
        val reasoningEnd = (activity.reasoningOffset ?: reasoningCursor)
            .coerceIn(reasoningCursor, reasoning.length)
        if (reasoningEnd > reasoningCursor) {
            timeline += ProcessingTimelineItem.Thinking(
                activity.contentOffset.coerceIn(0, version.content.length),
                reasoning.substring(reasoningCursor, reasoningEnd)
            )
        }
        timeline += ProcessingTimelineItem.Tool(activity)
        reasoningCursor = reasoningEnd
    }
    if (reasoningCursor < reasoning.length) {
        timeline += ProcessingTimelineItem.Thinking(
            activities.lastOrNull()?.contentOffset?.coerceIn(0, version.content.length) ?: 0,
            reasoning.substring(reasoningCursor)
        )
    }
    return timeline
}

@Composable
private fun StreamingProcessingPreview(version: MessageVersion) {
    val timeline = remember(version.reasoningContent, version.toolActivity, version.content.length) {
        buildProcessingTimeline(version)
    }
    val visibleItems = timeline.takeLast(2).mapIndexed { index, item ->
        NumberedProcessingItem(timeline.size - 1 + index, item)
    }
    val lastItem = timeline.lastOrNull()

    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        visibleItems.forEach { numbered ->
            val item = numbered.item
            key(
                when (item) {
                    is ProcessingTimelineItem.Thinking -> "thinking_${numbered.number}_${item.contentOffset}"
                    is ProcessingTimelineItem.Tool -> item.activity.id
                }
            ) {
                when (item) {
                    is ProcessingTimelineItem.Thinking -> LiveThinkingBlock(
                        reasoning = item.reasoning,
                        blockNumber = numbered.number,
                        active = item === lastItem
                    )
                    is ProcessingTimelineItem.Tool -> LiveToolBlock(
                        activity = item.activity,
                        blockNumber = numbered.number
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveThinkingBlock(
    reasoning: String,
    blockNumber: Int,
    active: Boolean
) {
    val scrollState = rememberScrollState()
    val previewHeight = with(LocalDensity.current) { 48.sp.toDp() }
    LaunchedEffect(reasoning, scrollState.maxValue, active) {
        if (active && scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$blockNumber. ${if (active) androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.thinking_streaming) else androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.processed)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            if (active) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = previewHeight)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun LiveToolBlock(activity: ToolActivity, blockNumber: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$blockNumber.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp)
        )
        Box(Modifier.weight(1f)) {
            ToolActivityCards(listOf(activity))
        }
    }
}

@Composable
private fun SmoothStreamingMarkdownMessage(content: String, isStreaming: Boolean) {
    StreamingMarkdownRenderer(content = content, isStreaming = isStreaming)
}

private data class ProcessingBlock(
    val offset: Int,
    val items: List<ProcessingDropdownItem>
)

@Composable
private fun CompletedAssistantContent(
    version: MessageVersion,
    processingDurationMillis: Long?,
    animateStreamingContent: Boolean
) {
    val blocks = remember(version.content, version.reasoningContent, version.toolActivity) {
        buildProcessingBlocks(version)
    }
    val content = version.content

    SelectionContainer {
        Column {
            if (blocks.isEmpty()) {
                if (content.isNotBlank()) {
                    SmoothStreamingMarkdownMessage(content, isStreaming = animateStreamingContent)
                }
            } else {
                var cursor = 0
                var nextItemNumber = 1
                blocks.forEach { block ->
                    val offset = block.offset.coerceIn(cursor, content.length)
                    if (offset > cursor) {
                        val textBefore = content.substring(cursor, offset)
                        if (textBefore.isNotBlank()) {
                            FormattedMarkdownMessage(content = textBefore)
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    ProcessingDropdown(
                        items = block.items,
                        durationMillis = processingDurationMillis.takeIf { blocks.size == 1 },
                        startNumber = nextItemNumber,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                    )
                    nextItemNumber += block.items.size
                    cursor = offset
                }

                if (cursor < content.length) {
                    val remaining = content.substring(cursor)
                    if (remaining.isNotBlank()) {
                        SmoothStreamingMarkdownMessage(remaining, isStreaming = animateStreamingContent)
                    }
                }
            }
        }
    }
}

private fun buildProcessingBlocks(version: MessageVersion): List<ProcessingBlock> {
    val timeline = buildProcessingTimeline(version)
    if (timeline.isEmpty()) return emptyList()

    fun ProcessingTimelineItem.toDropdownItem(): ProcessingDropdownItem = when (this) {
        is ProcessingTimelineItem.Thinking -> ProcessingDropdownItem.Thinking(reasoning)
        is ProcessingTimelineItem.Tool -> ProcessingDropdownItem.Tool(activity)
    }

    val content = version.content
    val blocks = mutableListOf<ProcessingBlock>()
    var groupOffset = timeline.first().contentOffset.coerceIn(0, content.length)
    var previousOffset = groupOffset
    var groupItems = mutableListOf(timeline.first().toDropdownItem())

    timeline.drop(1).forEach { item ->
        val itemOffset = item.contentOffset.coerceIn(previousOffset, content.length)
        val hasVisibleContentBetween = itemOffset > previousOffset &&
            content.substring(previousOffset, itemOffset).isNotBlank()

        if (hasVisibleContentBetween) {
            blocks += ProcessingBlock(
                offset = groupOffset,
                items = groupItems.toList()
            )
            groupOffset = itemOffset
            groupItems = mutableListOf()
        }

        groupItems += item.toDropdownItem()
        previousOffset = itemOffset
    }

    blocks += ProcessingBlock(
        offset = groupOffset,
        items = groupItems.toList()
    )
    return blocks
}

@Composable
private fun MessageActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun FormattedMarkdownMessage(content: String) {
    MarkdownRenderer(content = content)
}

sealed class ContentBlock {
    data class Paragraph(val text: String) : ContentBlock()
    data class Code(val language: String, val code: String) : ContentBlock()
}

fun parseMarkdownBlocks(markdown: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    val codeRegex = Regex("```(\\w*)\\n?([\\s\\S]*?)```")
    var lastIndex = 0

    codeRegex.findAll(markdown).forEach { match ->
        val textBefore = markdown.substring(lastIndex, match.range.first).trim()
        if (textBefore.isNotEmpty()) {
            blocks.add(ContentBlock.Paragraph(textBefore))
        }
        val language = match.groupValues[1].trim()
        val code = match.groupValues[2].trim()
        blocks.add(ContentBlock.Code(language, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < markdown.length) {
        val remaining = markdown.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            blocks.add(ContentBlock.Paragraph(remaining))
        }
    }

    if (blocks.isEmpty() && markdown.isNotBlank()) {
        blocks.add(ContentBlock.Paragraph(markdown))
    }

    return blocks
}
