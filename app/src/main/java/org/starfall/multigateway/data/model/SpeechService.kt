package org.starfall.multigateway.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechService(
    val id: String,
    val name: String,
    val provider: String = "system", // "system" or LlmProviderInfo.id
    val modelId: String? = null,
    val voice: String = "Default",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val apiKey: String = "",
    val sortOrder: Int = Int.MAX_VALUE,
    val instructions: String = "",
    val responseFormat: String = "mp3",
    val languageCode: String = "",
    val extraBody: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
)
