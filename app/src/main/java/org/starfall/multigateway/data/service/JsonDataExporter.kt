package org.starfall.multigateway.data.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.model.SpeechService

enum class JsonExportKind(val fileName: String) {
    PROVIDERS("multigateway-providers.json"),
    MCP_SERVERS("multigateway-mcp.json"),
    SPEECH_SERVICES("multigateway-speech-services.json"),
    CONVERSATIONS("multigateway-chats.json")
}

private val exportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun buildJsonExport(
    kind: JsonExportKind,
    providers: List<LlmProviderInfo>,
    mcpServers: List<McpInfo>,
    speechServices: List<SpeechService>,
    conversations: List<Conversation>
): String {
    val items: JsonElement = when (kind) {
        JsonExportKind.PROVIDERS -> exportJson.parseToJsonElement(exportJson.encodeToString(providers))
        JsonExportKind.MCP_SERVERS -> exportJson.parseToJsonElement(exportJson.encodeToString(mcpServers))
        JsonExportKind.SPEECH_SERVICES -> exportJson.parseToJsonElement(exportJson.encodeToString(speechServices))
        JsonExportKind.CONVERSATIONS -> exportJson.parseToJsonElement(exportJson.encodeToString(conversations))
    }
    return exportJson.encodeToString(buildJsonObject {
        put("formatVersion", 1)
        put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        put("type", kind.name.lowercase(Locale.US))
        put("items", items)
    })
}

private fun importedItems(raw: String, kind: JsonExportKind): JsonElement {
    val root = exportJson.parseToJsonElement(raw).jsonObject
    require(root["formatVersion"]?.jsonPrimitive?.int == 1) {
        "Unsupported JSON export version."
    }
    require(root["type"]?.jsonPrimitive?.content == kind.name.lowercase(Locale.US)) {
        "This file is not a ${kind.name.lowercase(Locale.US)} export."
    }
    return root["items"] ?: error("The JSON export has no items.")
}

fun parseProvidersExport(raw: String): List<LlmProviderInfo> =
    exportJson.decodeFromString(importedItems(raw, JsonExportKind.PROVIDERS).toString())

fun parseMcpExport(raw: String): List<McpInfo> =
    exportJson.decodeFromString(importedItems(raw, JsonExportKind.MCP_SERVERS).toString())

fun parseSpeechServicesExport(raw: String): List<SpeechService> =
    exportJson.decodeFromString(importedItems(raw, JsonExportKind.SPEECH_SERVICES).toString())

fun parseConversationsExport(raw: String): List<Conversation> =
    exportJson.decodeFromString(importedItems(raw, JsonExportKind.CONVERSATIONS).toString())
