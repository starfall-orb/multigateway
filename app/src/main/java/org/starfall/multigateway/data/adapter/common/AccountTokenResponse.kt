package org.starfall.multigateway.data.adapter.common

import kotlinx.serialization.json.*
import org.starfall.multigateway.data.tools.text

/** Refresh responses may rotate credentials while omitting account metadata. */
internal fun accountTokenFromResponse(
    payload: JsonObject,
    previous: AccountTokenState? = null,
    now: Long = System.currentTimeMillis()
): AccountTokenState {
    val access = payload.text("access_token")
    require(access.isNotBlank()) { "OAuth response has no access token" }
    val account = payload["account"] as? JsonObject
    return AccountTokenState(
        accessToken = access,
        refreshToken = payload.text("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
        tokenType = payload.text("token_type").ifBlank { previous?.tokenType ?: "Bearer" },
        expiresAt = (payload["expires_in"] as? JsonPrimitive)?.longOrNull
            ?.let { now + it * 1000 },
        accountId = account?.text("uuid")?.takeIf(String::isNotBlank)
            ?: payload.text("account_id").takeIf(String::isNotBlank) ?: previous?.accountId,
        email = account?.text("email_address")?.takeIf(String::isNotBlank)
            ?: payload.text("email").takeIf(String::isNotBlank) ?: previous?.email,
        projectId = previous?.projectId
    )
}
