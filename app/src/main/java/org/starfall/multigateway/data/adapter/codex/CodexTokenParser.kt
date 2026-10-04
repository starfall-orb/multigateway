package org.starfall.multigateway.data.adapter.codex

import kotlinx.serialization.json.*
import java.util.Base64

internal class CodexTokenParser(private val json: Json) {
    fun parse(raw: String, previousRefreshToken: String?): CodexTokenState {
        val payload = json.parseToJsonElement(raw).jsonObject
        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("OpenAI Codex token response did not include an access token.")
        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: previousRefreshToken ?: error("OpenAI Codex token response did not include a refresh token.")
        val tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Bearer"
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val idClaims = parseJwtClaims(payload["id_token"]?.jsonPrimitive?.contentOrNull)
        val accessClaims = parseJwtClaims(accessToken)
        val accountId = idClaims?.let(::extractAccountId) ?: accessClaims?.let(::extractAccountId)
        val email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: accessClaims?.get("email")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val jwtExpiresAt = (idClaims ?: accessClaims)?.get("exp")?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.times(1000L)
        return CodexTokenState(accessToken = accessToken, refreshToken = refreshToken, tokenType = tokenType,
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000L } ?: jwtExpiresAt,
            accountId = accountId, email = email)
    }

    private fun parseJwtClaims(token: String?): JsonObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(parts[1])
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private fun extractAccountId(claims: JsonObject): String? {
        claims["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val namespaced = claims["https://api.openai.com/auth"] as? JsonObject
        namespaced?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        return (claims["organizations"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
            ?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
