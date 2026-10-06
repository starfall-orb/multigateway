package org.starfall.multigateway.data.tools

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
internal fun str(value: String) = JsonPrimitive(value)
internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun providerBase(p: LlmProviderInfo): String {
    val url = p.baseUrl.trim().toHttpUrlOrNull() ?: error("Provider URL must use http:// or https://")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Put credentials in Authorization and use a base URL without query or fragment"
    }
    val base = url.toString().trimEnd('/')
    val suffix = listOf("/chat/completions", "/responses", "/messages", "/models", "/chat").firstOrNull { base.endsWith(it) }
    return if (suffix == null) base else base.removeSuffix(suffix)
}
class ToolHttp(
    val files: ToolFiles? = null,
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).build()
) {
    fun requireFiles(): ToolFiles = files ?: error("Tool/media storage is required for this operation")
    suspend fun execute(request: Request): Response = execute(client.newCall(request))
    internal suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }
    internal suspend fun <T> withResponse(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        execute(call).use { response ->
            val cancelRead = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { block(response) } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally { cancelRead.cancel() }
        }
    }
    fun request(url: String, provider: LlmProviderInfo? = null): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        provider?.let { p ->
            val auth = p.auth
            auth.requestHeaders(p.type).forEach { (key, value) -> builder.header(key, value) }
            if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
                builder.url(builder.build().url.newBuilder().setQueryParameter(auth.key?.takeIf { it.isNotBlank() } ?: "key", auth.token).build())
            }
            if (p.type == ProviderType.ANTHROPIC) builder.header("anthropic-version", "2023-06-01")
            p.config.headers.forEach { (k,v) -> builder.header(k,v) }
        }
        return builder
    }
    suspend fun json(request: Request, allowOAuthError: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        withResponse(request) { response ->
            requireSuccess(response)
            val parsed = Json.parseToJsonElement(readJson(response)) as? JsonObject ?: error("Provider returned invalid JSON: expected an object")
            check(allowOAuthError || parsed["error"] == null || parsed["error"] == JsonNull) { "Provider error: " + safeError(parsed["error"], requestSecrets(request)) }
            parsed
        }
    }
    internal fun requestSecrets(request: Request): List<String> = request.headers.flatMap { listOf(it.second, it.second.removePrefix("Bearer ")) } +
        request.url.queryParameterNames.mapNotNull { request.url.queryParameter(it) }
    internal fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val body = response.body?.source()?.let { source ->
            source.request(4096)
            source.buffer.clone().readUtf8(minOf(source.buffer.size, 4096L))
        }.orEmpty()
        val value = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val detail = safeError((value as? JsonObject)?.get("error") ?: value, requestSecrets(response.request))
        error("HTTP ${response.code}: $detail")
    }
    internal suspend fun readJson(response: Response): String {
        val body = response.body ?: error("Empty response (HTTP ${response.code})")
        return body.charStream().use { reader ->
            files?.sanitize(reader) ?: run {
                val buffer = CharArray(8192); val out = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = reader.read(buffer); if (n < 0) break
                    check(out.length + n <= 2 * 1024 * 1024) { "Response exceeds 2 MB metadata limit" }
                    out.append(buffer, 0, n)
                }
                out.toString()
            }
        }
    }
    suspend fun post(url: String, data: JsonObject, provider: LlmProviderInfo? = null) =
        json(request(url, provider).post(data.toString().toRequestBody("application/json".toMediaType())).build())
    suspend fun download(url: String, provider: LlmProviderInfo? = null): String = withContext(Dispatchers.IO) {
        var request = request(url, provider).header("Accept", "image/*, video/*, audio/*, application/octet-stream").get().build()
        repeat(5) {
            val downloaded = withResponse(request) { response ->
                if (response.isRedirect) {
                    val target = response.header("Location")?.let { request.url.resolve(it) } ?: error("Invalid download redirect")
                    check(target.scheme == "https" || (request.url.scheme == "http" && target.host == request.url.host && target.port == request.url.port)) { "Unsafe media redirect" }
                    // Never forward provider credentials to a CDN or signed download URL.
                    request = Request.Builder().url(target).build()
                    null
                } else {
                    requireSuccess(response)
                    val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                    check(mime.isEmpty() || mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/octet-stream") { "Download did not return media content" }
                    val store = requireFiles()
                    val name = store.save((response.body ?: error("Empty media response (HTTP ${response.code})")).byteStream(), mime)
                    if (name.endsWith(".bin")) { store.delete(listOf(name)); error("Downloaded content is not supported media") }
                    name
                }
            }
            if (downloaded != null) return@withContext downloaded
        }
        error("Too many media redirects")
    }
}
