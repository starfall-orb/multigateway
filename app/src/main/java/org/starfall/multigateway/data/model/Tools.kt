package org.starfall.multigateway.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class McpAccess(val enabled: Boolean = false, val tools: Map<String, Boolean> = emptyMap())

@Serializable
data class SystemToolConfig(
    val enabled: Boolean = false,
    val providerId: String = "",
    val modelId: String = "",
    val prompt: String = "",
    val imageOptionsByModel: Map<String, JsonObject> = emptyMap(),
    val videoOptionsByModel: Map<String, JsonObject> = emptyMap()
) {
    val imageOptions: JsonObject
        get() = imageOptionsByModel["$providerId/$modelId"] ?: JsonObject(emptyMap())

    fun withImageOptions(options: JsonObject) =
        copy(imageOptionsByModel = imageOptionsByModel + ("$providerId/$modelId" to options))

    val videoOptions: JsonObject
        get() = videoOptionsByModel["$providerId/$modelId"] ?: JsonObject(emptyMap())

    fun withVideoOptions(options: JsonObject) =
        copy(videoOptionsByModel = videoOptionsByModel + ("$providerId/$modelId" to options))
}

@Serializable
data class ToolSettings(
    val system: Map<String, SystemToolConfig> = emptyMap(),
    val quickMcp: Map<String, Boolean> = emptyMap(),
    val mcpTools: Map<String, Map<String, Boolean>> = emptyMap()
)

@Serializable
data class ToolActivity(
    val id: String,
    val name: String,
    val status: String = "running",
    val summary: String = "",
    val files: List<String> = emptyList(),
    val arguments: String = "",
    val response: String = "",
    val contentOffset: Int = 0,
    val reasoningOffset: Int? = null,
    val inlineMedia: Boolean = false,
    val responseFile: String? = null
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val serverId: String? = null,
    val originalName: String = name
)

/**
 * MCP server names are user-controlled, so two servers can still produce the
 * same readable wire name after sanitization. Disambiguate only those rare
 * collisions; the normal name remains server_tool.
 */
internal fun List<ToolDefinition>.withUniqueWireNames(): List<ToolDefinition> {
    val used = mutableSetOf<String>()
    return map { definition ->
        var name = definition.name
        if (!used.add(name)) {
            val baseSuffix = UUID.nameUUIDFromBytes(
                (definition.serverId.orEmpty() + ":" + definition.originalName).toByteArray()
            ).toString().replace("-", "").take(10)
            var attempt = 0
            do {
                val suffix = if (attempt == 0) baseSuffix else "$baseSuffix$attempt"
                name = definition.name.take(64 - suffix.length - 1) + "_" + suffix
                attempt++
            } while (!used.add(name))
        }
        if (name == definition.name) definition else definition.copy(name = name)
    }
}

fun systemMediaToolAvailable(
    name: String,
    config: SystemToolConfig,
    providers: List<LlmProviderInfo>
): Boolean {
    if (config.providerId.isBlank() || config.modelId.isBlank()) return false
    val requiredType = when (name) {
        "generate_image" -> ModelType.IMAGE_GENERATION
        "generate_video" -> ModelType.VIDEO_GENERATION
        else -> return false
    }
    val provider = providers.find { it.id == config.providerId } ?: return false
    if (!provider.type.isOpenAi && provider.type != ProviderType.GOOGLE) return false
    val model = provider.config.modelConfigs[config.modelId] ?: return false
    return model.modelType == requiredType && provider.config.modelIds?.contains(config.modelId) != false
}

sealed interface GenerationEvent {
    data class Text(val text: String) : GenerationEvent
    data class Reasoning(val text: String, val signature: String? = null) : GenerationEvent
    data class Tool(val activity: ToolActivity) : GenerationEvent
    /** A user message inserted between a completed tool turn and the next model turn. */
    data class UserMessage(val message: StoredMessage) : GenerationEvent
}

fun globalMcpToolEnabled(settings: ToolSettings, serverId: String, name: String): Boolean =
    settings.mcpTools[serverId]?.get(name) != false

fun toolAllowed(
    access: McpAccess?,
    quick: Boolean?,
    globalEnabled: Boolean,
    name: String
): Boolean =
    access?.enabled == true && quick != false && globalEnabled && (access.tools[name] != false)

const val DEFAULT_TITLE_GENERATION_PROMPT = "Generate a concise title for this conversation. Return only the title, without quotation marks or extra commentary. Keep it under 8 words."
const val DEFAULT_CHAT_SUMMARY_PROMPT = "Summarize the conversation faithfully for use as future context. Preserve user goals, decisions, constraints, important facts, code or technical details, unresolved issues, and commitments. Remove repetition and incidental chatter. Do not invent information."
