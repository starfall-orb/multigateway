package org.starfall.multigateway.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class IconRule(val id: String = UUID.randomUUID().toString(), val pattern: String, val image: String)

/** Model families prefer the suffix after "/" and then the prefix, trimming "-..." from each. */
internal fun iconMatchNames(name: String, model: Boolean): List<String> {
    val clean = name.trim()
    return (if (model && '/' in clean) listOf(clean.substringAfterLast('/'), clean.substringBefore('/'))
        else listOf(clean)).map { (if (model) it.substringBefore('-') else it).trim() }
        .filter { it.isNotEmpty() }.distinct()
}

/** Icons belong to the app, independent of temporary photo-picker URI permissions. */
class IconStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "entity-icons").apply { mkdirs() }

    private val preferences get() = context.getSharedPreferences("named-entity-icons", Context.MODE_PRIVATE)
    companion object {
        private val changes = MutableStateFlow(0L)
        val revision = changes.asStateFlow()
        private val storedIconName = Regex("^icon-[a-f0-9-]+\\.png$")
    }

    fun rules(): List<IconRule> {
        val saved = preferences.getString("rules", null)
        if (saved != null) return runCatching { Json.decodeFromString<List<IconRule>>(saved) }.getOrDefault(emptyList())
        // Upgrade earlier name caches into one shared collection.
        return preferences.all.mapNotNull { (key, value) ->
            if (':' !in key || value !is String) null
            else IconRule(pattern = Regex.escape(key.substringAfter(':')), image = value)
        }.distinctBy { it.pattern }
    }

    fun saveRules(rules: List<IconRule>) {
        require(rules.all { it.pattern.isNotBlank() && runCatching { Regex(it.pattern) }.isSuccess })
        check(preferences.edit().clear().putString("rules", Json.encodeToString(rules)).commit())
        changes.value += 1
    }

    fun find(name: String, model: Boolean = false): String? {
        val compiled = rules().mapNotNull { rule ->
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) to rule.image }.getOrNull()
        }
        for (candidate in iconMatchNames(name, model)) {
            compiled.firstOrNull { it.first.matches(candidate) }?.let { return it.second }
        }
        return null
    }

    fun cache(name: String, image: String, model: Boolean = false) {
        val candidate = iconMatchNames(name, model).firstOrNull() ?: return
        val pattern = Regex.escape(candidate)
        val existing = rules()
        val index = existing.indexOfFirst { it.pattern.equals(pattern, ignoreCase = true) }
        if (index >= 0 && existing[index].image == image) return
        val rule = if (index >= 0) existing[index].copy(image = image) else IconRule(pattern = pattern, image = image)
        saveRules(listOf(rule) + existing.filterNot { it.id == rule.id })
    }

    fun importImage(uri: Uri): String {
        val staging = File.createTempFile("import-", ".tmp", directory)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open image")
            input.use { source ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 20L * 1024 * 1024) { "Image is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staging.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val bitmap = BitmapFactory.decodeFile(staging.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Cannot decode image")
            val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
            val resized = Bitmap.createScaledBitmap(bitmap,
                maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
            val id = "icon-${UUID.randomUUID()}.png"
            val target = File(directory, id)
            try {
                target.outputStream().use { check(resized.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally {
                if (resized !== bitmap) resized.recycle()
                bitmap.recycle()
            }
            return id
        } finally {
            staging.delete()
        }
    }

    fun load(id: String?): Bitmap? {
        if (id == null || !storedIconName.matches(id)) return null
        return BitmapFactory.decodeFile(File(directory, id).path)
    }

    /**
     * Removes imported files that are no longer referenced by either persisted entities or
     * shared matching rules. This is intentionally reference-driven so deleting a cache rule
     * never removes an icon that is still explicitly assigned to a Provider, Model or MCP server.
     */
    fun prune(entityImages: Collection<String?>) {
        val referenced = buildSet {
            rules().mapTo(this) { it.image }
            entityImages.filterNotNull().filterTo(this) { storedIconName.matches(it) }
        }
        directory.listFiles().orEmpty().forEach { file ->
            val staleIcon = storedIconName.matches(file.name) && file.name !in referenced
            val staleImport = file.name.startsWith("import-") && file.extension == "tmp"
            if (staleIcon || staleImport) file.delete()
        }
    }
}
