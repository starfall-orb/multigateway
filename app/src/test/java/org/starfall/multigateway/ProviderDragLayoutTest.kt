package org.starfall.multigateway

import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.*

class ProviderDragLayoutTest {
    private fun p(id: String, group: String? = null) = LlmProviderInfo(id, id, ProviderType.OPENAI, baseUrl = "", groupId = group)
    private fun slot(id: String, group: Boolean = false) = ProviderRootOrderItem(id, group)
    private val original = ProviderDragLayout(listOf(p("outside"), p("a", "g"), p("b", "g"), p("tail")),
        listOf(slot("outside"), slot("g", true), slot("tail")))
    private fun ProviderDragLayout.members(group: String) = providers.filter { it.groupId == group }.map { it.id }

    @Test fun hoveringMemberAddsProviderImmediatelyAtHoveredPosition() {
        val next = moveProviderDrag(original, "outside", slot("a"))
        assertEquals("g", next.providers.first { it.id == "outside" }.groupId)
        assertEquals(listOf("a", "outside", "b"), next.members("g"))
        assertEquals(listOf(slot("g", true), slot("tail")), next.root)
        assertEquals(null, original.providers.first { it.id == "outside" }.groupId)
    }

    @Test fun hoveringRootItemUngroupsAndPlacesProviderUsingTheSameMoveRule() {
        val next = moveProviderDrag(original, "a", slot("tail"))
        assertNull(next.providers.first { it.id == "a" }.groupId)
        assertEquals(listOf("b"), next.members("g"))
        assertEquals(listOf(slot("outside"), slot("g", true), slot("tail"), slot("a")), next.root)
    }

    @Test fun providersCanCrossFolderBlocksWithoutASeparateDelay() {
        val next = moveProviderDrag(original, "outside", slot("tail"))
        assertEquals(listOf(slot("g", true), slot("tail"), slot("outside")), next.root)
        assertEquals(listOf("a", "b"), next.members("g"))
    }

    @Test fun providerCanMoveBetweenFoldersAndReverseWithinTheNewFolder() {
        val layout = original.copy(providers = original.providers + p("c", "h"), root = original.root + slot("h", true))
        val next = moveProviderDrag(layout, "a", slot("c"))
        assertEquals(listOf("b"), next.members("g"))
        assertEquals(listOf("c", "a"), next.members("h"))
        assertEquals(listOf("a", "c"), moveProviderDrag(next, "a", slot("c")).members("h"))
        assertEquals(next, moveProviderDrag(next, "a", slot("a")))
    }

    @Test fun folderHeaderAcceptsFirstMemberAndEmptyAreaUngroupsLastMember() {
        val layout = original.copy(providers = listOf(p("outside"), p("tail")))
        val next = moveProviderDrag(layout, "outside", slot("g", true))
        assertEquals(listOf("outside"), next.members("g"))
        val end = moveProviderDrag(next, "outside", null)
        assertTrue(end.members("g").isEmpty())
        assertEquals(slot("outside"), end.root.last())
        val start = moveProviderDrag(next, "outside", null, atStart = true)
        assertEquals(slot("outside"), start.root.first())
    }
}
