package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.data.model.ToolDefinition
import org.starfall.multigateway.data.model.withUniqueWireNames
import org.starfall.multigateway.data.service.mcpToolWireName

class McpToolNameTest {
    @Test fun wireNameUsesReadableServerAndToolNames() {
        assertEquals("Context7_get_docs", mcpToolWireName("Context7", "get_docs", "context7"))
        assertEquals("My_Server_search_web", mcpToolWireName("My Server", "search/web", "server"))
        assertFalse(mcpToolWireName("Context7", "get_docs", "context7").startsWith("mcp_"))
    }

    @Test fun longNamesStayWithinProviderFunctionNameLimit() {
        val name = mcpToolWireName("Server".repeat(20), "tool".repeat(20), "server")

        assertTrue(name.length <= 64)
        assertTrue(name.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test fun duplicateReadableNamesAreDisambiguatedOnlyWhenNeeded() {
        val definitions = listOf(
            ToolDefinition("Context7_get_docs", "", kotlinx.serialization.json.buildJsonObject {}, "one", "get_docs"),
            ToolDefinition("Context7_get_docs", "", kotlinx.serialization.json.buildJsonObject {}, "two", "get_docs")
        ).withUniqueWireNames()

        assertEquals("Context7_get_docs", definitions[0].name)
        assertTrue(definitions[1].name.startsWith("Context7_get_docs_"))
        assertTrue(definitions.map { it.name }.toSet().size == definitions.size)
    }
}
