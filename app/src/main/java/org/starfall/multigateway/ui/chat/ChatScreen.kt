package org.starfall.multigateway.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.StoredMessage
import org.starfall.multigateway.data.model.ConversationSummaryProgress
import org.starfall.multigateway.data.model.ConversationSummaryRequest
import org.starfall.multigateway.data.model.SummaryRole

@Composable
fun ChatScreen(
    conversation: Conversation?,
    isGenerating: Boolean,
    generatingConversationId: String?,
    chatError: String?,
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup> = emptyList(),
    selectedProviderId: String,
    selectedModelName: String,
    onSendMessage: (String, List<String>) -> Boolean,
    onStopGenerating: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenEndDrawer: () -> Unit,
    onRegenerate: (String) -> Unit,
    onEditMessage: (messageId: String, newContent: String, files: List<String>) -> Boolean,
    onDeleteMessage: (messageId: String) -> Unit,
    onDeleteMessageVersion: (messageId: String) -> Unit,
    onSwitchVersion: (messageId: String, versionIndex: Int) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    summaryProgress: ConversationSummaryProgress?,
    onSetReasoningEffort: (String?) -> Unit,
    onStartConversationSummary: (ConversationSummaryRequest) -> Boolean,
    onSummaryRoleChange: (SummaryRole) -> Unit,
    onDeleteSummary: () -> Unit,
    onReadMessage: (String) -> Unit,
    onFetchOllamaModels: (suspend (String) -> List<String>)? = null,
    queuedMessages: List<StoredMessage> = emptyList(),
    onEditQueuedMessage: (String, String, List<String>) -> Boolean = { _, _, _ -> true },
    onDeleteQueuedMessage: (String) -> Unit = {},
    autoScroll: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = key(conversation?.id) { rememberLazyListState() }
    var followBottom by remember(conversation?.id) { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    val seenMessageIds = remember(conversation?.id) { mutableSetOf<String>() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val messages = remember(conversation?.messages, queuedMessages) {
        val base = conversation?.messages ?: emptyList()
        base + queuedMessages
    }

    var editDraft by remember(conversation?.id) { mutableStateOf<ChatInputEditDraft?>(null) }

    // Dialog state for deleting a message
    var deletingMessageId by remember(conversation?.id) { mutableStateOf<String?>(null) }

    var regeneratingMessageId by remember(conversation?.id) { mutableStateOf<String?>(null) }
    var showSummaryDialog by remember(conversation?.id) { mutableStateOf(false) }
    val streamingHere = isGenerating && generatingConversationId == conversation?.id
    val lastMessage = messages.lastOrNull()
    val autoScrollTick = if (!autoScroll) null else if (streamingHere) {
        ((lastMessage?.content?.length ?: 0) + (lastMessage?.reasoningContent?.length ?: 0)) / 48
    } else {
        lastMessage?.activeVersionIndex
    }
    fun whenIdle(action: () -> Unit) {
        if (isGenerating) Toast.makeText(context, "Stop the current response before editing messages.", Toast.LENGTH_SHORT).show()
        else action()
    }
    val scrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f) followBottom = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && !listState.canScrollForward) followBottom = true
                return Offset.Zero
            }
        }
    }
    val messageIndexOffset = if (isGenerating && !streamingHere) 1 else 0
    val messageIds = remember(messages) { messages.map { it.id } }
    LaunchedEffect(conversation?.id, messageIds, autoScroll) {
        val newMessageIndex = messageIds.indexOfLast { it !in seenMessageIds }
        seenMessageIds.addAll(messageIds)
        if (!autoScroll && newMessageIndex >= 0) {
            listState.scrollToItem(newMessageIndex + messageIndexOffset)
        }
    }
    LaunchedEffect(
        conversation?.id,
        messages.size,
        autoScroll,
        autoScrollTick,
        followBottom,
        streamingHere,
        if (autoScroll) summaryProgress?.progress else null
    ) {
        if (autoScroll && followBottom && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex + messageIndexOffset)
            val height = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == lastMessage?.id }?.size ?: 0
            if (height > 0) listState.scrollToItem(messages.lastIndex + messageIndexOffset, height)
        }
    }
    LaunchedEffect(chatError) {
        chatError?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(conversation?.id) {
        focusManager.clearFocus(force = true)
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    val topBarClearance = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topBarClearance, bottom = 88.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "MultiGateway",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect to multiple LLM providers and agents seamlessly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (isGenerating && !streamingHere) {
                Text(
                    "A response is running in another conversation. Use Stop to cancel it.",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topBarClearance, start = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollConnection),
                contentPadding = PaddingValues(top = topBarClearance, bottom = 104.dp)
            ) {
                if (isGenerating && !streamingHere) {
                    item(key = "generation-warning") {
                        Text(
                            "A response is running in another conversation. Use Stop to cancel it.",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    val isLast = msg.id == messages.lastOrNull()?.id

                    if (msg.role == ChatRole.USER) {
                        UserMessageCard(
                            message = msg,
                            onEdit = {
                                if (msg.isQueued) {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        revision = System.nanoTime()
                                    )
                                } else {
                                    whenIdle {
                                        editDraft = ChatInputEditDraft(
                                            messageId = msg.id,
                                            text = msg.content,
                                            attachments = msg.files,
                                            revision = System.nanoTime()
                                        )
                                    }
                                }
                            },
                            onDelete = {
                                if (msg.isQueued) {
                                    onDeleteQueuedMessage(msg.id)
                                } else {
                                    whenIdle { deletingMessageId = msg.id }
                                }
                            },
                            onSwitchVersion = { newIdx ->
                                if (!msg.isQueued) {
                                    whenIdle { onSwitchVersion(msg.id, newIdx) }
                                }
                            },
                            modifier = if (msg.isQueued) Modifier.alpha(0.5f) else Modifier
                        )
                    } else {
                        AssistantMessageCard(
                            message = msg,
                            isStreaming = streamingHere && isLast,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Copied", msg.content))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onRegenerate = {
                                whenIdle {
                                    if (!isLast) regeneratingMessageId = msg.id
                                    else onRegenerate(msg.id)
                                }
                            },
                            onEdit = {
                                whenIdle {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        revision = System.nanoTime()
                                    )
                                }
                            },
                            onDelete = {
                                whenIdle { deletingMessageId = msg.id }
                            },
                            onRead = {
                                onReadMessage(msg.content)
                            },
                            onSwitchVersion = { newIdx ->
                                whenIdle { onSwitchVersion(msg.id, newIdx) }
                            }
                        )
                    }

                    val progressHere = summaryProgress?.takeIf {
                        it.conversationId == conversation?.id && it.throughMessageId == msg.id
                    }
                    val summaryHere = conversation?.summary?.takeIf {
                        it.throughMessageId == msg.id && progressHere == null
                    }
                    if (progressHere != null || summaryHere != null) {
                        ConversationSummaryDivider(
                            summary = summaryHere,
                            progress = progressHere,
                            onOpenSummary = { showSummaryDialog = true },
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                item(key = "chat-bottom-spacer") {
                    // Leave enough room to align even a short new bubble below the top bar.
                    val spacerHeight = if (autoScroll) 96.dp else
                        (maxHeight - topBarClearance - 104.dp).coerceAtLeast(96.dp)
                    Spacer(modifier = Modifier.height(spacerHeight))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (messages.isNotEmpty() && !followBottom) {
                TextButton(
                    onClick = {
                        followBottom = true
                        coroutineScope.launch {
                            val index = messages.lastIndex + messageIndexOffset
                            listState.scrollToItem(index)
                            if (autoScroll) {
                                val height = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == lastMessage?.id }?.size ?: 0
                                if (height > 0) listState.scrollToItem(index, height)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Jump to latest")
                }
            }
            UserInputArea(
                isGenerating = isGenerating,
                onSendMessage = { text, files ->
                    onSendMessage(text, files).also { if (it) followBottom = true }
                },
                onEditMessage = { id, text, files ->
                    val isQueued = queuedMessages.any { it.id == id }
                    if (isQueued) {
                        onEditQueuedMessage(id, text, files).also { if (it) followBottom = true }
                    } else {
                        onEditMessage(id, text, files).also { if (it) followBottom = true }
                    }
                },
                editDraft = editDraft,
                onCancelEdit = { editDraft = null },
                onStopGenerating = onStopGenerating,
                selectedModelName = selectedModelName,
                providers = providers,
                providerGroups = providerGroups,
                selectedProviderId = selectedProviderId,
                onSelectModel = onSelectModel,
                conversationReasoningEffort = conversation?.reasoningEffort,
                onSetReasoningEffort = onSetReasoningEffort,
                onStartConversationSummary = onStartConversationSummary,
                onFetchOllamaModels = onFetchOllamaModels
            )
        }

        ChatAppBar(
            currentSession = conversation,
            onOpenDrawer = onOpenDrawer,
            onOpenEndDrawer = onOpenEndDrawer,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (showSummaryDialog) {
        conversation?.summary?.let { summary ->
            ConversationSummaryDialog(
                summary = summary,
                onRoleChange = onSummaryRoleChange,
                onDelete = onDeleteSummary,
                onDismiss = { showSummaryDialog = false }
            )
        } ?: run { showSummaryDialog = false }
    }

    if (regeneratingMessageId != null) {
        AlertDialog(
            onDismissRequest = { regeneratingMessageId = null },
            title = { Text("Regenerate this response?") },
            text = { Text("Later messages will be removed. The current response will remain available as an earlier version.") },
            confirmButton = { TextButton(onClick = {
                regeneratingMessageId?.let(onRegenerate)
                regeneratingMessageId = null
            }) { Text("Regenerate") } },
            dismissButton = { TextButton(onClick = { regeneratingMessageId = null }) { Text("Cancel") } }
        )
    }

    // Delete message/version confirmation dialog
    val deletingMessage = deletingMessageId?.let { id -> messages.firstOrNull { it.id == id } }
    if (deletingMessage != null) {
        val hasMultipleVersions = deletingMessage.versions.size > 1
        AlertDialog(
            onDismissRequest = { deletingMessageId = null },
            title = { Text(if (hasMultipleVersions) "Delete Message Version" else "Delete Message") },
            text = {
                Text(
                    if (hasMultipleVersions) {
                        "This message has ${deletingMessage.versions.size} versions. Delete only the current version or delete the entire message with all versions?"
                    } else {
                        "Are you sure you want to delete this message?"
                    }
                )
            },
            confirmButton = {
                if (hasMultipleVersions) {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            onClick = {
                                onDeleteMessageVersion(deletingMessage.id)
                                deletingMessageId = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete current version")
                        }
                        TextButton(
                            onClick = {
                                onDeleteMessage(deletingMessage.id)
                                deletingMessageId = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete all versions")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            onDeleteMessage(deletingMessage.id)
                            deletingMessageId = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMessageId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}