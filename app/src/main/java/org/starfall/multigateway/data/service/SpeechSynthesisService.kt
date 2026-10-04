package org.starfall.multigateway.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

fun supportsSpeechProvider(provider: LlmProviderInfo): Boolean =
    provider.type.isOpenAi || provider.type == ProviderType.GOOGLE

fun speechVoice(provider: LlmProviderInfo, service: SpeechService): String =
    service.voice.trim().takeUnless { it.isBlank() || it.equals("Default", true) }
        ?: if (provider.type == ProviderType.GOOGLE) "Kore" else "alloy"

fun speechPayload(provider: LlmProviderInfo, service: SpeechService, text: String): JsonObject {
    val model = service.modelId?.takeIf { it.isNotBlank() } ?: error("No TTS model is selected.")
    require(text.isNotBlank()) { "No text to read." }
    return buildJsonObject {
        service.extraBody.forEach { (key, value) -> put(key, value) }
        if (provider.type == ProviderType.GOOGLE) {
            put("contents", buildJsonArray { add(buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject {
                    put("text", listOf(service.instructions.trim(), text).filter { it.isNotBlank() }.joinToString("\n\n"))
                }) })
            }) })
            val extraConfig = service.extraBody["generationConfig"] as? JsonObject
            put("generationConfig", buildJsonObject {
                extraConfig?.forEach { (key, value) -> put(key, value) }
                provider.config.modelConfigs[model]?.temperature?.let { put("temperature", it) }
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    if (service.languageCode.isNotBlank()) put("languageCode", service.languageCode.trim())
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", speechVoice(provider, service)) })
                    })
                })
            })
        } else {
            require(provider.type.isOpenAi) { "This provider does not support speech synthesis." }
            require(service.responseFormat in listOf("mp3", "opus", "aac", "flac", "wav", "pcm")) { "Unsupported audio format." }
            put("model", model)
            put("input", text)
            put("voice", speechVoice(provider, service))
            put("response_format", service.responseFormat)
            put("speed", service.speed.coerceIn(0.25f, 4f))
            if (service.instructions.isNotBlank()) {
                require(model !in listOf("tts-1", "tts-1-hd")) { "This model does not support voice instructions." }
                put("instructions", service.instructions.trim())
            }
        }
    }
}

internal fun pcmWave(pcm: ByteArray, sampleRate: Int = 24_000): ByteArray {
    require(sampleRate in 8_000..192_000) { "Unsupported PCM sample rate." }
    val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return buffer.array()
}

class SpeechSynthesisService {
    suspend fun synthesize(provider: LlmProviderInfo, service: SpeechService, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(supportsSpeechProvider(provider)) { "Provider ${provider.name} does not support TTS." }
            val payload = speechPayload(provider, service, text)
            val google = provider.type == ProviderType.GOOGLE
            val base = provider.baseUrl.trimEnd('/')
            var endpoint = if (google) {
                val model = URLEncoder.encode(service.modelId!!.removePrefix("models/"), "UTF-8").replace("+", "%20")
                "$base/models/$model:generateContent"
            } else "$base/audio/speech"
            val auth = if (service.apiKey.isNotBlank()) Authorization(value = service.apiKey) else provider.auth
            if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
                val key = URLEncoder.encode(auth.key?.ifBlank { "key" } ?: "key", "UTF-8")
                endpoint += "?" + key + "=" + URLEncoder.encode(auth.token, "UTF-8")
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (google) "application/json" else "audio/*")
                auth.requestHeaders(provider.type).forEach { (key, value) -> setRequestProperty(key, value) }
                provider.config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(500).orEmpty()
                    error("TTS request failed (HTTP $status)${if (detail.isBlank()) "" else ": $detail"}")
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                require(bytes.isNotEmpty()) { "TTS returned empty audio." }
                if (!google) return@withContext if (service.responseFormat == "pcm") pcmWave(bytes) else bytes
                val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val parts = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                val audio = parts.mapNotNull { it.jsonObject["inlineData"] as? JsonObject }
                    .firstOrNull { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true }
                    ?: error("Gemini returned no audio.")
                val decoded = Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content)
                val mime = audio["mimeType"]!!.jsonPrimitive.content
                if (mime.startsWith("audio/L16", true) || mime.startsWith("audio/pcm", true)) {
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: 24_000
                    pcmWave(decoded, rate)
                } else decoded
            } finally {
                connection.disconnect()
            }
        }
}
