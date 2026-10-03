package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ModelType
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.components.EntityIcon

sealed interface ModelPickerItem {
    data class Group(
        val group: ProviderGroup,
        val providerCount: Int
    ) : ModelPickerItem

    data class Provider(
        val provider: LlmProviderInfo,
        val modelCount: Int,
        val depth: Int
    ) : ModelPickerItem

    data class Model(
        val provider: LlmProviderInfo,
        val modelId: String,
        val config: ModelConfiguration,
        val isSelected: Boolean,
        val depth: Int
    ) : ModelPickerItem
}

fun matchesModel(selectedModelId: String, itemModelId: String, itemDisplayName: String): Boolean {
    if (selectedModelId.isBlank()) return false
    val s = selectedModelId.trim().lowercase()
    val m = itemModelId.trim().lowercase()
    val d = itemDisplayName.trim().lowercase()
    return m == s || d == s || m.endsWith("/$s") || s.endsWith("/$m") ||
        m.substringAfterLast('/') == s.substringAfterLast('/')
}

fun computeModelPickerItems(
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup>,
    dynamicModelsMap: Map<String, List<String>>,
    selectedProviderId: String,
    selectedModelId: String,
    query: String = "",
    providerFilterId: String? = null,
    collapsedGroupIds: Set<String> = emptySet(),
    collapsedProviderIds: Set<String> = emptySet()
): List<ModelPickerItem> {
    data class ProviderNode(val provider: LlmProviderInfo, val models: List<String>)

    val normalizedQuery = query.trim()
    val groupById = providerGroups.associateBy { it.id }
    val visibleProviders = providers.mapNotNull { provider ->
        if (providerFilterId != null && provider.id != providerFilterId) return@mapNotNull null

        val groupMatches = provider.groupId
            ?.let(groupById::get)
            ?.name
            ?.contains(normalizedQuery, ignoreCase = true) == true
        val providerMatches = provider.name.contains(normalizedQuery, ignoreCase = true)
        val models = providerModels(provider, dynamicModelsMap, selectedProviderId, selectedModelId)
            .filter { modelId ->
                normalizedQuery.isBlank() ||
                    groupMatches ||
                    providerMatches ||
                    modelId.contains(normalizedQuery, ignoreCase = true) ||
                    provider.config.modelConfigs[modelId]?.displayName.orEmpty()
                        .contains(normalizedQuery, ignoreCase = true)
            }

        if (models.isEmpty()) null else ProviderNode(provider, models)
    }

    val hasExactProviderMatch = visibleProviders.any { node ->
        selectedProviderId.isNotBlank() &&
            node.provider.id == selectedProviderId &&
            node.models.any { modelId ->
                val config = node.provider.config.modelConfigs[modelId] ?: ModelConfiguration()
                matchesModel(selectedModelId, modelId, config.displayName)
            }
    }

    val forceExpanded = normalizedQuery.isNotBlank() || providerFilterId != null
    fun modelItems(node: ProviderNode, depth: Int): List<ModelPickerItem> =
        node.models.map { modelId ->
            val config = node.provider.config.modelConfigs[modelId] ?: ModelConfiguration()
            val isSelected = if (hasExactProviderMatch) {
                node.provider.id == selectedProviderId &&
                    matchesModel(selectedModelId, modelId, config.displayName)
            } else {
                matchesModel(selectedModelId, modelId, config.displayName)
            }
            ModelPickerItem.Model(node.provider, modelId, config, isSelected, depth)
        }

    return buildList {
        providerGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
            .forEach { group ->
                val nodes = visibleProviders.filter { it.provider.groupId == group.id }
                if (nodes.isEmpty()) return@forEach

                add(ModelPickerItem.Group(group, nodes.size))
                if (!forceExpanded && group.id in collapsedGroupIds) return@forEach

                nodes.forEach { node ->
                    add(ModelPickerItem.Provider(node.provider, node.models.size, depth = 1))
                    if (forceExpanded || node.provider.id !in collapsedProviderIds) {
                        addAll(modelItems(node, depth = 2))
                    }
                }
            }

        visibleProviders
            .filter { it.provider.groupId == null || it.provider.groupId !in groupById }
            .forEach { node ->
                add(ModelPickerItem.Provider(node.provider, node.models.size, depth = 0))
                if (forceExpanded || node.provider.id !in collapsedProviderIds) {
                    addAll(modelItems(node, depth = 1))
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup> = emptyList(),
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
    var collapsedGroupIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsedProviderIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val flatItems = remember(
        providers,
        providerGroups,
        dynamicModelsMap,
        selectedProviderId,
        selectedModelId,
        query,
        providerFilterId,
        collapsedGroupIds,
        collapsedProviderIds
    ) {
        computeModelPickerItems(
            providers = providers,
            providerGroups = providerGroups,
            dynamicModelsMap = dynamicModelsMap,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            query = query,
            providerFilterId = providerFilterId,
            collapsedGroupIds = collapsedGroupIds.toSet(),
            collapsedProviderIds = collapsedProviderIds.toSet()
        )
    }

    val targetIndex = remember(flatItems, selectedProviderId, selectedModelId) {
        flatItems.indexOfFirst {
            it is ModelPickerItem.Model && it.isSelected
        }.takeIf { it >= 0 } ?: 0
    }
    val listState = remember(targetIndex) {
        LazyListState(firstVisibleItemIndex = targetIndex)
    }

    LaunchedEffect(query, providerFilterId) {
        if (query.isNotBlank() || providerFilterId != null) listState.scrollToItem(0)
    }

    AppBottomSheet(
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
                placeholder = { Text(stringResource(R.string.search_models_placeholder)) },
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (flatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_matching_models),
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
                                is ModelPickerItem.Group -> "group_${item.group.id}"
                                is ModelPickerItem.Provider -> "provider_${item.provider.id}"
                                is ModelPickerItem.Model -> "model_${item.provider.id}_${item.modelId}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is ModelPickerItem.Group -> {
                                val collapsed = item.group.id in collapsedGroupIds &&
                                    query.isBlank() && providerFilterId == null
                                ModelPickerGroupRow(
                                    group = item.group,
                                    providerCount = item.providerCount,
                                    collapsed = collapsed,
                                    onToggle = {
                                        collapsedGroupIds =
                                            if (item.group.id in collapsedGroupIds) collapsedGroupIds - item.group.id
                                            else collapsedGroupIds + item.group.id
                                    }
                                )
                            }

                            is ModelPickerItem.Provider -> {
                                val collapsed = item.provider.id in collapsedProviderIds &&
                                    query.isBlank() && providerFilterId == null
                                ModelPickerProviderRow(
                                    provider = item.provider,
                                    modelCount = item.modelCount,
                                    depth = item.depth,
                                    collapsed = collapsed,
                                    selected = item.provider.id == selectedProviderId,
                                    onToggle = {
                                        collapsedProviderIds =
                                            if (item.provider.id in collapsedProviderIds) collapsedProviderIds - item.provider.id
                                            else collapsedProviderIds + item.provider.id
                                    }
                                )
                            }

                            is ModelPickerItem.Model -> {
                                Box(Modifier.padding(start = (item.depth * 18).dp)) {
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
                        label = { Text(stringResource(R.string.common_all)) }
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
private fun ModelPickerGroupRow(
    group: ProviderGroup,
    providerCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (collapsed) R.string.expand_group else R.string.collapse_group, group.name),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                group.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                providerCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelPickerProviderRow(
    provider: LlmProviderInfo,
    modelCount: Int,
    depth: Int,
    collapsed: Boolean,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp)
            .clickable(onClick = onToggle)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (collapsed) R.string.expand_group else R.string.collapse_group, provider.name),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            ProviderMark(
                provider = provider,
                modelId = provider.config.modelIds?.firstOrNull().orEmpty(),
                compact = true
            )
            Spacer(Modifier.width(8.dp))
            Text(
                provider.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                modelCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                EntityIcon(config.icon, Modifier.size(52.dp), text = modelInitial(modelId), matchName = config.displayName.ifBlank { modelId }, model = true)

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
                val efforts = listOf<String?>("none", null, "low", "medium", "high", "xhigh")
                val labels = listOf("Off", "Auto", "Low", "Medium", "High", "Extra high")
                val currentEffortNormalized = conversationReasoningEffort?.lowercase()?.trim()
                val initialIndex = efforts.indexOfFirst { it == currentEffortNormalized }.takeIf { it >= 0 } ?: 1
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
                    valueRange = 0f..5f,
                    steps = 4,
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
internal fun ModelCapabilityBadges(config: ModelConfiguration) {
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
            icon = Icons.Outlined.ChatBubbleOutline,
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
    EntityIcon(provider.icon, Modifier.size(if (compact) 22.dp else 42.dp), text = mark, matchName = provider.name)
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