package org.starfall.multigateway.data.tools

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.*

class SystemMediaTools(private val http: ToolHttp) {
    suspend fun generate(kind: String, provider: LlmProviderInfo, model: String, prompt: String, imageOptions: JsonObject = obj(), inputImage: String? = null, inputImages: List<String> = emptyList(), videoOptions: JsonObject = obj(), inputImageUrl: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val files = http.requireFiles()
        require(kind in setOf("generate_image", "generate_video")) { "Unsupported media tool" }
        require(prompt.isNotBlank() && prompt.length <= 32000) { "A prompt of 1–32000 characters is required" }
        require(inputImages.isEmpty() || kind == "generate_image") { "input_images is only supported by generate_image. Video generation accepts one input_image." }
        require(inputImages.size <= 16) { "Image generation accepts at most 16 reference images." }
        if (inputImages.isNotEmpty()) require(provider.type.isOpenAi || provider.type == ProviderType.GOOGLE) {
            "This provider does not support image editing. Select an OpenAI-compatible or Gemini image provider."
        }
        val referenceImages = inputImages.map { resolveInputImage(it, files, if (provider.type.isOpenAi && !isAgnesProvider(provider)) 50L * 1024 * 1024 - 1 else MAX_MEDIA_INPUT_BYTES) }
        val image = inputImage?.let {
            require(kind == "generate_video") { "input_image is only supported by generate_video." }
            require(provider.type.isOpenAi || provider.type == ProviderType.GOOGLE) {
                "This provider does not support image-to-video. Select an OpenAI-compatible or Google video provider."
            }
            resolveInputImage(it, files)
        }
        require(inputImageUrl == null || kind == "generate_video") { "input_image_url is only supported by generate_video." }
        val base = providerBase(provider)
        val response = when(provider.type) {
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if(kind == "generate_image") {
                if (isAgnesProvider(provider)) {
                    http.postMedia("${agnesApiBase(provider)}/images/generations", agnesImageRequest(model, prompt, imageOptions, referenceImages), provider)
                } else if (referenceImages.isEmpty()) {
                    http.postMedia("$base/images/generations", imageGenerationRequest(provider.type, model, prompt, imageOptions), provider)
                } else {
                    http.postMedia("$base/images/edits", openAiImageEditRequest(model, prompt, imageOptions, referenceImages), provider)
                }
            } else {
                VideoGeneration(http).generate(provider, model, prompt, image, videoOptions, inputImageUrl)
            }
            ProviderType.GOOGLE -> {
                val root = if(Regex("/v1(?:beta|alpha)?$").containsMatchIn(base)) base else "$base/v1beta"
                if(kind == "generate_image") {
                    val method = if(model.contains("imagen",ignoreCase = true)) "predict" else "generateContent"
                    val body = if (referenceImages.isEmpty()) imageGenerationRequest(provider.type, model, prompt, imageOptions)
                        else googleImageEditRequest(model, prompt, imageOptions, referenceImages)
                    http.post("$root/models/$model:$method", body, provider)
                } else {
                    require(videoOptions.isEmpty() && inputImageUrl == null) { "Google video generation uses attached images and provider defaults." }
                    var job = http.post("$root/models/$model:predictLongRunning", googleVideoRequest(prompt, image), provider)
                    val name = job.text("name")
                    require(name.matches(Regex("[A-Za-z0-9_./-]+")) && !name.contains("..")) { "Invalid video operation" }
                    job = withTimeout(15*60*1000L) {
                        var state = job
                        while((state["done"] as? JsonPrimitive)?.booleanOrNull != true) {
                            delay(3000); state = http.json(http.request("$root/$name",provider).get().build())
                        }; state
                    }
                    check(job["error"] == null) { "Video generation failed" }
                    job
                }
            }
            else -> error("This provider has no supported image/video generation API. Use an OpenAI-compatible or Google provider.")
        }
        val names = mutableListOf<String>()
        suspend fun collect(value: JsonElement, key: String = "") {
            when(value) {
                is JsonObject -> value.forEach { (k,v) -> collect(v,k) }
                is JsonArray -> value.forEach { collect(it,key) }
                is JsonPrimitive -> {
                    val s = value.contentOrNull.orEmpty()
                    if(s.startsWith("tool-file:")) names += s.removePrefix("tool-file:")
                    else if(key in listOf("url","uri") && s.startsWith("http")) {
                        check(names.size < 10) { "Too many media files in response" }
                        val sameOrigin = runCatching { java.net.URI(s).let { target -> java.net.URI(base).let { origin -> target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port } } }.getOrDefault(false)
                        names += http.download(s, if(sameOrigin) provider else null)
                    }
                }
                else -> Unit
            }
        }
        collect(response)
        check(names.isNotEmpty()) { "Provider returned no supported media. Check the selected model and endpoint." }
        obj("files" to JsonArray(names.distinct().map { str("tool-file:$it") }), "message" to str("Media saved and displayed to the user. Reuse these tool-file: URIs directly as inputs to compatible tools; send_file is not required."))
    }
}
