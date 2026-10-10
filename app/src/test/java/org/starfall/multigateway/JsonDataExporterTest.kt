package org.starfall.multigateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.data.model.Authorization
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.model.SpeechService
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.service.JsonExportKind
import org.starfall.multigateway.data.service.buildJsonExport
import org.starfall.multigateway.data.service.parseConversationsExport
import org.starfall.multigateway.data.service.parseMcpExport
import org.starfall.multigateway.data.service.parseProvidersExport
import org.starfall.multigateway.data.service.parseSpeechServicesExport

class JsonDataExporterTest {
    @Test fun exportsEachCollectionAsVersionedJsonWithItsType() {
        val provider = LlmProviderInfo(
            id = "provider-1",
            name = "Test provider",
            type = ProviderType.OPENAI,
            auth = Authorization(AuthMethod.PLATFORM_DEFAULT, value = "api-key"),
            baseUrl = "https://example.com"
        )
        val mcp = McpInfo(id = "mcp-1", name = "Test MCP", url = "https://example.com/mcp")
        val speech = SpeechService(id = "speech-1", name = "Test speech", apiKey = "speech-key")
        val chat = Conversation(id = "chat-1", title = "Test chat", createdAt = 1, updatedAt = 2)

        val outputs = JsonExportKind.entries.map { kind ->
            Json.parseToJsonElement(
                buildJsonExport(kind, listOf(provider), listOf(mcp), listOf(speech), listOf(chat))
            ).jsonObject
        }

        assertEquals(JsonExportKind.entries.size, outputs.size)
        outputs.forEachIndexed { index, output ->
            assertEquals(1, output.getValue("formatVersion").jsonPrimitive.int)
            assertEquals(JsonExportKind.entries[index].name.lowercase(), output.getValue("type").jsonPrimitive.content)
            assertTrue(output.getValue("items").toString().isNotBlank())
        }
        assertTrue(outputs[0].getValue("items").toString().contains("api-key"))
        assertTrue(outputs[1].getValue("items").toString().contains("Test MCP"))
        assertTrue(outputs[2].getValue("items").toString().contains("speech-key"))
        assertTrue(outputs[3].getValue("items").toString().contains("Test chat"))
    }

    @Test fun exportedCollectionsCanBeParsedBackIntoTheirOriginalModels() {
        val provider = LlmProviderInfo("p", "Provider", ProviderType.OPENAI, baseUrl = "https://example.com")
        val mcp = McpInfo("m", "MCP", url = "https://example.com/mcp")
        val speech = SpeechService("s", "Speech")
        val chat = Conversation("c", "Chat", createdAt = 10, updatedAt = 20)

        assertEquals(provider, parseProvidersExport(buildJsonExport(
            JsonExportKind.PROVIDERS, listOf(provider), listOf(), listOf(), listOf()
        )).single())
        assertEquals(mcp, parseMcpExport(buildJsonExport(
            JsonExportKind.MCP_SERVERS, listOf(), listOf(mcp), listOf(), listOf()
        )).single())
        assertEquals(speech, parseSpeechServicesExport(buildJsonExport(
            JsonExportKind.SPEECH_SERVICES, listOf(), listOf(), listOf(speech), listOf()
        )).single())
        assertEquals(chat, parseConversationsExport(buildJsonExport(
            JsonExportKind.CONVERSATIONS, listOf(), listOf(), listOf(), listOf(chat)
        )).single())
    }
}
