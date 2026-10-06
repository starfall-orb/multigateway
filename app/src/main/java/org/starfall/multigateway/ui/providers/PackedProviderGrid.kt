package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
                // Diagonal cells have a concave inner corner. Do not add a
                // rectangular bridge: its stroke necessarily protrudes toward
                // the lower-right outside of the folder. The two rounded cell
                // outlines remain clean and meet at the shared folder region.
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
    return regions
}

internal fun packedGroupPath(parts: List<Rect>, cellRects: Set<Rect>, origin: Offset, corner: Float): Path {
    var outline = Path()
    parts.forEach { rect ->
        val local = rect.translate(-origin)
        val part = Path().apply {
            if (rect in cellRects) addRoundRect(RoundRect(local, CornerRadius(corner)))
            else addRect(local)
        }
        outline = Path.combine(PathOperation.Union, outline, part)
    }
    return outline
}

@Composable
internal fun PackedProviderGrid(
    cells: List<PackedGridCell>,
    onGroupBoundsChanged: (String, List<Rect>) -> Unit,
    onCellBoundsChanged: (String, Rect?) -> Unit,
    content: @Composable (Int) -> Unit
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentBoundsCallback by rememberUpdatedState(onGroupBoundsChanged)
    val currentCellBoundsCallback by rememberUpdatedState(onCellBoundsChanged)
    val regions = packedGroupRegions(cells, bounds)
    val groupIds = cells.mapNotNull { it.groupId }.toSet()
    DisposableEffect(groupIds) {
        onDispose { groupIds.forEach { currentBoundsCallback(it, emptyList()) } }
    }
    LaunchedEffect(regions, groupIds) {
        groupIds.forEach { currentBoundsCallback(it, regions[it].orEmpty()) }
    }
    val cellRects = bounds.values.toSet()
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        regions.values.forEach { parts ->
            val outline = packedGroupPath(parts, cellRects, origin, 20.dp.toPx())
            drawPath(outline, background)
            // Keep the concave transition inside the union envelope. Applying a
            // stroke corner effect here rounds both sides of the path and makes
            // the inner join bulge outward.
            drawPath(outline, border, style = Stroke(1.5.dp.toPx()))
        }
    }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("provider_list")
        ) {
            itemsIndexed(cells, key = { _, cell -> cell.key }) { index, cell ->
                DisposableEffect(cell.key) { onDispose { bounds.remove(cell.key); currentCellBoundsCallback(cell.key, null) } }
                Box(Modifier.animateItem().fillMaxWidth().height(164.dp)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        val rect = Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                        bounds[cell.key] = rect
                        currentCellBoundsCallback(cell.key, rect)
                    }
                    .padding(6.dp)) {
                    content(index)
                }
            }
        }
    }
}
