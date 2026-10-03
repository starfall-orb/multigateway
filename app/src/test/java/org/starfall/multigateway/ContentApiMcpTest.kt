package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.data.model.CONTENT_API_MCP_ENDPOINT
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.model.isContentApiName
import org.starfall.multigateway.data.model.resolvedUrl

class ContentApiMcpTest {
    @Test
    fun recognizedNamesUseHiddenDefaultEndpoint() {
        listOf("Content API", "contentapi", "CONTENT-API").forEach { name ->
            assertEquals(CONTENT_API_MCP_ENDPOINT, McpInfo("id", name).resolvedUrl())
            assertTrue(name.isContentApiName())
        }
        assertNull(McpInfo("id", "Another server").resolvedUrl())
    }

    @Test
    fun explicitUrlAlwaysWins() {
        assertEquals(
            "https://example.test/custom",
            McpInfo("id", "Content API", url = " https://example.test/custom ").resolvedUrl()
        )
    }
}
