package org.starfall.multigateway.data.local.preferences

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.data.model.DiscoveredModel
import org.starfall.multigateway.data.model.ModelConfiguration
import java.util.Locale

internal fun rememberedModelName(modelId: String): String =
    modelId.trim().substringAfterLast('/').trim().lowercase(Locale.ROOT)

/** Portable model behavior, independent of provider prefixes and entity-specific icons. */
class ModelConfigurationMemory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("model-configuration-memory", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun get(modelId: String): ModelConfiguration? = preferences.getString(rememberedModelName(modelId), null)?.let {
        runCatching { json.decodeFromString<ModelConfiguration>(it) }.getOrNull()
    }

    fun remember(modelId: String, configuration: ModelConfiguration, onlyIfMissing: Boolean = false) {
        val key = rememberedModelName(modelId)
        if (key.isBlank() || (onlyIfMissing && preferences.contains(key))) return
        preferences.edit().putString(key, json.encodeToString(configuration.copy(displayName = "", icon = null))).apply()
    }

    fun configurationFor(model: DiscoveredModel): ModelConfiguration {
        val saved = get(model.id) ?: return model.configuration()
        return saved.copy(displayName = model.displayName,
            contextWindowTokens = model.contextWindowTokens ?: saved.contextWindowTokens)
    }
}
