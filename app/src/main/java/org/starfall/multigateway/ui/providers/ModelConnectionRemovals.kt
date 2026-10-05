package org.starfall.multigateway.ui.providers

import androidx.compose.runtime.mutableStateListOf

/** Keep dialog rows stable; edits are reversible until the dialog is closed. */
internal class ModelConnectionRemovals(modelIds: Collection<String>) {
    val modelIds = modelIds.distinct()
    val pending = mutableStateListOf<String>()
    private var closed = false

    fun toggle(id: String) {
        if (closed || id !in modelIds) return
        if (!pending.remove(id)) pending.add(id)
    }

    fun mark(ids: Collection<String>) {
        if (closed) return
        pending.addAll(ids.filter { it in modelIds && it !in pending }.distinct())
    }

    fun restoreAll() {
        if (!closed) pending.clear()
    }

    fun close(remove: (Set<String>) -> Unit, dismiss: () -> Unit) {
        if (closed) return
        closed = true
        if (pending.isNotEmpty()) remove(pending.toSet())
        dismiss()
    }
}
