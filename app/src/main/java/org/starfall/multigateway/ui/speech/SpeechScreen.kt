package org.starfall.multigateway.ui.speech

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ModelType
import org.starfall.multigateway.data.model.SpeechService
import java.util.UUID
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved

private data class SpeechEditor(val service: SpeechService, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechScreen(
    speechServices: List<SpeechService>,
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    providers: List<LlmProviderInfo>,
    selectedSpeechServiceId: String?,
    onSelectService: (String?) -> Unit,
    onSaveService: (SpeechService) -> Unit,
    onDeleteService: (String) -> Unit,
    onReorderServices: (List<String>) -> Unit,
    onTestVoice: (SpeechService, String) -> Unit,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<SpeechEditor?>(null) }
    var deletingServiceId by remember { mutableStateOf<String?>(null) }
    var orderedServices by remember(speechServices) { mutableStateOf(speechServices) }
    val providersById = remember(providers) { providers.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Speech Services",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            "Use Android TTS or TTS models from Providers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                            contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View"
                        )
                    }
                    IconButton(
                        onClick = {
                            editor = SpeechEditor(SpeechService(
                                id = UUID.randomUUID().toString(),
                                name = "Custom TTS",
                                provider = "system",
                                modelId = null,
                                voice = "Default",
                                speed = 1.0f,
                                pitch = 1.0f
                            ), true)
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add speech service")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isGridView) 2 else 1),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            itemsIndexed(orderedServices, key = { _, item -> item.id }) { index, service ->
                val providerName = when {
                    service.provider.equals("system", ignoreCase = true) -> "Android System TTS"
                    else -> providersById[service.provider]?.name ?: service.provider
                }
                SpeechServiceUnifiedCard(
                    service = service,
                    isGrid = isGridView,
                    modifier = Modifier
                        .animateItem(
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .longPressReorder(
                            index = index,
                            itemCount = orderedServices.size,
                            columns = if (isGridView) 2 else 1,
                            onMove = { from, to -> orderedServices = orderedServices.moved(from, to) },
                            onDrop = { onReorderServices(orderedServices.map { it.id }) }
                        ),
                    providerName = providerName,
                    selected = service.id == selectedSpeechServiceId ||
                        (selectedSpeechServiceId == null && service.provider.equals("system", true)),
                    onSelect = { onSelectService(service.id) },
                    onTest = {
                        onTestVoice(
                            service,
                            "Hello! This is a preview of the ${service.name} voice."
                        )
                    },
                    onEdit = { editor = SpeechEditor(service, false) },
                    onDelete = { deletingServiceId = service.id }
                )
            }
        }
    }

    editor?.let { page ->
        AddOrEditSpeechDialog(
            initialService = page.service,
            isNew = page.isNew,
            providers = providers,
            onTest = onTestVoice,
            onDismiss = {
                editor = null
            },
            onSave = { saved ->
                onSaveService(saved)
                if (selectedSpeechServiceId == null && !saved.provider.equals("system", true)) {
                    onSelectService(saved.id)
                }
                editor = null
            }
        )
    }

    deletingServiceId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingServiceId = null },
            title = { Text("Delete Speech Service") },
            text = { Text("Are you sure you want to remove this TTS configuration?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteService(id)
                        if (selectedSpeechServiceId == id) onSelectService(null)
                        deletingServiceId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingServiceId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SpeechServiceUnifiedCard(
    service: SpeechService,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    providerName: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val shapeCorner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "speechShapeCorner"
    )

    Surface(
        shape = RoundedCornerShape(shapeCorner),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        MorphingCardLayout(
            isGrid = isGrid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTest, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Test voice",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    ItemOverflowMenu(
                        onEdit = onEdit,
                        onDelete = onDelete,
                        deleteColor = MaterialTheme.colorScheme.error
                    )
                }
            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            service.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        providerName,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val modelLine = service.modelId?.let { "Model: $it · " }.orEmpty()
                    Text(
                        "${modelLine}Voice: ${service.voice}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }
}

@Composable
private fun AddOrEditSpeechDialog(
    initialService: SpeechService,
    isNew: Boolean,
    providers: List<LlmProviderInfo>,
    onTest: (SpeechService, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SpeechService) -> Unit
) {
    val ttsModelsByProvider = remember(providers) {
        providers.associate { provider ->
            provider.id to provider.config.modelConfigs
                .filterValues { it.modelType == ModelType.TEXT_TO_SPEECH }
                .keys
                .toList()
        }.filterValues { it.isNotEmpty() }
    }

    var name by remember(initialService.id) { mutableStateOf(initialService.name) }
    var providerId by remember(initialService.id) {
        mutableStateOf(
            initialService.provider.takeIf {
                it.equals("system", true) || it in ttsModelsByProvider
            } ?: "system"
        )
    }
    var modelId by remember(initialService.id) {
        mutableStateOf(initialService.modelId)
    }
    var voice by remember(initialService.id) { mutableStateOf(initialService.voice) }
    var speed by remember(initialService.id) { mutableFloatStateOf(initialService.speed) }
    var pitch by remember(initialService.id) { mutableFloatStateOf(initialService.pitch) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val availableModels = ttsModelsByProvider[providerId].orEmpty()
    LaunchedEffect(providerId) {
        if (!providerId.equals("system", true) && modelId !in availableModels) {
            modelId = availableModels.firstOrNull()
        }
        if (providerId.equals("system", true)) modelId = null
    }

    fun currentService() = initialService.copy(
        name = name.trim(),
        provider = providerId,
        modelId = modelId,
        voice = voice.trim().ifEmpty {
            if (providerId.equals("system", true)) "Default" else "alloy"
        },
        speed = speed,
        pitch = pitch
    )

    val canSave = name.isNotBlank() &&
        (providerId.equals("system", true) || !modelId.isNullOrBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add Speech Service" else "Edit Speech Service") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Service name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedButton(
                        onClick = { providerExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (providerId.equals("system", true)) {
                                "Android System TTS"
                            } else {
                                providers.find { it.id == providerId }?.name ?: providerId
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Android System TTS") },
                            onClick = {
                                providerId = "system"
                                providerExpanded = false
                            }
                        )
                        providers.filter { it.id in ttsModelsByProvider }.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    providerId = provider.id
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }

                if (!providerId.equals("system", true)) {
                    Box {
                        OutlinedButton(
                            onClick = { modelExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(modelId ?: "Select TTS model", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            availableModels.forEach { id ->
                                val displayName = providers
                                    .find { it.id == providerId }
                                    ?.config
                                    ?.modelConfigs
                                    ?.get(id)
                                    ?.displayName
                                    ?.takeIf { it.isNotBlank() }
                                DropdownMenuItem(
                                    text = { Text(displayName ?: id) },
                                    onClick = {
                                        modelId = id
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else if (ttsModelsByProvider.isEmpty()) {
                    Text(
                        "No Provider models are marked as Text to speech yet. Configure a model type in Providers to use it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = {
                        Text(
                            if (providerId.equals("system", true)) {
                                "Voice"
                            } else {
                                "Voice ID (for example: alloy)"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Speed: ${String.format("%.1f", speed)}x")
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.5f..2.0f
                )

                if (providerId.equals("system", true)) {
                    Text("Pitch: ${String.format("%.1f", pitch)}x")
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = 0.5f..2.0f
                    )
                }

                OutlinedButton(
                    onClick = {
                        onTest(currentService(), "Testing speech voice output.")
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Test Voice")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(currentService()) },
                enabled = canSave
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
