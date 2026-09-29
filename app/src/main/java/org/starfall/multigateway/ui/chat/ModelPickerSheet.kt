package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ModelType
import org.starfall.multigateway.data.model.ProviderType

sealed interface ModelPickerItem {
    data class Header(val providerId: String, val providerName: String) : ModelPickerItem
    data class Model(
        val provider: LlmProviderInfo,
        val modelId: String,
        val config: ModelConfiguration,
        val isSelected: Boolean
    ) : ModelPickerItem
}

fun matchesModel(selectedModelId: String, itemModelId: String, itemDisplayName: String): Boolean {
    if (selectedModelId.isBlank()) return false
    val s = selectedModelId.trim().lowercase()
    val m = itemModelId.trim().lowercase()
    val d = itemDisplayName.trim().lowercase()
    return m == s || d == s || m.endsWith("/$s") || s.endsWith("/$m") || m.substringAfterLast('/') == s.substringAfterLast('/')
}

fun computeModelPickerItems(
    providers: List<LlmProviderInfo>,
    dynamicModelsMap: Map<String, List<String>>,
    selectedProviderId: String,
    selectedModelId: String,
    query: String = "",
    providerFilterId: String? = null
): List<ModelPickerItem> {
    val visibleProviders = providers.mapNotNull { provider ->
        if (providerFilterId != null && provider.id != providerFilterId) return@mapNotNull null
        val models = providerModels(provider, dynamicModelsMap, selectedProviderId, selectedModelId)
            .filter { modelId ->
                query.isBlank() ||
                        modelId.contains(query, ignoreCase = true) ||
                        provider.config.modelConfigs[modelId]?.displayName.orEmpty()
                            .contains(query, ignoreCase = true) ||
                        provider.name.contains(query, ignoreCase = true)
            }
        if (models.isEmpty()) null else provider to models
    }

    val hasExactProviderMatch = visibleProviders.any { (provider, models) ->
        (selectedProviderId.isNotBlank() && provider.id == selectedProviderId) &&
                models.any { modelId ->
                    val config = provider.config.modelConfigs[modelId] ?: ModelConfiguration()
                    matchesModel(selectedModelId, modelId, config.displayName)
                }
    }

    return visibleProviders.flatMap { (provider, models) ->
        val header = ModelPickerItem.Header(provider.id, provider.name)
        val modelItems = models.map { modelId ->
            val config = provider.config.modelConfigs[modelId] ?: ModelConfiguration()
            val isSelected = if (hasExactProviderMatch) {
                provider.id == selectedProviderId && matchesModel(selectedModelId, modelId, config.displayName)
            } else {
                matchesModel(selectedModelId, modelId, config.displayName)
            }
            ModelPickerItem.Model(
                provider = provider,
                modelId = modelId,
                config = config,
                isSelected = isSelected
            )
        }
        listOf(header) + modelItems
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<LlmProviderInfo>,
    selectedProviderId: String,
    selectedModelId: String,
    conversationReasoningEffort: String?,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetReasoningEffort: (String?) -> Unit,
    dynamicModelsMap: Map<String, List<String>> = emptyMap(),
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var providerFilterId by remember { mutableStateOf<String?>(null) }

    val flatItems = remember(providers, dynamicModelsMap, selectedProviderId, selectedModelId, query, providerFilterId) {
        computeModelPickerItems(
            providers = providers,
            dynamicModelsMap = dynamicModelsMap,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            query = query,
            providerFilterId = providerFilterId
        )
    }

    val targetIndex = remember(flatItems) {
        flatItems.indexOfFirst {
            it is ModelPickerItem.Model && it.isSelected
        }.takeIf { it >= 0 } ?: 0
    }

    val listState = remember(targetIndex) {
        LazyListState(firstVisibleItemIndex = targetIndex)
    }

    LaunchedEffect(targetIndex) {
        if (targetIndex in flatItems.indices) {
            listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(query, providerFilterId) {
        if (query.isNotBlank() || providerFilterId != null) {
            listState.scrollToItem(0)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Enter model name to search") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (flatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching models",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    items(
                        flatItems,
                        key = { item ->
                            when (item) {
                                is ModelPickerItem.Header -> "header_${item.providerId}"
                                is ModelPickerItem.Model -> "model_${item.provider.id}_${item.modelId}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is ModelPickerItem.Header -> {
                                Text(
                                    text = item.providerName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp)
                                )
                            }
                            is ModelPickerItem.Model -> {
                                ModelPickerCard(
                                    modelId = item.modelId,
                                    config = item.config,
                                    isSelected = item.isSelected,
                                    conversationReasoningEffort = conversationReasoningEffort,
                                    onSetReasoningEffort = onSetReasoningEffort,
                                    onClick = {
                                        onSelectModel(item.provider.id, item.modelId)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "all-providers") {
                    FilterChip(
                        selected = providerFilterId == null,
                        onClick = { providerFilterId = null },
                        label = { Text("All") }
                    )
                }
                items(providers, key = { it.id }) { provider ->
                    FilterChip(
                        selected = providerFilterId == provider.id,
                        onClick = { providerFilterId = provider.id },
                        leadingIcon = {
                            ProviderMark(
                                provider = provider,
                                modelId = provider.config.modelIds?.firstOrNull().orEmpty(),
                                compact = true
                            )
                        },
                        label = {
                            Text(
                                text = provider.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerCard(
    modelId: String,
    config: ModelConfiguration,
    isSelected: Boolean,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = modelInitial(modelId),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = config.displayName.ifBlank { modelId },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    ModelCapabilityBadges(config)
                }

                if (isSelected) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Expanded section for currently selected model: Reasoning Effort (only if reasoning is supported/enabled)
            if (isSelected && config.supportsThinking) {
                val efforts = listOf<String?>(null, "low", "medium", "high", "xhigh")
                val labels = listOf("Default", "Low", "Medium", "High", "Extra high")
                val currentEffortNormalized = conversationReasoningEffort?.lowercase()?.trim()
                val initialIndex = efforts.indexOfFirst { it == currentEffortNormalized }.takeIf { it >= 0 } ?: 0
                var sliderValue by remember(conversationReasoningEffort) { mutableFloatStateOf(initialIndex.toFloat()) }
                val activeIndex = sliderValue.roundToInt().coerceIn(0, efforts.size - 1)
                val activeLabel = labels[activeIndex]

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Reasoning Effort",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = activeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        val step = it.roundToInt().coerceIn(0, efforts.size - 1)
                        onSetReasoningEffort(efforts[step])
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    labels.forEach { label ->
                        Text(
                            text = when (label) {
                                "Extra high" -> "X-High"
                                "Medium" -> "Med"
                                else -> label
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCapabilityBadges(config: ModelConfiguration) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModelBadge(
            label = when (config.modelType) {
                ModelType.TEXT_GENERATION -> "Chat"
                else -> config.modelType.displayName
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        if (config.supportsVision) {
            ModelBadge(
                label = "Image",
                icon = Icons.Outlined.Image,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
        if (config.supportsVideoInput) {
            ModelBadge(
                label = "Video",
                icon = Icons.Outlined.Videocam,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
        if (config.supportsAudioInput) {
            ModelBadge(
                label = "Audio",
                icon = Icons.Outlined.Audiotrack,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
        if (config.supportsThinking) {
            ModelBadge(
                label = "Thinking",
                icon = Icons.Outlined.Psychology,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
        if (config.supportsToolCalls) {
            ModelBadge(
                label = "Tools",
                icon = Icons.Outlined.Build,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun ModelBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    colors: ChipColors
) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, fontSize = 11.sp) },
        leadingIcon = icon?.let { imageVector ->
            { Icon(imageVector, contentDescription = null, modifier = Modifier.size(14.dp)) }
        },
        colors = colors,
        border = null,
        modifier = Modifier.height(28.dp)
    )
}

@Composable
private fun ProviderMark(
    provider: LlmProviderInfo,
    modelId: String,
    compact: Boolean = false
) {
    val mark = when (provider.type) {
        ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (modelId.startsWith(
                "o",
                ignoreCase = true
            )
        ) "◉" else "◎"

        ProviderType.GOOGLE -> "✦"
        ProviderType.ANTHROPIC -> "A"
        ProviderType.OLLAMA -> "◌"
        else -> provider.type.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    Text(
        text = mark,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 14.sp else 24.sp
        ),
        maxLines = 1
    )
}

private fun providerModels(
    provider: LlmProviderInfo,
    dynamicModelsMap: Map<String, List<String>>,
    selectedProviderId: String,
    selectedModelId: String
): List<String> = (
        provider.config.modelIds
            ?: ((dynamicModelsMap[provider.id]
                ?: defaultProviderModels(provider.type)) + provider.config.modelConfigs.keys)
        )
    .plus(if (provider.id == selectedProviderId && selectedModelId.isNotBlank()) listOf(selectedModelId) else emptyList())
    .distinct()

internal fun modelInitial(modelName: String): String {
    val source = if ('/' in modelName) modelName.substringAfterLast('/') else modelName
    return source.trim().firstOrNull()?.uppercaseChar()?.toString()
        ?: modelName.trim().firstOrNull()?.uppercaseChar()?.toString()
        ?: "?"
}

internal fun defaultProviderModels(type: ProviderType): List<String> = emptyList()
