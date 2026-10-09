package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.ScrollMoveMode

internal data class PackedGridCell(val key: String, val groupId: String?)

private val PackedGridCell.isFolderBoundary: Boolean
    get() = key.startsWith("folder-start_") || key.startsWith("folder-end_")

/** One full-width rectangle per folder, using grid slots rather than animated/dragged card bounds. */
internal fun packedGroupBlockBounds(
    cells: List<PackedGridCell>,
    bounds: Map<String, Rect>,
    viewport: Size,
    inset: Float
): Map<String, Rect> = buildMap {
    cells.filter { it.groupId != null }.groupBy { it.groupId!! }.forEach { (group, members) ->
        val visible = members.mapNotNull { bounds[it.key] }
        if (visible.isNotEmpty()) {
            // Lazy grids only measure visible rows. When an end is offscreen, extend beyond
            // the viewport so scrolling does not invent a rounded cap halfway down the folder.
            val top = bounds[members.first().key]?.top?.plus(inset)
                ?: minOf(visible.minOf { it.top }, -viewport.height)
            val bottom = bounds[members.last().key]?.bottom?.minus(inset)
                ?: maxOf(visible.maxOf { it.bottom }, viewport.height * 2)
            put(group, Rect(1f, top, (viewport.width - 1f).coerceAtLeast(1f), bottom))
        }
    }
}

/** Legacy geometry helpers retained for rendering regression tests and tooling. */
internal fun packedGroupRegions(cells: List<PackedGridCell>, bounds: Map<String, Rect>): Map<String, List<Rect>> {
    val regions = mutableMapOf<String, MutableList<Rect>>()
    cells.forEachIndexed { index, cell ->
        val group = cell.groupId ?: return@forEachIndexed
        val rect = bounds[cell.key] ?: return@forEachIndexed
        val parts = regions.getOrPut(group) { mutableListOf() }
        parts += rect
        for (nextIndex in listOf(index + 1, index + 2)) {
            val next = cells.getOrNull(nextIndex)?.takeIf { it.groupId == group } ?: continue
            val other = bounds[next.key] ?: continue
            val diagonal = nextIndex == index + 1 && index % 2 == 1
            if (diagonal) {
                val upper = if (rect.center.y <= other.center.y) rect else other
                val x = if (rect.center.x < other.center.x) rect.right else rect.left
                val y = upper.bottom
                val connector = 2f
                parts += Rect(x - connector, y - connector, x + connector, y + connector)
                continue
            }
            val horizontal = nextIndex == index + 1
            if (horizontal && (kotlin.math.abs(rect.center.y - other.center.y) > 1f || other.left <= rect.left)) continue
            if (!horizontal && (kotlin.math.abs(rect.center.x - other.center.x) > 1f || other.top <= rect.top)) continue
            parts += if (nextIndex == index + 1)
                Rect(rect.center.x, maxOf(rect.top, other.top), other.center.x, minOf(rect.bottom, other.bottom))
            else Rect(maxOf(rect.left, other.left), rect.center.y, minOf(rect.right, other.right), other.center.y)
        }
    }
    cells.map { it.groupId }.filterNotNull().distinct().forEach { group ->
        val lastIndex = cells.indexOfLast { it.groupId == group && bounds.containsKey(it.key) }
        val lastRect = cells.getOrNull(lastIndex)?.let { bounds[it.key] } ?: return@forEach
        val partnerIndex = if (lastIndex % 2 == 0) lastIndex + 1 else lastIndex - 1
        if (partnerIndex !in cells.indices) {
            val partner = if (lastIndex % 2 == 0) {
                Rect(lastRect.right, lastRect.top, lastRect.right + lastRect.width, lastRect.bottom)
            } else {
                Rect(lastRect.left - lastRect.width, lastRect.top, lastRect.left, lastRect.bottom)
            }
            regions.getOrPut(group) { mutableListOf() } += partner
        }
    }
    return regions
}

internal fun packedGroupPath(parts: List<Rect>, cellRects: Set<Rect>, origin: Offset, corner: Float): Path {
    var outline = Path()
    parts.forEach { rect ->
        val local = rect.translate(-origin)
        outline = Path.combine(PathOperation.Union, outline, Path().apply { addRect(local) })
    }
    return outline
}

/** Fold a dragged folder into its keyed handle so Calvin can keep it alive during edge scrolling. */
internal fun providerDragCells(cells: List<PackedGridCell>, isGrid: Boolean, draggingKey: String?): List<PackedGridCell> {
    val draggedGroup = draggingKey?.takeIf { it.startsWith("group_") || it.startsWith("heading_") }
        ?.removePrefix("group_")?.removePrefix("heading_")
    val contentCells = buildList {
        cells.forEach { cell ->
            val heading = !isGrid && cell.key.startsWith("group_") && cell.groupId != null
            if (draggedGroup != null && (cell.groupId == draggedGroup || cell.key == "group_$draggedGroup")) {
                if (cell.key == draggingKey) add(if (isGrid) cell.copy(groupId = null) else cell)
                else if (heading && draggingKey == "heading_$draggedGroup") add(PackedGridCell(draggingKey, draggedGroup))
            } else {
                if (heading) add(PackedGridCell("heading_${cell.groupId}", cell.groupId))
                add(cell)
            }
        }
    }
    return buildList {
        add(PackedGridCell("root:start", null))
        var activeGroup: String? = null
        var column = 0
        contentCells.forEach { cell ->
            val group = cell.groupId
            if (activeGroup != null && group != activeGroup) {
                add(PackedGridCell("folder-end_$activeGroup", activeGroup))
                activeGroup = null
                column = 0
            }
            // An even-positioned folder keeps its header beside the preceding root card.
            // Its members start a new block below; an odd-positioned header stays in the block.
            if (isGrid && group != null && cell.key.startsWith("group_") && column == 1) {
                add(cell.copy(groupId = null))
                column = 0
                return@forEach
            }
            // Full-span boundaries reserve whole rows for a folder. Root cards and other
            // folders can never occupy an empty half-row inside its rectangular container.
            if (group != null && activeGroup == null) {
                add(PackedGridCell("folder-start_$group", group))
                activeGroup = group
                column = 0
            }
            add(cell)
            column = (column + 1) % 2
        }
        activeGroup?.let { add(PackedGridCell("folder-end_$it", it)) }
        add(PackedGridCell("root:end", null))
    }
}

/** Folder membership changes happen in onMove; Calvin owns one drag/scroll surface in both modes. */
@Composable
internal fun PackedProviderGrid(
    cells: List<PackedGridCell>,
    isGrid: Boolean = true,
    dragEnabled: Boolean = true,
    onMove: (String, String) -> Unit,
    onDragStarted: (String) -> Unit,
    onDragFinished: (String, Boolean) -> Unit,
    content: @Composable ReorderableCollectionItemScope.(String, Modifier) -> Unit
) {
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingSpan by remember { mutableIntStateOf(1) }
    val displayCells = providerDragCells(cells, isGrid, draggingKey)
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState, scrollMoveMode = ScrollMoveMode.INSERT) { from, to ->
        onMove(from.key as String, to.key as String)
    }
    val folderCells = displayCells.filter { it.groupId != null }
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val border = MaterialTheme.colorScheme.outlineVariant
    val columns = remember(isGrid) {
        if (isGrid) GridCells.Fixed(2) else object : GridCells {
            override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
                val first = 100.dp.roundToPx().coerceAtMost((availableSize - spacing).coerceAtLeast(0))
                return listOf(first, (availableSize - spacing - first).coerceAtLeast(0))
            }
        }
    }
    val firstMembers = cells.filter { it.groupId != null && it.key.startsWith("provider_") }
        .groupBy { it.groupId }.mapValues { it.value.first().key }
    Box(Modifier.fillMaxSize().clipToBounds().drawBehind {
        val slots = gridState.layoutInfo.visibleItemsInfo.associate { item ->
            item.key.toString() to Rect(
                item.offset.x.toFloat(), item.offset.y.toFloat(),
                (item.offset.x + item.size.width).toFloat(), (item.offset.y + item.size.height).toFloat()
            )
        }
        val blocks = packedGroupBlockBounds(folderCells, slots, size, inset = 6.dp.toPx())
        blocks.forEach { (group, block) ->
            if (draggingKey == "group_$group" || draggingKey == "heading_$group") return@forEach
            val corner = 20.dp.toPx()
            drawRoundRect(
                color = background,
                topLeft = block.topLeft,
                size = block.size,
                cornerRadius = CornerRadius(corner, corner)
            )
            drawRoundRect(
                color = border,
                topLeft = block.topLeft,
                size = block.size,
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(1.dp.toPx())
            )
        }
    }) {
        LazyVerticalGrid(state = gridState, columns = columns,
            // One scroll/drag surface, with independent full-width folder blocks behind it.
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxSize().testTag("provider_list")
        ) {
            items(displayCells, key = { it.key }, span = { cell ->
                val narrow = cell.groupId != null && (cell.key.startsWith("group_") || firstMembers[cell.groupId] == cell.key)
                GridItemSpan(if (cell.isFolderBoundary) maxLineSpan else if (cell.key == draggingKey) draggingSpan
                    else if (cell.key.startsWith("root:") || (!isGrid && !narrow)) maxLineSpan else 1)
            }) { cell ->
                if (cell.isFolderBoundary) {
                    // Boundaries are not draggable, but they must still be registered with
                    // Calvin's reorder state so dropping a provider on a folder edge produces
                    // a real folder target instead of falling through to the root grid.
                    ReorderableItem(reorderState, key = cell.key, animateItemModifier = Modifier) {
                        Spacer(Modifier.fillMaxWidth().height(12.dp).testTag(cell.key))
                    }
                    return@items
                }
                val interaction = remember(cell.key) { MutableInteractionSource() }
                var active by remember(cell.key) { mutableStateOf(false) }
                val finish by rememberUpdatedState(onDragFinished)
                LaunchedEffect(interaction) {
                    interaction.interactions.collect { event ->
                        if (active && (event is DragInteraction.Stop || event is DragInteraction.Cancel)) {
                            active = false
                            draggingKey = null
                            finish(cell.key, event is DragInteraction.Cancel)
                        }
                    }
                }
                val height = when {
                    cell.key.startsWith("root:") -> 24.dp
                    cell.key.startsWith("heading_") -> 52.dp
                    isGrid -> 164.dp
                    else -> 100.dp
                }
                ReorderableItem(
                    reorderState,
                    key = cell.key,
                    // Other items animate to their new slots while dragging. The dragged item is excluded:
                    // the reorderable state positions it under the finger, and a placement animation on top
                    // of that makes it jump around the finger while the grid scrolls at the bottom edge.
                    animateItemModifier = if (cell.key == draggingKey) Modifier else Modifier.animateItem()
                ) { dragging ->
                    val handle = Modifier.longPressDraggableHandle(
                        enabled = dragEnabled && !cell.key.startsWith("root:") && !cell.isFolderBoundary,
                        interactionSource = interaction, onDragStarted = {
                            active = true
                            val narrow = cell.groupId != null && (cell.key.startsWith("group_") || firstMembers[cell.groupId] == cell.key)
                            draggingSpan = if (!isGrid && !narrow) 2 else 1
                            draggingKey = cell.key
                            onDragStarted(cell.key)
                        })
                    Box(Modifier.fillMaxWidth().height(height).zIndex(if (dragging) 100f else 0f)
                        .testTag(if (dragging && cell.key.startsWith("provider_")) "dragged_provider" else "provider_slot_${cell.key}")
                        .padding(6.dp)) {
                        if (!cell.key.startsWith("root:")) content(cell.key, handle)
                    }
                }
            }
        }
    }
}
