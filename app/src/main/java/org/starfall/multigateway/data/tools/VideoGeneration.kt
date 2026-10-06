package org.starfall.multigateway.data.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.starfall.multigateway.data.model.LlmProviderInfo

internal class VideoGeneration(
    private val http: ToolHttp,
    private val pollIntervalMillis: Long = 1500,
    private val pollingTimeoutMillis: Long = 15 * 60 * 1000L
) {
    suspend fun generate(provider: LlmProviderInfo, model: String, prompt: String, image: MediaInputImage?, options: JsonObject, imageUrl: String?): JsonObject {
        val api = videoApi(provider)
        require(image == null || imageUrl == null) { "Use one source image: an attachment or input_image_url." }
        imageUrl?.let(::validateVideoImageUrl)
        val effectiveOptions = if (api == VideoApi.AGNES && imageUrl != null) JsonObject(options + ("first_frame" to str(imageUrl))) else options
        validateVideoOptions(provider, model, effectiveOptions)
        val configuredBase = providerBase(provider).removeSuffix("/videos")
        val base = if (api == VideoApi.AGNES) agnesApiBase(provider) else configuredBase
        var job = when (api) {
            VideoApi.OPENAI -> {
                require(imageUrl == null) { "This video API needs an attached source image, not input_image_url." }
                http.json(http.request("$base/videos", provider).post(openAiVideoRequest(model, prompt, image)).build())
            }
            VideoApi.AGNES -> {
                require(image == null) { "Agnes Video requires public image URLs. Local file uploads are not supported by its documented API; use first-frame URL in Video settings or input_image_url." }
                http.post("$base/videos", agnesVideoRequest(model, prompt, options, imageUrl), provider)
            }
        }
        val id = if (api == VideoApi.AGNES) job.text("video_id") else job.text("id").ifBlank { job.text("task_id") }
        require(id.isEmpty() || id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid video job ID." }
        fun terminal(state: JsonObject) = state.text("status").lowercase() in setOf("completed", "failed", "cancelled", "canceled")
        if (!terminal(job)) {
            if (id.isBlank() && api == VideoApi.OPENAI && job.text("status").isBlank()) return job
            require(id.isNotBlank()) { "Video API returned no ${if (api == VideoApi.AGNES) "video_id" else "job ID"} to retrieve the result." }
            val pollUrl = if (api == VideoApi.AGNES) {
                "${base.removeSuffix("/v1")}/agnesapi".toHttpUrl().newBuilder()
                    .addQueryParameter("video_id", id).addQueryParameter("model_name", model).build().toString()
            } else "$base/videos/$id"
            job = withTimeout(pollingTimeoutMillis) {
                var state = job
                while (!terminal(state)) {
                    delay(pollIntervalMillis)
                    state = http.json(http.request(pollUrl, provider).get().build())
                }
                state
            }
        }
        check(job.text("status").equals("completed", true)) { "Video generation failed or was cancelled." }
        if (api == VideoApi.AGNES) {
            val url = resultUrl(job)
            check(url.isNotBlank()) { "Agnes completed the task without a video download URL." }
            return obj("url" to str(url))
        }
        // OpenAI-compatible gateways may provide a CDN URL for the common media collector.
        if (resultUrl(job).isNotBlank()) return obj("url" to str(resultUrl(job)))
        if (id.isBlank()) return job
        return obj("file" to str("tool-file:" + http.download("$base/videos/$id/content", provider)))
    }

    private fun resultUrl(job: JsonObject): String = job.text("url").ifBlank {
        job.text("video_url").ifBlank { (job["metadata"] as? JsonObject)?.text("url").orEmpty() }
    }
}
