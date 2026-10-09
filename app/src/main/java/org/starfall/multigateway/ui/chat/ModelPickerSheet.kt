package org.starfall.multigateway.ui.chat
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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

private data class ModelPickerTab(
    val key: String,
    val folderId: String?,
    val label: String,
    val ungroupedOnly: Boolean = false
)

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
    selectedFolderId: String? = null,
    ungroupedOnly: Boolean = false,
    modelFilter: (LlmProviderInfo, String, ModelConfiguration) -> Boolean = { _, _, _ -> true }
): List<ModelPickerItem> {
    data class ProviderNode(val provider: LlmProviderInfo, val models: List<String>)

    val defaultConfig = ModelConfiguration()
    val normalizedQuery = query.trim()
    val groupById = providerGroups.associateBy { it.id }
    val providerOrder = compareBy<LlmProviderInfo> { it.sortOrder }.thenBy { it.id }
    val orderedProviders = when {
        selectedFolderId != null -> providers
            .filter { it.groupId == selectedFolderId }
            .sortedWith(providerOrder)
        ungroupedOnly -> providers
            .filter { it.groupId == null || it.groupId !in groupById }
            .sortedWith(providerOrder)
        else -> buildList {
            providerGroups
                .sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
                .forEach { group ->
                    addAll(providers.filter { it.groupId == group.id }.sortedWith(providerOrder))
                }
            addAll(
                providers
                    .filter { it.groupId == null || it.groupId !in groupById }
                    .sortedWith(providerOrder)
            )
        }
    }
    val visibleProviders = orderedProviders.mapNotNull { provider ->
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

    // The selection belongs to one provider. Only fall back to matching by model name alone
    // when no provider was recorded (or it no longer exists); otherwise models that merely
    // share a name with the selected one under other providers would also look selected.
    val selectedProviderKnown = selectedProviderId.isNotBlank() && providers.any { it.id == selectedProviderId }

    val forceExpanded = (normalizedQuery.isNotBlank() && expandSearchResults) || providerFilterId != null
    fun modelItems(node: ProviderNode, depth: Int): List<ModelPickerItem.Model> =
        node.models.map { modelId ->
            val config = node.provider.config.modelConfigs[modelId] ?: defaultConfig
            val isSelected = (!selectedProviderKnown || node.provider.id == selectedProviderId) &&
                matchesModel(selectedModelId, modelId, config.displayName)
            ModelPickerItem.Model(node.provider, modelId, config, isSelected, depth)
        }

    return buildList {
        visibleProviders.forEach { node ->
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
    var expandSearchResults by remember(query) { mutableStateOf(true) }
    val forceExpanded = query.isNotBlank() && expandSearchResults
    var collapsedGroupIds by remember { mutableStateOf(collapsedGroupIdsState) }
    var collapsedProviderIds by remember { mutableStateOf(collapsedProviderIdsState) }
    val defaultTabLabel = stringResource(R.string.common_default)
    val ungroupedTabLabel = stringResource(R.string.ungrouped)
    val folderTabs = remember(providers, providerGroups, defaultTabLabel, ungroupedTabLabel) {
        val groupsById = providerGroups.associateBy { it.id }
        val providerGroupIds = providers.mapNotNull { it.groupId }.filter { it in groupsById }.toSet()
        val hasUngroupedProviders = providers.any { provider ->
            provider.groupId == null || provider.groupId !in groupsById
        }
        buildList {
            add(ModelPickerTab(key = "default", folderId = null, label = defaultTabLabel))
            providerGroups
                .asSequence()
                .filter { it.id in providerGroupIds }
                .sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
                .forEach { group ->
                    add(ModelPickerTab(key = "folder_${group.id}", folderId = group.id, label = group.name))
                }
            if (hasUngroupedProviders) {
                add(ModelPickerTab(key = "ungrouped", folderId = null, label = ungroupedTabLabel, ungroupedOnly = true))
            }
        }
    }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    // Keep a separate LazyListState for every tab. Reusing one state here makes
    // Compose restore the previous tab's position (or jump to its initial row)
    // whenever the tab content is replaced.
    val tabListStates = remember { mutableMapOf<String, LazyListState>() }
    val initializedTabKeys = remember { mutableSetOf<String>() }
    val tabPositionsBeforeSearch = remember { mutableMapOf<String, Pair<Int, Int>>() }
    // Switching tabs replaces most rows at once while the list is also repositioned. Fading the old
    // rows out during that jump can leave them stuck on screen, so rows are swapped instantly.
    var instantTabSwap by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTabIndex) {
        if (instantTabSwap) {
            delay(400)
            instantTabSwap = false
        }
    }
    LaunchedEffect(folderTabs) {
        selectedTabIndex = selectedTabIndex.coerceIn(0, (folderTabs.size - 1).coerceAtLeast(0))
    }
    val selectedTab = folderTabs.getOrNull(selectedTabIndex)
    val selectedTabKey = selectedTab?.key ?: "default"
    val selectedFolderId = selectedTab?.folderId
    val ungroupedOnly = selectedTab?.ungroupedOnly == true
    LaunchedEffect(collapsedGroupIdsState) {
        collapsedGroupIds = collapsedGroupIdsState
    }
    LaunchedEffect(collapsedProviderIdsState) {
        collapsedProviderIds = collapsedProviderIdsState
    }
    fun setCollapsedGroups(value: Set<String>) {
        collapsedGroupIds = value
        onCollapsedGroupIdsChange(value)
    }
    fun setCollapsedProviders(value: Set<String>) {
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
        selectedFolderId,
        ungroupedOnly,
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
            selectedFolderId = selectedFolderId,
            ungroupedOnly = ungroupedOnly,
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
    val listState = remember(selectedTabKey) {
        tabListStates.getOrPut(selectedTabKey) { LazyListState(targetIndex) }
    }
    LaunchedEffect(selectedTabKey, flatItems, query, targetIndex) {
        if (query.isBlank() && flatItems.isNotEmpty() &&
            initializedTabKeys.add(selectedTabKey) && !listState.isScrollInProgress
        ) {
            listState.scrollToItem(targetIndex)
        }
    }
    LaunchedEffect(query, selectedTabKey) {
        if (query.isNotBlank()) {
            tabPositionsBeforeSearch.putIfAbsent(
                selectedTabKey,
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            )
            listState.scrollToItem(0)
        } else {
            tabPositionsBeforeSearch.remove(selectedTabKey)?.let { (index, offset) ->
                listState.scrollToItem(index, offset)
            }
        }
    }
    val allCollapsed = !forceExpanded && providers.all { it.id in collapsedProviderIds }

    AppBottomSheet(
        onDismissRequest = onDismiss,
        fixedHeightFraction = 0.75f
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

            if (folderTabs.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    edgePadding = 12.dp,
                    containerColor = Color.Transparent,
                    divider = {}
                ) {
                    folderTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                if (selectedTabIndex != index) instantTabSwap = true
                                selectedTabIndex = index
                            },
                            modifier = Modifier.testTag("model-picker-tab_${tab.key}"),
                            text = {
                                Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                    fadeInSpec = if (instantTabSwap) null else spring<Float>(stiffness = Spring.StiffnessMediumLow),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    fadeOutSpec = if (instantTabSwap) null else spring<Float>(stiffness = Spring.StiffnessMediumLow)
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
                                is ModelPickerItem.Group -> Unit

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
                                            providerType = item.provider.type,
                                            maxTokens = item.provider.config.maxTokens,
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
internal fun SelectedModelOverviewSheet(
    provider: LlmProviderInfo?,
    folderName: String?,
    modelId: String,
    config: ModelConfiguration,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    onSetSendThinkingContent: ((Boolean) -> Unit)? = null,
    onOpenModelPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModelPickerCard(
                modelId = modelId,
                providerType = provider?.type,
                maxTokens = provider?.config?.maxTokens ?: Int.MAX_VALUE,
                config = config,
                isSelected = true,
                providerName = provider?.name,
                folderName = folderName,
                conversationReasoningEffort = conversationReasoningEffort,
                onSetReasoningEffort = onSetReasoningEffort,
                showReasoningEffort = true,
                onClick = onOpenModelPicker
            )
            if (config.supportsThinking && !config.reasoningDisabled && onSetSendThinkingContent != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = config.sendThinkingContent,
                                role = Role.Switch,
                                onValueChange = onSetSendThinkingContent
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("selected-model-passback-thinking"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(R.string.send_thinking_content_back),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = config.sendThinkingContent, onCheckedChange = null)
                    }
                }
            }
            Text(
                "Tap the model card to choose another model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    providerType: ProviderType?,
    maxTokens: Int,
    config: ModelConfiguration,
    isSelected: Boolean,
    providerName: String? = null,
    folderName: String? = null,
    conversationReasoningEffort: String?,
    onSetReasoningEffort: (String?) -> Unit,
    showReasoningEffort: Boolean,
    onClick: () -> Unit
) {
    val effortOptions = remember(providerType, modelId, maxTokens) {
        reasoningEffortOptions(providerType, modelId, maxTokens)
    }
    var previewReasoningValue by remember(conversationReasoningEffort, isSelected, effortOptions) {
        mutableFloatStateOf(reasoningEffortIndex(conversationReasoningEffort, effortOptions).toFloat())
    }
    val maximumThinking = isSelected && showReasoningEffort && config.supportsThinking && !config.reasoningDisabled &&
        previewReasoningValue.roundToInt() >= effortOptions.lastIndex
    val cardTint by animateFloatAsState(
        if (maximumThinking) 1f else 0f,
        tween(900, easing = FastOutSlowInEasing),
        label = "thinkingCardTint"
    )
    val cardBrush = maximumThinkingCardBrush(MaterialTheme.colorScheme.surfaceContainerLow)
    val cardBurst = rememberThinkingColorBurst()
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
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
                .onGloballyPositioned { cardOrigin = it.positionInRoot() }
                .drawBehind {
                    revealThinkingColor(cardBurst) { drawRect(cardBrush, alpha = cardTint) }
                    drawThinkingColorBurst(cardBurst)
                }
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntityIcon(config.icon, Modifier.size(40.dp), text = modelInitial(modelId), matchName = config.displayName.ifBlank { modelId }, model = true)

                Spacer(Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = config.displayName.ifBlank { modelId },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.displayName.isNotBlank() && config.displayName != modelId) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!providerName.isNullOrBlank() || !folderName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            providerName?.takeIf { it.isNotBlank() }?.let {
                                ModelContextChip(
                                    text = it,
                                    modifier = Modifier.weight(1f),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            folderName?.takeIf { it.isNotBlank() }?.let {
                                ModelContextChip(
                                    text = "Group: $it",
                                    modifier = Modifier.weight(1f),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    ModelCapabilityBadges(config)
                }

                if (isSelected) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Shown only while reasoning is enabled in the model configuration. A conversation's own
            // "Off" choice keeps the slider so it can be turned back up.
            if (showReasoningEffort && isSelected && config.supportsThinking && !config.reasoningDisabled) {
                val activeLabel = effortOptions[previewReasoningValue.roundToInt().coerceIn(effortOptions.indices)].label

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(8.dp))

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
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (usesThinkingBudget(providerType, modelId))
                                "Thinking Budget (tokens)" else "Reasoning Effort",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
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
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                ReasoningEffortControl(
                    effort = conversationReasoningEffort,
                    onEffortChange = onSetReasoningEffort,
                    onPreviewValueChange = { previewReasoningValue = it },
                    options = effortOptions,
                    onColorBurst = { origin, maximum ->
                        if (maximum) cardBurst.start(origin - cardOrigin, Color(0xFFA78BFA))
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelContextChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = containerColor.copy(alpha = 0.78f),
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            icon?.let { Icon(it, contentDescription = if (iconOnly) label else null, modifier = Modifier.size(12.dp)) }
            if (!iconOnly) Text(label, fontSize = 10.sp)
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
