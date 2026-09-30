package org.starfall.multigateway

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*

class SidebarOrganizationTest {
    private val state = SidebarOrganization(
        folders = listOf(ChatFolder("work", "Work"), ChatFolder("other", "Other")),
        chatFolders = mapOf("a" to "work", "b" to "work"),
        pinnedChatIds = setOf("a", "c")
    )

    @Test fun moveSelectedChatsReplacesMembershipWithoutChangingPins() {
        val moved = state.moveChats(setOf("a", "c"), "other")
        assertEquals(mapOf("a" to "other", "b" to "work", "c" to "other"), moved.chatFolders)
        assertEquals(state.pinnedChatIds, moved.pinnedChatIds)
        assertEquals(state.folders, moved.folders)
    }
    @Test fun removingFolderReturnsChatsToHistoryAndKeepsPins() {
        val removed = state.removeFolder("work")
        assertTrue(removed.chatFolders.isEmpty())
        assertEquals(listOf(state.folders[1]), removed.folders)
        assertEquals(state.pinnedChatIds, removed.pinnedChatIds)
    }
    @Test fun returningToHistoryAndDeletingChatsOnlyAffectsSelectedIds() {
        assertEquals(mapOf("b" to "work"), state.moveChats(setOf("a"), null).chatFolders)
        val removed = state.removeChats(setOf("a", "c"))
        assertEquals(mapOf("b" to "work"), removed.chatFolders)
        assertTrue(removed.pinnedChatIds.isEmpty())
        assertEquals(state.folders, removed.folders)
    }
    @Test fun missingFolderCannotCreateOrphanedMembership() {
        assertEquals(state, state.moveChats(setOf("c"), "missing"))
    }
    @Test fun serializedStatePreservesPinsAndCollapsedFolders() {
        val saved = state.copy(folders = listOf(ChatFolder("work", "Work", pinned = true, expanded = false)))
        assertEquals(saved, Json.decodeFromString<SidebarOrganization>(Json.encodeToString(saved)))
        assertEquals(SidebarOrganization(), Json.decodeFromString<SidebarOrganization>("{}"))
    }
}
