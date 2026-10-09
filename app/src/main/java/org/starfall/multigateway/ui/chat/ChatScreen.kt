package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog
import org.starfall.multigateway.ui.components.ChatAttachmentSelection
import org.starfall.multigateway.ui.components.LocalChatAttachmentSelection
import org.starfall.multigateway.ui.components.PlatformTextSelection

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.unit.Velocity
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    modelPickerCollapsedGroups: Set<String> = emptySet(),
    modelPickerCollapsedProviders: Set<String> = emptySet(),
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    selectedModelName: String,
    onSendMessage: (String, List<String>) -> Boolean,
    onSendMedia: (DirectMediaRequest) -> Boolean,
    onStopGenerating: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRegenerate: (String) -> Unit,
    onEditMessage: (messageId: String, newContent: String, files: List<String>) -> Boolean,
    onDeleteMessage: (messageId: String) -> Unit,
    onDeleteMessageVersion: (messageId: String) -> Unit,
    onSwitchVersion: (messageId: String, versionIndex: Int) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    summaryProgress: ConversationSummaryProgress?,
    onSetReasoningEffort: (String?) -> Unit,
    onSetSendThinkingContent: (providerId: String, modelId: String, enabled: Boolean) -> Unit = { _, _, _ -> },
    onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit = { _, _, _ -> },
    onStartConversationSummary: (ConversationSummaryRequest) -> Boolean,
    onSummaryRoleChange: (SummaryRole) -> Unit,
    onDeleteSummary: () -> Unit,
    onReadMessage: (String, String) -> Unit,
    onFetchOllamaModels: (suspend (String) -> List<String>)? = null,
    queuedMessages: List<StoredMessage> = emptyList(),
    onEditQueuedMessage: (String, String, List<String>) -> Boolean = { _, _, _ -> true },
    onDeleteQueuedMessage: (String) -> Unit = {},
    autoScroll: Boolean = false,
    contextWindowStatus: ContextWindowStatus? = null,
    speakingMessageId: String? = null,
    onResendUserMessage: (String) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val conversationKey = conversation?.id ?: "__empty_conversation__"
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    val listState = remember(conversationKey) {
        listStates.getOrPut(conversationKey) { LazyListState() }
    }
    val followBottomStates = remember { mutableMapOf<String, Boolean>() }
    var followBottom by remember(conversationKey) {
        mutableStateOf(followBottomStates[conversationKey] ?: true)
    }
    LaunchedEffect(conversationKey, followBottom) {
        followBottomStates[conversationKey] = followBottom
    }
    val coroutineScope = rememberCoroutineScope()
    val seenMessageSets = remember { mutableMapOf<String, MutableSet<String>>() }
    val seenMessageIds = remember(conversationKey) {
        seenMessageSets.getOrPut(conversationKey) { mutableSetOf() }
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val selectedModelDisplayName = selectedProvider?.config?.modelConfigs?.get(selectedModelName)
        ?.displayName?.takeIf { it.isNotBlank() } ?: selectedModelName

    val conversationMessages = conversation?.messages.orEmpty()
    val messages = remember(conversationMessages, queuedMessages) {
        conversationMessages + queuedMessages
    }

    var editDraft by remember(conversation?.id) { mutableStateOf<ChatInputEditDraft?>(null) }
    val selection = LocalChatAttachmentSelection.current ?: remember(conversation?.id) { ChatAttachmentSelection() }
    var inputAttachments by selection.attachments
    val chatModeRequest = selection.requestId
    val toggleChatImage: (String) -> Unit = selection::toggle

    // Dialog state for deleting a message
    var deletingMessageId by remember(conversation?.id) { mutableStateOf<String?>(null) }

    var regeneratingMessageId by remember(conversation?.id) { mutableStateOf<String?>(null) }
    var showContextSummarySheet by remember(conversation?.id) { mutableStateOf(false) }
    var showSummaryDialog by remember(conversation?.id) { mutableStateOf(false) }
    var inlineChatError by remember(conversation?.id) { mutableStateOf<String?>(null) }
    val streamingHere = isGenerating && generatingConversationId == conversation?.id
    val lastMessage = conversationMessages.lastOrNull()
    val streamingMessage = if (streamingHere) conversationMessages.lastOrNull { it.role == ChatRole.MODEL } else null
    val unansweredMessages = remember(conversationMessages) { unansweredUserMessageIds(conversationMessages) }
    // Streaming text is followed by the per-frame loop below; stepping on text length made the scroll jump per chunk.
    val autoScrollTick = if (!autoScroll || streamingHere) null else lastMessage?.activeVersionIndex
    fun whenIdle(action: () -> Unit) {
        if (!isGenerating) action()
    }
    val nearBottomPx = with(LocalDensity.current) { 32.dp.toPx() }
    fun isNearBottom(): Boolean {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull() ?: return false
        if (last.index != layout.totalItemsCount - 1) return false
        return last.offset + last.size - (layout.viewportEndOffset - layout.afterContentPadding) <= nearBottomPx
    }
    val scrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f) followBottom = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Drag and fling both count: reaching the bottom resumes following without the jump button.
                if (consumed.y < 0f && isNearBottom()) followBottom = true
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (consumed.y <= 0f && isNearBottom()) followBottom = true
                return Velocity.Zero
            }
        }
    }
    val messageIndexOffset = if (isGenerating && !streamingHere) 1 else 0
    suspend fun scrollToBottom(includeQueued: Boolean = true) {
        val index = if (includeQueued) listState.layoutInfo.totalItemsCount - 1
            else conversationMessages.lastIndex + messageIndexOffset
        if (index < 0) return
        listState.scrollToItem(index)
        val height = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }?.size ?: 0
        val layout = listState.layoutInfo
        val offset = height - (layout.viewportEndOffset - layout.viewportStartOffset) +
            layout.afterContentPadding
        listState.scrollToItem(index, offset)
    }
    val messageIds = remember(conversationMessages) { conversationMessages.map { it.id } }
    LaunchedEffect(conversation?.id, messageIds, autoScroll) {
        val newMessageIndex = messageIds.indexOfLast { it !in seenMessageIds }
        val firstMessageLoad = seenMessageIds.isEmpty() && newMessageIndex >= 0
        seenMessageIds.addAll(messageIds)
        if (!autoScroll && newMessageIndex >= 0) {
            if (firstMessageLoad) {
                // A conversation opens at its latest message. Wait for the lazy
                // list to have measured at least one item before calculating the
                // bottom offset; otherwise the initial scroll can land at item 0.
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                scrollToBottom(includeQueued = true)
            } else {
                listState.scrollToItem(newMessageIndex + messageIndexOffset)
            }
        }
    }
    LaunchedEffect(
        conversation?.id,
        conversationMessages.size,
        autoScroll,
        autoScrollTick,
        followBottom,
        streamingHere,
        if (autoScroll) summaryProgress?.progress else null
    ) {
        if (autoScroll && followBottom && messages.isNotEmpty() && !streamingHere) {
            scrollToBottom(includeQueued = true)
        }
    }
    // Ease toward the bottom every frame while text streams, like the live thinking block does.
    LaunchedEffect(conversation?.id, conversationMessages.size, autoScroll, streamingHere, followBottom) {
        if (!autoScroll || !streamingHere || !followBottom) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val elapsedMs = if (previous == 0L) 16f else ((now - previous) / 1_000_000f).coerceIn(1f, 64f)
            previous = now
            if (listState.isScrollInProgress || conversationMessages.isEmpty()) continue
            val index = conversationMessages.lastIndex + messageIndexOffset
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null) {
                scrollToBottom(includeQueued = false)
                continue
            }
            val remaining = (item.offset + item.size - (layout.viewportEndOffset - layout.afterContentPadding)).toFloat()
            if (remaining > 0.5f) {
                val step = (remaining * (1f - exp(-elapsedMs / 120f))).coerceAtLeast(min(remaining, elapsedMs * 0.05f))
                try { listState.scrollBy(step) } catch (e: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
    }
    LaunchedEffect(chatError) {
        chatError?.takeIf { it.startsWith("Could not save conversation:") }?.let { inlineChatError = it }
    }
    LaunchedEffect(conversation?.id) {
        focusManager.clearFocus(force = true)
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    val density = LocalDensity.current
    var appBarHeightPx by remember { mutableIntStateOf(0) }
    var inputAreaHeightPx by remember { mutableIntStateOf(0) }
    val appBarHeight = if (appBarHeightPx > 0) with(density) { appBarHeightPx.toDp() }
        else 72.dp

    PlatformTextSelection {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        // Reserve the app bar's actual bounds rather than scrollable list padding.
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(top = appBarHeight).clipToBounds()
        ) {
        val bottomClearance = with(density) { inputAreaHeightPx.toDp() } + maxHeight * 0.015f
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 88.dp),
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
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollConnection),
                contentPadding = PaddingValues(top = 8.dp, bottom = bottomClearance)
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
                    val isLast = msg.id == lastMessage?.id

                    if (msg.role == ChatRole.USER) {
                        UserMessageCard(
                            message = msg,
                            selectedImageAttachments = inputAttachments,
                            onToggleChatImage = toggleChatImage,
                            onResend = if (msg.id in unansweredMessages) ({
                                if (onResendUserMessage(msg.id)) followBottom = true
                            }) else null,
                            resendEnabled = !isGenerating && summaryProgress == null &&
                                selectedModelName.isNotBlank() && providers.any { it.id == selectedProviderId },
                            onEdit = {
                                if (msg.isQueued) {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        isQueued = true,
                                        revision = System.nanoTime()
                                    )
                                } else {
                                    editDraft = ChatInputEditDraft(
                                        messageId = msg.id,
                                        text = msg.content,
                                        attachments = msg.files,
                                        revision = System.nanoTime()
                                    )
                                }
                            },
                            onDelete = {
                                if (msg.isQueued) {
                                    onDeleteQueuedMessage(msg.id)
                                } else {
                                    deletingMessageId = msg.id
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
                            isReading = speakingMessageId == msg.id,
                            fallbackModelName = providers.find { it.id == conversation?.providerId }
                                ?.config?.modelConfigs?.get(conversation?.modelId)?.displayName
                                ?.ifBlank { conversation?.modelId.orEmpty() } ?: conversation?.modelId.orEmpty(),
                            selectedImageAttachments = inputAttachments,
                            onToggleChatImage = toggleChatImage,
                            isStreaming = streamingHere && msg.id == streamingMessage?.id,
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
                                editDraft = ChatInputEditDraft(
                                    messageId = msg.id,
                                    text = msg.content,
                                    attachments = msg.files,
                                    revision = System.nanoTime()
                                )
                            },
                            onDelete = {
                                deletingMessageId = msg.id
                            },
                            onRead = {
                                onReadMessage(msg.id, msg.content)
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

            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            inlineChatError?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { inlineChatError = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
            if (messages.isNotEmpty() && !followBottom) {
                FilledTonalIconButton(
                    onClick = {
                        followBottom = true
                        coroutineScope.launch {
                            scrollToBottom()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to latest",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            if (contextWindowStatus?.shouldSuggestSummary == true && !streamingHere && summaryProgress == null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.context_summary_suggestion,
                            contextWindowStatus.percentage), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { showContextSummarySheet = true }) {
                            Text(androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.summarize_now))
                        }
                    }
                }
            }
            UserInputArea(
                attachments = inputAttachments,
                onAttachmentsChange = { inputAttachments = it },
                chatModeRequest = chatModeRequest,
                modifier = Modifier.onSizeChanged { inputAreaHeightPx = it.height },
                isGenerating = isGenerating,
                onSendMedia = { request ->
                    inlineChatError = null
                    onSendMedia(request).also { if (it) followBottom = true }
                },
                onSendMessage = { text, files ->
                    inlineChatError = null
                    onSendMessage(text, files).also { if (it && !isGenerating) followBottom = true }
                },
                onEditMessage = { id, text, files ->
                    val isQueued = editDraft?.isQueued == true
                    if (isQueued) {
                        onEditQueuedMessage(id, text, files)
                    } else {
                        inlineChatError = null
                        onEditMessage(id, text, files).also { if (it) followBottom = true }
                    }
                },
                onSubmissionFailed = { inlineChatError = it },
                editDraft = editDraft,
                onCancelEdit = { editDraft = null },
                onStopGenerating = onStopGenerating,
                selectedModelName = selectedModelName,
                providers = providers,
                providerGroups = providerGroups,
                modelPickerCollapsedGroups = modelPickerCollapsedGroups,
                modelPickerCollapsedProviders = modelPickerCollapsedProviders,
                onModelPickerCollapsedGroupsChange = onModelPickerCollapsedGroupsChange,
                onModelPickerCollapsedProvidersChange = onModelPickerCollapsedProvidersChange,
                selectedProviderId = selectedProviderId,
                onSelectModel = onSelectModel,
                conversationReasoningEffort = conversation?.reasoningEffort,
                onSetReasoningEffort = onSetReasoningEffort,
                onSetSendThinkingContent = onSetSendThinkingContent,
                onSetModelReasoningEffort = onSetModelReasoningEffort,
                onStartConversationSummary = onStartConversationSummary,
                onFetchOllamaModels = onFetchOllamaModels
            )
        }

        }
        ChatAppBar(
            modelName = selectedModelDisplayName,
            providerName = selectedProvider?.name.orEmpty(),
            onOpenDrawer = onOpenDrawer,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter)
                .onSizeChanged { appBarHeightPx = it.height }
        )
    }

    }

    if (showContextSummarySheet) {
        ConversationSummarySheet(onStart = onStartConversationSummary, onDismiss = { showContextSummarySheet = false })
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
