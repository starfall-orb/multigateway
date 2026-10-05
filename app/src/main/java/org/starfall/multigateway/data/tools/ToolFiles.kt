package org.starfall.multigateway.data.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** App-private files only; database rows never contain binary data. */
class ToolFiles(val directory: File) {
    constructor(context: Context): this(File(context.filesDir, "tool_media"))
    init {
        directory.mkdirs()
        synchronized(initialized) {
            if(initialized.add(directory.canonicalPath)) directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }
    private data class CachedHash(val length: Long, val modified: Long, val digest: String)
    private class DirectoryIndex { val hashes = mutableMapOf<String, CachedHash>() }
    companion object {
        val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        private val initialized = mutableSetOf<String>()
        private val indexes = mutableMapOf<String, DirectoryIndex>()
    }
    private val index = synchronized(initialized) {
        indexes.getOrPut(directory.canonicalPath) { DirectoryIndex() }
    }
    private val limit = 256L * 1024 * 1024
    fun resolve(name: String): File? = name.takeIf { it.matches(Regex("[a-zA-Z0-9._-]+")) }
        ?.let { File(directory, it) }?.takeIf {
            it.isFile && !it.name.endsWith(".part") && it.canonicalFile.parentFile == directory.canonicalFile
        }
    fun list(): List<File> = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun delete(names: Collection<String>) {
        synchronized(index) {
            names.forEach { name ->
                if (resolve(name)?.delete() == true) index.hashes.remove(name)
            }
        }
        revision.update { it + 1 }
    }
    private fun checkSpace() { check(directory.usableSpace > 64L * 1024 * 1024) { "Not enough storage. Free space in Storage." } }
    suspend fun save(input: InputStream, mime: String? = null, maxBytes: Long = limit): String = withContext(Dispatchers.IO) {
        checkSpace()
        val temp = File(directory, "${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { out ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if(total % (1024*1024) < n) checkSpace()
                    check(total <= minOf(limit, maxBytes)) { "File exceeds the ${minOf(limit, maxBytes) / (1024 * 1024)} MB limit." }
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            }
            check(temp.length() > 0) { "Empty media response" }
            val header = ByteArray(16)
            temp.inputStream().use { it.read(header) }
            val ext = when {
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "png"
                header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "jpg"
                String(header, 0, 3) == "GIF" -> "gif"
                String(header, 8, 4) == "WEBP" -> "webp"
                String(header, 0, 3) == "ID3" -> "mp3"
                String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE" -> "wav"
                String(header, 0, 4) == "OggS" -> "ogg"
                String(header, 0, 4) == "fLaC" -> "flac"
                String(header, 4, 4) == "ftyp" && (String(header, 8, 4).startsWith("M4A") ||
                    mime?.substringBefore(';') in setOf("audio/mp4", "audio/x-m4a")) -> "m4a"
                mime?.substringBefore(';') == "audio/webm" -> "weba"
                mime?.substringBefore(';') in setOf("audio/mpeg", "audio/mp3") -> "mp3"
                mime?.substringBefore(';') in setOf("audio/aac", "audio/aacp") -> "aac"
                header[0] == 0xff.toByte() && (header[1].toInt() and 0xf6) == 0xf0 -> "aac"
                header[0] == 0xff.toByte() && (header[1].toInt() and 0xe0) == 0xe0 -> "mp3"
                String(header, 4, 4) == "ftyp" -> "mp4"
                header[0] == 0x1a.toByte() && header[1] == 0x45.toByte() -> "webm"
                String(header, 0, 4) == "%PDF" -> "pdf"
                mime?.substringBefore(';') == "application/pdf" -> "pdf"
                mime?.substringBefore(';') in setOf("application/zip", "application/x-zip-compressed") -> "zip"
                mime?.substringBefore(';') == "text/csv" -> "csv"
                mime?.substringBefore(';') == "text/html" -> "html"
                mime == "text/plain" -> "txt"
                mime == "application/json" -> "json"
                else -> "bin"
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val context = coroutineContext
            synchronized(index) {
                context.ensureActive()
                val existingFiles = list().mapNotNull { resolve(it.name) }
                index.hashes.keys.retainAll(existingFiles.map { it.name }.toSet())
                val existing = existingFiles.firstOrNull { file ->
                    if (file.length() != temp.length()) return@firstOrNull false
                    val cached = index.hashes[file.name]?.takeIf {
                        it.length == file.length() && it.modified == file.lastModified()
                    }
                    val fingerprint = cached ?: try {
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { source ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                context.ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                fileDigest.update(buffer, 0, count)
                            }
                        }
                        CachedHash(file.length(), file.lastModified(), fileDigest.digest().joinToString("") { "%02x".format(it) })
                            .also { index.hashes[file.name] = it }
                    } catch (_: IOException) { return@firstOrNull false }
                    fingerprint.digest == hash
                }
                if (existing != null) existing.name else {
                    context.ensureActive()
                    // Never overwrite an existing referenced file, even if a
                    // hash-named file was externally changed or is a symlink.
                    val canonical = File(directory, "$hash.$ext")
                    val dest = if (canonical.exists()) File(directory, "${UUID.randomUUID()}.$ext") else canonical
                    check(temp.renameTo(dest)) { "Could not save media" }
                    index.hashes[dest.name] = CachedHash(dest.length(), dest.lastModified(), hash)
                    revision.update { it + 1 }
                    dest.name
                }
            }
        } finally { input.close(); temp.delete() }
    }
    suspend fun decode(raw: File): String = raw.inputStream().use { source ->
        java.util.Base64.getMimeDecoder().wrap(source).use { save(it) }
    }
    /** Streaming JSON string scanner: large base64 strings are spooled to disk, not held in RAM. */
    suspend fun sanitize(reader: Reader): String = withContext(Dispatchers.IO) {
        val input = PushbackReader(BufferedReader(reader), 2)
        val output = StringBuilder()
        var lastKey = ""
        while (true) {
            coroutineContext.ensureActive()
            val c = input.read()
            if (c < 0) break
            if (c != '"'.code) {
                output.append(c.toChar())
            } else {
                val value = StringBuilder()
                var raw: File? = null
                var spool: Writer? = null
                var escaped = false
                var length = 0L
                var media = false
                try {
                    while (true) {
                        val n = input.read()
                        check(n >= 0) { "Truncated JSON response" }
                        if (!escaped && n == '"'.code) break
                        length++
                        if (length % 32768L == 0L) coroutineContext.ensureActive()
                        check(length <= limit * 2) { "Tool result exceeds file limit" }
                        if (raw == null && value.length >= 16384) {
                            media = lastKey in setOf("b64_json", "bytesBase64Encoded", "video_base64", "base64", "blob") ||
                                value.startsWith("data:image/") || value.startsWith("data:video/") || value.startsWith("data:audio/") ||
                                (lastKey == "data" && value.all { it.isLetterOrDigit() || it in "+/=\\" })
                            checkSpace()
                            raw = File(directory, "${UUID.randomUUID()}.part")
                            spool = raw.bufferedWriter()
                            spool.write(value.toString())
                            value.setLength(0)
                        }
                        if (spool != null) spool.write(n) else if (value.length < 65536) value.append(n.toChar())
                        escaped = if (escaped) false else n == '\\'.code
                    }
                    spool?.close()
                    var next: Int
                    do { next = input.read() } while (next >= 0 && next.toChar().isWhitespace())
                    if (next >= 0) input.unread(next)
                    if (next == ':'.code) {
                        lastKey = value.toString()
                        output.append('"').append(value).append('"')
                    } else if (raw != null && !media) {
                        // Keep full escaped text on disk; only its reference enters model/UI state.
                        val name = save(raw.inputStream(), "text/plain")
                        output.append('"').append("tool-file:").append(name).append('"')
                    } else if (raw != null) {
                        val normalized = File(directory, "${UUID.randomUUID()}.part")
                        try {
                            // JSON base64 may escape forward slashes. No media string is materialized.
                            raw.reader().buffered().use { r -> normalized.bufferedWriter().use { w ->
                                var slash = false
                                r.mark(64)
                                val prefix = CharArray(11)
                                val count = r.read(prefix)
                                r.reset()
                                if (count > 0 && String(prefix, 0, count).startsWith("data:")) {
                                    var headerLength = 0
                                    while (r.read() != ','.code) { check(++headerLength < 256) { "Invalid media data URL" } }
                                }
                                var processed = 0
                                while (true) {
                                    if (++processed % 32768 == 0) coroutineContext.ensureActive()
                                    val v = r.read(); if (v < 0) break
                                    if (slash) { if (v != 'n'.code && v != 'r'.code) w.write(v); slash = false }
                                    else if (v == '\\'.code) slash = true else w.write(v)
                                }
                            } }
                            val name = decode(normalized)
                            output.append('"').append("tool-file:").append(name).append('"')
                        } finally { normalized.delete() }
                    } else {
                        // Small base64 media is also saved, including small previews.
                        val text = value.toString()
                        if (text.startsWith("data:image/") || text.startsWith("data:video/") || text.startsWith("data:audio/") || lastKey in setOf("b64_json", "bytesBase64Encoded", "video_base64", "base64", "blob") ||
                            (lastKey == "data" && text.length > 100 && text.matches(Regex("[A-Za-z0-9+/=\\\\]+")))) {
                            val rawSmall = File(directory, "${UUID.randomUUID()}.part")
                            try {
                                rawSmall.writeText((if (text.startsWith("data:")) text.substringAfter(",") else text).replace("\\/", "/"))
                                output.append('"').append("tool-file:").append(decode(rawSmall)).append('"')
                            } finally { rawSmall.delete() }
                        } else {
                            val decoded = runCatching { kotlinx.serialization.json.Json.parseToJsonElement("\"$text\"") }.getOrNull()
                            output.append(decoded?.toString() ?: "\"[Text truncated]\"")
                        }
                    }
                } finally { spool?.close(); raw?.delete() }
            }
            check(output.length <= 2 * 1024 * 1024) { "Tool metadata exceeds 2 MB limit" }
        }
        output.toString()
    }
}
