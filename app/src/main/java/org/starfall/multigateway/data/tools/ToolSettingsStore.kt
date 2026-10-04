package org.starfall.multigateway.data.tools

import android.content.Context
import org.starfall.multigateway.data.repository.ImmediateState
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.data.model.*

private val Context.toolDataStore by preferencesDataStore(name = "tool_settings")
class ToolSettingsStore(context: Context) {
    private val store = context.applicationContext.toolDataStore
    private val key = stringPreferencesKey("config")
    private val json = Json { ignoreUnknownKeys = true }
    private val stored = store.data.map { p -> runCatching { json.decodeFromString<ToolSettings>(p[key] ?: "{}") }.getOrDefault(ToolSettings()) }
    private val state = ImmediateState(stored)
    val settings = state.flow
    suspend fun update(transform: (ToolSettings) -> ToolSettings) {
        state.mutate(transform) {
            store.edit { p ->
                val old = runCatching { json.decodeFromString<ToolSettings>(p[key] ?: "{}") }.getOrDefault(ToolSettings())
                p[key] = json.encodeToString(transform(old))
            }
        }
    }
}
