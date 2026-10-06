package org.starfall.multigateway

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.ProviderApiKeysState
import org.starfall.multigateway.ui.providers.maskedProviderApiKey

class ProviderApiKeysTest {
    private val identity: (String) -> String = { bearerHeaderValue("Authorization", it) }

    @Test fun existingExternalKeyIsImportedOnceAndMatchesBearerFormatting() {
        val state = ProviderApiKeysState("first-secret-key", true, emptyList())
        state.ensureCurrent(identity)
        val selected = state.selectedId(identity)
        state.currentKey = "Bearer first-secret-key"
        state.ensureCurrent(identity)
        assertEquals(1, state.entries.size)
        assertEquals(selected, state.selectedId(identity))
        state.currentKey = "another-secret-key"
        state.ensureCurrent(identity)
        assertEquals(2, state.entries.size)
        assertNotEquals(selected, state.selectedId(identity))
        assertEquals("first-secret-key", state.entries.first().value)
    }

    @Test fun editingAndDeletingActiveKeyKeepsExternalFieldInSync() {
        val state = ProviderApiKeysState("active-key", true, listOf(
            ProviderApiKey("a", "Primary", "active-key"), ProviderApiKey("b", "Backup", "backup-key")))
        state.save("a", " Renamed ", " updated-key ", identity)
        assertEquals("updated-key", state.currentKey)
        assertEquals("a", state.selectedId(identity))
        assertEquals("Renamed", state.entries.first().label)
        state.save("b", "Backup", "new-backup-key", identity)
        assertEquals("updated-key", state.currentKey)
        state.delete("a", identity)
        assertEquals("new-backup-key", state.currentKey)
        assertEquals("b", state.selectedId(identity))
        state.delete("b", identity)
        assertEquals("", state.currentKey)
        assertNull(state.selectedId(identity))
    }

    @Test fun addingKeyPreservesSelectionAndRejectsBlankAndDuplicateKeys() {
        val state = ProviderApiKeysState("selected-key", true, emptyList())
        state.ensureCurrent(identity)
        val selected = state.selectedId(identity)
        state.save(null, "Backup", "backup-key", identity)
        assertEquals(selected, state.selectedId(identity))
        assertTrue(runCatching { state.save(null, "", "  ", identity) }.isFailure)
        assertTrue(runCatching { state.save(null, "Duplicate", "Bearer selected-key", identity) }.isFailure)
        assertEquals(2, state.entries.size)
        state.currentKey = ""
        state.save(null, "First", "first-key", identity)
        assertEquals("first-key", state.currentKey)
    }

    @Test fun savedKeysAndPreferenceRoundTripWithoutChangingActiveAuthorization() {
        val legacy = Json.decodeFromString<ProviderConfiguration>("""{"headers":{"X-Client":"app"}}""")
        assertFalse(legacy.multipleApiKeys)
        assertTrue(legacy.apiKeys.isEmpty())
        val provider = LlmProviderInfo("p", "Provider", ProviderType.OPENAI,
            baseUrl = "https://example.com/v1", auth = Authorization(value = "active-key"),
            config = legacy.copy(multipleApiKeys = true, apiKeys = listOf(
                ProviderApiKey("a", "", "active-key"), ProviderApiKey("b", "Backup", "backup-key"))))
        val restored = Json.decodeFromString<LlmProviderInfo>(Json.encodeToString(LlmProviderInfo.serializer(), provider))
        assertEquals(provider, restored)
        assertEquals(mapOf("Authorization" to "Bearer active-key"), restored.auth.requestHeaders(restored.type))
        val state = ProviderApiKeysState(restored.auth.token, restored.config.multipleApiKeys, restored.config.apiKeys)
        assertEquals("a", state.selectedId(identity))
    }

    @Test fun unlabeledKeysShowOnlySmallFragmentsIncludingForShortKeys() {
        assertEquals("sk-a••••wxyz", maskedProviderApiKey("Bearer sk-abcdefgh-wxyz"))
        for (key in listOf("", "a", "ab", "abc", "abcdefgh", "long-secret-key")) {
            val masked = maskedProviderApiKey(key)
            assertTrue(masked.contains("••••"))
            if (key.isNotBlank()) assertFalse(masked.contains(key))
        }
    }
}
