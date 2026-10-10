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
    // Entering a folder from above must be able to take the first member's slot; otherwise that slot is
    // unreachable because the folder header appends to the end.
    val takesFirstMemberSlot = destination != null && provider.groupId != destination && targetProvider != null &&
        layout.providers.firstOrNull { it.groupId == destination }?.id == targetProvider.id
    val after = target != null && !target.isGroup && from >= 0 && from < to && !takesFirstMemberSlot
    val moved = provider.copy(groupId = destination)
    val remaining = layout.providers.filterNot { it.id == id }.toMutableList()
    if (targetProvider != null) {
        val index = remaining.indexOfFirst { it.id == targetProvider.id }
        remaining.add(index + if (after) 1 else 0, moved)
    } else if (destination != null) {
        val last = remaining.indexOfLast { it.groupId == destination }
        // A member hovering its own folder's header (the logo cell sits before the first member) means "first slot".
        val at = when {
            last < 0 -> remaining.size
            provider.groupId == destination -> remaining.indexOfFirst { it.groupId == destination }
            else -> last + 1
        }
        remaining.add(at, moved)
    } else remaining.add(if (atStart) 0 else remaining.size, moved)
    val root = layout.root.filterNot { !it.isGroup && it.id == id }.toMutableList()
    if (destination == null) {
        val index = targetProvider?.let { p -> root.indexOfFirst { !it.isGroup && it.id == p.id } } ?: -1
        root.add(if (index >= 0) index + if (after) 1 else 0 else if (atStart) 0 else root.size,
            ProviderRootOrderItem(id, false))
    }
    return ProviderDragLayout(remaining, root)
}

/**
 * Take a provider out of its folder and place it in the root order right after that folder.
 * Used when a member is dragged out of the open folder container, before the pointer has
 * picked a more specific root slot.
 */
internal fun ejectProviderFromFolder(layout: ProviderDragLayout, id: String, afterGroupId: String): ProviderDragLayout {
    val provider = layout.providers.firstOrNull { it.id == id } ?: return layout
    if (provider.groupId == null) return layout
    val providers = layout.providers.map { if (it.id == id) it.copy(groupId = null) else it }
    val root = layout.root.filterNot { !it.isGroup && it.id == id }.toMutableList()
    val folderIndex = root.indexOfFirst { it.isGroup && it.id == afterGroupId }
    root.add(if (folderIndex >= 0) folderIndex + 1 else root.size, ProviderRootOrderItem(id, false))
    return ProviderDragLayout(providers, root)
}
