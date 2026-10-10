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
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import org.starfall.multigateway.ui.theme.LocalAmoledMode
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import java.util.UUID
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.DiscoveredModel
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderPlacement
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
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent


private data class ProviderEditor(val provider: LlmProviderInfo, val isNew: Boolean)
/** A tile being dragged. [fromFolderId] is set while it is still a member of the open folder container. */
private data class ProviderDragSession(
    val key: String,
    val size: IntSize,
    val grab: Offset,
    val fromFolderId: String? = null,
    val folderTile: Boolean = fromFolderId != null,
    val isGrid: Boolean = true
)

/** Where the drag surface and the folder container sit; read from gesture callbacks, never from composition. */
private class ProviderDragSurfaces {
    var rootGrid: LazyGridState? = null
    var folderGrid: LazyGridState? = null
    var boxOrigin = Offset.Zero
    var panelBounds: Rect? = null
    var folderGridOrigin = Offset.Zero
    var layoutGate = 0
}

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

private fun ProviderRootItem.cellKey(): String =
    if (this is ProviderRootItem.GroupItem) "group_$id" else "provider_$id"

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
    onPlaceProvider: (suspend (ProviderPlacement) -> Result<Unit>)? = null,
    onPlaceProviderImmediately: ((ProviderPlacement) -> Unit)? = null,
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
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ProviderEditor?>(null) }
    var deletingProviderId by remember { mutableStateOf<String?>(null) }
    var orderedProviders by remember { mutableStateOf(providers) }
    var rootItems by remember { mutableStateOf(providerRootItems(providers, providerGroups)) }
    var pendingProviderOrder by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var pendingRootOrder by remember { mutableStateOf<List<ProviderRootOrderItem>?>(null) }
    var pendingGridLayout by remember { mutableStateOf<ProviderDragLayout?>(null) }
    var collapsedSections by remember { mutableStateOf(collapsedSectionsState) }
    var pendingCollapsedSections by remember { mutableStateOf<Set<String>?>(null) }
    var groupToRename by remember { mutableStateOf<ProviderGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<ProviderGroup?>(null) }
    var movingProvider by remember { mutableStateOf<LlmProviderInfo?>(null) }
    var openGroupId by remember { mutableStateOf<String?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFields by remember { mutableStateOf(setOf(ProviderSearchField.PROVIDER_NAME)) }
    var searchFilterOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val query = searchQuery.trim()
    fun matches(provider: LlmProviderInfo) = matchesProviderSearch(provider, query, searchFields)
    // Search is a flat provider list, including members of matching folders.
    val matchingGroupIds = providerGroups.filter {
        matchesFolderSearch(it.name, query, searchFields)
    }.mapTo(hashSetOf()) { it.id }
    val visibleRootItems = if (searching) {
        orderedProviders.filter { matches(it) || it.groupId in matchingGroupIds }
            .map { ProviderRootItem.ProviderItem(it) }
    } else rootItems
    fun closeSearch() { searching = false; searchQuery = ""; searchFilterOpen = false }
    BackHandler(enabled = LocalScreenTransitionActive.current && searching && editor == null) { closeSearch() }
    BackHandler(enabled = LocalScreenTransitionActive.current && openGroupId != null && editor == null) { openGroupId = null }
    var draggedProviderId by remember { mutableStateOf<String?>(null) }
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var dragRootSnapshot by remember { mutableStateOf<List<ProviderRootItem>?>(null) }
    var dragProviderSnapshot by remember { mutableStateOf<List<LlmProviderInfo>?>(null) }
    var dragCollapsedSnapshot by remember { mutableStateOf<Set<String>?>(null) }
    var liveOrderChanged by remember { mutableStateOf(false) }
    var dragSession by remember { mutableStateOf<ProviderDragSession?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var dropFolderId by remember { mutableStateOf<String?>(null) }
    var dragHoverKey by remember { mutableStateOf<String?>(null) }
    val surfaces = remember { ProviderDragSurfaces() }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val sortedGroups = remember(providerGroups) {
        providerGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val validGroupIds = remember(providerGroups) { providerGroups.mapTo(hashSetOf()) { it.id } }
    val openGroup = sortedGroups.firstOrNull { it.id == openGroupId }

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

    fun beginDrag(key: String) {
        dragRootSnapshot = rootItems
        dragProviderSnapshot = orderedProviders
        dragCollapsedSnapshot = collapsedSections
        liveOrderChanged = false
        dragHoverKey = null
        if (key.startsWith("provider_")) draggedProviderId = key.removePrefix("provider_")
        else draggedGroupId = key.removePrefix("group_")
    }

    fun currentLayout() = ProviderDragLayout(orderedProviders, rootItems.map { it.toOrderItem() })

    fun applyLayout(next: ProviderDragLayout) {
        orderedProviders = next.providers
        val groups = rootItems.filterIsInstance<ProviderRootItem.GroupItem>().associateBy { it.id }
        val byId = next.providers.associateBy { it.id }
        rootItems = next.root.mapNotNull { item ->
            if (item.isGroup) groups[item.id] else byId[item.id]?.let { ProviderRootItem.ProviderItem(it) }
        }
        liveOrderChanged = true
        // The grids still describe the previous layout until they re-measure; hold further reorders back.
        surfaces.layoutGate = 2
    }

    fun applyProviderPreview(id: String, target: ProviderRootOrderItem?) {
        val layout = currentLayout()
        val next = moveProviderDrag(layout, id, target)
        if (next != layout) applyLayout(next)
    }

    fun moveRootItem(fromKey: String, toKey: String) {
        val from = rootItems.indexOfFirst { it.cellKey() == fromKey }
        val to = rootItems.indexOfFirst { it.cellKey() == toKey }
        if (from < 0 || to < 0 || from == to) return
        rootItems = rootItems.moved(from, to)
        liveOrderChanged = true
        surfaces.layoutGate = 2
    }

    fun commitProviderMove(id: String) {
        val layout = ProviderDragLayout(orderedProviders, rootItems.map { it.toOrderItem() })
        val original = dragProviderSnapshot?.firstOrNull { it.id == id }
        val moved = orderedProviders.firstOrNull { it.id == id }
        if (original != null && moved != null && liveOrderChanged) {
            val groupOrders = rootItems.filterIsInstance<ProviderRootItem.GroupItem>().associate { item ->
                item.id to orderedProviders.filter { it.groupId == item.id }.map { it.id }
            }
            pendingGridLayout = layout
            val placement = ProviderPlacement(id, moved.groupId, layout.root, groupOrders)
            val previousProviders = dragProviderSnapshot.orEmpty()
            val previousRoot = dragRootSnapshot.orEmpty()
            val previousCollapsed = dragCollapsedSnapshot.orEmpty()
            if (onPlaceProviderImmediately != null) {
                // The persistence owner must outlive this screen. Calling this synchronously
                // lets the ViewModel enqueue the write before navigation can dispose the screen.
                onPlaceProviderImmediately(placement)
            } else if (onPlaceProvider != null) scope.launch {
                if (onPlaceProvider(placement).isFailure) {
                    if (pendingGridLayout == layout) {
                        pendingGridLayout = null
                        orderedProviders = previousProviders
                        rootItems = previousRoot
                        setCollapsedSections(previousCollapsed)
                    }
                    Toast.makeText(context, context.getString(R.string.provider_drag_save_failed), Toast.LENGTH_SHORT).show()
                }
            } else {
                if (original.groupId != moved.groupId) onMoveProviderToGroup(id, moved.groupId)
                groupOrders.filter { (group, ids) -> previousProviders.filter { it.groupId == group }.map { it.id } != ids }
                    .values.forEach(onReorderProviders)
                if (previousRoot.map { it.toOrderItem() } != layout.root) onReorderRootItems(layout.root)
            }
        }
        draggedProviderId = null
        draggedGroupId = null
        dragRootSnapshot = null
        dragProviderSnapshot = null
        liveOrderChanged = false
    }

    fun finishDrag(key: String, cancelled: Boolean) {
        if (cancelled) {
            dragRootSnapshot?.let { rootItems = it }
            dragProviderSnapshot?.let { orderedProviders = it }
            dragCollapsedSnapshot?.let { collapsedSections = it }
        } else {
            if (key.startsWith("provider_")) {
                commitProviderMove(key.removePrefix("provider_"))
            }
            else if (liveOrderChanged) {
                val order = rootItems.map { it.toOrderItem() }
                pendingRootOrder = order
                onReorderRootItems(order)
            }
            if (collapsedSections != dragCollapsedSnapshot) setCollapsedSections(collapsedSections)
        }
        draggedProviderId = null
        draggedGroupId = null
        dragRootSnapshot = null
        dragProviderSnapshot = null
        dragCollapsedSnapshot = null
        liveOrderChanged = false
    }

    fun rootTiles(): List<DragTile> = surfaces.rootGrid?.dragTiles().orEmpty()

    fun folderTiles(): List<DragTile> {
        val grid = surfaces.folderGrid ?: return emptyList()
        val origin = surfaces.folderGridOrigin - surfaces.boxOrigin
        return grid.dragTiles().map { DragTile(it.key, it.rect.translate(origin)) }
    }

    fun panelRect(): Rect? = surfaces.panelBounds?.translate(-surfaces.boxOrigin)

    fun pickTile(point: Offset): DragTile? {
        if (openGroupId != null) {
            // While a folder is open only its members can be picked up; the scrim blocks the root grid.
            val panel = panelRect() ?: return null
            return if (panel.contains(point)) hitTile(folderTiles(), point) else null
        }
        return hitTile(rootTiles(), point)
    }

    fun updateDragTarget(finalDrop: Boolean = false) {
        val session = dragSession ?: return
        if (!finalDrop && surfaces.layoutGate > 0) return
        val point = dragPointer
        val folderId = session.fromFolderId
        if (folderId != null) {
            val panel = panelRect()
            val slop = with(density) { 24.dp.toPx() }
            if (panel != null && panel.inflate(slop).contains(point)) {
                // Reorder among the folder's members.
                dropFolderId = null
                val target = hitTile(folderTiles(), point)?.key
                if (target != null && target != session.key && surfaces.layoutGate == 0 && dragHoverKey != target) {
                    dragHoverKey = target
                    applyProviderPreview(
                        session.key.removePrefix("provider_"),
                        ProviderRootOrderItem(target.removePrefix("provider_"), false)
                    )
                } else if (target == null || target == session.key) {
                    dragHoverKey = null
                }
                return
            }
            // Dragged out of the container: close the folder and continue on the root grid.
            dragHoverKey = null
            val layout = currentLayout()
            val next = ejectProviderFromFolder(layout, session.key.removePrefix("provider_"), folderId)
            if (next != layout) applyLayout(next)
            dragSession = session.copy(fromFolderId = null)
            openGroupId = null
            return
        }
        val tiles = rootTiles()
        val tile = hitTile(tiles, point)
        if (tile == null || tile.key == session.key) {
            dropFolderId = null
            // Empty space is still a valid root drop position. Pick the closest visible cell so
            // a provider leaving a folder can be placed above, between, or after root items.
            if (tile == null && surfaces.layoutGate == 0) {
                val nearest = tiles
                    .filterNot { it.key == session.key }
                    .minByOrNull { candidate ->
                        val dx = point.x - candidate.rect.center.x
                        val dy = point.y - candidate.rect.center.y
                        dx * dx + dy * dy
                    }
                if (nearest != null && surfaces.layoutGate == 0) {
                    dragHoverKey = nearest.key
                    moveRootItem(session.key, nearest.key)
                } else dragHoverKey = null
            }
            return
        }
        if (session.key.startsWith("provider_") && tile.key.startsWith("group_") && isFolderDropZone(tile.rect, point)) {
            // Releasing here moves the provider into the folder. Nothing is rearranged while hovering.
            dropFolderId = tile.key.removePrefix("group_")
            dragHoverKey = tile.key
            return
        }
        dropFolderId = null
        if (surfaces.layoutGate == 0 && dragHoverKey != tile.key) {
            dragHoverKey = tile.key
            moveRootItem(session.key, tile.key)
        }
    }

    fun pickUp(point: Offset) {
        val tile = pickTile(point) ?: return
        beginDrag(tile.key)
        dragPointer = point
        dragSession = ProviderDragSession(
            key = tile.key,
            size = IntSize(tile.rect.width.roundToInt(), tile.rect.height.roundToInt()),
            grab = point - tile.rect.topLeft,
            fromFolderId = openGroupId,
            isGrid = isGridView
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragMove(point: Offset) {
        dragPointer = point
        updateDragTarget()
    }

    fun dropDrag() {
        // Resolve the release position even if the last preview is waiting for layout.
        val wasInFolder = dragSession?.fromFolderId != null
        updateDragTarget(finalDrop = true)
        // Leaving a folder changes the drag surface; resolve the root target too.
        if (wasInFolder && dragSession?.fromFolderId == null) updateDragTarget(finalDrop = true)
        val session = dragSession ?: return
        val folder = dropFolderId
        if (session.key.startsWith("provider_")) {
            val id = session.key.removePrefix("provider_")
            val layout = currentLayout()
            val next = if (folder != null) moveProviderDrag(layout, id, ProviderRootOrderItem(folder, true)) else layout
            if (next != layout) applyLayout(next)
        }
        dragSession = null
        dropFolderId = null
        dragHoverKey = null
        finishDrag(session.key, cancelled = false)
    }

    fun cancelDrag() {
        val session = dragSession ?: return
        dragSession = null
        dropFolderId = null
        dragHoverKey = null
        finishDrag(session.key, cancelled = true)
    }

    LaunchedEffect(dragSession != null) {
        if (dragSession == null) return@LaunchedEffect
        val zone = with(density) { 80.dp.toPx() }
        val pxPerSecond = with(density) { 720.dp.toPx() }
        while (true) {
            val session = dragSession ?: break
            val inFolder = session.fromFolderId != null
            val grid = if (inFolder) surfaces.folderGrid else surfaces.rootGrid
            val top = if (inFolder) surfaces.folderGridOrigin.y - surfaces.boxOrigin.y else 0f
            val pointer = dragPointer
            val nearEdge = grid != null && autoScrollDelta(pointer.y - top, grid.viewportHeightPx, zone, 1f) != 0f
            if (surfaces.layoutGate == 0 && !nearEdge) {
                // Nothing time-based to do: sleep until the finger moves instead of ticking every frame.
                snapshotFlow { dragPointer to dragSession }.first { (p, s) -> p != pointer || s !== session }
                continue
            }
            if (surfaces.layoutGate > 0) surfaces.layoutGate--
            if (dragSession == null) break
            updateDragTarget()
            if (grid != null) {
                // A coroutine delay keeps the drag loop out of Compose's animation clock. This
                // preserves continuous edge scrolling without making the whole screen perpetually
                // non-idle while a finger is held down.
                val delta = autoScrollDelta(dragPointer.y - top, grid.viewportHeightPx, zone, pxPerSecond * 0.016f)
                if (delta != 0f) grid.scrollBy(delta)
            }
            delay(16)
        }
    }

    LaunchedEffect(providers, providerGroups, draggedProviderId, draggedGroupId) {
        if (draggedProviderId != null || draggedGroupId != null) return@LaunchedEffect

        val incomingProvidersById = providers.associateBy { it.id }
        pendingGridLayout?.let { preview ->
            val incomingRoot = providerRootItems(providers, providerGroups).map { it.toOrderItem() }
            val sameCatalog = preview.providers.map { it.id }.toSet() == providers.map { it.id }.toSet() &&
                preview.root.filter { it.isGroup }.map { it.id }.toSet() == providerGroups.map { it.id }.toSet()
            val acknowledged = incomingRoot == preview.root && preview.providers.all { p ->
                incomingProvidersById[p.id]?.groupId == p.groupId
            } && providerGroups.all { g ->
                providers.filter { it.groupId == g.id }.map { it.id } == preview.providers.filter { it.groupId == g.id }.map { it.id }
            }
            if (sameCatalog && !acknowledged) return@LaunchedEffect
            pendingGridLayout = null
        }
        val pendingProvider = pendingProviderOrder
        if (pendingProvider == null) {
            orderedProviders = providers
        } else {
            val (groupId, expectedOrder) = pendingProvider
            val actualOrder = providers.filter { it.groupId == groupId }.map { it.id }
            // A folder move/add/delete changes membership, so an old drag order
            // can no longer be acknowledged and must not keep the old positions.
            if (actualOrder == expectedOrder || actualOrder.toSet() != expectedOrder.toSet()) {
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
        } else if (incomingRootItems.map { it.toOrderItem() } == pendingRoot ||
            incomingRootItems.map { it.toOrderItem() }.toSet() != pendingRoot.toSet()) {
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
        if (validGroupIds.isEmpty()) return@LaunchedEffect // groups not loaded yet; don't wipe the saved collapsed state
        val cleaned = collapsedSections.filterTo(linkedSetOf()) { it in validGroupIds }
        if (cleaned != collapsedSections) setCollapsedSections(cleaned)
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
                            if (searching) {
                                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    placeholder = { Text(stringResource(R.string.search_providers)) },
                                    singleLine = true,
                                    trailingIcon = {
                                        Box {
                                            IconButton(onClick = { searchFilterOpen = true }) {
                                                Icon(Icons.Outlined.FilterList, stringResource(R.string.provider_search_filter),
                                                    tint = if (searchFields == setOf(ProviderSearchField.PROVIDER_NAME))
                                                        MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                                            }
                                            DropdownMenu(expanded = searchFilterOpen, onDismissRequest = { searchFilterOpen = false }) {
                                                listOf(
                                                    ProviderSearchField.PROVIDER_NAME to R.string.provider_search_name,
                                                    ProviderSearchField.FOLDER_NAME to R.string.provider_search_folder,
                                                    ProviderSearchField.MODEL_NAME to R.string.provider_search_model
                                                ).forEach { (field, label) ->
                                                    val selected = field in searchFields
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(label)) },
                                                        leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                                                        enabled = !selected || searchFields.size > 1,
                                                        onClick = {
                                                            searchFields = if (selected) searchFields - field else searchFields + field
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).testTag("provider_search"),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                                    )
                                )
                            } else Column {
                                Text(
                                    text = stringResource(R.string.providers_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.providers_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                            }
                        },
                        actions = {
                            if (searching) {
                                IconButton(onClick = ::closeSearch) {
                                    Icon(Icons.Default.Close, stringResource(R.string.close_provider_search))
                                }
                            } else {
                                IconButton(onClick = { openGroupId = null; searching = true }) {
                                    Icon(Icons.Default.Search, stringResource(R.string.search_providers))
                                }
                                IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                                    Icon(
                                        imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                        contentDescription = stringResource(
                                            if (isGridView) R.string.switch_to_list_view else R.string.switch_to_grid_view
                                        )
                                    )
                                }
                                IconButton(onClick = { creatingGroup = true }) {
                                    Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.add_provider_group))
                                }
                                IconButton(
                                    onClick = { createProvider(openGroupId) },
                                    modifier = Modifier.testTag("add_provider")
                                ) {
                                    Icon(Icons.Default.Add, stringResource(R.string.add_provider))
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .onGloballyPositioned { surfaces.boxOrigin = it.positionInRoot() }
                        .providerDragGesture(
                            enabled = !searching,
                            canPickUp = { pickTile(it) != null },
                            onPickUp = ::pickUp,
                            onMove = ::dragMove,
                            onDrop = ::dropDrag,
                            onCancel = ::cancelDrag
                        )
                ) {
                    if (visibleRootItems.isEmpty()) {
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
                                    text = stringResource(if (query.isNotEmpty()) R.string.no_provider_search_results else R.string.no_providers_added),
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
                            val gridState = rememberLazyGridState()
                            if (gridMode == isGridView) SideEffect { surfaces.rootGrid = gridState }
                            val cellHeight = if (gridMode) 164.dp else 100.dp
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(if (gridMode) 2 else 1),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxSize().testTag("provider_list")
                            ) {
                                items(visibleRootItems, key = { it.cellKey() }) { item ->
                                    val key = item.cellKey()
                                    Box(
                                        Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .height(cellHeight)
                                            .graphicsLayer { alpha = if (dragSession?.key == key) 0.25f else 1f }
                                            .padding(6.dp)
                                    ) {
                                        ProviderRootCard(
                                            item = item,
                                            members = (item as? ProviderRootItem.GroupItem)
                                                ?.let { g -> orderedProviders.filter { it.groupId == g.id } }.orEmpty(),
                                            isGrid = gridMode,
                                            isDropTarget = item is ProviderRootItem.GroupItem && dropFolderId == item.id,
                                            onEditProvider = { editor = ProviderEditor(it, false) },
                                            onMoveProvider = { movingProvider = it },
                                            onDeleteProvider = { deletingProviderId = it },
                                            onOpenGroup = { openGroupId = it }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    openGroup?.let { group ->
                        val members = orderedProviders.filter { it.groupId == group.id }
                        val folderMatches = query.isEmpty() || matchesFolderSearch(group.name, query, searchFields)
                        ProviderFolderOverlay(
                            group = group,
                            providers = if (folderMatches) members else members.filter(::matches),
                            isGrid = isGridView,
                            surfaces = surfaces,
                            draggingKey = dragSession?.key,
                            onDismiss = { openGroupId = null },
                            onEditGroup = { groupToRename = group },
                            onDeleteGroup = { deletingGroup = group; openGroupId = null },
                            onEditProvider = { editor = ProviderEditor(it, false) },
                            onMoveProvider = { movingProvider = it },
                            onDeleteProvider = { deletingProviderId = it }
                        )
                    }

                    dragSession?.let { session ->
                        val provider = if (session.key.startsWith("provider_"))
                            orderedProviders.firstOrNull { it.id == session.key.removePrefix("provider_") } else null
                        val groupItem = if (session.key.startsWith("group_"))
                            rootItems.firstOrNull { it.cellKey() == session.key } as? ProviderRootItem.GroupItem else null
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (dragPointer.x - session.grab.x).roundToInt(),
                                        (dragPointer.y - session.grab.y).roundToInt()
                                    )
                                }
                                 .size(with(density) { session.size.width.toDp() }, with(density) { session.size.height.toDp() })
                                 .zIndex(10f)
                                 .graphicsLayer {
                                     // Keep the drag ghost the same size and shape as the source
                                     // tile; only its z-order distinguishes it while dragging.
                                     alpha = 1f
                                 }
                                .then(if (provider != null) Modifier.testTag("dragged_provider") else Modifier)
                        ) {
                             when {
                                 provider != null && session.folderTile ->
                                     ProviderFolderProviderTile(
                                         provider = provider,
                                         isGrid = session.isGrid,
                                         onEdit = {},
                                         onMoveToGroup = {},
                                         onDelete = {},
                                         testTag = false
                                     )
                                provider != null || groupItem != null -> Box(Modifier.fillMaxSize().padding(6.dp)) {
                            ProviderRootCard(
                                        item = groupItem ?: ProviderRootItem.ProviderItem(provider!!),
                                        members = groupItem?.let { g -> orderedProviders.filter { it.groupId == g.id } }.orEmpty(),
                                         isGrid = session.isGrid,
                                        isDropTarget = false,
                                        testTag = false,
                                        onEditProvider = {}, onMoveProvider = {}, onDeleteProvider = {}, onOpenGroup = {}
                                    )
                                }
                            }
                        }
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

/** One root tile: a provider card or a folder card. Shared by the grid cell and the drag ghost. */
@Composable
private fun ProviderRootCard(
    item: ProviderRootItem,
    members: List<LlmProviderInfo>,
    isGrid: Boolean,
    isDropTarget: Boolean,
    testTag: Boolean = true,
    onEditProvider: (LlmProviderInfo) -> Unit,
    onMoveProvider: (LlmProviderInfo) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onOpenGroup: (String) -> Unit
) {
    when (item) {
        is ProviderRootItem.ProviderItem -> ProviderUnifiedCard(
            provider = item.provider,
            isGrid = isGrid,
            modifier = Modifier.then(if (testTag) Modifier.testTag("provider_${item.provider.id}") else Modifier),
            onEdit = { onEditProvider(item.provider) },
            onMoveToGroup = { onMoveProvider(item.provider) },
            onDelete = { onDeleteProvider(item.provider.id) }
        )
        is ProviderRootItem.GroupItem -> ProviderGroupCollapsedCard(
            group = item.group,
            isGrid = isGrid,
            providers = members,
            isDropTarget = isDropTarget,
            modifier = Modifier.then(if (testTag) Modifier.testTag("provider_group_${item.group.id}") else Modifier),
            onOpen = { onOpenGroup(item.group.id) }
        )
    }
}

@Composable
private fun ProviderGroupCollapsedCard(
    group: ProviderGroup,
    isGrid: Boolean,
    providers: List<LlmProviderInfo>,
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
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
        color = if (isDropTarget) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isDropTarget) 2.dp else 1.5.dp,
            color = if (isDropTarget) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clickable(onClick = onOpen)
    ) {
        val titleColor = if (isDropTarget) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current
        if (isGrid) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                ProviderFolderPreview(
                    group,
                    providers,
                    Modifier.size(52.dp).testTag("provider_group_icon_${group.id}")
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.provider_count, providers.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        } else {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProviderFolderPreview(
                    group,
                    providers,
                    Modifier.size(46.dp).testTag("provider_group_icon_${group.id}")
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.provider_count, providers.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A folder shows its own logo when it has one (picked, or recognised from the folder name).
 * Only a folder without a logo falls back to a 2x2 preview of its first four providers.
 */
@Composable
private fun ProviderFolderPreview(
    group: ProviderGroup,
    providers: List<LlmProviderInfo>,
    modifier: Modifier = Modifier
) {
    val previewProviders = providers.take(4)
    EntityIcon(
        image = group.icon,
        modifier = modifier,
        fallback = Icons.Outlined.Folder,
        matchName = group.name,
        fallbackContent = if (previewProviders.isEmpty()) null else ({ ProviderFolderMiniGrid(group.id, previewProviders) })
    )
}

@Composable
private fun ProviderFolderMiniGrid(groupId: String, providers: List<LlmProviderInfo>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(4.dp).testTag("provider_group_preview_$groupId"),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        providers.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.forEach { provider ->
                    EntityIcon(
                        image = provider.icon,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        text = providerInitials(provider.name),
                        matchName = provider.name
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The open folder: an in-window overlay (not a Dialog) so the screen-level drag gesture keeps
 * receiving the finger when a member is dragged out of it.
 */
@Composable
private fun ProviderFolderOverlay(
    group: ProviderGroup,
    providers: List<LlmProviderInfo>,
    isGrid: Boolean,
    surfaces: ProviderDragSurfaces,
    draggingKey: String?,
    onDismiss: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onEditProvider: (LlmProviderInfo) -> Unit,
    onMoveProvider: (LlmProviderInfo) -> Unit,
    onDeleteProvider: (String) -> Unit
) {
    val amoled = LocalAmoledMode.current
    val gridState = rememberLazyGridState()
    SideEffect { surfaces.folderGrid = gridState }
    DisposableEffect(Unit) {
        onDispose {
            surfaces.folderGrid = null
            surfaces.panelBounds = null
        }
    }
    AnimatedVisibility(
        visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
        enter = fadeIn() + scaleIn(initialScale = 0.94f),
        exit = ExitTransition.None
    ) {
        Box(Modifier.fillMaxSize().zIndex(5f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.78f)
                    .widthIn(max = 520.dp)
                    .onGloballyPositioned { surfaces.panelBounds = it.boundsInRoot() }
                    .testTag("provider_folder_dialog_${group.id}"),
                shape = RoundedCornerShape(28.dp),
                // AMOLED: true black with the same 1dp outline as dialogs. Tonal elevation must be off,
                // otherwise Surface tints the black (it equals colorScheme.surface) and lifts it to gray.
                color = if (amoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = if (amoled) 0.dp else 6.dp,
                border = if (amoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderFolderPreview(
                            group = group,
                            providers = providers,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.provider_count, providers.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ProviderGroupOverflowMenu(onEditGroup, onDeleteGroup)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.common_close))
                        }
                    }

                    if (providers.isEmpty()) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.empty_provider_group),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(if (isGrid) 2 else 1),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .onGloballyPositioned { surfaces.folderGridOrigin = it.positionInRoot() }
                                .testTag("provider_folder_grid_${group.id}"),
                            contentPadding = PaddingValues(vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(providers, key = { "provider_${it.id}" }) { provider ->
                                ProviderFolderProviderTile(
                                    provider = provider,
                                    isGrid = isGrid,
                                    onEdit = { onEditProvider(provider) },
                                    onMoveToGroup = { onMoveProvider(provider) },
                                    onDelete = { onDeleteProvider(provider.id) },
                                    testTag = true,
                                    modifier = Modifier
                                        .animateItem()
                                        .graphicsLayer {
                                            alpha = if (draggingKey == "provider_${provider.id}") 0.25f else 1f
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

@Composable
private fun ProviderFolderProviderTile(
    provider: LlmProviderInfo,
    isGrid: Boolean,
    onEdit: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
    testTag: Boolean = true,
    modifier: Modifier = Modifier
) {
    val taggedModifier = modifier.then(
        if (testTag) Modifier.testTag("provider_folder_provider_${provider.id}") else Modifier
    )
    if (!isGrid) {
        ProviderUnifiedCard(
            provider = provider,
            isGrid = false,
            modifier = taggedModifier,
            onEdit = onEdit,
            onMoveToGroup = onMoveToGroup,
            onDelete = onDelete,
            compact = true
        )
        return
    }
    Surface(
        modifier = taggedModifier.fillMaxWidth().height(132.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onEdit
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EntityIcon(
                    image = provider.icon,
                    modifier = Modifier.size(52.dp),
                    text = providerInitials(provider.name),
                    matchName = provider.name
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                ProviderOverflowMenu(onEdit, onMoveToGroup, onDelete)
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
                    ProviderGroupChoiceCard(
                        title = stringResource(R.string.ungrouped),
                        icon = null,
                        selected = provider.groupId == null,
                        onClick = { onSelect(null) }
                    )
                }
                items(groups, key = { it.id }) { group ->
                    ProviderGroupChoiceCard(
                        title = group.name,
                        icon = group.icon,
                        selected = provider.groupId == group.id,
                        onClick = { onSelect(group.id) }
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ProviderGroupChoiceCard(
    title: String,
    icon: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntityIcon(
                image = icon,
                modifier = Modifier.size(44.dp),
                text = title.take(2).uppercase(),
                fallback = Icons.Outlined.Folder,
                matchName = title
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WordWrappedFadeText(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().testTag("provider_title_${provider.id}")
                    )
                    if (provider.type.isAccountProvider) ProviderOAuthStatus(provider)
                }
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
                        if (provider.type.isAccountProvider || provider.auth.method == AuthMethod.OAUTH) ProviderOAuthStatus(provider)
                        if (!provider.type.isAccountProvider) Text(
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
private fun ProviderOAuthStatus(provider: LlmProviderInfo) {
    val signedIn = provider.auth.method == AuthMethod.OAUTH && !provider.auth.value.isNullOrBlank()
    val color = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (signedIn) Icons.Outlined.CheckCircle else Icons.Outlined.AccountCircle,
            contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Text(stringResource(if (signedIn) R.string.oauth_signed_in else R.string.oauth_signed_out),
            style = MaterialTheme.typography.labelSmall, color = color)
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


@Composable
private fun ProviderGroupGridHeading(
    group: ProviderGroup,
    modifier: Modifier,
    detached: Boolean = false,
    onCollapse: () -> Unit,
    onAddProvider: () -> Unit,
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(ProviderGridCardHeight).clickable(onClick = onCollapse),
        shape = RoundedCornerShape(20.dp),
        color = if (detached) MaterialTheme.colorScheme.surfaceContainerLow
            else androidx.compose.ui.graphics.Color.Transparent,
        border = if (detached) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).clickable(onClick = onCollapse),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
                IconButton(
                    onClick = onAddProvider,
                    modifier = Modifier.size(32.dp).testTag("add_provider_to_group_${group.id}")
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_provider), modifier = Modifier.size(20.dp))
                }
                ProviderGroupOverflowMenu(onEditGroup, onDeleteGroup)
            }
            ProviderGroupIconTile(
                group,
                Modifier.fillMaxWidth().testTag("provider_group_icon_${group.id}"),
                tileHeight = ProviderGridCardHeight - 36.dp,
                onClick = onCollapse
            )
        }
    }
}
