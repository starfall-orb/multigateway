package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.ProviderType
import java.util.UUID

internal fun JsonObject.requireObject(key: String): JsonObject =
    get(key) as? JsonObject ?: error("Provider response: missing or invalid $key object")
internal fun JsonObject.requireArray(key: String): JsonArray =
    get(key) as? JsonArray ?: error("Provider response: missing or invalid $key array")
internal fun JsonElement.requireObject(): JsonObject = this as? JsonObject ?: error("Provider response: expected an object")
internal fun toolArguments(value: JsonElement?): JsonObject = when (value) {
    is JsonObject -> value
    is JsonPrimitive -> {
        require(value.isString && value.content.length <= 65536) { "Tool arguments must be a JSON object or JSON object string (max 64 KB)" }
        runCatching { Json.parseToJsonElement(value.content) as? JsonObject }.getOrNull()
            ?: error("Tool arguments contain invalid JSON; expected an object")
    }
    else -> error("Tool arguments are missing or invalid")
}
internal fun validateToolTurn(turn: JsonObject): JsonObject {
    val seen = mutableSetOf<String>()
    val calls = turn.requireArray("tool_calls").map { value ->
        val call = value.requireObject()
        val function = call.requireObject("function")
        require(function.text("name").isNotBlank()) { "Tool call is missing a function name" }
        val id = call.text("id")
        require(id.isNotBlank() && seen.add(id)) { "Tool call ID is missing or duplicated" }
        JsonObject(call + ("function" to JsonObject(function + ("arguments" to toolArguments(function["arguments"])))))
    }
    return JsonObject(turn + ("tool_calls" to JsonArray(calls)))
}
internal fun providerTurn(type: ProviderType, response: JsonObject): JsonObject {
    check(response["error"] == null || response["error"] == JsonNull) {
        "${type.displayName} returned an error: " + safeError(response["error"])
    }

    fun turn(
        text: String,
        calls: List<JsonElement>,
        reasoning: String? = null,
        reasoningSignature: String? = null
    ): JsonObject {
        var value = obj(
            "role" to str("assistant"),
            "content" to str(text),
            "tool_calls" to JsonArray(calls)
        )
        if (!reasoning.isNullOrBlank()) {
            value = JsonObject(value + ("reasoning_content" to str(reasoning)))
        }
        if (!reasoningSignature.isNullOrBlank()) {
            value = JsonObject(value + ("reasoning_signature" to str(reasoningSignature)))
        }
        return validateToolTurn(value)
    }

    fun call(id: String, name: String, args: JsonElement?, part: JsonElement? = null) = buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", obj("name" to str(name), "arguments" to toolArguments(args)))
        part?.let { put("googlePart", it) }
    }

    return when (type) {
        ProviderType.OPENAI_RESPONSES -> {
            check(response.text("status") != "failed") { "OpenAI Responses request failed" }
            val output = response.requireArray("output")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = mutableListOf<JsonElement>()
            output.forEach { item ->
                val value = item.requireObject()
                when (value.text("type")) {
                    "message" -> value.requireArray("content").forEach { part ->
                        val content = part.requireObject()
                        if (content.text("type") == "output_text") text.append(content.text("text"))
                    }
                    "reasoning" -> (
                        (value["content"] as? JsonArray).orEmpty() +
                            (value["summary"] as? JsonArray).orEmpty()
                    ).forEach { part ->
                        val content = part.requireObject()
                        if (content.text("type") in setOf("reasoning_text", "summary_text")) {
                            reasoning.append(content.text("text"))
                        }
                    }
                    "function_call" -> calls += call(value.text("call_id"), value.text("name"), value["arguments"])
                }
            }
            JsonObject(turn(text.toString(), calls, reasoning.toString()) + ("responsesOutput" to output))
        }

        ProviderType.OPENAI, ProviderType.OLLAMA -> {
            val message = if (type == ProviderType.OLLAMA) {
                response.requireObject("message")
            } else {
                val choice = response.requireArray("choices").firstOrNull()
                    ?: error("OpenAI-compatible provider returned no choices")
                choice.requireObject().requireObject("message")
            }
            val values = message["tool_calls"]
            require(values == null || values == JsonNull || values is JsonArray) {
                "Provider returned invalid tool_calls"
            }
            turn(
                message.text("content"),
                (values as? JsonArray).orEmpty().map { item ->
                    val c = item.requireObject()
                    val f = c.requireObject("function")
                    call(
                        c.text("id").ifBlank {
                            if (type == ProviderType.OLLAMA) UUID.randomUUID().toString()
                            else error("Tool call is missing an ID")
                        },
                        f.text("name"),
                        f["arguments"]
                    )
                },
                message.text("reasoning_content").takeIf { it.isNotBlank() }
            )
        }

        ProviderType.ANTHROPIC -> {
            val blocks = response.requireArray("content").map { it.requireObject() }
            val thinkingBlocks = blocks.filter { it.text("type") == "thinking" }
            turn(
                blocks.filter { it.text("type") == "text" }.joinToString("") { it.text("text") },
                blocks.filter { it.text("type") == "tool_use" }
                    .map { call(it.text("id"), it.text("name"), it["input"]) },
                thinkingBlocks.joinToString("") { it.text("thinking") }.takeIf { it.isNotBlank() },
                thinkingBlocks.joinToString("") { it.text("signature") }.takeIf { it.isNotBlank() }
            )
        }

        ProviderType.GOOGLE -> {
            val candidate = response.requireArray("candidates").firstOrNull()
                ?: error("Google response has no candidates")
            val parts = candidate.requireObject().requireObject("content")
                .requireArray("parts").map { it.requireObject() }
            turn(
                parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
                    .joinToString("") { it.text("text") },
                parts.filter { it.containsKey("functionCall") }.map {
                    val f = it.requireObject("functionCall")
                    call(UUID.randomUUID().toString(), f.text("name"), f["args"] ?: obj(), it)
                },
                parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
                    .joinToString("") { it.text("text") }
                    .takeIf { it.isNotBlank() }
            )
        }
        else -> error("Unsupported provider response protocol: ${type.displayName}")
    }
}
/** Only display bounded error details; redact common secret formats and echoed request credentials. */
internal fun safeError(value: JsonElement?, secrets: Collection<String> = emptyList()): String {
    var text = when (value) {
        is JsonObject -> value.text("message").ifBlank { value.text("detail").ifBlank { value.text("code") } }
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }.take(2000)
    secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { text = text.replace(it, "[redacted]") }
    text = text.replace(Regex("(?i)(bearer\\s+|sk-)[A-Za-z0-9._~+/-]+"), "[redacted]")
        .replace(Regex("(?i)(api[_-]?key|token|authorization|password)([\\s\"':=]+)[^\\s,}]+"), "$1=[redacted]")
    return text.filter { it >= ' ' }.take(500).ifBlank { "No safe error details provided" }
}
