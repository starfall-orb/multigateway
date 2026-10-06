package org.starfall.multigateway.data.adapter.common

import android.net.Uri
import kotlinx.serialization.json.*
import net.openid.appauth.*
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.starfall.multigateway.data.tools.ToolHttp

/** Standard OAuth requests use AppAuth; provider/MCP discovery and token storage stay in the app. */
internal object AppAuthTransactions {
    fun authorization(authorizationEndpoint: String, tokenEndpoint: String, clientId: String,
        redirectUri: String, scope: String?, additional: Map<String, String> = emptyMap()): AuthorizationRequest =
        AuthorizationRequest.Builder(AuthorizationServiceConfiguration(Uri.parse(authorizationEndpoint), Uri.parse(tokenEndpoint)),
            clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
            .setScope(scope?.takeIf { it.isNotBlank() })
            .setPrompt(additional["prompt"])
            .setAdditionalParameters(additional - "prompt")
            .build()

    fun token(endpoint: String, clientId: String, fields: Map<String, String>): TokenRequest =
        TokenRequest.Builder(AuthorizationServiceConfiguration(Uri.parse(endpoint), Uri.parse(endpoint)), clientId)
            .setGrantType(fields.getValue("grant_type"))
            .setAuthorizationCode(fields["code"])
            .setRedirectUri(fields["redirect_uri"]?.let(Uri::parse))
            .setCodeVerifier(fields["code_verifier"])
            .setRefreshToken(fields["refresh_token"])
            .setScope(fields["scope"])
            .setAdditionalParameters(fields - setOf("grant_type", "code", "redirect_uri", "code_verifier", "refresh_token", "scope"))
            .build()

    fun authentication(method: String, secret: String?): ClientAuthentication = when (method) {
        "", "none" -> NoClientAuthentication.INSTANCE
        "client_secret_basic" -> ClientSecretBasic(requireNotNull(secret) { "OAuth client secret is missing." })
        "client_secret_post" -> ClientSecretPost(requireNotNull(secret) { "OAuth client secret is missing." })
        else -> error("Unsupported token endpoint authentication method: $method")
    }

    fun fields(request: TokenRequest, auth: ClientAuthentication = NoClientAuthentication.INSTANCE): Map<String, String> =
        request.requestParameters + auth.getRequestParameters(request.clientId).orEmpty()

    /** Reuse the cancellable HTTP transport, including providers with JSON token endpoints. */
    suspend fun exchange(http: ToolHttp, request: TokenRequest, auth: ClientAuthentication = NoClientAuthentication.INSTANCE,
        jsonBody: Boolean = false): JsonObject {
        val values = fields(request, auth)
        val headers = auth.getRequestHeaders(request.clientId).orEmpty()
        val response = if (jsonBody) http.json(http.request(request.configuration.tokenEndpoint.toString()).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.post(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
            .toRequestBody("application/json".toMediaType())).build())
        else http.json(http.request(request.configuration.tokenEndpoint.toString()).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.post(FormBody.Builder().apply { values.forEach { (name, value) -> add(name, value) } }.build()).build())
        // Some compatible servers omit token_type; retain the app's Bearer fallback.
        val normalized = JsonObject(response + (if (response["token_type"] == null)
            mapOf("token_type" to JsonPrimitive("Bearer")) else emptyMap()))
        TokenResponse.Builder(request).fromResponseJson(JSONObject(normalized.toString())).build()
        return normalized
    }
}
