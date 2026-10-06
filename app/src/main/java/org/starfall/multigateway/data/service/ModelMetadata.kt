package org.starfall.multigateway.data.service

import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.DiscoveredModel

internal fun discoveredModel(id: String, item: JsonObject): DiscoveredModel {
    val keys = listOf("context_window", "context_length", "contextWindow", "contextWindowTokens", "max_context_length", "inputTokenLimit", "max_input_tokens", "max_context_window_tokens", "max_prompt_tokens")
    val containers = listOfNotNull(item, item["architecture"] as? JsonObject, item["limits"] as? JsonObject,
        item["metadata"] as? JsonObject, item["capabilities"] as? JsonObject,
        (item["capabilities"] as? JsonObject)?.get("limits") as? JsonObject)
    val limit = containers.firstNotNullOfOrNull { source ->
        keys.firstNotNullOfOrNull { key ->
            (source[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        }
    }
    val name = ((item["display_name"] ?: item["displayName"] ?: item["name"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
    return DiscoveredModel(id, limit, name, metadata = item)
}
