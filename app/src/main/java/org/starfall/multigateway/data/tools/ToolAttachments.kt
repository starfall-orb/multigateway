package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.ProviderType

/** Internal attachment metadata is converted once to the selected wire protocol. */
internal fun toolAttachmentParts(message: JsonObject, type: ProviderType): List<JsonElement> =
    (message["_attachments"] as? JsonArray).orEmpty().map { attachment ->
        val file = attachment.jsonObject
        val mime = file.text("mimeType")
        val data = file.text("data")
        when (type) {
            ProviderType.GOOGLE -> obj("inlineData" to obj("mimeType" to str(mime), "data" to str(data)))
            ProviderType.ANTHROPIC -> {
                require(mime.startsWith("image/") || mime == "application/pdf") { "Unsupported Claude attachment" }
                obj("type" to str(if (mime == "application/pdf") "document" else "image"),
                    "source" to obj("type" to str("base64"), "media_type" to str(mime), "data" to str(data)))
            }
            ProviderType.OPENAI_RESPONSES -> {
                require(mime.startsWith("image/")) { "Unsupported Responses attachment" }
                obj("type" to str("input_image"), "image_url" to str("data:$mime;base64,$data"))
            }
            else -> {
                require(mime.startsWith("image/")) { "Unsupported chat attachment" }
                obj("type" to str("image_url"), "image_url" to obj("url" to str("data:$mime;base64,$data")))
            }
        }
    }

internal fun toolMessageContent(message: JsonObject, type: ProviderType): JsonElement {
    if (type == ProviderType.OLLAMA) return message["content"] ?: str("")
    val files = toolAttachmentParts(message, type)
    if (files.isEmpty()) return message["content"] ?: str("")
    return JsonArray(listOf(obj("type" to str(if (type == ProviderType.OPENAI_RESPONSES) "input_text" else "text"),
        "text" to str(message.text("content")))) + files)
}
