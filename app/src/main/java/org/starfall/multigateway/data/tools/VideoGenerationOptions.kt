package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.multigateway.data.model.LlmProviderInfo

internal enum class VideoApi { OPENAI, AGNES }

internal fun videoApi(provider: LlmProviderInfo): VideoApi = when {
    isAgnesProvider(provider) -> VideoApi.AGNES
    else -> VideoApi.OPENAI
}

internal fun validateVideoImageUrl(value: String): String {
    val url = value.toHttpUrlOrNull()
    require(url != null && url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty()) {
        "Reference images require a publicly accessible HTTPS URL without embedded credentials."
    }
    return value
}

fun videoOptionFields(provider: LlmProviderInfo, model: String): List<ImageOptionField> = when (videoApi(provider)) {
    VideoApi.AGNES -> listOf(
        ImageOptionField("seconds", "Duration (seconds)", "integer", min = 4.0, max = 12.0),
        ImageOptionField("size", "Resolution", choices = if (model.equals("agnes-video-2.5-flash", true)) listOf("720P") else listOf("720P", "1080P", "1K", "2K")),
        ImageOptionField("aspect_ratio", "Aspect ratio", choices = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")),
        ImageOptionField("first_frame", "First-frame image URL"),
        ImageOptionField("last_frame", "Last-frame image URL"),
        ImageOptionField("seed", "Seed", "integer")
    )
    VideoApi.OPENAI -> emptyList()
}

fun validateVideoOptions(provider: LlmProviderInfo, model: String, options: JsonObject) {
    require(options.toString().length <= 65536) { "Video settings must be under 64 KB." }
    require(listOf("model", "prompt", "input_reference", "image", "instances").none { it in options }) {
        "Model, prompt, and attached files are supplied by chat."
    }
    val allowed = when (videoApi(provider)) {
        VideoApi.AGNES -> setOf("mode", "seconds", "size", "aspect_ratio", "seed", "n", "first_frame", "last_frame", "images", "audios", "videos")
        VideoApi.OPENAI -> emptySet()
    }
    require(options.keys.all { it in allowed }) { "Unsupported video settings: ${(options.keys - allowed).joinToString()}." }
    videoOptionFields(provider, model).forEach { field ->
        val value = options[field.path] ?: return@forEach
        val primitive = value as? JsonPrimitive ?: error("${field.label} must be ${field.kind}.")
        if (field.kind == "integer") {
            val number = primitive.longOrNull
            require((!primitive.isString || field.path == "seconds") && number != null && (field.min == null || number >= field.min) && (field.max == null || number <= field.max)) {
                "Invalid ${field.label.lowercase()}."
            }
        } else require(primitive.isString && (field.choices.isEmpty() || primitive.content in field.choices)) { "Invalid ${field.label.lowercase()}." }
    }
    listOf("first_frame", "last_frame").forEach { key ->
        if (key in options) validateVideoImageUrl(options.text(key))
    }
    if (videoApi(provider) != VideoApi.AGNES) return
    val mode = options.text("mode")
    require("mode" !in options || (options["mode"] is JsonPrimitive && options["mode"]!!.jsonPrimitive.isString && mode in listOf("text", "keyframe", "reference"))) { "Video mode must be text, keyframe, or reference." }
    options["n"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull == 1) { "Agnes supports one video per request." } }
    listOf("images", "audios").forEach { key ->
        options[key]?.let { value ->
            val limit = if (key == "audios") 3 else if (model.equals("agnes-video-2.5-flash", true)) 5 else 8
            require(value is JsonArray && value.size in 1..limit) { "$key requires 1–$limit public media URLs." }
            value.forEach { require(it is JsonPrimitive && it.isString) { "$key must contain URLs." }; validateVideoImageUrl(it.content) }
        }
    }
    options["videos"]?.let { value ->
        require(!model.equals("agnes-video-2.5-flash", true) && value is JsonArray && value.size == 1) { "Only Agnes Video 2.5 accepts one reference video." }
        val video = value.single() as? JsonObject ?: error("Reference videos must be objects containing url.")
        require(video.keys.all { it in setOf("url", "start_seconds", "require_audio") }) { "Unsupported reference video fields." }
        validateVideoImageUrl(video.text("url"))
        video["start_seconds"]?.let { require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.let { n -> n.isFinite() && n >= 0 } == true) { "start_seconds must be non-negative." } }
        video["require_audio"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "require_audio must be a boolean." } }
    }
    val hasFrames = listOf("first_frame", "last_frame").any { it in options }
    val hasReferences = listOf("images", "audios", "videos").any { it in options }
    require(!hasFrames || !hasReferences) { "Use either keyframes or references, not both." }
    require(mode.isEmpty() || when (mode) {
        "text" -> !hasFrames && !hasReferences
        "keyframe" -> hasFrames && !hasReferences
        else -> hasReferences && !hasFrames
    }) { "Video mode does not match its reference media." }
}
