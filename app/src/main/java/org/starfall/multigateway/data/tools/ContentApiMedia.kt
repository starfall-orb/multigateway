package org.starfall.multigateway.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ContentApiMedia(val url: String, val type: String)
internal data class PreparedContentApiResult(val content: JsonObject, val media: List<ContentApiMedia>)
internal data class ContentApiMediaResult(val content: JsonObject, val files: List<String>)

/** Handle direct JSON, MCP structuredContent and JSON encoded in MCP text blocks. */
internal fun prepareContentApiResult(result: JsonObject): PreparedContentApiResult {
    val media = linkedMapOf<String, ContentApiMedia>()
    val hiddenUrls = linkedSetOf<String>()
    fun embedded(value: JsonPrimitive): JsonElement? {
        if (!value.isString) return null
        val text = value.content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!text.startsWith("{") && !text.startsWith("[")) return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }
    fun collect(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element["media"] as? JsonArray)?.forEach { value ->
                    val item = value as? JsonObject ?: return@forEach
                    val url = item.text("url").trim()
                    if (url.isNotBlank()) hiddenUrls += url
                    val type = item.text("type").lowercase()
                    if (type in setOf("image", "video", "audio") && url.toHttpUrlOrNull() != null) {
                        media.putIfAbsent(url, ContentApiMedia(url, type))
                    }
                }
                element.text("thumbnail_url").takeIf { it.isNotBlank() }?.let(hiddenUrls::add)
                element.values.forEach(::collect)
            }
            is JsonArray -> element.forEach(::collect)
            is JsonPrimitive -> embedded(element)?.let(::collect)
            else -> Unit
        }
    }
    collect(result)
    val replacements = hiddenUrls.sortedByDescending { it.length }
    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (!element.isString) element else {
            val parsed = embedded(element)
            if (parsed != null) JsonPrimitive(redact(parsed).toString())
            else JsonPrimitive(replacements.fold(element.content) { text, url -> text.replace(url, "[media URL hidden by app]") })
        }
        else -> element
    }
    return PreparedContentApiResult(redact(result).jsonObject, media.values.take(32))
}

internal suspend fun resolveContentApiMedia(
    result: JsonObject,
    http: ToolHttp,
    onMediaReady: suspend (List<String>) -> Unit = {}
): ContentApiMediaResult {
    val prepared = prepareContentApiResult(result)
    val files = mutableListOf<String>()
    val displayed = mutableListOf<JsonElement>()
    val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
    for (media in prepared.media) {
        var downloaded = false
        if (!isError) {
            try {
                files += http.download(media.url)
                downloaded = true
                onMediaReady(files.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the extraction metadata useful even if one CDN URL expired or failed.
            }
        }
        displayed += obj("type" to str(media.type), "available" to JsonPrimitive(downloaded),
            "displayed_in_chat" to JsonPrimitive(false)).let { entry ->
            if (downloaded) JsonObject(entry + ("uri" to str("tool-file:${files.last()}"))) else entry
        }
    }
    val content = if (prepared.media.isEmpty()) prepared.content else JsonObject(prepared.content + mapOf(
        "app_media" to JsonArray(displayed),
        "app_media_note" to str("Media has been downloaded for you. Call send_file(type, uri) with an available media URI to send it to the user. Media URLs are hidden; do not output media links or local file references.")
    ))
    return ContentApiMediaResult(content, files)
}
