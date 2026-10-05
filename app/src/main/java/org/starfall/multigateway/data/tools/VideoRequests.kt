package org.starfall.multigateway.data.tools

import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.Base64

/** Shared by OpenAI-style /videos endpoints, including H3-compatible gateways. */
internal fun openAiVideoRequest(model: String, prompt: String, image: MediaInputImage? = null): MultipartBody =
    MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model)
        .addFormDataPart("prompt", prompt)
        .apply {
            image?.let {
                addFormDataPart("input_reference", it.file.name, it.file.asRequestBody(it.mimeType.toMediaType()))
            }
        }.build()

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
