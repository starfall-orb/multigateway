package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.multigateway.data.model.LlmProviderInfo
import java.util.Base64

// Select the protocol by the documented API origin, never by a model name.
internal fun isAgnesProvider(provider: LlmProviderInfo): Boolean =
    provider.type.isOpenAi && provider.baseUrl.trim().toHttpUrlOrNull()?.host == "apihub.agnes-ai.com"

internal fun agnesApiBase(provider: LlmProviderInfo): String {
    val base = providerBase(provider).removeSuffix("/images/generations").removeSuffix("/images/edits").removeSuffix("/videos")
    return if (base.toHttpUrl().encodedPath == "/") "$base/v1" else base
}

internal fun agnesImageOptionFields(): List<ImageOptionField> = listOf(
    ImageOptionField("size", "Image size", choices = listOf("1K", "2K", "3K", "4K", "1024x768", "1024x1024", "768x1024")),
    ImageOptionField("ratio", "Aspect ratio", choices = listOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9")),
    ImageOptionField("return_base64", "Return Base64 (text-to-image)", "boolean"),
    ImageOptionField("extra_body.response_format", "Response format", choices = listOf("url", "b64_json"))
)

internal fun validateAgnesImageOptions(options: JsonObject) {
    require(options.toString().length <= 65536) { "Image options must be under 64 KB" }
    require(options.keys.all { it in setOf("size", "ratio", "return_base64", "extra_body") }) {
        "Agnes image settings support size, ratio, return_base64, and extra_body. Use extra_body.response_format for the output format."
    }
    options["extra_body"]?.let { value ->
        val extra = value as? JsonObject ?: error("extra_body must be a JSON object")
        require(extra.keys.all { it in setOf("image", "response_format") }) { "Unsupported Agnes extra_body settings." }
        extra["image"]?.let { images ->
            require(images is JsonArray && images.size in 1..16) { "Reference images require 1–16 public image URLs." }
            images.forEach {
                require(it is JsonPrimitive && it.isString) { "Reference images must contain public HTTPS URLs." }
                validateVideoImageUrl(it.content)
            }
        }
    }
    require((options["return_base64"] as? JsonPrimitive)?.booleanOrNull != true ||
        (options["extra_body"] as? JsonObject)?.text("response_format") != "url") {
        "return_base64 conflicts with extra_body.response_format: url."
    }
    agnesImageOptionFields().forEach { field ->
        options.optionAt(field.path)?.let { value ->
            val primitive = value as? JsonPrimitive ?: error("${field.label} has an invalid type.")
            if (field.kind == "boolean") require(!primitive.isString && primitive.booleanOrNull != null) { "${field.label} must be true or false." }
            else {
                require(primitive.isString && primitive.content.isNotBlank()) { "${field.label} must be text." }
                if (field.path == "size") require(primitive.content in field.choices || primitive.content.matches(Regex("[1-9][0-9]*x[1-9][0-9]*"))) { "Invalid image size." }
                else require(primitive.content in field.choices) { "Invalid ${field.label.lowercase()}." }
            }
        }
    }
}

/** Agnes uses the same JSON generation endpoint for text, editing, and composition. */
internal fun agnesImageRequest(model: String, prompt: String, options: JsonObject, images: List<MediaInputImage>): JsonObject {
    validateAgnesImageOptions(options)
    require(images.size <= 16) { "Image generation accepts at most 16 reference images." }
    val extra = options["extra_body"] as? JsonObject ?: obj()
    require(images.isEmpty() || "image" !in extra) { "Use either attached reference images or extra_body.image URLs, not both." }
    require((images.isEmpty() && "image" !in extra) || (options["return_base64"] as? JsonPrimitive)?.booleanOrNull != true) {
        "For Agnes image editing, use extra_body.response_format: b64_json instead of return_base64."
    }
    // Bound the encoded request before reading attachments into memory.
    val estimatedBytes = images.sumOf { ((it.file.length() + 2) / 3) * 4 + 1024 } +
        prompt.toByteArray(Charsets.UTF_8).size + options.toString().toByteArray(Charsets.UTF_8).size
    require(estimatedBytes <= 20L * 1024 * 1024) { "The inline image request exceeds 20 MB. Use fewer or smaller reference images." }
    val defaults = if ((options["return_base64"] as? JsonPrimitive)?.booleanOrNull == true) obj("size" to str("1024x1024"))
        else obj("size" to str("1024x1024"), "extra_body" to obj("response_format" to str("url")))
    val request = mergeImageOptions(defaults, options)
    val references = if (images.isEmpty()) obj() else obj("extra_body" to obj("image" to JsonArray(images.map {
        str("data:${it.mimeType};base64," + Base64.getEncoder().encodeToString(it.file.readBytes()))
    })))
    val result = JsonObject(mergeImageOptions(request, references) + mapOf("model" to str(model), "prompt" to str(prompt)))
    require(result.toString().toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) { "The inline image request exceeds 20 MB. Use fewer or smaller reference images." }
    return result
}
