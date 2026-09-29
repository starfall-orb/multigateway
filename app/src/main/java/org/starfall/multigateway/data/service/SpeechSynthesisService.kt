package org.starfall.multigateway.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.SpeechService
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SpeechSynthesisService {
    suspend fun synthesize(
        provider: LlmProviderInfo,
        service: SpeechService,
        text: String
    ): ByteArray = withContext(Dispatchers.IO) {
        require(provider.type.isOpenAi) {
            "Provider ${provider.name} does not expose the OpenAI-compatible TTS endpoint."
        }
        val modelId = service.modelId?.takeIf { it.isNotBlank() }
            ?: error("No TTS model is selected.")

        var endpoint = provider.baseUrl.trimEnd('/') + "/audio/speech"
        val auth = provider.auth
        if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
            val key = URLEncoder.encode(auth.key?.ifBlank { "key" } ?: "key", "UTF-8")
            val value = URLEncoder.encode(auth.value ?: auth.token, "UTF-8")
            endpoint += if ('?' in endpoint) "&$key=$value" else "?$key=$value"
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            when (auth.method) {
                AuthMethod.BEARER_TOKEN, AuthMethod.OTHER -> {
                    auth.token.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
                AuthMethod.CUSTOM_HEADER -> {
                    auth.key?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty(it, auth.value ?: auth.token)
                    }
                }
                AuthMethod.QUERY_PARAM, AuthMethod.OAUTH -> Unit
            }
            provider.config.headers.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        try {
            val payload = buildJsonObject {
                put("model", modelId)
                put("input", text)
                put("voice", service.voice.ifBlank { "alloy" })
                put("response_format", "mp3")
                put("speed", service.speed.coerceIn(0.25f, 4.0f))
            }.toString()

            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(500)
                    .orEmpty()
                error("TTS request failed (HTTP $status)${if (detail.isBlank()) "" else ": $detail"}")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
