package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import java.io.File

/** References supplied by the model never go through the Android attachment/path resolver. */
internal data class MediaInputImage(val file: File, val mimeType: String)

internal const val MAX_MEDIA_INPUT_BYTES = 20L * 1024 * 1024

internal fun resolveInputImage(reference: String, files: ToolFiles, maxBytes: Long = MAX_MEDIA_INPUT_BYTES): MediaInputImage {
    require(reference.startsWith("tool-file:")) { "input_image must be a tool-file: URI returned by an attachment or tool." }
    val name = reference.removePrefix("tool-file:")
    require(name.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+"))) { "Invalid input_image tool-file: reference." }
    val file = files.resolve(name) ?: error("The input_image file is unavailable. Attach or generate the image again.")
    require(file.length() in 1..maxBytes) { "Input image is empty or exceeds the selected API's file-size limit (${(maxBytes + 1024 * 1024 - 1) / (1024 * 1024)} MB)." }
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    val mime = when {
        count >= 8 && header.take(8) == listOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map { it.toByte() } -> "image/png"
        count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
        count == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> error("input_image must contain a PNG, JPEG, or WebP image; other MIME types are unsupported.")
    }
    return MediaInputImage(file, mime)
}

internal fun mediaInputImagesArgument(arguments: JsonObject): List<String> {
    val value = arguments["input_images"] ?: return emptyList()
    require(value is JsonArray && value.size in 1..16) { "input_images must be an array of 1–16 tool-file: image URIs." }
    return value.map {
        require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "Each input_images entry must be a tool-file: URI string." }
        it.content
    }
}

internal fun mediaInputImageArgument(arguments: JsonObject): String? {
    val value = arguments["input_image"] ?: return null
    require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
        "input_image must be a non-empty tool-file: URI string. Omit it for text-to-video."
    }
    return value.content
}

internal fun mediaInputImageUrlArgument(arguments: JsonObject): String? {
    val value = arguments["input_image_url"] ?: return null
    require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { "input_image_url must be a public HTTPS URL string." }
    return validateVideoImageUrl(value.content)
}

internal fun mediaToolSchema(name: String): JsonObject = obj(
    "type" to str("object"),
    "properties" to buildJsonObject {
        put("prompt", obj("type" to str("string")))
        if (name == "generate_video") put("input_image", obj(
            "type" to str("string"),
            "description" to str("Optional tool-file: URI of an attached or previously generated image to animate. Reuse it directly; send_file is not needed.")
        ))
        if (name == "generate_video") put("input_image_url", obj(
            "type" to str("string"),
            "description" to str("Optional public HTTPS source-image URL for Agnes Video or standalone H3. Agnes requires public URLs and does not accept local tool-file uploads. Use either input_image or input_image_url, not both.")
        ))
        if (name == "generate_image") put("input_images", obj(
            "type" to str("array"),
            "items" to obj("type" to str("string")),
            "minItems" to JsonPrimitive(1), "maxItems" to JsonPrimitive(16),
            "description" to str("Optional tool-file: URIs of attached or previously generated images to edit or use as references. Reuse directly; send_file is not needed.")
        ))
    },
    "required" to JsonArray(listOf(str("prompt")))
)

internal fun toolAttachmentReferences(attachments: JsonArray): String =
    if (attachments.isEmpty()) "" else attachments.mapIndexed { index, item ->
        val attachment = item.jsonObject
        "Attachment ${index + 1}: ${attachment.text("mimeType")}; URI: ${attachment.text("uri")}"
    }.joinToString("\n", prefix = "\nAvailable attachments for tool inputs (reuse these URIs directly):\n")
