package org.starfall.multigateway

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.providers.formatCatalogMetadata
import java.time.ZoneId

class ModelCatalogMetadataTest {
    private val utc = ZoneId.of("UTC")

    @Test fun convertsUnixSecondsAndMillisecondsButPreservesOtherNumbers() {
        assertEquals("2024-01-01 00:00:00 UTC", formatCatalogMetadata("created", JsonPrimitive(1704067200L), utc))
        assertEquals("2024-01-01 00:00:00 UTC", formatCatalogMetadata("updatedAt", JsonPrimitive(1704067200000L), utc))
        assertEquals("1704067200", formatCatalogMetadata("context_window", JsonPrimitive(1704067200L), utc))
        assertEquals("2024-01-01T00:00:00Z", formatCatalogMetadata("created_at", JsonPrimitive("2024-01-01T00:00:00Z"), utc))
    }

    @Test fun nestedDatesUseSelectedZoneAndInvalidDatesRemainReadable() {
        val metadata = buildJsonObject {
            put("created_at", 1704067200L)
            put("tokens", 128000)
        }
        val display = formatCatalogMetadata("details", metadata, ZoneId.of("Asia/Ho_Chi_Minh"))
        assertTrue(display.contains("2024-01-01 07:00:00"))
        assertTrue(display.contains("128000"))
        assertEquals("unknown", formatCatalogMetadata("created", JsonPrimitive("unknown"), utc))
    }
}
