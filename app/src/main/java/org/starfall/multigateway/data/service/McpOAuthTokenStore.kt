package org.starfall.multigateway.data.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.data.local.db.SecretCipher

@Serializable
internal data class McpOAuthTokenState(
    val accessToken: String,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null,
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: String = "none",
    val authorizationServer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String,
    val scope: String? = null
)

internal class McpOAuthTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("multigateway.mcp.oauth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(serverId: String): McpOAuthTokenState? {
        val encrypted = preferences.getString(serverId, null) ?: return null
        return runCatching { json.decodeFromString<McpOAuthTokenState>(SecretCipher.decrypt(encrypted)) }.getOrNull()
    }

    fun save(serverId: String, state: McpOAuthTokenState) {
        check(preferences.edit().putString(serverId, SecretCipher.encrypt(json.encodeToString(state))).commit()) {
            "Could not save MCP OAuth credentials."
        }
    }

    fun delete(serverId: String) { preferences.edit().remove(serverId).apply() }
}
