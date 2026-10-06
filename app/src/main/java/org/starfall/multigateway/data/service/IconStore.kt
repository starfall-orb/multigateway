package org.starfall.multigateway.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

@Serializable
data class IconRule(val id: String = UUID.randomUUID().toString(), val pattern: String, val image: String)

data class StoredIcon(val image: String, val filename: String, val patterns: List<String>,
    val lightImage: String = image, val darkImage: String? = null)

/** Try each model family first, then progressively restore hyphen suffixes, before the vendor. */
internal fun iconMatchNames(name: String, model: Boolean): List<String> {
    val clean = name.trim()
    return (if (model && '/' in clean) listOf(clean.substringAfterLast('/'), clean.substringBefore('/'))
        else listOf(clean)).flatMap { part ->
            if (!model) {
                // Exact names win. Then try complete word groups, longest first,
                // so descriptive prefixes/suffixes work for every brand, including
                // multi-word brands, without matching fragments inside other words.
                val providerName = part.trim()
                val words = Regex("[\\p{L}\\p{N}]+").findAll(providerName).map { it.value }.toList()
                buildList {
                    add(providerName)
                    for (length in words.size downTo 1) {
                        for (start in 0..words.size - length) {
                            add(words.subList(start, start + length).joinToString(" "))
                        }
                    }
                }
            } else {
                val segments = part.trim().split('-')
                segments.indices.map { segments.take(it + 1).joinToString("-") }
            }
        }
        .filter { it.isNotEmpty() }.distinct()
}

/** Icons belong to the app, independent of temporary photo-picker URI permissions. */
class IconStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "entity-icons").apply { mkdirs() }

    private val preferences get() = context.getSharedPreferences("named-entity-icons", Context.MODE_PRIVATE)
    private val assets get() = context.getSharedPreferences("icon-assets", Context.MODE_PRIVATE)
    private val automatic get() = context.getSharedPreferences("automatic-entity-icons", Context.MODE_PRIVATE)
    private val variants get() = context.getSharedPreferences("icon-variants", Context.MODE_PRIVATE)
    companion object {
        private val changes = MutableStateFlow(0L)
        val revision = changes.asStateFlow()
        private val lookupChanges = MutableStateFlow(0L)
        val lookupRevision = lookupChanges.asStateFlow()
        private val bitmaps = object : android.util.LruCache<String, Bitmap>(8 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
        private val storedIconName = Regex("^((?:entity-)?icon-[a-f0-9-]+|lobe-[a-z0-9-]+)\\.png$")
        private val downloadLock = Mutex()
        private val mutationLock = Any()
        private val source = LobeIconSource()
        private val remoteClient = okhttp3.OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        private val failedDownloads = mutableMapOf<String, Long>()
        private val missingDarkVariants = mutableSetOf<String>()
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

    fun saveRules(rules: List<IconRule>) = synchronized(mutationLock) {
        require(rules.all { it.pattern.isNotBlank() && runCatching { Regex(it.pattern) }.isSuccess })
        check(preferences.edit().clear().putString("rules", Json.encodeToString(rules)).commit())
        changes.value += 1
        lookupChanges.value += 1
    }

    fun entries(): List<StoredIcon> {
        val rules = rules()
        val variantImages = variants.all.values.filterIsInstance<String>().toSet()
        return directory.listFiles().orEmpty().filter { storedIconName.matches(it.name) &&
            !it.name.startsWith("entity-icon-") && !it.name.startsWith("lobe-dark-") && it.name !in variantImages }.map { file ->
            StoredIcon(file.name, assets.getString(file.name, file.name) ?: file.name,
                rules.filter { it.image == file.name }.map { it.pattern },
                lightImage = themedImage(file.name, false),
                darkImage = variants.getString("dark:${file.name}", null)?.takeIf { File(directory, it).isFile })
        }.sortedBy { it.filename.lowercase(java.util.Locale.ROOT) }
    }

    fun editMatches(image: String, patterns: List<String>) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        val existing = rules()
        val old = existing.filter { it.image == image }
        val replacement = patterns.distinct().map { pattern ->
            old.firstOrNull { it.pattern == pattern } ?: IconRule(pattern = pattern, image = image)
        }
        // Retain the relative precedence of existing rules, appending newly added cases.
        saveRules(existing.filter { it.image != image || it.pattern in patterns } +
            replacement.filter { rule -> old.none { it.id == rule.id } })
        check(assets.edit().putString(image, assets.getString(image, image)).commit())
    }

    fun delete(image: String) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        val file = File(directory, image)
        bitmaps.remove(file.absolutePath)
        check(!file.exists() || file.delete())
        saveRules(rules().filterNot { it.image == image })
        val editor = automatic.edit()
        automatic.all.filterValues { it == image }.keys.forEach { editor.remove(it) }
        check(editor.commit())
        check(assets.edit().remove(image).commit())
        check(variants.edit().remove("light:$image").remove("dark:$image").commit())
        changes.value += 1
    }

    /** Called on IO only, after explicit icons and user regex rules have been considered. */
    internal suspend fun resolve(name: String, model: Boolean = false, iconSource: LobeIconSource = source): String? {
        find(name, model)?.let { image ->
            if (image.startsWith("lobe-") && !image.startsWith("lobe-dark-")) {
                downloadLock.withLock { ensureDarkVariant(image, iconSource) }
            }
            return image
        }
        return downloadLock.withLock {
            find(name, model)?.let { return@withLock it }
            val remoteCandidates = buildList {
                if (model) LobeModelIconResolver.resolve(name)?.let(::add)
                addAll(iconMatchNames(name, model))
            }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
            for (candidate in remoteCandidates) {
                var attemptedId: String? = null
                val key = candidate.lowercase(java.util.Locale.ROOT)
                automatic.getString(key, null)?.let { cached ->
                    if (File(directory, cached).isFile) {
                        ensureDarkVariant(cached, iconSource)
                        return@withLock cached
                    }
                }
                try {
                    val filename = iconSource.find(candidate) ?: continue
                    val id = "lobe-$filename"
                    attemptedId = id
                    if ((failedDownloads[id] ?: 0L) > System.currentTimeMillis()) continue
                    val file = File(directory, id)
                    if (!file.exists()) {
                        val bytes = iconSource.image(filename)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                        synchronized(mutationLock) {
                            val staging = File.createTempFile("download-", ".tmp", directory)
                            try {
                                staging.writeBytes(bytes)
                                check(staging.renameTo(file))
                            } finally { staging.delete() }
                        }
                    }
                    ensureDarkVariant(id, iconSource)
                    synchronized(mutationLock) {
                        if (file.exists()) {
                            check(assets.edit().putString(id, filename).commit())
                            check(automatic.edit().putString(key, id).commit())
                            changes.value += 1
                        }
                    }
                    return@withLock find(name, model) ?: id
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Offline/missing images leave the existing UI fallback intact; retry later.
                    attemptedId?.let { failedDownloads[it] = System.currentTimeMillis() + 60_000 }
                }
            }
            null
        }
    }

    private fun ensureDarkVariant(image: String, iconSource: LobeIconSource) {
        if (!image.startsWith("lobe-") || image.startsWith("lobe-dark-") ||
            variants.contains("dark:$image") || image in missingDarkVariants) return
        val failureKey = "dark:$image"
        if ((failedDownloads[failureKey] ?: 0L) > System.currentTimeMillis()) return
        try {
            val brand = image.removePrefix("lobe-").removeSuffix(".png").removeSuffix("-color")
            val filename = iconSource.find(brand, dark = true)
            if (filename == null) { missingDarkVariants += image; return }
            val id = "lobe-dark-$filename"
            val file = File(directory, id)
            if (!file.isFile) {
                val bytes = iconSource.image(filename, dark = true)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                val staging = File.createTempFile("download-", ".tmp", directory)
                try { staging.writeBytes(bytes); check(staging.renameTo(file)) } finally { staging.delete() }
            }
            synchronized(mutationLock) {
                check(variants.edit().putString("dark:$image", id).commit())
                changes.value += 1
            }
        } catch (e: Exception) { failedDownloads[failureKey] = System.currentTimeMillis() + 60_000 }
    }

    fun themedImage(image: String, dark: Boolean): String {
        val light = variants.getString("light:$image", null)?.takeIf { File(directory, it).isFile } ?: image
        return if (dark) variants.getString("dark:$image", null)?.takeIf { File(directory, it).isFile } ?: light else light
    }

    fun setVariant(image: String, replacement: String?, dark: Boolean) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        require(replacement == null || (storedIconName.matches(replacement) && File(directory, replacement).isFile))
        val key = "${if (dark) "dark" else "light"}:$image"
        val editor = variants.edit()
        if (replacement == null) editor.remove(key) else editor.putString(key, replacement)
        check(editor.commit())
        changes.value += 1
        lookupChanges.value += 1
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

    fun cache(name: String, image: String, model: Boolean = false) = synchronized(mutationLock) {
        if (image.startsWith("entity-icon-")) return
        val candidate = iconMatchNames(name, model).firstOrNull() ?: return
        val pattern = Regex.escape(candidate)
        val existing = rules()
        val index = existing.indexOfFirst { it.pattern.equals(pattern, ignoreCase = true) }
        if (index >= 0 && existing[index].image == image) return
        val rule = if (index >= 0) existing[index].copy(image = image) else IconRule(pattern = pattern, image = image)
        saveRules(listOf(rule) + existing.filterNot { it.id == rule.id })
    }

    fun importImage(uri: Uri, shared: Boolean = true): String {
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
            val id = "${if (shared) "" else "entity-"}icon-${UUID.randomUUID()}.png"
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
            val filename = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: id
            if (shared) check(assets.edit().putString(id, filename).commit())
            changes.value += 1
            return id
        } finally {
            staging.delete()
        }
    }

    /** Save a confirmed favicon preview as an app-owned icon. Called on IO. */
    internal fun importBitmap(bitmap: Bitmap): String {
        val staging = File.createTempFile("favicon-", ".png", context.cacheDir)
        try {
            staging.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return importImage(Uri.fromFile(staging), shared = false)
        } finally { staging.delete() }
    }

    /** Explicit URL icons use the same app-owned, resized cache as imported pictures. Called on IO. */
    internal suspend fun loadIcon(image: String?, dark: Boolean = false): Bitmap? {
        if (image == null) return null
        val uri = Uri.parse(image)
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return load(themedImage(image, dark))
        return downloadLock.withLock {
            val cacheKey = "remote:$image"
            automatic.getString(cacheKey, null)?.let { cached -> load(themedImage(cached, dark))?.let { return@withLock it } }
            if ((failedDownloads[cacheKey] ?: 0L) > System.currentTimeMillis()) return@withLock null
            val staging = File.createTempFile("remote-", ".tmp", directory)
            try {
                remoteClient.newCall(okhttp3.Request.Builder().url(image).build()).execute().use { response ->
                    check(response.isSuccessful) { "Icon download failed" }
                    val body = response.body ?: error("Empty icon response")
                    require(body.contentLength() <= 20L * 1024 * 1024) { "Icon is too large" }
                    body.byteStream().use { input ->
                        staging.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 20L * 1024 * 1024) { "Icon is too large" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                val id = importImage(Uri.fromFile(staging))
                check(assets.edit().putString(id, uri.lastPathSegment ?: "Provider icon").commit())
                check(automatic.edit().putString(cacheKey, id).commit())
                load(themedImage(id, dark))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedDownloads[cacheKey] = System.currentTimeMillis() + 60_000
                null
            } finally { staging.delete() }
        }
    }

    fun load(id: String?): Bitmap? {
        if (id == null || !storedIconName.matches(id)) return null
        val file = File(directory, id)
        if (!file.isFile) { bitmaps.remove(file.absolutePath); return null }
        bitmaps.get(file.absolutePath)?.takeUnless { it.isRecycled }?.let { return it }
        return BitmapFactory.decodeFile(file.path)?.also { bitmaps.put(file.absolutePath, it) }
    }

    /**
     * Removes imported files that are no longer referenced by either persisted entities or
     * shared matching rules. This is intentionally reference-driven so deleting a cache rule
     * never removes an icon that is still explicitly assigned to a Provider, Model or MCP server.
     */
    fun prune(entityImages: Collection<String?>) = synchronized(mutationLock) {
        val referenced = buildSet {
            rules().mapTo(this) { it.image }
            entityImages.filterNotNull().filterTo(this) { storedIconName.matches(it) }
            assets.all.keys.filterTo(this) { storedIconName.matches(it) }
            variants.all.values.filterIsInstance<String>().filterTo(this) { storedIconName.matches(it) }
        }
        directory.listFiles().orEmpty().forEach { file ->
            val staleIcon = storedIconName.matches(file.name) && file.name !in referenced
            val staleImport = file.name.startsWith("import-") && file.extension == "tmp"
            if (staleIcon || staleImport) file.delete()
        }
    }
}
