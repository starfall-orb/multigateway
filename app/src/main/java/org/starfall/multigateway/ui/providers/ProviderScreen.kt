package org.starfall.multigateway.ui.providers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.Authorization
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderRootOrderItem
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.bearerHeaderValue
import org.starfall.multigateway.data.model.defaultAuthorization
import org.starfall.multigateway.data.model.editMethod
import org.starfall.multigateway.data.model.withType
import org.starfall.multigateway.ui.chat.ModelCapabilityBadges
import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.components.EntityIcon
import org.starfall.multigateway.ui.components.IconPickerRow
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.components.rememberLazyListReorderState
import org.starfall.multigateway.ui.components.reorderGestures
import org.starfall.multigateway.ui.components.reorderItem
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent


private data class ProviderEditor(val provider: LlmProviderInfo, val isNew: Boolean)
private const val UNGROUPED_SECTION = "__ungrouped__"
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
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editor by remember { mutableStateOf<ProviderEditor?>(null) }
    var deletingProviderId by remember { mutableStateOf<String?>(null) }
    var orderedProviders by remember(providers) { mutableStateOf(providers) }
    var rootItems by remember(providers, providerGroups) {
        mutableStateOf(providerRootItems(providers, providerGroups))
    }
    var collapsedSections by remember { mutableStateOf(collapsedSectionsState) }
    var groupToRename by remember { mutableStateOf<ProviderGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<ProviderGroup?>(null) }
    var movingProvider by remember { mutableStateOf<LlmProviderInfo?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    val groupCardBounds = remember { mutableStateMapOf<String, Rect>() }

    val sortedGroups = remember(providerGroups) {
        providerGroups.sortedWith(compareBy<ProviderGroup> { it.sortOrder }.thenBy { it.name.lowercase() })
    }
    val validGroupIds = remember(providerGroups) { providerGroups.mapTo(hashSetOf()) { it.id } }

    fun setCollapsedSections(value: Set<String>) {
        collapsedSections = value
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
        onReorderRootItems(rootItems.map { it.toOrderItem() })
    }

    fun dropGroupAt(point: Offset, sourceGroupId: String?): String? =
        providerGroups.firstOrNull { group ->
            group.id != sourceGroupId && groupCardBounds[group.id]?.containsPoint(point) == true
        }?.id

    LaunchedEffect(collapsedSectionsState) {
        collapsedSections = collapsedSectionsState
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
                            IconButton(onClick = {
                                editor = ProviderEditor(
                                    LlmProviderInfo(
                                        id = "custom_\${System.currentTimeMillis()}",
                                        name = "",
                                        type = ProviderType.OPENAI,
                                        baseUrl = "",
                                        auth = ProviderType.OPENAI.defaultAuthorization()
                                    ),
                                    true
                                )
                            }) {
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
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (isGridView) 2 else 1),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            rootItems.forEachIndexed { rootIndex, rootItem ->
                                when (rootItem) {
                                    is ProviderRootItem.GroupItem -> {
                                        val group = rootItem.group
                                        val groupProviders = orderedProviders.filter { it.groupId == group.id }
                                        val expanded = group.id !in collapsedSections
                                        item(
                                            key = "group_\${group.id}",
                                            span = { GridItemSpan(if (expanded) maxLineSpan else 1) }
                                        ) {
                                            if (expanded) {
                                                ProviderGroupExpandedContainer(
                                                    group = group,
                                                    providers = groupProviders,
                                                    isGrid = isGridView,
                                                    modifier = Modifier.animateItem(
                                                        placementSpec = spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    ),
                                                    headerDragModifier = Modifier.longPressReorder(
                                                        index = rootIndex,
                                                        itemCount = rootItems.size,
                                                        columns = if (isGridView) 2 else 1,
                                                        onMove = { from, to ->
                                                            rootItems = rootItems.moved(from, to)
                                                        },
                                                        onDrop = ::persistRootOrder
                                                    ),
                                                    onBoundsChanged = { bounds ->
                                                        if (bounds == null) groupCardBounds.remove(group.id)
                                                        else groupCardBounds[group.id] = bounds
                                                    },
                                                    onCollapse = { toggleSection(group.id) },
                                                    onEditGroup = { groupToRename = group },
                                                    onDeleteGroup = { deletingGroup = group },
                                                    onEditProvider = { provider -> editor = ProviderEditor(provider, false) },
                                                    onMoveProvider = { provider -> movingProvider = provider },
                                                    onDeleteProvider = { provider -> deletingProviderId = provider.id },
                                                    onMoveWithinGroup = { from, to ->
                                                        moveWithinGroup(group.id, from, to)
                                                    },
                                                    onPersistProviderOrder = {
                                                        onReorderProviders(
                                                            orderedProviders
                                                                .filter { it.groupId == group.id }
                                                                .map { it.id }
                                                        )
                                                    },
                                                    onDropProviderAt = { provider, point ->
                                                        dropGroupAt(point, group.id)?.let { targetGroupId ->
                                                            onMoveProviderToGroup(provider.id, targetGroupId)
                                                        }
                                                    }
                                                )
                                            } else {
                                                ProviderGroupCollapsedCard(
                                                    group = group,
                                                    isGrid = isGridView,
                                                    modifier = Modifier
                                                        .animateItem(
                                                            placementSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMediumLow
                                                            )
                                                        )
                                                        .longPressReorder(
                                                            index = rootIndex,
                                                            itemCount = rootItems.size,
                                                            columns = if (isGridView) 2 else 1,
                                                            onMove = { from, to ->
                                                                rootItems = rootItems.moved(from, to)
                                                            },
                                                            onDrop = ::persistRootOrder
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

                                    is ProviderRootItem.ProviderItem -> {
                                        val provider = rootItem.provider
                                        item(key = "provider_\${provider.id}") {
                                            ProviderUnifiedCard(
                                                provider = provider,
                                                isGrid = isGridView,
                                                modifier = Modifier
                                                    .animateItem(
                                                        placementSpec = spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                    .longPressReorder(
                                                        index = rootIndex,
                                                        itemCount = rootItems.size,
                                                        columns = if (isGridView) 2 else 1,
                                                        onMove = { from, to ->
                                                            rootItems = rootItems.moved(from, to)
                                                        },
                                                        onDrop = ::persistRootOrder,
                                                        onDropAt = { point ->
                                                            dropGroupAt(point, null)?.let { targetGroupId ->
                                                                onMoveProviderToGroup(provider.id, targetGroupId)
                                                            }
                                                        }
                                                    ),
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
    onEditGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onEditProvider: (LlmProviderInfo) -> Unit,
    onMoveProvider: (LlmProviderInfo) -> Unit,
    onDeleteProvider: (LlmProviderInfo) -> Unit,
    onMoveWithinGroup: (Int, Int) -> Unit,
    onPersistProviderOrder: () -> Unit,
    onDropProviderAt: (LlmProviderInfo, Offset) -> Unit
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
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProviderGroupOverflowMenu(onEditGroup, onDeleteGroup)
                Spacer(Modifier.width(2.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isGrid) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    var providerIndex = 0
                    var slot = 0
                    val totalSlots = providers.size + 1
                    while (slot < totalSlots) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            repeat(2) {
                                when {
                                    slot == 0 -> {
                                        ProviderGroupIconTile(
                                            group = group,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    providerIndex < providers.size -> {
                                        val childIndex = providerIndex
                                        val provider = providers[childIndex]
                                        ProviderUnifiedCard(
                                            provider = provider,
                                            isGrid = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .longPressReorder(
                                                    index = childIndex + 1,
                                                    itemCount = providers.size + 1,
                                                    columns = 2,
                                                    minIndex = 1,
                                                    onMove = { from, to ->
                                                        onMoveWithinGroup(from - 1, to - 1)
                                                    },
                                                    onDrop = onPersistProviderOrder,
                                                    onDropAt = { point ->
                                                        onDropProviderAt(provider, point)
                                                    }
                                                ),
                                            onEdit = { onEditProvider(provider) },
                                            onMoveToGroup = { onMoveProvider(provider) },
                                            onDelete = { onDeleteProvider(provider) }
                                        )
                                        providerIndex++
                                    }

                                    else -> Spacer(Modifier.weight(1f))
                                }
                                slot++
                            }
                        }
                    }
                    if (providers.isEmpty()) {
                        Text(
                            stringResource(R.string.empty_provider_group),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ProviderGroupIconTile(
                        group = group,
                        modifier = Modifier.width(ProviderListCardHeight),
                        tileHeight = ProviderListCardHeight
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (providers.isEmpty()) {
                            Text(
                                stringResource(R.string.empty_provider_group),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            providers.forEachIndexed { childIndex, provider ->
                                ProviderUnifiedCard(
                                    provider = provider,
                                    isGrid = false,
                                    modifier = Modifier.longPressReorder(
                                        index = childIndex,
                                        itemCount = providers.size,
                                        columns = 1,
                                        onMove = onMoveWithinGroup,
                                        onDrop = onPersistProviderOrder,
                                        onDropAt = { point -> onDropProviderAt(provider, point) }
                                    ),
                                    onEdit = { onEditProvider(provider) },
                                    onMoveToGroup = { onMoveProvider(provider) },
                                    onDelete = { onDeleteProvider(provider) }
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
private fun ProviderGroupIconTile(
    group: ProviderGroup,
    modifier: Modifier = Modifier,
    tileHeight: Dp = ProviderGridCardHeight
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(tileHeight)
    ) {
        Box(contentAlignment = Alignment.Center) {
            EntityIcon(
                image = group.icon,
                modifier = Modifier.size(72.dp),
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
                OutlinedTextField(
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
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
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
    onDelete: () -> Unit
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
            .height(if (isGrid) ProviderGridCardHeight else ProviderListCardHeight)
            .clickable { onEdit() }
    ) {
        MorphingCardLayout(
            isGrid = isGrid,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            icon = {
                EntityIcon(provider.icon, Modifier.size(42.dp), fallback = Icons.Outlined.Hub, matchName = provider.name)
            },
            actions = {
                ProviderOverflowMenu(onEdit, onMoveToGroup, onDelete)
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
                            text = provider.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = provider.type.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
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

fun AuthMethod.displayName(): String = when (this) {
    AuthMethod.PLATFORM_DEFAULT -> "Platform Default"
    AuthMethod.NONE -> "None"
    AuthMethod.BEARER_TOKEN -> "Bearer Token"
    AuthMethod.QUERY_PARAM -> "URL Query"
    AuthMethod.CUSTOM_HEADER -> "Bearer Token"
    AuthMethod.OAUTH -> "OAuth Flow"
    AuthMethod.OTHER -> "None"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditScreen(
    initialProvider: LlmProviderInfo,
    isNew: Boolean,
    onAuthorizeProvider: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)? = null,
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit,
    onReorderModels: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (LlmProviderInfo) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialProvider.name) }
    var providerIcon by remember { mutableStateOf(initialProvider.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initialProvider.type) }
    var baseUrl by remember { mutableStateOf(initialProvider.baseUrl) }
    var authMethod by remember { mutableStateOf(initialProvider.auth.editMethod()) }
    var authName by remember { mutableStateOf(
        initialProvider.auth.key.orEmpty().takeIf {
            initialProvider.auth.method in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)
        }.orEmpty().ifBlank {
            if (initialProvider.auth.method == AuthMethod.QUERY_PARAM) "key" else "Authorization"
        }
    ) }
    var apiKey by remember { mutableStateOf(initialProvider.auth.token) }
    var oauthAuthorized by remember(initialProvider.id) {
        mutableStateOf(
            initialProvider.auth.method == AuthMethod.OAUTH &&
                initialProvider.auth.value?.isNotBlank() == true
        )
    }
    var oauthIdentity by remember(initialProvider.id) {
        mutableStateOf(initialProvider.auth.key?.takeIf { oauthAuthorized })
    }
    var oauthMarker by remember(initialProvider.id) {
        mutableStateOf(initialProvider.auth.value.orEmpty().takeIf { oauthAuthorized }.orEmpty())
    }
    var providerPersisted by remember(initialProvider.id) { mutableStateOf(!isNew) }
    var oauthAuthorizing by remember { mutableStateOf(false) }
    var oauthClearing by remember { mutableStateOf(false) }
    var oauthAuthError by remember { mutableStateOf<String?>(null) }
    fun authorization() = if (authMethod == AuthMethod.OAUTH) {
        Authorization(
            AuthMethod.OAUTH,
            key = oauthIdentity,
            value = if (oauthAuthorized) oauthMarker else ""
        )
    } else {
        Authorization(
            authMethod,
            if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) authName.trim() else null,
            if (authMethod == AuthMethod.BEARER_TOKEN) bearerHeaderValue(authName.ifBlank { "Authorization" }, apiKey) else apiKey.trim()
        )
    }
    var showTestDialog by remember { mutableStateOf(false) }
    val testingModels = remember { mutableStateMapOf<String, Boolean>() }
    val modelTestResults = remember { mutableStateMapOf<String, Result<String>>() }
    var typeExpanded by remember { mutableStateOf(false) }
    var authExpanded by remember { mutableStateOf(false) }
    var supportStream by remember { mutableStateOf(initialProvider.config.supportStream) }
    val headerRows = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initialProvider.config.headers.entries.map { it.key to it.value })
        }
    }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val selectedTab = pagerState.currentPage
    var modelConfigs by remember {
        mutableStateOf(initialProvider.config.modelIds?.associateWith {
            initialProvider.config.modelConfigs[it] ?: ModelConfiguration()
        } ?: initialProvider.config.modelConfigs)
    }
    var modelOrder by remember { mutableStateOf(modelConfigs.keys.toList()) }
    var configuringModel by remember { mutableStateOf<String?>(null) }
    var showModelCatalog by remember { mutableStateOf(false) }
    val activeHeaders = headerRows.filterNot { (key, value) -> key.isBlank() && value.isBlank() }
    val headersValid = activeHeaders.all { (key, value) ->
        key.isNotBlank() &&
            key.none { it <= ' ' || it == ':' || it.code >= 127 } &&
            value.none { it == '\r' || it == '\n' }
    } && activeHeaders.map { it.first.lowercase() }.distinct().size == activeHeaders.size
    val parsedHeaders = if (headersValid) activeHeaders.associate { it.first.trim() to it.second } else null
    val urlValid = runCatching { org.starfall.multigateway.data.tools.providerBase(initialProvider.copy(baseUrl = baseUrl)) }.isSuccess
    val authValid = apiKey.isBlank() || authMethod !in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM) ||
        authName.isBlank() || authName.none { it <= ' ' || it == ':' || it.code >= 127 }
    val requestValid = urlValid && authValid && headersValid
    fun requestConfig() = initialProvider.config.copy(
        supportStream = supportStream, headers = parsedHeaders ?: initialProvider.config.headers,
        modelConfigs = modelOrder.associateWith { modelConfigs.getValue(it) }, modelIds = modelOrder
    )
    fun dismissEditor() { onDismiss() }
    BackHandler(enabled = LocalScreenTransitionActive.current && configuringModel == null, onBack = ::dismissEditor)
    fun updateModels(updated: Map<String, ModelConfiguration>) {
        val existingIds = modelOrder.toSet()
        val nextOrder = modelOrder.filter { it in updated } + updated.keys.filterNot { it in existingIds }
        modelOrder = nextOrder
        modelConfigs = updated
        if (providerPersisted) onSaveModels(initialProvider.id, nextOrder.associateWith { updated.getValue(it) })
    }

    SlideScreenContent(editor = configuringModel, label = "Model editor") { editingModelId ->
    if (editingModelId != null) {
        key(editingModelId) {
            ModelEditScreen(
                provider = initialProvider.copy(type = type, config = requestConfig()),
                initialModelId = editingModelId,
                existingModelIds = modelConfigs.keys,
                onSave = { newId, config ->
                    updateModels((modelConfigs - editingModelId) + (newId to config))
                    configuringModel = null
                },
                onBack = { configuringModel = null }
            )
        }
    } else {
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) stringResource(R.string.new_provider) else name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = ::dismissEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        Button(
                            enabled = requestValid && baseUrl.isNotBlank() &&
                                !oauthAuthorizing && !iconImporting,
                            onClick = {
                                onSave(initialProvider.copy(
                                    name = name.trim().ifEmpty { type.defaultName },
                                    icon = providerIcon,
                                    type = type,
                                    baseUrl = baseUrl.trim(),
                                    auth = authorization(),
                                    config = requestConfig()
                                ))
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text(stringResource(R.string.common_save)) }
                    } else {
                        IconButton(
                            onClick = { showTestDialog = true },
                            enabled = onTestConnection != null && baseUrl.isNotBlank() && requestValid && modelConfigs.isNotEmpty()
                        ) { Icon(Icons.Outlined.NetworkCheck, contentDescription = stringResource(R.string.test_connection_lower)) }
                        IconButton(onClick = { configuringModel = "" }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_model_manually)
                            )
                        }
                        IconButton(onClick = { showModelCatalog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.FormatListBulleted,
                                contentDescription = stringResource(R.string.open_model_catalog)
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.provider_configuration)) })
                Tab(selected = selectedTab == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.provider_models)) })
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconPickerRow(providerIcon, onChange = { providerIcon = it },
                        fallback = Icons.Outlined.Hub, onBusyChange = { iconImporting = it }, matchName = name)
                    // Provider Type Dropdown
                    ExposedDropdownMenuBox(
                        expanded = typeExpanded,
                        onExpandedChange = { typeExpanded = !typeExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = type.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.common_type)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            ProviderType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.displayName) },
                                    onClick = {
                                        if (t != type) {
                                            val updated = initialProvider.copy(
                                                name = name,
                                                type = type,
                                                baseUrl = baseUrl
                                            ).withType(t)
                                            name = updated.name
                                            baseUrl = updated.baseUrl
                                            type = updated.type
                                            val defaultAuth = t.defaultAuthorization()
                                            authMethod = defaultAuth.method
                                            authName = defaultAuth.key.orEmpty()
                                            apiKey = defaultAuth.value.orEmpty()
                                            oauthAuthorized = false
                                            oauthIdentity = null
                                            oauthMarker = ""
                                            oauthAuthError = null
                                        }
                                        typeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.common_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.provider_base_url)) },
                        isError = !urlValid,
                        supportingText = { if (!urlValid) Text(stringResource(R.string.provider_invalid_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Default URL suggestions for each provider type
                    when (type) {
                        ProviderType.OLLAMA -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://ollama.com/api" },
                                label = { Text(stringResource(R.string.provider_default_url, "https://ollama.com/api"), fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.OPENAI_CODEX, ProviderType.CLAUDE_CODE, ProviderType.ANTIGRAVITY, ProviderType.GITHUB_COPILOT -> {
                            SuggestionChip(
                                onClick = { baseUrl = type.defaultBaseUrl },
                                label = { Text(stringResource(R.string.provider_default_url, type.defaultBaseUrl), fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://api.openai.com/v1" },
                                label = { Text(stringResource(R.string.provider_default_url, "https://api.openai.com/v1"), fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.GOOGLE -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://generativelanguage.googleapis.com/v1beta" },
                                label = { Text(stringResource(R.string.provider_default_url, "https://generativelanguage.googleapis.com/v1beta"), fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.ANTHROPIC -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://api.anthropic.com/v1" },
                                label = { Text(stringResource(R.string.provider_default_url, "https://api.anthropic.com/v1"), fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }

                    if (baseUrl.trim().startsWith("http://", true)) {
                        Text(stringResource(R.string.http_unencrypted_provider_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Text(stringResource(R.string.authorization), style = MaterialTheme.typography.titleMedium)
                    if (!type.isAccountProvider) Box {
                        OutlinedButton(onClick = { authExpanded = true }) {
                            Text(authMethod.displayName())
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                            listOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE)
                                .forEach { method ->
                                    DropdownMenuItem(
                                        text = { Text(method.displayName()) },
                                        onClick = {
                                            authMethod = method
                                            authName = if (method == AuthMethod.QUERY_PARAM) "key" else "Authorization"
                                            oauthAuthError = null
                                            authExpanded = false
                                        }
                                    )
                                }
                        }
                    }

                    if (authMethod == AuthMethod.OAUTH) {
                        OAuthAccountCard(
                            signedIn = oauthAuthorized,
                            identity = oauthIdentity,
                            signingIn = oauthAuthorizing && !oauthClearing,
                            signingOut = oauthClearing,
                            canSignIn = onAuthorizeProvider != null,
                            canSignOut = onClearOAuthCredentials != null,
                            error = oauthAuthError,
                            onSignIn = org.starfall.multigateway.ui.components.rememberOAuthStart {
                                coroutineScope.launch {
                                    oauthAuthorizing = true
                                    oauthAuthError = null
                                    val draft = initialProvider.copy(
                                        name = name.trim().ifEmpty { type.defaultName },
                                    icon = providerIcon,
                                        type = type,
                                        baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl },
                                        auth = Authorization(
                                            AuthMethod.OAUTH,
                                            key = oauthIdentity,
                                            value = oauthMarker
                                        ),
                                        config = requestConfig()
                                    )
                                    val result = onAuthorizeProvider?.invoke(draft)
                                        ?: Result.failure(IllegalStateException("OAuth is unavailable for this provider."))
                                    result.onSuccess { authorized ->
                                        providerPersisted = true
                                        oauthAuthorized = true
                                        oauthIdentity = authorized.auth.key
                                        oauthMarker = authorized.auth.value.orEmpty()
                                        baseUrl = authorized.baseUrl
                                    }.onFailure { error ->
                                        oauthAuthError = error.message ?: "OAuth authorization failed."
                                    }
                                    oauthAuthorizing = false
                                }
                            },
                            onSignOut = {
                                coroutineScope.launch {
                                    oauthAuthorizing = true
                                    oauthClearing = true
                                    oauthAuthError = null
                                    val current = initialProvider.copy(
                                        name = name.trim().ifEmpty { type.defaultName },
                                    icon = providerIcon,
                                        type = type,
                                        baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl },
                                        auth = authorization(),
                                        config = requestConfig()
                                    )
                                    val result = onClearOAuthCredentials?.invoke(current)
                                        ?: Result.failure(IllegalStateException("OAuth credential removal is unavailable."))
                                    result.onSuccess {
                                        oauthAuthorized = false
                                        oauthIdentity = null
                                        oauthMarker = ""
                                    }.onFailure { error ->
                                        oauthAuthError = error.message ?: "Could not remove OAuth credentials."
                                    }
                                    oauthClearing = false
                                    oauthAuthorizing = false
                                }
                            }
                        )
                    } else if (authMethod != AuthMethod.NONE) {
                        if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) {
                            OutlinedTextField(authName, { authName = it }, label = { Text(if (authMethod == AuthMethod.BEARER_TOKEN) "Header key" else "Query parameter name") },
                                isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(if (authMethod == AuthMethod.PLATFORM_DEFAULT) "API Key (Optional)" else "Value (Optional)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().onFocusChanged {
                                if (!it.isFocused && authMethod == AuthMethod.BEARER_TOKEN) {
                                    apiKey = bearerHeaderValue(authName.trim().ifBlank { "Authorization" }, apiKey)
                                }
                            }
                        )
                    }

                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stream), modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Switch(checked = supportStream, onCheckedChange = { supportStream = it })
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.custom_headers), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { headerRows.add("" to "") }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_header))
                        }
                    }
                    Text(
                        "Add custom HTTP headers for provider requests",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    headerRows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = row.first,
                                onValueChange = { headerRows[index] = it to headerRows[index].second },
                                label = { Text(stringResource(R.string.common_key)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = row.second,
                                onValueChange = { headerRows[index] = headerRows[index].first to it },
                                label = { Text(stringResource(R.string.common_value)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                headerRows.removeAt(index)
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_header))
                            }
                        }
                    }
                    if (!headersValid) {
                        Text(
                            "Header names and values must be valid and header names must be unique.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                val models = modelOrder
                fun moveModel(from: Int, to: Int) {
                    modelOrder = modelOrder.moved(from, to)
                }
                fun persistModelOrder() {
                    if (providerPersisted) onReorderModels(initialProvider.id, modelOrder)
                }
                val modelListState = rememberLazyListState()
                val reorderState = rememberLazyListReorderState(
                    listState = modelListState,
                    onMove = ::moveModel,
                    onDrop = ::persistModelOrder
                )
                LazyColumn(
                    state = modelListState,
                    modifier = Modifier.fillMaxSize().reorderGestures(reorderState),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (models.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "No models have been added to this provider yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(onClick = { configuringModel = "" }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.add_manually))
                                    }
                                    Button(onClick = { showModelCatalog = true }) {
                                        Icon(Icons.Outlined.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.open_model_catalog))
                                    }
                                }
                            }
                        }
                    }
                    items(models, key = { it }) { modelId ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                                .animateItem(placementSpec = if (reorderState.draggedKey == modelId) null else spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow))
                                .reorderItem(reorderState, modelId)
                                .clickable { configuringModel = modelId }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                EntityIcon(modelConfigs[modelId]?.icon, Modifier.size(42.dp),
                                    text = org.starfall.multigateway.ui.chat.modelInitial(modelId),
                                    matchName = modelConfigs[modelId]?.displayName?.ifBlank { modelId } ?: modelId, model = true)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val model = modelConfigs.getValue(modelId)
                                    Text(model.displayName.ifBlank { modelId },
                                        style = MaterialTheme.typography.titleMedium,
                                        overflow = TextOverflow.Ellipsis, maxLines = 2)
                                    if (model.displayName.isNotBlank()) Text(modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ModelCapabilityBadges(model)
                                }
                                ItemOverflowMenu(
                                    onEdit = { configuringModel = modelId },
                                    onDelete = { updateModels(modelConfigs - modelId) },
                                    deleteColor = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
    if (showTestDialog) {
        val testTarget = initialProvider.copy(
            name = name.trim().ifEmpty { "Provider" },
            type = type,
            baseUrl = baseUrl.trim(),
            auth = authorization(),
            config = requestConfig()
        )
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text(stringResource(R.string.test_connection)) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            "Use the Test button for a model to start testing. Results appear directly below that model.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(modelOrder, key = { it }) { modelId ->
                        val testing = testingModels[modelId] == true
                        val result = modelTestResults[modelId]
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        val config = modelConfigs[modelId] ?: ModelConfiguration()
                                        Text(config.displayName.ifBlank { modelId }, fontWeight = FontWeight.SemiBold)
                                        if (config.displayName.isNotBlank()) {
                                            Text(modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        enabled = !testing,
                                        onClick = {
                                            testingModels[modelId] = true
                                            modelTestResults.remove(modelId)
                                            coroutineScope.launch {
                                                val tested = try {
                                                    onTestConnection?.invoke(testTarget, modelId)
                                                        ?: Result.failure(IllegalStateException("Connection testing is unavailable."))
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Result.failure(e)
                                                }
                                                modelTestResults[modelId] = tested
                                                testingModels[modelId] = false
                                            }
                                        }
                                    ) {
                                        if (testing) {
                                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Outlined.NetworkCheck, contentDescription = stringResource(R.string.test_model, modelId))
                                        }
                                    }
                                    IconButton(
                                        enabled = !testing,
                                        onClick = {
                                            testingModels.remove(modelId)
                                            modelTestResults.remove(modelId)
                                            updateModels(modelConfigs - modelId)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.delete_model, modelId),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                result?.let { tested ->
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            if (tested.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (tested.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            tested.getOrNull() ?: tested.exceptionOrNull()?.localizedMessage ?: stringResource(R.string.connection_failed),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (tested.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTestDialog = false }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    if (showModelCatalog) {
        ProviderModelCatalogSheet(
            provider = initialProvider.copy(
                name = name, type = type, baseUrl = baseUrl.trim(),
                auth = authorization(),
                config = requestConfig()
            ),
            selectedModels = modelConfigs.keys,
            onFetchModels = onFetchModels,
            onToggle = { id ->
                updateModels(if (id in modelConfigs) modelConfigs - id else modelConfigs + (id to ModelConfiguration()))
            },
            onSetSelection = { ids, selected ->
                updateModels(if (selected) {
                    modelConfigs + ids.associateWith { modelConfigs[it] ?: ModelConfiguration() }
                } else {
                    modelConfigs - ids.toSet()
                })
            },
            onDismiss = { showModelCatalog = false }
        )
    }

    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelCatalogSheet(
    provider: LlmProviderInfo,
    selectedModels: Set<String>,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)?,
    onToggle: (String) -> Unit,
    onSetSelection: (List<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        loading = true
        loadError = null
        try {
            models = (onFetchModels ?: error("Model discovery is unavailable"))(provider).distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.localizedMessage ?: "Unable to load models"
        } finally {
            loading = false
        }
    }
    // The list and bulk action use exactly the same filtered IDs.
    val visibleModels = (models + selectedModels).distinct().filter { it.contains(query, ignoreCase = true) }
    val allVisibleSelected = visibleModels.isNotEmpty() && visibleModels.all { it in selectedModels }
    AppBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh_models))
                }
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_models)) },
                    singleLine = true, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onSetSelection(visibleModels, !allVisibleSelected) },
                    enabled = visibleModels.isNotEmpty()
                ) {
                    Icon(
                        imageVector = if (allVisibleSelected) Icons.Outlined.Close else Icons.Outlined.SelectAll,
                        contentDescription = stringResource(if (allVisibleSelected) R.string.remove_all_visible_models else R.string.select_all_visible_models),
                        tint = when {
                            visibleModels.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            allVisibleSelected -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.common_retry)) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (visibleModels.isEmpty() && !loading && loadError == null) {
                    item { Text(stringResource(if (query.isBlank()) R.string.no_models_returned else R.string.no_matching_models), modifier = Modifier.padding(16.dp)) }
                }
                items(visibleModels, key = { it }) { id ->
                    val added = id in selectedModels
                    ListItem(
                        headlineContent = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(if (added) R.string.model_added else R.string.model_not_added)) },
                        trailingContent = {
                            IconButton(onClick = { onToggle(id) }) {
                                Icon(if (added) Icons.Outlined.Close else Icons.Default.Add,
                                    contentDescription = stringResource(if (added) R.string.remove_model else R.string.add_model, id),
                                    tint = if (added) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
    }
}