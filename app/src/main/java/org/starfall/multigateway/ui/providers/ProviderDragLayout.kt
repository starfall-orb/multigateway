package org.starfall.multigateway.ui.providers

import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderRootOrderItem

internal data class ProviderDragLayout(val providers: List<LlmProviderInfo>, val root: List<ProviderRootOrderItem>) {
    fun slots(): List<ProviderRootOrderItem> = root.flatMap { item ->
        if (item.isGroup) listOf(item) + providers.filter { it.groupId == item.id }.map { ProviderRootOrderItem(it.id, false) }
        else listOf(item)
    }
}

/** Preview a move at the hovered slot, including membership changes, without writing storage. */
internal fun moveProviderDrag(layout: ProviderDragLayout, id: String, target: ProviderRootOrderItem?, atStart: Boolean = false): ProviderDragLayout {
    val provider = layout.providers.firstOrNull { it.id == id } ?: return layout
    if (target?.id == id && !target.isGroup) return layout
    val targetProvider = target?.takeUnless { it.isGroup }?.let { t -> layout.providers.firstOrNull { it.id == t.id } }
    if (target != null && !target.isGroup && targetProvider == null) return layout
    if (target?.isGroup == true && target !in layout.root) return layout
    val destination = if (target?.isGroup == true) target.id else targetProvider?.groupId?.takeIf { group -> layout.root.any { it.isGroup && it.id == group } }
    val slots = layout.slots()
    val from = slots.indexOf(ProviderRootOrderItem(id, false))
    val to = slots.indexOf(target)
    val after = target != null && !target.isGroup && from >= 0 && from < to
    val moved = provider.copy(groupId = destination)
    val remaining = layout.providers.filterNot { it.id == id }.toMutableList()
    if (targetProvider != null) {
        val index = remaining.indexOfFirst { it.id == targetProvider.id }
        remaining.add(index + if (after) 1 else 0, moved)
    } else if (destination != null) {
        val last = remaining.indexOfLast { it.groupId == destination }
        remaining.add(if (last < 0) remaining.size else last + 1, moved)
    } else remaining.add(if (atStart) 0 else remaining.size, moved)
    val root = layout.root.filterNot { !it.isGroup && it.id == id }.toMutableList()
    if (destination == null) {
        val index = targetProvider?.let { p -> root.indexOfFirst { !it.isGroup && it.id == p.id } } ?: -1
        root.add(if (index >= 0) index + if (after) 1 else 0 else if (atStart) 0 else root.size,
            ProviderRootOrderItem(id, false))
    }
    return ProviderDragLayout(remaining, root)
}
