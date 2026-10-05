package org.starfall.multigateway.ui.providers

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import org.starfall.multigateway.ui.components.windowHeightIn

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.util.UUID
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.DiscoveredModel
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderRootOrderItem
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.defaultAuthorization
import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.components.EntityIcon
import org.starfall.multigateway.ui.components.FadeGridListContent
import org.starfall.multigateway.ui.components.WordWrappedFadeText
import org.starfall.multigateway.ui.components.providerInitials
import org.starfall.multigateway.ui.components.IconPickerRow
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.AdaptiveCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent


private data class ProviderEditor(val provider: LlmProviderInfo, val isNew: Boolean)
private data class DraggedProvider(val provider: LlmProviderInfo, val bounds: Rect, val compact: Boolean)

private const val UNGROUPED_SECTION = "__ungrouped__"
internal fun newProviderId(): String = "custom_${UUID.randomUUID()}"
private val ProviderListCardHeight = 88.dp
private val ProviderGridCardHeight = 152.dp

private sealed interface ProviderRootItem {
    val id: String
    val sortOrder: Int

    data class GroupItem(val group: ProviderGroup) : ProviderRootItem {
        override val id: String get() = group.id
        override val sortOrder: Int get() = group.sortOrder
    }

    data class ProviderItem(val provider: LlmProviderInfo) : ProviderRootItem {
        override val id: String get() = provider.id
        override val sortOrder: Int get() = provider.sortOrder
    }
}

private fun providerRootItems(
    providers: List<LlmProviderInfo>,
    groups: List<ProviderGroup>
): List<ProviderRootItem> {
    val groupIds = groups.mapTo(hashSetOf()) { it.id }
    return buildList {
        groups.forEach { add(ProviderRootItem.GroupItem(it)) }
        providers
            .filter { it.groupId == null || it.groupId !in groupIds }
            .forEach { add(ProviderRootItem.ProviderItem(it)) }
    }.sortedWith(
        compareBy<ProviderRootItem> { it.sortOrder }
            .thenBy { if (it is ProviderRootItem.GroupItem) 0 else 1 }
            .thenBy { it.id }
    )
}

private fun ProviderRootItem.toOrderItem(): ProviderRootOrderItem =
    ProviderRootOrderItem(id = id, isGroup = this is ProviderRootItem.GroupItem)

private fun Rect.containsPoint(point: Offset): Boolean =
    point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

internal fun nearestProviderSlot(
    providerId: String,
    sectionIds: List<String>,
    bounds: Map<String, Rect>,
    visualCenter: Offset,
): String? = sectionIds
    .mapNotNull { id -> bounds[id]?.let { rect -> id to (rect.center - visualCenter).getDistance() } }
    .minByOrNull { it.second }
    ?.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderScreen(
    providers: List<LlmProviderInfo>,
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedSectionsState: Set<String> = emptySet(),
    onCollapsedSectionsChange: (Set<String>) -> Unit = {},
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    onSaveProvider: (LlmProviderInfo) -> Unit,
    onSaveGroup: (ProviderGroup) -> Unit = {},
    onDeleteGroup: (String) -> Unit = {},
    onMoveProviderToGroup: (String, String?) -> Unit = { _, _ -> },
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit,
    onReorderModels: (String, List<String>) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onReorderProviders: (List<String>) -> Unit,
    onReorderRootItems: (List<ProviderRootOrderItem>) -> Unit = {},
    onAuthorizeProvider: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onCancelProviderAuthorization: ((LlmProviderInfo) -> Unit)? = null,
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<DiscoveredModel>)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editor by remember { mutableStateOf<ProviderEditor?>(null) }
    var deletingProviderId by remember { mutableStateOf<String?>(null) }
    var orderedProviders by remember { mutableStateOf(providers) }
    var rootItems by remember { mutableStateOf(providerRootItems(providers, providerGroups)) }
    var pendingProviderOrder by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var pendingRootOrder by remember { mutableStateOf<List<ProviderRootOrderItem>?>(null) }
    var collapsedSections by remember { mutableStateOf(collapsedSectionsState) }
    var pendingCollapsedSections by remember { mutableStateOf<Set<String>?>(null) }
    var groupToRename by remember { mutableStateOf<ProviderGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<ProviderGroup?>(null) }
    var movingProvider by remember { mutableStateOf<LlmProviderInfo?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    val groupCardBounds = remember { mutableMapOf<String, Rect>() }
    val providerCardBounds = remember { mutableMapOf<String, Rect>() }
    var draggedProvider by remember { mutableStateOf<DraggedProvider?>(null) }
    var dragOverlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var dragRootSnapshot by remember { mutableStateOf<List<ProviderRootItem>?>(null) }
    var dragProviderSnapshot by remember { mutableStateOf<List<LlmProviderInfo>?>(null) }
    var folderReorderCandidate by remember { mutableStateOf<String?>(null) }
    var liveOrderChanged by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val sortedGroups = remember(providerGroups) {
        providerGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val validGroupIds = remember(providerGroups) { providerGroups.mapTo(hashSetOf()) { it.id } }

    fun createProvider(groupId: String? = null) {
        editor = ProviderEditor(
            LlmProviderInfo(
                id = newProviderId(),
                name = "",
                type = ProviderType.OPENAI,
                baseUrl = "",
                auth = ProviderType.OPENAI.defaultAuthorization(),
                groupId = groupId
            ),
            true
        )
    }

    fun setCollapsedSections(value: Set<String>) {
        collapsedSections = value
        pendingCollapsedSections = value
        onCollapsedSectionsChange(value)
    }

    fun toggleSection(id: String) {
        setCollapsedSections(
            if (id in collapsedSections) collapsedSections - id else collapsedSections + id
        )
    }

    fun moveWithinGroup(groupId: String, from: Int, to: Int) {
        val currentSection = orderedProviders.filter { it.groupId == groupId }
        val fromId = currentSection.getOrNull(from)?.id ?: return
        val toId = currentSection.getOrNull(to)?.id ?: return
        val fromGlobal = orderedProviders.indexOfFirst { it.id == fromId }
        val toGlobal = orderedProviders.indexOfFirst { it.id == toId }
        if (fromGlobal >= 0 && toGlobal >= 0) {
            orderedProviders = orderedProviders.moved(fromGlobal, toGlobal)
        }
    }

    fun persistRootOrder() {
        val order = rootItems.map { it.toOrderItem() }
        pendingRootOrder = order
        onReorderRootItems(order)
    }

    fun persistProviderOrder(groupId: String) {
        val order = orderedProviders.filter { it.groupId == groupId }.map { it.id }
        pendingProviderOrder = groupId to order
        onReorderProviders(order)
    }

    fun dropProvider(provider: LlmProviderInfo, point: Offset) {
        draggedProvider = null
        folderReorderCandidate = null
        dragRootSnapshot = null
        dragProviderSnapshot = null
        val sourceGroupId = provider.groupId?.takeIf { it in validGroupIds }
        val targetGroupId = groupCardBounds.entries.firstOrNull { it.value.containsPoint(point) }?.key
        // Folder membership takes priority over reordering, including leaving a folder.
        if (targetGroupId != sourceGroupId) {
            if (liveOrderChanged) {
                if (sourceGroupId == null) persistRootOrder()
                else persistProviderOrder(sourceGroupId)
            }
            onMoveProviderToGroup(provider.id, targetGroupId)
            return
        }
        if (liveOrderChanged) {
            if (sourceGroupId == null) persistRootOrder()
            else persistProviderOrder(sourceGroupId)
            return
        }
        val target = orderedProviders.firstOrNull {
            it.id != provider.id && it.groupId?.takeIf { id -> id in validGroupIds } == sourceGroupId &&
                providerCardBounds[it.id]?.containsPoint(point) == true
        } ?: return
        if (sourceGroupId != null) {
            val members = orderedProviders.filter { it.groupId == sourceGroupId }
            moveWithinGroup(sourceGroupId, members.indexOfFirst { it.id == provider.id }, members.indexOfFirst { it.id == target.id })
            persistProviderOrder(sourceGroupId)
        } else {
            rootItems = rootItems.moved(rootItems.indexOfFirst { it.id == provider.id }, rootItems.indexOfFirst { it.id == target.id })
            persistRootOrder()
        }
    }

    fun providerDragModifier(provider: LlmProviderInfo): Modifier = Modifier
        .testTag("provider_${provider.id}")
        .providerDrag(
            providerId = provider.id,
            onBoundsChanged = { bounds ->
                if (bounds == null) providerCardBounds.remove(provider.id)
                else providerCardBounds[provider.id] = bounds
            },
            onDrag = { bounds, point ->
                if (draggedProvider == null) {
                    dragRootSnapshot = rootItems
                    dragProviderSnapshot = orderedProviders
                    liveOrderChanged = false
                }
                val sourceGroup = provider.groupId?.takeIf { it in validGroupIds }
                val compact = draggedProvider?.compact ?: (!isGridView && sourceGroup != null &&
                    orderedProviders.firstOrNull { it.groupId == sourceGroup }?.id == provider.id)
                draggedProvider = DraggedProvider(provider, bounds, compact)
                val insideFolder = groupCardBounds.entries.firstOrNull { it.value.containsPoint(point) }?.key
                val margin = with(density) { 36.dp.toPx() }
                folderReorderCandidate = if (sourceGroup == null && insideFolder == null) {
                    groupCardBounds.entries.filter { it.value.inflate(margin).containsPoint(point) }
                        .minByOrNull { (it.value.center - point).getDistance() }?.key
                } else null
                if (insideFolder == sourceGroup) {
                    val sectionIds = if (sourceGroup != null) {
                        orderedProviders.filter { it.groupId == sourceGroup }.map { it.id }
                    } else {
                        rootItems.filterIsInstance<ProviderRootItem.ProviderItem>().map { it.id }
                    }
                    val targetId = nearestProviderSlot(
                        providerId = provider.id,
                        sectionIds = sectionIds,
                        bounds = providerCardBounds,
                        visualCenter = bounds.center
                    )
                    if (targetId != null && targetId != provider.id) {
                        if (sourceGroup != null) {
                            val members = orderedProviders.filter { it.groupId == sourceGroup }
                            val from = members.indexOfFirst { it.id == provider.id }
                            val to = members.indexOfFirst { it.id == targetId }
                            if (from >= 0 && to >= 0 && from != to) {
                                moveWithinGroup(sourceGroup, from, to)
                                liveOrderChanged = true
                            }
                        } else {
                            val from = rootItems.indexOfFirst { it.id == provider.id }
                            val to = rootItems.indexOfFirst { it.id == targetId }
                            // A provider cannot displace a folder until the explicit outside dwell.
                            if (from >= 0 && to >= 0 && from != to &&
                                rootItems.subList(minOf(from, to), maxOf(from, to) + 1)
                                    .none { it is ProviderRootItem.GroupItem }
                            ) {
                                rootItems = rootItems.moved(from, to)
                                liveOrderChanged = true
                            }
                        }
                    }
                }
            },
            onDrop = { dropProvider(provider, it) },
            onCancel = {
                dragRootSnapshot?.let { rootItems = it }
                dragProviderSnapshot?.let { orderedProviders = it }
                draggedProvider = null
                folderReorderCandidate = null
                dragRootSnapshot = null
                dragProviderSnapshot = null
            }
        )

    LaunchedEffect(folderReorderCandidate) {
        val groupId = folderReorderCandidate ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1_000)
        val provider = draggedProvider?.provider ?: return@LaunchedEffect
        val from = rootItems.indexOfFirst { it is ProviderRootItem.ProviderItem && it.id == provider.id }
        val to = rootItems.indexOfFirst { it is ProviderRootItem.GroupItem && it.id == groupId }
        if (from >= 0 && to >= 0 && from != to) {
            rootItems = rootItems.moved(from, to)
            liveOrderChanged = true
        }
    }

    LaunchedEffect(providers, providerGroups, draggedProvider, draggedGroupId) {
        if (draggedProvider != null || draggedGroupId != null) return@LaunchedEffect

        val incomingProvidersById = providers.associateBy { it.id }
        val pendingProvider = pendingProviderOrder
        if (pendingProvider == null) {
            orderedProviders = providers
        } else {
            val (groupId, expectedOrder) = pendingProvider
            val actualOrder = providers.filter { it.groupId == groupId }.map { it.id }
            if (actualOrder == expectedOrder) {
                pendingProviderOrder = null
                orderedProviders = providers
            } else {
                val currentIds = orderedProviders.mapTo(hashSetOf()) { it.id }
                orderedProviders =
                    orderedProviders.mapNotNull { incomingProvidersById[it.id] } +
                        providers.filter { it.id !in currentIds }
            }
        }

        val incomingRootItems = providerRootItems(providers, providerGroups)
        val pendingRoot = pendingRootOrder
        if (pendingRoot == null) {
            rootItems = incomingRootItems
        } else if (incomingRootItems.map { it.toOrderItem() } == pendingRoot) {
            pendingRootOrder = null
            rootItems = incomingRootItems
        } else {
            val incomingByKey = incomingRootItems.associateBy { it.toOrderItem() }
            val currentKeys = rootItems.mapTo(hashSetOf()) { it.toOrderItem() }
            rootItems =
                rootItems.mapNotNull { incomingByKey[it.toOrderItem()] } +
                    incomingRootItems.filter { it.toOrderItem() !in currentKeys }
        }
    }

    LaunchedEffect(collapsedSectionsState) {
        val pending = pendingCollapsedSections
        when {
            pending == null -> collapsedSections = collapsedSectionsState
            collapsedSectionsState == pending -> pendingCollapsedSections = null
        }
    }
    LaunchedEffect(validGroupIds) {
        val cleaned = collapsedSections.filterTo(linkedSetOf()) { it in validGroupIds }
        if (cleaned != collapsedSections) setCollapsedSections(cleaned)
        groupCardBounds.keys.toList().filterNot { it in validGroupIds }.forEach(groupCardBounds::remove)
    }

    SlideScreenContent(
        editor = editor,
        label = stringResource(R.string.provider_editor)
    ) { page ->
        if (page != null) {
            ProviderEditScreen(
                initialProvider = page.provider,
                isNew = page.isNew,
                onAuthorizeProvider = onAuthorizeProvider,
                onCancelProviderAuthorization = onCancelProviderAuthorization,
                onClearOAuthCredentials = onClearOAuthCredentials,
                onTestConnection = onTestConnection,
                onFetchModels = onFetchModels,
                onSaveModels = onSaveModels,
                onReorderModels = onReorderModels,
                onDismiss = { editor = null },
                onSave = { saved ->
                    onSaveProvider(saved)
                    editor = ProviderEditor(saved, false)
                    Toast.makeText(context, context.getString(R.string.provider_saved), Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.providers_title),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = stringResource(R.string.providers_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { creatingGroup = true }) {
                                Icon(
                                    Icons.Outlined.CreateNewFolder,
                                    contentDescription = stringResource(R.string.add_provider_group)
                                )
                            }
                            IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                                Icon(
                                    imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                    contentDescription = stringResource(
                                        if (isGridView) R.string.switch_to_list_view else R.string.switch_to_grid_view
                                    )
                                )
                            }
                            IconButton(onClick = { createProvider() }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_provider))
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onGloballyPositioned { dragOverlayOrigin = it.positionInRoot() }
                ) {
                    if (providers.isEmpty() && providerGroups.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.no_providers_added),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        FadeGridListContent(
                            isGrid = isGridView,
                            modifier = Modifier.fillMaxSize()
                        ) { gridMode ->
                            LazyVerticalGrid(
                            columns = GridCells.Fixed(if (gridMode) 2 else 1),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().testTag("provider_list")
                        ) {
                            rootItems.forEachIndexed { rootIndex, rootItem ->
                                when (rootItem) {
                                    is ProviderRootItem.GroupItem -> {
                                        val group = rootItem.group
                                        val groupProviders = orderedProviders.filter { it.groupId == group.id }
                                        val expanded = group.id !in collapsedSections
                                        item(
                                            key = "group_${group.id}",
                                            span = { GridItemSpan(if (expanded) maxLineSpan else 1) }
                                        ) {
                                            // Lazy-grid placement animation belongs on the item
                                            // wrapper, not inside the expanded/collapsed content.
                                            Box(Modifier
                                                .animateItem(
                                                    placementSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                )
                                                .zIndex(if (draggedGroupId == group.id) 100f else 0f)) {
                                            if (expanded) {
                                                ProviderGroupExpandedContainer(
                                                    group = group,
                                                    providers = groupProviders,
                                                    isGrid = gridMode,
                                                    modifier = Modifier.testTag("provider_group_${group.id}"),
                                                    headerDragModifier = Modifier.longPressReorder(
                                                        index = rootIndex,
                                                        itemCount = rootItems.size,
                                                        columns = if (gridMode) 2 else 1,
                                                        onMove = { from, to ->
                                                            rootItems = rootItems.moved(from, to)
                                                        },
                                                        onDrop = ::persistRootOrder,
                                                        onDraggingChanged = { dragging -> draggedGroupId = group.id.takeIf { dragging } }
                                                    ),
                                                    onBoundsChanged = { bounds ->
                                                        if (bounds == null) groupCardBounds.remove(group.id)
                                                        else groupCardBounds[group.id] = bounds
                                                    },
                                                    onCollapse = { toggleSection(group.id) },
                                                    onAddProvider = { createProvider(group.id) },
                                                    onEditGroup = { groupToRename = group },
                                                    onDeleteGroup = { deletingGroup = group },
                                                    onEditProvider = { provider -> editor = ProviderEditor(provider, false) },
                                                    onMoveProvider = { provider -> movingProvider = provider },
                                                    onDeleteProvider = { provider -> deletingProviderId = provider.id },
                                                    providerDragModifier = ::providerDragModifier
                                                )
                                            } else {
                                                ProviderGroupCollapsedCard(
                                                    group = group,
                                                    isGrid = gridMode,
                                                    modifier = Modifier
                                                        .testTag("provider_group_${group.id}")
                                                        .longPressReorder(
                                                            index = rootIndex,
                                                            itemCount = rootItems.size,
                                                            columns = if (gridMode) 2 else 1,
                                                            onMove = { from, to ->
                                                                rootItems = rootItems.moved(from, to)
                                                            },
                                                            onDrop = ::persistRootOrder,
                                                            onDraggingChanged = { dragging -> draggedGroupId = group.id.takeIf { dragging } }
                                                        ),
                                                    onBoundsChanged = { bounds ->
                                                        if (bounds == null) groupCardBounds.remove(group.id)
                                                        else groupCardBounds[group.id] = bounds
                                                    },
                                                    onOpen = { toggleSection(group.id) }
                                                )
                                            }
                                            }
                                        }
                                    }

                                    is ProviderRootItem.ProviderItem -> {
                                        val provider = rootItem.provider
                                        item(key = "provider_${provider.id}") {
                                            ProviderUnifiedCard(
                                                provider = provider,
                                                isGrid = gridMode,
                                                modifier = Modifier
                                                    .animateItem(
                                                        placementSpec = spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                    .then(providerDragModifier(provider)),
                                                onEdit = { editor = ProviderEditor(provider, false) },
                                                onMoveToGroup = { movingProvider = provider },
                                                onDelete = { deletingProviderId = provider.id }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                    draggedProvider?.let { lifted ->
                        ProviderUnifiedCard(
                            provider = lifted.provider,
                            isGrid = isGridView,
                            compact = lifted.compact,
                            modifier = Modifier
                                .testTag("dragged_provider")
                                .zIndex(100f)
                                .requiredSize(
                                    with(density) { lifted.bounds.width.toDp() },
                                    with(density) { lifted.bounds.height.toDp() }
                                )
                                .graphicsLayer {
                                    translationX = lifted.bounds.left - dragOverlayOrigin.x
                                    translationY = lifted.bounds.top - dragOverlayOrigin.y
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                    shadowElevation = 10.dp.toPx()
                                    shape = RoundedCornerShape(if (isGridView) 20.dp else 16.dp)
                                },
                            onEdit = {}, onMoveToGroup = {}, onDelete = {}
                        )
                    }
                }
            }

            if (creatingGroup) {
                ProviderGroupNameDialog(
                    title = stringResource(R.string.new_provider_group),
                    initialName = "",
                    initialIcon = null,
                    onDismiss = { creatingGroup = false },
                    onSave = { name, icon ->
                        onSaveGroup(
                            ProviderGroup(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                sortOrder = (rootItems.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                                icon = icon
                            )
                        )
                        creatingGroup = false
                    }
                )
            }

            groupToRename?.let { group ->
                ProviderGroupNameDialog(
                    title = stringResource(R.string.edit_provider_group),
                    initialName = group.name,
                    initialIcon = group.icon,
                    onDismiss = { groupToRename = null },
                    onSave = { name, icon ->
                        onSaveGroup(group.copy(name = name, icon = icon))
                        groupToRename = null
                    }
                )
            }

            deletingGroup?.let { group ->
                AlertDialog(
                    onDismissRequest = { deletingGroup = null },
                    title = { Text(stringResource(R.string.delete_provider_group)) },
                    text = { Text(stringResource(R.string.delete_provider_group_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                onDeleteGroup(group.id)
                                setCollapsedSections(collapsedSections - group.id)
                                deletingGroup = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.common_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletingGroup = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            movingProvider?.let { provider ->
                ProviderGroupPickerDialog(
                    provider = provider,
                    groups = sortedGroups,
                    onDismiss = { movingProvider = null },
                    onSelect = { groupId ->
                        onMoveProviderToGroup(provider.id, groupId)
                        movingProvider = null
                    }
                )
            }

            if (deletingProviderId != null) {
                AlertDialog(
                    onDismissRequest = { deletingProviderId = null },
                    title = { Text(stringResource(R.string.delete_provider_title)) },
                    text = { Text(stringResource(R.string.delete_provider_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                deletingProviderId?.let { onDeleteProvider(it) }
                                deletingProviderId = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.common_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletingProviderId = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderGroupCollapsedCard(
    group: ProviderGroup,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect?) -> Unit,
    onOpen: () -> Unit
) {
    DisposableEffect(group.id) {
        onDispose { onBoundsChanged(null) }
    }
    val corner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "providerGroupCorner"
    )
    val height = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight

    Surface(
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                onBoundsChanged(
                    Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height
                    )
                )
            }
            .clickable(onClick = onOpen)
    ) {
        if (isGrid) {
            Box(Modifier.fillMaxSize().padding(14.dp)) {
                EntityIcon(
                    image = group.icon,
                    modifier = Modifier.size(42.dp).align(Alignment.TopStart),
                    fallback = Icons.Outlined.Folder,
                    matchName = group.name
                )
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        } else {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntityIcon(
                    image = group.icon,
                    modifier = Modifier.size(42.dp),
                    fallback = Icons.Outlined.Folder,
                    matchName = group.name
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ProviderGroupExpandedContainer(
    group: ProviderGroup,
    providers: List<LlmProviderInfo>,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    headerDragModifier: Modifier = Modifier,
    onBoundsChanged: (Rect?) -> Unit,
    onCollapse: () -> Unit,
    onAddProvider: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onEditProvider: (LlmProviderInfo) -> Unit,
    onMoveProvider: (LlmProviderInfo) -> Unit,
    onDeleteProvider: (LlmProviderInfo) -> Unit,
    providerDragModifier: (LlmProviderInfo) -> Modifier
) {
    DisposableEffect(group.id) {
        onDispose { onBoundsChanged(null) }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                onBoundsChanged(
                    Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height
                    )
                )
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = headerDragModifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(onClick = onCollapse)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onAddProvider,
                    modifier = Modifier.size(36.dp).testTag("add_provider_to_group_${group.id}")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_provider),
                        modifier = Modifier.size(20.dp)
                    )
                }
                ProviderGroupOverflowMenu(onEditGroup, onDeleteGroup)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ProviderGroupItemsLayout(
                isGrid = isGrid,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                key("group-icon") {
                    ProviderGroupIconTile(
                        group = group,
                        modifier = Modifier.testTag("provider_group_icon_${group.id}"),
                        tileHeight = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight,
                        fillFrame = isGrid,
                        onClick = onCollapse
                    )
                }
                providers.forEachIndexed { index, provider ->
                    key(provider.id) {
                        ProviderUnifiedCard(
                            provider = provider,
                            isGrid = isGrid,
                            compact = !isGrid && index == 0,
                            modifier = Modifier.then(providerDragModifier(provider)),
                            onEdit = { onEditProvider(provider) },
                            onMoveToGroup = { onMoveProvider(provider) },
                            onDelete = { onDeleteProvider(provider) }
                        )
                    }
                }
            }
            if (providers.isEmpty()) {
                Text(
                    stringResource(R.string.empty_provider_group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Keeps every provider under the same layout parent. Moving a provider between nested Rows used to
 * dispose and recreate its pointer input midway through a drag, which cancelled the gesture and
 * also made the placement animation start from the wrong item.
 */
@Composable
private fun ProviderGroupItemsLayout(
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val horizontalGap = 12.dp
    val verticalGap = if (isGrid) 12.dp else 8.dp
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val fullWidth = constraints.maxWidth
        val firstWidth = if (isGrid) (fullWidth - hGap).coerceAtLeast(0) / 2
            else ProviderListCardHeight.roundToPx().coerceAtMost(fullWidth)
        val secondWidth = (fullWidth - firstWidth - hGap).coerceAtLeast(0)
        val placeables = measurables.mapIndexed { index, measurable ->
            val width = when {
                isGrid -> firstWidth
                index == 0 -> firstWidth
                index == 1 -> secondWidth
                else -> fullWidth
            }
            val maxHeight = if (!isGrid && index <= 1) {
                ProviderListCardHeight.roundToPx().coerceAtMost(constraints.maxHeight)
            } else constraints.maxHeight
            measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = maxHeight))
        }
        val rows = placeables.chunked(2).takeIf { isGrid }
        val height = if (isGrid) {
            rows.orEmpty().sumOf { row -> row.maxOf { it.height } } +
                vGap * (rows.orEmpty().size - 1).coerceAtLeast(0)
        } else {
            val firstRowHeight = placeables.take(2).maxOfOrNull { it.height } ?: 0
            firstRowHeight + placeables.drop(2).sumOf { it.height } +
                vGap * (placeables.size - 2).coerceAtLeast(0)
        }
        layout(fullWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            if (isGrid) {
                var y = 0
                placeables.chunked(2).forEach { row ->
                    row.forEachIndexed { column, placeable ->
                        placeable.placeRelative(column * (firstWidth + hGap), y)
                    }
                    y += row.maxOf { it.height } + vGap
                }
            } else {
                val firstRowHeight = placeables.take(2).maxOfOrNull { it.height } ?: 0
                placeables.getOrNull(0)?.placeRelative(0, 0)
                placeables.getOrNull(1)?.placeRelative(firstWidth + hGap, 0)
                var y = firstRowHeight + vGap
                placeables.drop(2).forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + vGap
                }
            }
        }
    }
}

@Composable
private fun ProviderGroupIconTile(
    group: ProviderGroup,
    modifier: Modifier = Modifier,
    tileHeight: Dp = ProviderGridCardHeight,
    fillFrame: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .height(tileHeight)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            EntityIcon(
                image = group.icon,
                modifier = if (fillFrame) Modifier.fillMaxSize() else Modifier.size(72.dp),
                fallback = Icons.Outlined.Folder,
                matchName = group.name
            )
        }
    }
}

@Composable
private fun ProviderGroupOverflowMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_group), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}

@Composable
private fun ProviderGroupNameDialog(
    title: String,
    initialName: String,
    initialIcon: String?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var icon by remember(initialIcon) { mutableStateOf(initialIcon) }
    var iconImporting by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconPickerRow(
                    image = icon,
                    onChange = { icon = it },
                    fallback = Icons.Outlined.Folder,
                    onBusyChange = { iconImporting = it },
                    matchName = trimmed.ifBlank { name }
                )
                SelectableOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.provider_group_name)) },
                    supportingText = {
                        if (name.isNotEmpty() && trimmed.isEmpty()) {
                            Text(stringResource(R.string.provider_group_name_required))
                        }
                    },
                    isError = name.isNotEmpty() && trimmed.isEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !iconImporting,
                onClick = { onSave(trimmed, icon) }
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun ProviderGroupPickerDialog(
    provider: LlmProviderInfo,
    groups: List<ProviderGroup>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_group)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f)) {
                item(key = UNGROUPED_SECTION) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ungrouped)) },
                        leadingContent = { RadioButton(selected = provider.groupId == null, onClick = null) },
                        modifier = Modifier.clickable { onSelect(null) }
                    )
                }
                items(groups, key = { it.id }) { group ->
                    ListItem(
                        headlineContent = { Text(group.name) },
                        leadingContent = { RadioButton(selected = provider.groupId == group.id, onClick = null) },
                        modifier = Modifier.clickable { onSelect(group.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
fun ProviderUnifiedCard(
    provider: LlmProviderInfo,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean = false
) {
    val shapeCorner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "providerCorner"
    )

    Surface(
        shape = RoundedCornerShape(shapeCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.height(ProviderListCardHeight)
                else Modifier.heightIn(min = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight))
            .clickable { onEdit() }
    ) {
        if (compact) {
            // The first list member shares an 88dp row with the folder logo. Keep
            // its title and actions; metadata remains available in the editor.
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                EntityIcon(provider.icon, Modifier.size(42.dp), text = providerInitials(provider.name), matchName = provider.name)
                Spacer(Modifier.width(14.dp))
                WordWrappedFadeText(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).testTag("provider_title_${provider.id}")
                )
                ProviderOverflowMenu(onEdit, onMoveToGroup, onDelete)
            }
        } else {
            AdaptiveCardLayout(
                isGrid = isGrid,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                icon = {
                    EntityIcon(provider.icon, Modifier.size(42.dp), text = providerInitials(provider.name), matchName = provider.name)
                },
                actions = {
                    ProviderOverflowMenu(onEdit, onMoveToGroup, onDelete)
                },
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                                Text(
                                    text = provider.type.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                        if (provider.auth.method == AuthMethod.OAUTH) {
                            val signedIn = !provider.auth.value.isNullOrBlank()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    if (signedIn) Icons.Outlined.CheckCircle else Icons.Outlined.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(if (signedIn) R.string.oauth_signed_in else R.string.oauth_signed_out),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = provider.baseUrl,
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
}

@Composable
private fun ProviderOverflowMenu(
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_group)) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                onClick = { expanded = false; onMoveToGroup() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}
