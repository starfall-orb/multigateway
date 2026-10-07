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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.ScrollMoveMode

internal data class PackedGridCell(val key: String, val groupId: String?)

/** Only join adjacent cells belonging to the same folder; root items keep their own space. */
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
                // With zero grid spacing, diagonal members meet at one corner.
                // Join the rounded corners with a tiny connector so the folder
                // outline does not show a pinhole at that shared corner.
                val upper = if (rect.center.y <= other.center.y) rect else other
                val x = if (rect.center.x < other.center.x) rect.right else rect.left
                val y = upper.bottom
                val connector = 2f
                parts += Rect(x - connector, y - connector, x + connector, y + connector)
                continue
            }
            val horizontal = nextIndex == index + 1
            // Placement animations can temporarily put adjacent slots in different rows.
            if (horizontal && (kotlin.math.abs(rect.center.y - other.center.y) > 1f || other.left <= rect.left)) continue
            if (!horizontal && (kotlin.math.abs(rect.center.x - other.center.x) > 1f || other.top <= rect.top)) continue
            // Overlap the rounded corners at the shared edge so the frame stays continuous.
            parts += if (nextIndex == index + 1)
                Rect(rect.center.x, maxOf(rect.top, other.top), other.center.x, minOf(rect.bottom, other.bottom))
            else Rect(maxOf(rect.left, other.left), rect.center.y, minOf(rect.right, other.right), other.center.y)
        }
    }
    // If a folder ends on an incomplete grid row, reserve the missing partner
    // slot only when that slot is genuinely empty. This keeps both bottom
    // corners aligned without drawing the folder over a root provider card.
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
        // Use rectangular primitives for the union. The result is one
        // continuous region instead of a collection of independently rounded
        // cards with visible seams between them.
        val part = Path().apply { addRect(local) }
        outline = Path.combine(PathOperation.Union, outline, part)
    }
    return outline
}

/** Fold a dragged folder into its keyed handle so Calvin can keep it alive during edge scrolling. */
internal fun providerDragCells(cells: List<PackedGridCell>, isGrid: Boolean, draggingKey: String?): List<PackedGridCell> = buildList {
    val draggedGroup = draggingKey?.takeIf { it.startsWith("group_") || it.startsWith("heading_") }
        ?.removePrefix("group_")?.removePrefix("heading_")
    add(PackedGridCell("root:start", null))
    cells.forEach { cell ->
        val heading = !isGrid && cell.key.startsWith("group_") && cell.groupId != null
        if (draggedGroup != null && (cell.groupId == draggedGroup || cell.key == "group_$draggedGroup")) {
            if (cell.key == draggingKey) add(cell)
            else if (heading && draggingKey == "heading_$draggedGroup") add(PackedGridCell(draggingKey, draggedGroup))
        } else {
            if (heading) add(PackedGridCell("heading_${cell.groupId}", cell.groupId))
            add(cell)
        }
    }
    add(PackedGridCell("root:end", null))
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
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val regions = if (isGrid) packedGroupRegions(cells, bounds) else buildMap {
        displayCells.filter { it.groupId != null }.groupBy { it.groupId!! }.forEach { (group, members) ->
            val rects = members.mapNotNull { bounds[it.key] }
            val parts = rects.toMutableList()
            rects.zipWithNext().forEach { (first, second) ->
                if (kotlin.math.abs(first.center.y - second.center.y) < 1f) {
                    parts += Rect(first.center.x, maxOf(first.top, second.top), second.center.x, minOf(first.bottom, second.bottom))
                } else if (second.top >= first.bottom) {
                    val left = maxOf(first.left, second.left)
                    val right = minOf(first.right, second.right)
                    if (right > left) parts += Rect(left, first.center.y, right, second.center.y)
                }
            }
            put(group, parts)
        }
    }
    val cellRects = bounds.values.toSet()
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
    Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        regions.values.forEach { parts ->
            val outline = packedGroupPath(parts, cellRects, origin, 20.dp.toPx())
            drawPath(outline, background)
            drawPath(outline, border, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
    }) {
        LazyVerticalGrid(state = gridState, columns = columns,
            // Each slot keeps 6.dp of internal padding around its card. Keeping
            // the slots adjacent lets the shared folder outline remain closed.
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxSize().testTag("provider_list")
        ) {
            items(displayCells, key = { it.key }, span = { cell ->
                val narrow = cell.groupId != null && (cell.key.startsWith("group_") || firstMembers[cell.groupId] == cell.key)
                GridItemSpan(if (cell.key == draggingKey) draggingSpan else if (cell.key.startsWith("root:") || (!isGrid && !narrow)) maxLineSpan else 1)
            }) { cell ->
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
                DisposableEffect(cell.key) {
                    onDispose {
                        bounds.remove(cell.key)
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
                    // The reorderable state already tracks the dragged item and
                    // the grid is being scrolled at the same time. A second
                    // placement animation makes the item briefly use two
                    // positions while previewMove updates the slots, which is
                    // especially visible at the bottom edge.
                    animateItemModifier = if (draggingKey == null) Modifier.animateItem() else Modifier
                ) { dragging ->
                    val handle = Modifier.longPressDraggableHandle(enabled = dragEnabled && !cell.key.startsWith("root:"),
                        interactionSource = interaction, onDragStarted = {
                            active = true
                            val narrow = cell.groupId != null && (cell.key.startsWith("group_") || firstMembers[cell.groupId] == cell.key)
                            draggingSpan = if (!isGrid && !narrow) 2 else 1
                            draggingKey = cell.key
                            onDragStarted(cell.key)
                        })
                    Box(Modifier.fillMaxWidth().height(height).zIndex(if (dragging) 100f else 0f)
                        .testTag(if (dragging && cell.key.startsWith("provider_")) "dragged_provider" else "provider_slot_${cell.key}")
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            bounds[cell.key] = Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                        }.padding(6.dp)) {
                        if (!cell.key.startsWith("root:")) content(cell.key, handle)
                    }
                }
            }
        }
    }
}
