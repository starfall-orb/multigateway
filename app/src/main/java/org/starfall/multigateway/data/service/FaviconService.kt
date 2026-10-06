package org.starfall.multigateway.data.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.starfall.multigateway.data.tools.ToolHttp
import java.util.concurrent.TimeUnit

internal fun faviconHomepage(customUrl: String, baseUrl: String): HttpUrl {
    val value = customUrl.trim().ifBlank { baseUrl.trim() }
    require(value.isNotBlank()) { "Enter a homepage URL or set the provider Base URL." }
    val url = (if ("://" in value) value else "https://$value").toHttpUrlOrNull()
        ?: error("Enter a valid HTTP or HTTPS homepage URL.")
    require(url.username.isEmpty() && url.password.isEmpty()) { "The homepage URL must not contain embedded credentials." }
    return if (customUrl.isBlank()) url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        else url.newBuilder().fragment(null).build()
}

internal fun faviconFallbackUrls(homepage: HttpUrl): List<HttpUrl> {
    val origin = homepage.newBuilder().encodedPath("/").query(null).fragment(null).build()
    val hosts = listOfNotNull(origin.host, origin.topPrivateDomain()).distinct()
    return listOf("favicon.ico", "favicon.png").flatMap { file ->
        hosts.map { host -> origin.newBuilder().host(host).encodedPath("/$file").build() }
    }
}

/** Read icon links only inside head; ignore comments/scripts and resolve relative URLs. */
internal fun faviconHeadUrls(html: String, page: HttpUrl): List<HttpUrl> {
    val head = Regex("<head\\b[^>]*>(.*?)</head\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.get(1) ?: return emptyList()
    val clean = head.replace(Regex("<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    val tags = Regex("<(link|base)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE).findAll(clean)
    val attr = Regex("([a-zA-Z_:][a-zA-Z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    var base = page
    var hasBase = false
    val icons = mutableListOf<Pair<String, String>>()
    tags.forEach { tag ->
        val attrs = attr.findAll(tag.value).associate { match ->
            match.groupValues[1].lowercase() to Html.fromHtml(
                match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString()
        }
        val href = attrs["href"].orEmpty()
        if (tag.groupValues[1].equals("base", true) && !hasBase && href.isNotBlank()) {
            page.resolve(href)?.let { base = it; hasBase = true }
        } else if (tag.groupValues[1].equals("link", true)) {
            val rel = attrs["rel"].orEmpty().lowercase().split(Regex("\\s+"))
            if ("icon" in rel || rel.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" })
                icons += attrs["rel"].orEmpty() to href
        }
    }
    return icons.sortedBy { if (it.first.lowercase().contains("apple-touch-icon")) 1 else 0 }
        .mapNotNull { (_, href) -> href.takeIf { it.isNotBlank() }?.let(base::resolve) }
        .filter { it.username.isEmpty() && it.password.isEmpty() }.distinct().take(16)
}

internal data class FaviconResult(val bitmap: Bitmap, val url: HttpUrl)

/** Fetches public web pages without provider auth, with bounded reads and cancellation. */
internal class FaviconService(private val http: ToolHttp = ToolHttp(client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build())) {
    private data class Resource(val bytes: ByteArray, val url: HttpUrl)

    private suspend fun download(url: HttpUrl, html: Boolean = false): Resource {
        var target = url
        repeat(6) {
            val resource = http.withResponse(http.request(target.toString()).header("Accept",
                if (html) "text/html,application/xhtml+xml" else "image/*,*/*;q=0.5").get().build()) { response ->
                if (response.isRedirect) {
                    target = response.header("Location")?.let(target::resolve) ?: error("Redirect has no valid URL.")
                    require(target.username.isEmpty() && target.password.isEmpty()) { "Redirect contains embedded credentials." }
                    null
                } else {
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("Empty response.")
                    val limit = if (html) 1024 * 1024 else 2 * 1024 * 1024
                    require(body.contentLength() <= limit) { "Response exceeds ${limit / 1024} KB." }
                    val bytes = body.byteStream().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= limit) { "Response exceeds ${limit / 1024} KB." }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    Resource(bytes, response.request.url)
                }
            }
            if (resource != null) return resource
        }
        error("Too many redirects.")
    }

    suspend fun get(customUrl: String, baseUrl: String): FaviconResult = withContext(Dispatchers.IO) {
        val homepage = faviconHomepage(customUrl, baseUrl)
        val failures = mutableListOf<String>()
        val links = try {
            val page = download(homepage, html = true)
            faviconHeadUrls(page.bytes.toString(Charsets.UTF_8), page.url)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            failures += "Homepage: ${error.message ?: "could not load the page"}"
            emptyList()
        }
        for (url in (links + faviconFallbackUrls(homepage)).distinct()) {
            try {
                val resource = download(url)
                val bitmap = decodeFavicon(resource.bytes) ?: error("Not a supported PNG, JPEG, WebP, GIF, or ICO image.")
                return@withContext FaviconResult(bitmap, resource.url)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failures += "${url.host}${url.encodedPath}: ${error.message ?: "could not load the icon"}" }
        }
        error("No favicon could be retrieved. Tried icon links in the homepage head, then favicon.ico and favicon.png on the host and root domain.\n\n" + failures.joinToString("\n"))
    }
}

internal fun decodeFavicon(bytes: ByteArray): Bitmap? {
    if (bytes.size >= 6 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() && bytes[3] == 0.toByte())
        return decodeIco(bytes)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
    if (scale == 1f) return bitmap
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
}
