package org.starfall.multigateway.data.adapter.common

import android.net.Uri
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import net.openid.appauth.*
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.starfall.multigateway.data.tools.ToolHttp

/** AppAuth executes standard form token exchanges; explicit exceptions use CompatibilityOAuth. */
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

    suspend fun exchange(context: Context, http: ToolHttp, request: TokenRequest,
        auth: ClientAuthentication = NoClientAuthentication.INSTANCE, jsonBody: Boolean = false): JsonObject =
        if (jsonBody || request.configuration.tokenEndpoint.scheme != "https") {
            CompatibilityOAuth.exchangeToken(http, request, auth, jsonBody)
        } else executeToken(context, request, auth)

    /** AppAuth owns form encoding, response/error parsing and token endpoint execution. */
    internal suspend fun executeToken(context: Context, request: TokenRequest,
        auth: ClientAuthentication = NoClientAuthentication.INSTANCE,
        configuration: AppAuthConfiguration = AppAuthConfiguration.DEFAULT): JsonObject = withContext(Dispatchers.Main.immediate) {
        val service = AuthorizationService(context.applicationContext, configuration)
        try {
            suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(request, auth) { response, exception ->
                    if (!continuation.isActive) return@performTokenRequest
                    if (response == null) continuation.resumeWithException(exception ?: IllegalStateException("OAuth token response is missing"))
                    else continuation.resume(tokenPayload(response))
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { service.dispose() }
        }
    }

    internal fun tokenPayload(response: TokenResponse): JsonObject = buildJsonObject {
        response.additionalParameters.forEach { (key, value) -> put(key, value) }
        response.accessToken?.let { put("access_token", it) }
        response.refreshToken?.let { put("refresh_token", it) }
        response.tokenType?.let { put("token_type", it) }
        response.idToken?.let { put("id_token", it) }
        response.scope?.let { put("scope", it) }
        response.accessTokenExpirationTime?.let { put("expires_in", ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
    }
}

/** Only protocol exceptions live here; this transport must not become the default OAuth path. */
internal object CompatibilityOAuth {
    /** JSON bodies, cleartext MCP endpoints and Bearer fallback are explicit protocol exceptions. */
    suspend fun exchangeToken(http: ToolHttp, request: TokenRequest, auth: ClientAuthentication = NoClientAuthentication.INSTANCE,
        jsonBody: Boolean = false): JsonObject {
        val values = AppAuthTransactions.fields(request, auth)
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
