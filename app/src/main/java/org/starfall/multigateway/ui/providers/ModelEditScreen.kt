package org.starfall.multigateway.ui.providers
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.starfall.multigateway.ui.chat.RenderCodeBlock
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.starfall.multigateway.data.local.preferences.ModelConfigurationMemory
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.ModelConfigDialog
import org.starfall.multigateway.ui.components.IconPickerRow
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditScreen(
    provider: LlmProviderInfo,
    initialModelId: String,
    existingModelIds: Set<String>,
    onSave: (String, ModelConfiguration) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelMemory = remember(context) { ModelConfigurationMemory(context) }
    var modelId by remember { mutableStateOf(initialModelId) }
    var config by remember { mutableStateOf(provider.config.modelConfigs[initialModelId] ?: ModelConfiguration()) }
    var contextWindowText by remember { mutableStateOf(config.contextWindowTokens.toString()) }
    val contextWindow = contextWindowText.toIntOrNull()?.takeIf { it > 0 }
    var iconImporting by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var showSampling by remember { mutableStateOf(false) }
    var modelJsonExpanded by remember { mutableStateOf(false) }
    val trimmedId = modelId.trim()
    val modelJson = if (initialModelId.isNotBlank() && trimmedId == initialModelId) config.modelJson else JsonObject(emptyMap())
    val prettyJson = remember { Json { prettyPrint = true } }
    val modelJsonCode = remember(modelJson) { prettyJson.encodeToString(JsonObject.serializer(), modelJson) }
    LaunchedEffect(trimmedId) {
        if (initialModelId.isBlank()) modelMemory.get(trimmedId)?.let { remembered ->
            config = remembered.copy(displayName = config.displayName, icon = config.icon)
            contextWindowText = remembered.contextWindowTokens.toString()
        }
    }
    val idRequired = stringResource(R.string.model_id_required)
    val idWhitespace = stringResource(R.string.model_id_no_whitespace)
    val idDuplicate = stringResource(R.string.model_id_duplicate)
    val idApiHint = stringResource(R.string.model_id_api_hint)
    val idError = when {
        trimmedId.isBlank() -> idRequired
        trimmedId.any { it.isWhitespace() } -> idWhitespace
        trimmedId != initialModelId && trimmedId in existingModelIds -> idDuplicate
        else -> null
    }

    fun saveAndBack() {
        if (idError == null && contextWindow != null) {
            modelMemory.remember(trimmedId, config.copy(contextWindowTokens = contextWindow!!))
            onSave(
                trimmedId,
                config.copy(
                    contextWindowTokens = contextWindow!!,
                    modelJson = modelJson,
                    displayName = config.displayName.trim(),
                    reasoningEffort = config.reasoningEffort?.trim()?.ifEmpty { null }
                ).normalizedForStorage()
            )
            onBack()
        }
    }

    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (initialModelId.isBlank()) R.string.add_model_title else R.string.edit_model_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(onClick = ::saveAndBack, enabled = idError == null && contextWindow != null && !iconImporting) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconPickerRow(
                config.icon,
                onChange = { config = config.copy(icon = it) },
                text = org.starfall.multigateway.ui.chat.modelInitial(modelId),
                onBusyChange = { iconImporting = it },
                matchName = config.displayName.ifBlank { modelId },
                model = true,
                faviconBaseUrl = provider.baseUrl
            )
            SelectableOutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = { Text(stringResource(R.string.model_id)) },
                supportingText = { Text(idError ?: idApiHint) },
                isError = idError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SelectableOutlinedTextField(
                value = config.displayName,
                onValueChange = { config = config.copy(displayName = it) },
                label = { Text(stringResource(R.string.display_name)) },
                supportingText = { Text(stringResource(R.string.model_display_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                SelectableOutlinedTextField(
                    value = config.modelType.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.model_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    ModelType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                config = config.copy(modelType = type)
                                typeExpanded = false
                            }
                        )
                    }
                }
            }
            if (config.modelType == ModelType.TEXT_GENERATION) {
                SelectableOutlinedTextField(
                    value = contextWindowText,
                    onValueChange = { if (it.all(Char::isDigit)) contextWindowText = it },
                    label = { Text(stringResource(R.string.context_window)) },
                    supportingText = if (contextWindow == null) ({ Text(stringResource(R.string.context_window_invalid)) }) else null,
                    isError = contextWindow == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Text(stringResource(R.string.capabilities), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.input_media), style = MaterialTheme.typography.bodyMedium)
                MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        checked = config.supportsVision,
                        onCheckedChange = { config = config.copy(supportsVision = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_image)) }
                    )
                    SegmentedButton(
                        checked = config.supportsVideoInput,
                        onCheckedChange = { config = config.copy(supportsVideoInput = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        icon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_video)) }
                    )
                    SegmentedButton(
                        checked = config.supportsAudioInput,
                        onCheckedChange = { config = config.copy(supportsAudioInput = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        icon = { Icon(Icons.Outlined.Audiotrack, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_audio)) }
                    )
                }
                ModelCapabilitySwitch(stringResource(R.string.model_thinking), config.supportsThinking) {
                    config = config.copy(supportsThinking = it)
                }
                if (config.supportsThinking) {
                    Text(stringResource(R.string.reasoning_effort), style = MaterialTheme.typography.titleMedium)
                    val mode = if (config.reasoningDisabled) 2 else if (config.reasoningEffort == null) 0 else 1
                    val modeLabels = listOf(
                        stringResource(R.string.common_auto),
                        stringResource(R.string.common_on),
                        stringResource(R.string.common_off)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("reasoning-mode")) {
                        modeLabels.forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = mode == index,
                                onClick = {
                                    config = config.copy(reasoningEffort = when (index) {
                                        0 -> null
                                        1 -> config.reasoningEffort?.takeUnless { config.reasoningDisabled } ?: "medium"
                                        else -> "none"
                                    })
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3)
                            ) {
                                Text(label)
                            }
                        }
                    }
                    if (mode == 1) {
                        val efforts = listOf("low", "medium", "high", "xhigh")
                        val effortLabels = listOf(
                            stringResource(R.string.common_low),
                            stringResource(R.string.common_medium),
                            stringResource(R.string.common_high),
                            stringResource(R.string.common_extra_high)
                        )
                        val index = efforts.indexOf(config.reasoningEffort).coerceAtLeast(0)
                        Text(effortLabels[index], style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = index.toFloat(),
                            onValueChange = {
                                config = config.copy(reasoningEffort = efforts[it.roundToInt().coerceIn(0, 3)])
                            },
                            valueRange = 0f..3f,
                            steps = 2
                        )
                    }
                    if (!config.reasoningDisabled) {
                        ModelCapabilitySwitch(stringResource(R.string.send_thinking_content_back), config.sendThinkingContent) {
                            config = config.copy(sendThinkingContent = it)
                        }
                    }
                }
                ModelCapabilitySwitch(stringResource(R.string.tool_calls), config.supportsToolCalls) {
                    config = config.copy(supportsToolCalls = it)
                }
                HorizontalDivider()
                Text(stringResource(R.string.streaming), style = MaterialTheme.typography.titleMedium)
                val streamOptions = listOf(null, true, false)
                val streamLabels = listOf(
                    stringResource(R.string.common_default),
                    stringResource(R.string.common_on),
                    stringResource(R.string.common_off)
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("streaming-mode")) {
                    streamOptions.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = config.supportStream == value,
                            onClick = { config = config.copy(supportStream = value) },
                            shape = SegmentedButtonDefaults.itemShape(index, streamOptions.size)
                        ) {
                            Text(streamLabels[index])
                        }
                    }
                }
                OutlinedButton(onClick = { showSampling = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sampling_settings))
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().testTag("model-json-toggle")
                            .clickable { modelJsonExpanded = !modelJsonExpanded }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (modelJsonExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_json), style = MaterialTheme.typography.titleMedium)
                    }
                    if (modelJsonExpanded) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).testTag("model-json-code")) {
                            RenderCodeBlock(language = "json", code = modelJsonCode, isStreaming = false)
                        }
                    }
                }
            }
        }
    }
    if (showSampling) {
        ModelConfigDialog(
            provider = provider.copy(
                config = provider.config.copy(
                    modelConfigs = provider.config.modelConfigs + (initialModelId to config)
                )
            ),
            modelId = initialModelId,
            onSave = { config = it },
            onDismiss = { showSampling = false }
        )
    }
}

@Composable
private fun ModelCapabilitySwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
