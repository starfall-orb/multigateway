package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.EntityIcon
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ConversationSummaryRequest
import org.starfall.multigateway.data.model.ModelType
import org.starfall.multigateway.data.model.ProviderType

data class DirectMediaRequest(
    val prompt: String,
    val kind: ModelType,
    val providerId: String,
    val modelId: String
)

data class ChatInputEditDraft(
    val messageId: String,
    val text: String,
    val attachments: List<String>,
    val revision: Long
)

@Composable
fun UserInputArea(
    isGenerating: Boolean,
    onSendMessage: (String, List<String>) -> Boolean,
    onSendMedia: (DirectMediaRequest) -> Boolean,
    onEditMessage: (String, String, List<String>) -> Boolean,
    editDraft: ChatInputEditDraft?,
    onCancelEdit: () -> Unit,
    onStopGenerating: () -> Unit,
    selectedModelName: String,
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup> = emptyList(),
    modelPickerCollapsedGroups: Set<String> = emptySet(),
    modelPickerCollapsedProviders: Set<String> = emptySet(),
    onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onStartConversationSummary: (ConversationSummaryRequest) -> Boolean,
    onFetchOllamaModels: (suspend (String) -> List<String>)? = null,
    modifier: Modifier = Modifier
) {
    var textState by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var mediaKind by rememberSaveable { mutableStateOf<ModelType?>(null) }
    var mediaProviderId by rememberSaveable { mutableStateOf("") }
    var mediaModelId by rememberSaveable { mutableStateOf("") }
    val mediaProviders = providers.mapNotNull { provider ->
        if (provider.type !in listOf(ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES, ProviderType.GOOGLE)) {
            return@mapNotNull null
        }
        val models = provider.config.modelConfigs.filter { (id, config) ->
            config.modelType == mediaKind && provider.config.modelIds?.contains(id) != false
        }
        if (models.isEmpty()) null else provider.copy(config = provider.config.copy(
            modelIds = models.keys.toList(), modelConfigs = models
        ))
    }
    val mediaProvider = mediaProviders.find { it.id == mediaProviderId }
    val mediaModel = mediaProvider?.config?.modelConfigs?.get(mediaModelId)
    val mediaLabel = if (mediaKind == ModelType.VIDEO_GENERATION) "Video" else "Image"
    LaunchedEffect(mediaKind, mediaProviders) {
        if (mediaKind != null && mediaModel == null) {
            mediaProviderId = mediaProviders.firstOrNull()?.id.orEmpty()
            mediaModelId = mediaProviders.firstOrNull()?.config?.modelIds?.firstOrNull().orEmpty()
        }
    }
    val context = LocalContext.current


    val focusManager = LocalFocusManager.current
    var showModelPicker by remember { mutableStateOf(false) }
    var showMediaMenu by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    var showConversationSummary by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showFilesSheet by remember { mutableStateOf(false) }

    val dynamicModelsMap = remember { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(providers) {
        providers
            .filter { it.type == org.starfall.multigateway.data.model.ProviderType.OLLAMA && it.config.modelIds == null }
            .forEach { provider ->
                val remoteModels = onFetchOllamaModels?.invoke(provider.baseUrl).orEmpty()
                if (remoteModels.isNotEmpty()) dynamicModelsMap[provider.id] = remoteModels
            }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        focusManager.clearFocus(force = true)
    }

    LaunchedEffect(editDraft?.revision) {
        editDraft?.let {
            mediaKind = null
            textState = it.text
            attachments = it.attachments
        }
    }

    fun retainReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        uris.forEach(::retainReadPermission)
        attachments = (attachments + uris.map(Uri::toString)).distinct()
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val picked = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        picked.forEach(::retainReadPermission)
        attachments = (attachments + picked.map(Uri::toString)).distinct()
    }

    val canSend = if (mediaKind == null) textState.isNotBlank() || attachments.isNotEmpty()
        else textState.isNotBlank() && textState.length <= 32000 && attachments.isEmpty() && mediaModel != null && !isGenerating
    val showStop = isGenerating && (mediaKind != null || (textState.isEmpty() && attachments.isEmpty()))
    val mediaHint = when {
        attachments.isNotEmpty() -> "Remove attachments to generate"
        textState.length > 32000 -> "Prompt exceeds 32,000 characters"
        mediaModel == null -> "Choose a $mediaLabel model"
        else -> "$mediaLabel · Direct to ${mediaModel.displayName.ifBlank { mediaModelId }}"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .testTag("chat-input"),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, if (mediaKind != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            tonalElevation = 2.dp,
            shadowElevation = 3.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (editDraft != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 10.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Editing message",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onCancelEdit()
                                textState = ""
                                attachments = emptyList()
                            }
                        ) { Text("Cancel") }
                    }
                }
                if (attachments.isNotEmpty()) {
                    AttachmentStrip(
                        references = attachments,
                        removable = true,
                        onRemove = { ref -> attachments = attachments.filterNot { it == ref } },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        compact = false
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            showAddMenu = true
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attachments and tools",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    BasicTextField(
                        value = textState,
                        onValueChange = { textState = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            lineHeight = 22.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = if (mediaKind != null) 4.dp else 10.dp),
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Column {
                                if (mediaKind != null) {
                                    Text(
                                        mediaHint,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                        color = if (attachments.isNotEmpty() || textState.length > 32000)
                                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable {
                                            if (mediaModel == null) showModelPicker = true
                                            else Toast.makeText(context, mediaHint, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                Box(contentAlignment = Alignment.CenterStart) {
                                if (textState.isEmpty()) {
                                    Text(
                                        text = when (mediaKind) {
                                            ModelType.IMAGE_GENERATION -> "Describe an image…"
                                            ModelType.VIDEO_GENERATION -> "Describe a video…"
                                            else -> stringResource(R.string.chat_input_placeholder)
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                innerTextField()
                                }
                            }
                        }
                    )

                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (mediaKind != null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .semantics { contentDescription = if (mediaKind != null) "$mediaLabel mode options" else "Select model" }
                            .clickable { if (mediaKind != null) showMediaMenu = true else showModelPicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (mediaKind != null) {
                            Icon(if (mediaKind == ModelType.IMAGE_GENERATION) Icons.Outlined.Image else Icons.Outlined.Videocam,
                                contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        } else {
                        val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
                        val selectedConfig = selectedProvider?.config?.modelConfigs?.get(selectedModelName)
                            ?: selectedProvider?.config?.modelConfigs?.values?.firstOrNull {
                                it.displayName.isNotBlank() && it.displayName == selectedModelName
                            }
                        EntityIcon(selectedConfig?.icon, Modifier.fillMaxSize(),
                            text = modelInitial(selectedModelName),
                             matchName = selectedConfig?.displayName?.ifBlank { selectedModelName } ?: selectedModelName, model = true)
                        }
                        DropdownMenu(expanded = showMediaMenu, onDismissRequest = { showMediaMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Change $mediaLabel model") },
                                leadingIcon = { Icon(if (mediaKind == ModelType.IMAGE_GENERATION) Icons.Outlined.Image
                                    else Icons.Outlined.Videocam, null) },
                                onClick = { showMediaMenu = false; showModelPicker = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Back to Chat") },
                                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                                onClick = { showMediaMenu = false; mediaKind = null }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    showStop -> MaterialTheme.colorScheme.errorContainer
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            )
                            .clickable(
                                enabled = showStop || canSend,
                                onClick = {
                                    if (showStop) {
                                        onStopGenerating()
                                    } else if (canSend) {
                                        val submitted = editDraft?.let { draft ->
                                            onEditMessage(draft.messageId, textState, attachments)
                                        } ?: mediaKind?.let { kind ->
                                            onSendMedia(DirectMediaRequest(textState, kind, mediaProviderId, mediaModelId))
                                        } ?: onSendMessage(textState, attachments)
                                        if (submitted) {
                                            focusManager.clearFocus(force = true)
                                            textState = ""
                                            attachments = emptyList()
                                            if (editDraft != null) onCancelEdit()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Unable to submit. Check the selected model or wait for the current response and try again.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showStop) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop generation",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = when (mediaKind) {
                                    ModelType.IMAGE_GENERATION -> Icons.Outlined.Image
                                    ModelType.VIDEO_GENERATION -> Icons.Outlined.Videocam
                                    else -> Icons.Default.ArrowUpward
                                },
                                contentDescription = if (mediaKind != null) "Generate $mediaLabel" else "Send message",
                                tint = if (canSend) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddMenu) {
        FilesActionSheet(
            onPickImage = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickDocument = {
                documentPicker.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                )
            },
            onTakePhoto = { showFilesSheet = true },
            onCreateImage = { onCancelEdit(); mediaKind = ModelType.IMAGE_GENERATION },
            onCreateVideo = { onCancelEdit(); mediaKind = ModelType.VIDEO_GENERATION },
            onOpenConversationSummary = { showConversationSummary = true },
            onOpenTools = { showQuickActions = true },
            onDismiss = { showAddMenu = false }
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = if (mediaKind != null) mediaProviders else providers,
            providerGroups = providerGroups,
            collapsedGroupIdsState = modelPickerCollapsedGroups,
            collapsedProviderIdsState = modelPickerCollapsedProviders,
            onCollapsedGroupIdsChange = onModelPickerCollapsedGroupsChange,
            onCollapsedProviderIdsChange = onModelPickerCollapsedProvidersChange,
            selectedProviderId = if (mediaKind != null) mediaProviderId else selectedProviderId,
            selectedModelId = if (mediaKind != null) mediaModelId else selectedModelName,
            conversationReasoningEffort = if (mediaKind != null) null else conversationReasoningEffort,
            onSelectModel = { provider, model ->
                if (mediaKind != null) { mediaProviderId = provider; mediaModelId = model }
                else onSelectModel(provider, model)
            },
            onSetReasoningEffort = { if (mediaKind == null) onSetReasoningEffort(it) },
            dynamicModelsMap = if (mediaKind != null) emptyMap() else dynamicModelsMap,
            onDismiss = { showModelPicker = false }
        )
    }

    if (showQuickActions) {
        QuickActionsSheet(onDismiss = { showQuickActions = false })
    }

    if (showConversationSummary) {
        ConversationSummarySheet(
            onStart = onStartConversationSummary,
            onDismiss = { showConversationSummary = false }
        )
    }

    if (showFilesSheet) {
        AlertDialog(
            onDismissRequest = { showFilesSheet = false },
            title = { Text("Camera unavailable") },
            text = { Text("Camera capture is not connected yet. Use Photos or Files to attach existing media.") },
            confirmButton = {
                TextButton(onClick = { showFilesSheet = false }) { Text("OK") }
            }
        )
    }
}
