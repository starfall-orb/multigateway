package org.starfall.multigateway.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatFolder(val id: String, val name: String, val pinned: Boolean = false, val expanded: Boolean = true)

@Serializable
data class SidebarOrganization(
    val folders: List<ChatFolder> = emptyList(),
    val chatFolders: Map<String, String> = emptyMap(),
    val pinnedChatIds: Set<String> = emptySet()
) {
    fun moveChats(ids: Set<String>, folderId: String?): SidebarOrganization {
        if (folderId != null && folders.none { it.id == folderId }) return this
        return copy(chatFolders = (chatFolders - ids) + if (folderId == null) emptyMap() else ids.associateWith { folderId })
    }
    fun removeChats(ids: Set<String>) = copy(chatFolders = chatFolders - ids, pinnedChatIds = pinnedChatIds - ids)
    fun removeFolder(id: String) = copy(folders = folders.filterNot { it.id == id }, chatFolders = chatFolders.filterValues { it != id })
}
