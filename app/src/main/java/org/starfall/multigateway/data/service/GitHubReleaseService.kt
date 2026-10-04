package org.starfall.multigateway.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

internal data class ApkAsset(val name: String, val sizeBytes: Long, val downloadUrl: String)
internal data class GitHubReleaseInfo(val tagName: String, val title: String, val body: String,
    val pageUrl: String, val apkAssets: List<ApkAsset>)

internal suspend fun fetchLatestGitHubRelease(): GitHubReleaseInfo = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/starfall-orb/multigateway/releases/latest").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "MultiGateway-Android")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub returned HTTP $code")
        val root = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val title = root["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tag
        val body = root["body"]?.jsonPrimitive?.content.orEmpty()
        val page = root["html_url"]?.jsonPrimitive?.content.orEmpty()
        val assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject }
            .filter { it["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true }
            .map { asset -> ApkAsset(asset["name"]?.jsonPrimitive?.content.orEmpty(),
                asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()) }
        GitHubReleaseInfo(tag, title, body, page, assets)
    } finally { connection.disconnect() }
}
