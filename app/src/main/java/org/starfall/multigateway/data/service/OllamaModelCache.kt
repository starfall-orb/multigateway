package org.starfall.multigateway.data.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Last successful discovery is usable immediately, even when an Ollama host is offline. */
internal class OllamaModelCache(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val context = context.applicationContext
    private val preferences by lazy { context.getSharedPreferences("ollama-model-catalog", Context.MODE_PRIVATE) }
    @Serializable data class Entry(val models: List<String>, val fetchedAt: Long)

    private fun key(baseUrl: String): String = MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.trim().trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun read(baseUrl: String): Entry? = withContext(Dispatchers.IO) {
        runCatching { preferences.getString(key(baseUrl), null)?.let { Json.decodeFromString<Entry>(it) } }.getOrNull()
    }

    fun isFresh(entry: Entry): Boolean = now() - entry.fetchedAt in 0 until 5 * 60_000L

    suspend fun write(baseUrl: String, models: List<String>) = withContext(Dispatchers.IO) {
        preferences.edit().putString(key(baseUrl), Json.encodeToString(Entry(models.distinct(), now()))).apply()
    }
}
