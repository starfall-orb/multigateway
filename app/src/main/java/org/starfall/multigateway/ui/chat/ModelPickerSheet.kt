package org.starfall.multigateway.ui.chat
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ModelType
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.components.EntityIcon
import org.starfall.multigateway.ui.components.providerInitials

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

private val ModelPickerItem.folderId: String?
    get() = when (this) {
        is ModelPickerItem.Group -> group.id
        is ModelPickerItem.Provider -> provider.groupId.takeIf { depth == 1 }
        is ModelPickerItem.Model -> provider.groupId.takeIf { depth == 2 }
    }

// Each lazy row draws its part of one continuous folder outline, including the
// spacing between rows. Keeping rows lazy preserves scrolling to selected models.
private fun Modifier.folderOutline(first: Boolean, last: Boolean, color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val left = stroke / 2
    val right = size.width - stroke / 2
    val top = if (first) stroke / 2 else -4.dp.toPx()
    val bottom = if (last) size.height - stroke / 2 else size.height + 4.dp.toPx()
    val radius = 18.dp.toPx().coerceAtMost((bottom - top) / 2)
    // Draw the two sides independently. Joining the open segments with moveTo()
    // makes visible diagonal/kinked corners when LazyColumn spacing is present.
    drawLine(color, Offset(left, top + if (first) radius else 0f),
        Offset(left, bottom - if (last) radius else 0f), strokeWidth = stroke)
    drawLine(color, Offset(right, top + if (first) radius else 0f),
        Offset(right, bottom - if (last) radius else 0f), strokeWidth = stroke)
    if (first || last) {
        val path = Path().apply {
            if (first) {
                moveTo(left, top + radius)
                quadraticBezierTo(left, top, left + radius, top)
                lineTo(right - radius, top)
                quadraticBezierTo(right, top, right, top + radius)
            }
            if (last) {
                moveTo(left, bottom - radius)
                quadraticBezierTo(left, bottom, left + radius, bottom)
                lineTo(right - radius, bottom)
                quadraticBezierTo(right, bottom, right, bottom - radius)
            }
        }
        drawPath(path, color, style = Stroke(stroke))
    }
}

fun matchesModel(selectedModelId: String, itemModelId: String, itemDisplayName: String): Boolean {
    if (selectedModelId.isBlank()) return false
    val s = selectedModelId.trim().lowercase()
    val m = itemModelId.trim().lowercase()
    val d = itemDisplayName.trim().lowercase()
    return m == s || d == s || m.endsWith("/$s") || s.endsWith("/$m") ||
        m.substringAfterLast('/') == s.substringAfterLast('/')
}

internal val chatModelPickerFilter: (LlmProviderInfo, String, ModelConfiguration) -> Boolean = { _, _, config ->
    config.modelType == org.starfall.multigateway.data.model.ModelType.TEXT_GENERATION
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
    collapsedProviderIds: Set<String> = emptySet(),
    expandSearchResults: Boolean = true,
    modelFilter: (LlmProviderInfo, String, ModelConfiguration) -> Boolean = { _, _, _ -> true }
): List<ModelPickerItem> {
    data class ProviderNode(val provider: LlmProviderInfo, val models: List<String>)

    val defaultConfig = ModelConfiguration()
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
                modelFilter(provider, modelId, provider.config.modelConfigs[modelId] ?: defaultConfig)
            }
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
                val config = node.provider.config.modelConfigs[modelId] ?: defaultConfig
                matchesModel(selectedModelId, modelId, config.displayName)
            }
    }

    val forceExpanded = (normalizedQuery.isNotBlank() && expandSearchResults) || providerFilterId != null
    fun modelItems(node: ProviderNode, depth: Int): List<ModelPickerItem.Model> =
        node.models.map { modelId ->
            val config = node.provider.config.modelConfigs[modelId] ?: defaultConfig
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
                val groupCollapsed = !forceExpanded && group.id in collapsedGroupIds
                nodes.forEach { node ->
                    val models = modelItems(node, depth = 2)
                    if (!groupCollapsed || models.any { it.isSelected }) {
                        add(ModelPickerItem.Provider(node.provider, node.models.size, depth = 1))
                        addAll(if (groupCollapsed || (!forceExpanded && node.provider.id in collapsedProviderIds)) {
                            models.filter { it.isSelected }
                        } else models)
                    }
                }
            }

        visibleProviders
            .filter { it.provider.groupId == null || it.provider.groupId !in groupById }
            .forEach { node ->
                add(ModelPickerItem.Provider(node.provider, node.models.size, depth = 0))
                val models = modelItems(node, depth = 1)
                addAll(if (!forceExpanded && node.provider.id in collapsedProviderIds) {
                    models.filter { it.isSelected }
                } else models)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIdsState: Set<String> = emptySet(),
    collapsedProviderIdsState: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    selectedProviderId: String,
    selectedModelId: String,
    conversationReasoningEffort: String?,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetReasoningEffort: (String?) -> Unit,
    dynamicModelsMap: Map<String, List<String>> = emptyMap(),
    modelFilter: (LlmProviderInfo, String, ModelConfiguration) -> Boolean = { _, _, _ -> true },
    showReasoningEffort: Boolean = true,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var initialPositionApplied by remember { mutableStateOf(false) }
    var expandSearchResults by remember(query) { mutableStateOf(true) }
    val forceExpanded = query.isNotBlank() && expandSearchResults
    var collapsedGroupIds by remember { mutableStateOf(collapsedGroupIdsState) }
    var collapsedProviderIds by remember { mutableStateOf(collapsedProviderIdsState) }
    LaunchedEffect(collapsedGroupIdsState) {
        collapsedGroupIds = collapsedGroupIdsState
    }
    LaunchedEffect(collapsedProviderIdsState) {
        collapsedProviderIds = collapsedProviderIdsState
    }
    fun setCollapsedGroups(value: Set<String>) {
        initialPositionApplied = true
        collapsedGroupIds = value
        onCollapsedGroupIdsChange(value)
    }
    fun setCollapsedProviders(value: Set<String>) {
        initialPositionApplied = true
        collapsedProviderIds = value
        onCollapsedProviderIdsChange(value)
    }

    val flatItems = remember(
        providers,
        providerGroups,
        dynamicModelsMap,
        selectedProviderId,
        selectedModelId,
        query,
        expandSearchResults,
        collapsedGroupIds,
        collapsedProviderIds,
        modelFilter
    ) {
        computeModelPickerItems(
            providers = providers,
            providerGroups = providerGroups,
            dynamicModelsMap = dynamicModelsMap,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            query = query,
            expandSearchResults = expandSearchResults,
            collapsedGroupIds = collapsedGroupIds,
            collapsedProviderIds = collapsedProviderIds,
            modelFilter = modelFilter
        )
    }

    val targetIndex = remember(flatItems, selectedProviderId, selectedModelId) {
        val modelIndex = flatItems.indexOfFirst {
            it is ModelPickerItem.Model && it.isSelected
        }
        // Keep the selected model's visible parent headers in view when opening the picker.
        when {
            modelIndex < 0 -> 0
            flatItems.getOrNull(modelIndex - 1) is ModelPickerItem.Provider -> {
                if (flatItems.getOrNull(modelIndex - 2) is ModelPickerItem.Group) modelIndex - 2
                else modelIndex - 1
            }
            else -> modelIndex
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)
    LaunchedEffect(flatItems) {
        if (!initialPositionApplied && flatItems.isNotEmpty()) {
            initialPositionApplied = true
            if (query.isBlank() && !listState.isScrollInProgress) listState.scrollToItem(targetIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        initialPositionApplied = true
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            initialPositionApplied = true
            listState.scrollToItem(0)
        }
    }
    val allCollapsed = !forceExpanded &&
        providerGroups.all { it.id in collapsedGroupIds } &&
        providers.all { it.id in collapsedProviderIds }

    AppBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    enabled = providers.isNotEmpty() || providerGroups.isNotEmpty(),
                    onClick = {
                        expandSearchResults = false
                        if (allCollapsed) {
                            setCollapsedGroups(emptySet())
                            setCollapsedProviders(emptySet())
                        } else {
                            setCollapsedGroups(providerGroups.map { it.id }.toSet())
                            setCollapsedProviders(providers.map { it.id }.toSet())
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (allCollapsed) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
                        contentDescription = stringResource(
                            if (allCollapsed) R.string.expand_all_model_sections else R.string.collapse_all_model_sections
                        )
                    )
                }
                SelectableOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_models_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .testTag("model-picker-list"),
                contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (flatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
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
                    itemsIndexed(
                        flatItems,
                        key = { _, item ->
                                when (item) {
                                    is ModelPickerItem.Group -> "group_${item.group.id}"
                                    is ModelPickerItem.Provider -> "provider_${item.provider.id}"
                                    is ModelPickerItem.Model -> "model_${item.provider.id}_${item.modelId}"
                                }
                            }
                        ) { index, item ->
                            val folderId = item.folderId
                            val first = folderId != null && flatItems.getOrNull(index - 1)?.folderId != folderId
                            val last = folderId != null && flatItems.getOrNull(index + 1)?.folderId != folderId
                        Box(
                            Modifier.fillMaxWidth()
                                .animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .testTag("model-picker-frame_$index")
                                    .then(if (folderId != null) Modifier.folderOutline(
                                        first, last, MaterialTheme.colorScheme.outline
                                    ) else Modifier)
                                    .padding(
                                        start = 6.dp, end = 6.dp,
                                        top = if (first) 6.dp else 0.dp,
                                        bottom = if (last) 6.dp else 0.dp
                                    )
                            ) {
                            when (item) {
                                is ModelPickerItem.Group -> {
                                    val collapsed = item.group.id in collapsedGroupIds &&
                                        !forceExpanded
                                    ModelPickerGroupRow(
                                        group = item.group,
                                        collapsed = collapsed,
                                        onToggle = {
                                            expandSearchResults = false
                                            setCollapsedGroups(
                                                if (collapsed) collapsedGroupIds - item.group.id
                                                else collapsedGroupIds + item.group.id
                                            )
                                        }
                                    )
                                }

                                is ModelPickerItem.Provider -> {
                                    val collapsed = item.provider.id in collapsedProviderIds &&
                                        !forceExpanded
                                    ModelPickerProviderRow(
                                        provider = item.provider,
                                        collapsed = collapsed,
                                        onToggle = {
                                            expandSearchResults = false
                                            setCollapsedProviders(
                                                if (collapsed) collapsedProviderIds - item.provider.id
                                                else collapsedProviderIds + item.provider.id
                                            )
                                        }
                                    )
                                }

                                is ModelPickerItem.Model -> {
                                    Box(Modifier.fillMaxWidth().testTag("model-picker-model_${item.provider.id}_${item.modelId}")) {
                                        ModelPickerCard(
                                            modelId = item.modelId,
                                            config = item.config,
                                            isSelected = item.isSelected,
                                            conversationReasoningEffort = conversationReasoningEffort,
                                            onSetReasoningEffort = onSetReasoningEffort,
                                            showReasoningEffort = showReasoningEffort,
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
            }
        }
    }
}

@Composable
private fun ModelPickerGroupRow(
    group: ProviderGroup,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val toggleDescription = stringResource(
        if (collapsed) R.string.expand_group else R.string.collapse_group, group.name
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        ).testTag("model-picker-group_${group.id}")
            .semantics { contentDescription = toggleDescription }
            .clickable(onClick = onToggle)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntityIcon(
                image = group.icon,
                modifier = Modifier.size(24.dp),
                fallback = if (collapsed) Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                matchName = group.name
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group.name,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(32.dp))
        }
    }
}

@Composable
private fun ModelPickerProviderRow(
    provider: LlmProviderInfo,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
            .testTag("model-picker-provider_${provider.id}")
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
                compact = true
            )
            Spacer(Modifier.width(8.dp))
            Text(
                provider.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium
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
    showReasoningEffort: Boolean,
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

            // Keep the control available when the conversation disables reasoning on a thinking model.
            if (showReasoningEffort && isSelected && config.supportsThinking) {
                val activeLabel = if (reasoningEffortEnabled(conversationReasoningEffort)) {
                    reasoningEffortLabels[reasoningEffortIndex(conversationReasoningEffort)]
                } else "Off"

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

                ReasoningEffortControl(
                    effort = conversationReasoningEffort,
                    onEffortChange = onSetReasoningEffort
                )
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
            icon = when (config.modelType) {
                ModelType.TEXT_GENERATION -> Icons.Outlined.ChatBubbleOutline
                ModelType.IMAGE_GENERATION -> Icons.Outlined.Image
                ModelType.VIDEO_GENERATION -> Icons.Outlined.Videocam
                else -> Icons.Outlined.Audiotrack
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        if (config.modelType != ModelType.TEXT_GENERATION) return@Row
        ModelBadge(
            label = compactContextWindow(config.contextWindowTokens),
            icon = Icons.Outlined.Memory,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        if (config.supportsVision) {
            ModelBadge(
                label = "Image",
                iconOnly = true,
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
                iconOnly = true,
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
                iconOnly = true,
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
                iconOnly = true,
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
                iconOnly = true,
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

private fun compactContextWindow(tokens: Int): String {
    val count = tokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val divisor = when {
        count >= 1_000_000 -> 1_000_000
        count >= 1_000 -> 1_000
        else -> return count.toString()
    }
    val value = java.math.BigDecimal(count).divide(java.math.BigDecimal(divisor), 1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    return value + if (divisor == 1_000_000) "M" else "k"
}

@Composable
private fun ModelBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconOnly: Boolean = false,
    colors: ChipColors = AssistChipDefaults.assistChipColors()
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.containerColor,
        contentColor = colors.labelColor,
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon?.let { Icon(it, contentDescription = if (iconOnly) label else null, modifier = Modifier.size(14.dp)) }
            if (!iconOnly) Text(label, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ProviderMark(
    provider: LlmProviderInfo,
    compact: Boolean = false
) {
    val mark = providerInitials(provider.name)
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
