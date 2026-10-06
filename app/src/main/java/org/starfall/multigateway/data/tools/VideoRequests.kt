package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.Base64

/** OpenAI Videos uses multipart; Agnes uses a separate JSON protocol. */
internal fun openAiVideoRequest(model: String, prompt: String, image: MediaInputImage? = null): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model)
        .addFormDataPart("prompt", prompt)
        .apply {
            image?.let {
                addFormDataPart("input_reference", it.file.name, it.file.asRequestBody(it.mimeType.toMediaType()))
            }
        }.build()

internal fun agnesVideoRequest(model: String, prompt: String, options: JsonObject, imageUrl: String?): JsonObject {
    val inputs = if (imageUrl == null) options else JsonObject(options + ("first_frame" to str(validateVideoImageUrl(imageUrl))))
    val mode = inputs.text("mode").ifBlank {
        when {
            listOf("first_frame", "last_frame").any { it in inputs } -> "keyframe"
            listOf("images", "audios", "videos").any { it in inputs } -> "reference"
            else -> "text"
        }
    }
    return buildJsonObject {
        put("seconds", "5"); put("size", "720P"); put("aspect_ratio", "16:9")
        inputs.forEach { (key, value) -> put(key, if (key == "seconds") str(value.jsonPrimitive.content) else value) }
        put("model", model); put("prompt", prompt); put("mode", mode)
    }
}

/** Gemini Veo REST uses an inline image in the generation instance. */
internal fun googleVideoRequest(prompt: String, image: MediaInputImage? = null): JsonObject =
    obj("instances" to JsonArray(listOf(buildJsonObject {
        put("prompt", prompt)
        image?.let {
            put("image", obj("inlineData" to obj(
                "mimeType" to str(it.mimeType),
                "data" to str(Base64.getEncoder().encodeToString(it.file.readBytes()))
            )))
        }
    })))
