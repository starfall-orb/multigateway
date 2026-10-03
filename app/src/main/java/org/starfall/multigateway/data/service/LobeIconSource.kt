package org.starfall.multigateway.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Only the filename index is fetched in advance; image bytes are requested on demand. */
internal class LobeIconSource(
    private val indexUrl: String = "https://data.jsdelivr.com/v1/package/npm/@lobehub/icons-static-png@1.97.1/flat",
    private val imageBase: String = "https://cdn.jsdelivr.net/npm/@lobehub/icons-static-png@1.97.1/light/",
) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private var catalog: Set<String>? = null
    private var retryAfter = 0L

    fun find(candidate: String): String? {
        if (catalog == null) {
            if (System.currentTimeMillis() < retryAfter) return null
            try {
                val json = Json.parseToJsonElement(download(indexUrl, 2 * 1024 * 1024).decodeToString())
                catalog = json.jsonObject.getValue("files").jsonArray.mapNotNull { item ->
                    item.jsonObject["name"]?.jsonPrimitive?.content
                        ?.takeIf { Regex("^/light/[a-z0-9-]+\\.png$").matches(it) }
                        ?.removePrefix("/light/")
                }.toSet()
            } catch (error: Exception) {
                retryAfter = System.currentTimeMillis() + 60_000
                throw error
            }
        }
        return match(candidate, catalog.orEmpty())
    }

    fun image(filename: String): ByteArray = download(imageBase + filename, 2 * 1024 * 1024)

    private fun download(url: String, limit: Int): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Icon HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Icon response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    companion object {
        private fun normalized(name: String) = name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

        internal fun match(candidate: String, files: Set<String>): String? {
            val key = normalized(candidate)
            if (key.isEmpty()) return null
            // Exact normalized brand names first; aliases only cover model family names.
            val aliases = mapOf("gpt" to "openai", "chatgpt" to "openai", "o1" to "openai",
                "o3" to "openai", "o4" to "openai", "glm" to "chatglm")
            for (name in listOfNotNull(key, aliases[key]).distinct()) {
                files.sorted().firstOrNull { it.endsWith("-color.png") && normalized(it.removeSuffix("-color.png")) == name }
                    ?.let { return it }
                files.sorted().firstOrNull { normalized(it.removeSuffix(".png")) == name }
                    ?.let { return it }
            }
            return null
        }
    }
}
