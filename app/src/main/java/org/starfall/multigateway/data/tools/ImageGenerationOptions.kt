package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.LlmProviderInfo

data class ImageOptionField(
    val path: String, val label: String, val kind: String = "string",
    val choices: List<String> = emptyList(), val min: Double? = null, val max: Double? = null
)

fun imageOptionFields(provider: LlmProviderInfo, model: String): List<ImageOptionField> =
    if (isAgnesProvider(provider)) agnesImageOptionFields() else imageOptionFields(provider.type, model)

fun validateImageOptions(provider: LlmProviderInfo, model: String, options: JsonObject) {
    if (isAgnesProvider(provider)) validateAgnesImageOptions(options) else validateImageOptions(provider.type, model, options)
}

// Endpoint body names, not SDK-specific aliases.
// https://developers.openai.com/api/reference/resources/images/methods/generate
// https://ai.google.dev/api/generate-content#ImageConfig
fun imageOptionFields(type: ProviderType, model: String): List<ImageOptionField> {
    fun choice(path: String, label: String, vararg values: String) = ImageOptionField(path, label, choices = values.toList())
    fun integer(path: String, label: String, min: Double, max: Double) = ImageOptionField(path, label, "integer", min = min, max = max)
    return when (type) {
        ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> listOf(
            integer("n", "Number of images", 1.0, 10.0),
            choice("size", "Image size", "auto", "256x256", "512x512", "1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024"),
            choice("quality", "Quality", "auto", "low", "medium", "high", "xhigh", "max", "standard", "hd"),
            choice("background", "Background", "auto", "opaque", "transparent"),
            choice("output_format", "Output format", "png", "jpeg", "webp"),
            integer("output_compression", "Output compression (%)", 0.0, 100.0),
            choice("moderation", "Moderation", "auto", "low"),
            choice("style", "Style (DALL·E 3)", "vivid", "natural"),
            choice("response_format", "Response format (DALL·E)", "url", "b64_json"),
            ImageOptionField("user", "User identifier"),
            ImageOptionField("stream", "Stream image response", "boolean"),
            integer("partial_images", "Partial images when streaming", 0.0, 3.0)
        )
        ProviderType.GOOGLE -> if (model.contains("imagen", true)) listOf(
            integer("parameters.sampleCount", "Number of images", 1.0, 4.0),
            choice("parameters.aspectRatio", "Aspect ratio", "1:1", "3:4", "4:3", "9:16", "16:9"),
            choice("parameters.sampleImageSize", "Image size", "1K", "2K"),
            choice("parameters.personGeneration", "People", "dont_allow", "allow_adult", "allow_all"),
            ImageOptionField("parameters.negativePrompt", "Negative prompt"),
            integer("parameters.seed", "Seed", 0.0, 2147483647.0),
            ImageOptionField("parameters.guidanceScale", "Guidance scale", "number", min = 0.0),
            choice("parameters.language", "Prompt language", "auto", "en", "ja", "ko", "hi", "zh", "pt", "es"),
            choice("parameters.safetySetting", "Safety filter", "block_low_and_above", "block_medium_and_above", "block_only_high"),
            ImageOptionField("parameters.addWatermark", "Add watermark", "boolean"),
            ImageOptionField("parameters.enhancePrompt", "Enhance prompt", "boolean"),
            ImageOptionField("parameters.includeRaiReason", "Include filter reason", "boolean"),
            ImageOptionField("parameters.includeSafetyAttributes", "Include safety attributes", "boolean"),
            choice("parameters.outputOptions.mimeType", "Output format", "image/png", "image/jpeg"),
            integer("parameters.outputOptions.compressionQuality", "JPEG quality (%)", 0.0, 100.0)
        ) else listOf(
            choice("generationConfig.imageConfig.aspectRatio", "Aspect ratio", "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4", "8:1", "9:16", "16:9", "21:9"),
            choice("generationConfig.imageConfig.imageSize", "Image size", "512", "1K", "2K", "4K"),
            ImageOptionField("generationConfig.temperature", "Temperature", "number", min = 0.0, max = 2.0),
            ImageOptionField("generationConfig.topP", "Top P", "number", min = 0.0, max = 1.0),
            integer("generationConfig.topK", "Top K", 1.0, 2147483647.0),
            integer("generationConfig.seed", "Seed", 0.0, 2147483647.0),
            integer("generationConfig.maxOutputTokens", "Maximum output tokens", 1.0, 2147483647.0),
            choice("generationConfig.thinkingConfig.thinkingLevel", "Thinking level", "minimal", "low", "medium", "high"),
            integer("generationConfig.thinkingConfig.thinkingBudget", "Thinking budget (-1 = automatic)", -1.0, 2147483647.0)
        )
        else -> emptyList()
    }
}

fun JsonObject.optionAt(path: String): JsonElement? =
    path.split('.').fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

fun JsonObject.withOption(path: String, value: JsonElement?): JsonObject {
    val key = path.substringBefore('.')
    val updated = if ('.' in path) ((this[key] as? JsonObject) ?: obj()).withOption(path.substringAfter('.'), value).takeIf { it.isNotEmpty() } else value
    return JsonObject(toMutableMap().apply { if (updated == null) remove(key) else put(key, updated) })
}

internal fun mergeImageOptions(defaults: JsonObject, options: JsonObject): JsonObject =
    JsonObject(defaults.toMutableMap().apply {
        options.forEach { (key, value) ->
            val previous = this[key]
            this[key] = if (previous is JsonObject && value is JsonObject) mergeImageOptions(previous, value) else value
        }
    })

fun validateImageOptions(type: ProviderType, model: String, options: JsonObject) {
    require(options.toString().length <= 65536) { "Image options must be under 64 KB" }
    require(listOf("model", "prompt", "contents", "instances", "image", "images", "input_images", "input_image", "mask").none { it in options }) {
        "Model, prompt, and input files are supplied by the selected model and chat attachments."
    }
    listOf("parameters", "generationConfig").forEach { key ->
        require(options[key] == null || options[key] is JsonObject) { "$key must be a JSON object" }
    }
    imageOptionFields(type, model).forEach { field ->
        val value = options.optionAt(field.path) ?: return@forEach
        if (value == JsonNull) return@forEach
        val primitive = value as? JsonPrimitive ?: error("${field.label} must be a ${field.kind}")
        when (field.kind) {
            "integer", "number" -> {
                val number = primitive.doubleOrNull
                require(!primitive.isString && number != null && number.isFinite() &&
                    (field.kind != "integer" || number % 1.0 == 0.0) &&
                    (field.min == null || number >= field.min) && (field.max == null || number <= field.max)) {
                    "Invalid ${field.label.lowercase()}: check its numeric range."
                }
            }
            "boolean" -> require(!primitive.isString && primitive.booleanOrNull != null) { "${field.label} must be true or false" }
            else -> require(primitive.isString) { "${field.label} must be text" }
        }
    }
    if (type.isOpenAi) {
        require(model != "dall-e-3" || (options["n"] as? JsonPrimitive)?.intOrNull.let { it == null || it == 1 }) { "DALL·E 3 supports one image per request" }
        require(options.text("background") != "transparent" || options.text("output_format") != "jpeg") { "Transparent backgrounds require PNG or WebP" }
        require((options["partial_images"] as? JsonPrimitive)?.intOrNull.let { it == null || it == 0 } ||
            (options["stream"] as? JsonPrimitive)?.booleanOrNull == true) { "Enable streaming to request partial images" }
    }
}

internal fun imageGenerationRequest(type: ProviderType, model: String, prompt: String, options: JsonObject): JsonObject {
    validateImageOptions(type, model, options)
    return when (type) {
        ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> JsonObject(mergeImageOptions(obj("n" to JsonPrimitive(1)), options) +
            mapOf("model" to str(model), "prompt" to str(prompt)))
        ProviderType.GOOGLE -> if (model.contains("imagen", true)) {
            val defaults = obj("parameters" to obj("sampleCount" to JsonPrimitive(1)))
            val instances = JsonArray(listOf(obj("prompt" to str(prompt))))
            JsonObject(mergeImageOptions(defaults, options) + ("instances" to instances))
        } else {
            val defaults = obj("generationConfig" to obj("responseModalities" to JsonArray(listOf(str("TEXT"), str("IMAGE")))))
            val parts = JsonArray(listOf(obj("text" to str(prompt))))
            val contents = JsonArray(listOf(obj("parts" to parts)))
            JsonObject(mergeImageOptions(defaults, options) + ("contents" to contents))
        }
        else -> error("Unsupported image API")
    }
}
