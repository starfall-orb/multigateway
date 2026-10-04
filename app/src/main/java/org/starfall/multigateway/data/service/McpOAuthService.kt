package org.starfall.multigateway.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.starfall.multigateway.data.adapter.common.OAuthCallbackService
import org.starfall.multigateway.data.adapter.common.awaitOAuthAuthorizationCode
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.ToolHttp
import org.starfall.multigateway.data.tools.text

class McpOAuthService(
    context: Context,
    private val http: ToolHttp = ToolHttp(),
    private val openBrowser: suspend (String) -> Unit = { url ->
        withContext(Dispatchers.Main) {
            context.applicationContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
) {
    private val appContext = context.applicationContext
    private val store = McpOAuthTokenStore(appContext)

    suspend fun authorize(info: McpInfo): McpInfo {
        require(info.auth.method == McpAuthMethod.OAUTH2) { "MCP server is not configured for OAuth." }
        val endpoint = info.resolvedUrl() ?: error("MCP URL is missing")
        val resource = canonicalResource(endpoint)
        val discovery = discover(endpoint, resource)
        require(discovery.metadata.codeChallengeMethods.any { it.equals("S256", true) }) {
            "Authorization server does not advertise PKCE S256 support."
        }

        val previous = store.load(info.id)
        val configuredClientId = info.auth.oauthClientId?.trim().orEmpty()
        val configuredClientSecret = info.auth.oauthClientSecret?.takeIf { it.isNotBlank() }
        val registration = when {
            configuredClientId.isNotEmpty() -> ClientRegistration(
                clientId = configuredClientId,
                clientSecret = configuredClientSecret,
                tokenEndpointAuthMethod = when {
                    configuredClientSecret == null -> "none"
                    "client_secret_basic" in discovery.metadata.tokenEndpointAuthMethods -> "client_secret_basic"
                    "client_secret_post" in discovery.metadata.tokenEndpointAuthMethods -> "client_secret_post"
                    discovery.metadata.tokenEndpointAuthMethods.isEmpty() -> "client_secret_basic"
                    else -> error("Authorization server does not support a compatible client-secret authentication method.")
                }
            )
            previous != null &&
                sameUri(previous.authorizationServer, discovery.authorizationServer) &&
                sameUri(previous.resource, resource) -> ClientRegistration(
                    previous.clientId,
                    previous.clientSecret,
                    previous.tokenEndpointAuthMethod
                )
            discovery.metadata.registrationEndpoint != null ->
                registerClient(discovery.metadata.registrationEndpoint)
            else -> error(
                "This authorization server does not support dynamic client registration. " +
                    "Register MultiGateway as a public OAuth client and enter its Client ID."
            )
        }

        if (discovery.metadata.tokenEndpointAuthMethods.isNotEmpty()) {
            require(registration.tokenEndpointAuthMethod in discovery.metadata.tokenEndpointAuthMethods) {
                "Authorization server does not support token endpoint authentication method ${registration.tokenEndpointAuthMethod}."
            }
        }

        val verifier = randomValue(32)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        )
        val state = randomValue(32)
        val authorizeUrl = discovery.metadata.authorizationEndpoint.toHttpUrl().newBuilder().apply {
            addQueryParameter("response_type", "code")
            addQueryParameter("client_id", registration.clientId)
            addQueryParameter("redirect_uri", REDIRECT_URI)
            addQueryParameter("state", state)
            addQueryParameter("code_challenge", challenge)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("resource", resource)
            discovery.scope?.takeIf { it.isNotBlank() }?.let { addQueryParameter("scope", it) }
        }.build().toString()

        val code = OAuthCallbackService.keepAlive(appContext) {
            awaitOAuthAuthorizationCode(REDIRECT_URI, authorizeUrl, state, openBrowser)
        }

        val token = exchangeAuthorizationCode(
            discovery = discovery,
            registration = registration,
            resource = resource,
            scope = discovery.scope,
            code = code,
            verifier = verifier
        )
        store.save(info.id, token)
        return info.copy(
            auth = info.auth.copy(
                value = null,
                oauthClientId = configuredClientId.ifEmpty { null },
                oauthClientSecret = configuredClientSecret,
                oauthAuthorized = true
            )
        )
    }

    suspend fun accessToken(info: McpInfo): String {
        if (info.auth.method != McpAuthMethod.OAUTH2) return info.auth.token
        val current = store.load(info.id)
        if (current == null) {
            return info.auth.value?.takeIf { it.isNotBlank() }
                ?: error("MCP OAuth credentials are missing. Authorize this server again.")
        }
        val resource = canonicalResource(info.resolvedUrl() ?: error("MCP URL is missing"))
        require(sameUri(current.resource, resource)) {
            "MCP server URL changed after OAuth authorization. Authorize this server again."
        }
        info.auth.oauthClientId?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
            require(configured == current.clientId) {
                "MCP OAuth Client ID changed. Authorize this server again."
            }
        }
        info.auth.oauthClientSecret?.takeIf { it.isNotBlank() }?.let { configured ->
            require(configured == current.clientSecret) {
                "MCP OAuth client secret changed. Authorize this server again."
            }
        }
        if (current.expiresAt == null || current.expiresAt > System.currentTimeMillis() + 60_000) {
            return current.accessToken
        }
        require(current.refreshToken.isNotBlank()) {
            "MCP OAuth authorization expired. Authorize this server again."
        }
        val refreshed = refresh(current)
        store.save(info.id, refreshed)
        return refreshed.accessToken
    }

    fun clear(serverId: String) = store.delete(serverId)

    private suspend fun discover(endpointValue: String, resource: String): OAuthDiscovery {
        val endpoint = endpointValue.toHttpUrl()
        var challengedMetadataUrl: String? = null
        var challengedScope: String? = null

        val probeBody = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "oauth-discovery")
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "MultiGateway")
                    put("version", "1.0")
                })
            })
        }
        runCatching {
            http.withResponse(
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json, text/event-stream")
                    .post(probeBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ) { response ->
                if (response.code == 401 || response.code == 403) {
                    response.headers("WWW-Authenticate").forEach { value ->
                        if (!value.trimStart().startsWith("Bearer", true)) return@forEach
                        challengedMetadataUrl = bearerParameter(value, "resource_metadata") ?: challengedMetadataUrl
                        challengedScope = bearerParameter(value, "scope") ?: challengedScope
                    }
                }
            }
        }

        val metadataCandidates = buildList {
            challengedMetadataUrl?.let(::add)
            add(protectedResourceMetadataUrl(endpoint, includePath = true))
            add(protectedResourceMetadataUrl(endpoint, includePath = false))
        }.distinct()

        var protected: JsonObject? = null
        for (candidate in metadataCandidates) {
            protected = fetchJsonOrNull(candidate)
            if (protected != null) break
        }

        protected?.text("resource")?.takeIf { it.isNotBlank() }?.let { advertised ->
            require(sameUri(advertised, resource)) {
                "Protected Resource Metadata describes a different MCP resource."
            }
        }

        val authorizationServers = protected?.arrayStrings("authorization_servers").orEmpty()
        val servers = if (authorizationServers.isNotEmpty()) {
            authorizationServers
        } else {
            // Compatibility with the 2025-03-26 discovery model.
            listOf(endpoint.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
        }

        var lastError: Throwable? = null
        for (authorizationServer in servers) {
            try {
                val metadata = discoverAuthorizationServer(authorizationServer)
                val scope = challengedScope
                    ?: protected?.arrayStrings("scopes_supported")?.joinToString(" ")?.takeIf { it.isNotBlank() }
                return OAuthDiscovery(authorizationServer, metadata, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Unable to discover the MCP authorization server.")
    }

    private suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata {
        val issuerUrl = issuer.toHttpUrl()
        requireSecureOAuthUrl(issuerUrl, "authorization server")
        var lastError: Throwable? = null
        for (candidate in authorizationMetadataUrls(issuerUrl)) {
            val json = try {
                fetchJsonOrNull(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                null
            } ?: continue
            val metadataIssuer = json.text("issuer")
            if (metadataIssuer.isNotBlank()) {
                require(sameUri(metadataIssuer, issuer)) {
                    "Authorization server metadata issuer does not match the discovered issuer."
                }
            }
            val authorizationEndpoint = json.text("authorization_endpoint")
                .takeIf { it.isNotBlank() } ?: continue
            val tokenEndpoint = json.text("token_endpoint")
                .takeIf { it.isNotBlank() } ?: continue
            requireSecureOAuthUrl(authorizationEndpoint.toHttpUrl(), "authorization endpoint")
            requireSecureOAuthUrl(tokenEndpoint.toHttpUrl(), "token endpoint")
            return AuthorizationServerMetadata(
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = json.text("registration_endpoint").takeIf { it.isNotBlank() },
                codeChallengeMethods = json.arrayStrings("code_challenge_methods_supported"),
                tokenEndpointAuthMethods = json.arrayStrings("token_endpoint_auth_methods_supported")
            )
        }
        throw lastError ?: IllegalStateException("Unable to discover OAuth authorization server metadata.")
    }

    private suspend fun registerClient(endpoint: String): ClientRegistration {
        val url = endpoint.toHttpUrl()
        requireSecureOAuthUrl(url, "registration endpoint")
        val response = http.post(
            endpoint,
            buildJsonObject {
                put("client_name", "MultiGateway")
                put("application_type", "native")
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(REDIRECT_URI))))
                put("grant_types", JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("refresh_token")
                )))
                put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                put("token_endpoint_auth_method", "none")
            }
        )
        val clientId = response.text("client_id")
        require(clientId.isNotBlank()) { "Dynamic client registration returned no client_id." }
        return ClientRegistration(
            clientId = clientId,
            clientSecret = response.text("client_secret").takeIf { it.isNotBlank() },
            tokenEndpointAuthMethod = response.text("token_endpoint_auth_method").ifBlank { "none" }
        )
    }

    private suspend fun exchangeAuthorizationCode(
        discovery: OAuthDiscovery,
        registration: ClientRegistration,
        resource: String,
        scope: String?,
        code: String,
        verifier: String
    ): McpOAuthTokenState {
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
            "resource" to resource
        )
        val response = tokenRequest(
            discovery.metadata.tokenEndpoint,
            fields,
            registration
        )
        return tokenState(
            response = response,
            previous = null,
            registration = registration,
            discovery = discovery,
            resource = resource,
            scope = scope
        )
    }

    private suspend fun refresh(previous: McpOAuthTokenState): McpOAuthTokenState {
        val registration = ClientRegistration(
            previous.clientId,
            previous.clientSecret,
            previous.tokenEndpointAuthMethod
        )
        val response = tokenRequest(
            previous.tokenEndpoint,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
                "resource" to previous.resource
            ),
            registration
        )
        return tokenState(
            response = response,
            previous = previous,
            registration = registration,
            discovery = OAuthDiscovery(
                previous.authorizationServer,
                AuthorizationServerMetadata(
                    previous.authorizationEndpoint,
                    previous.tokenEndpoint,
                    null,
                    listOf("S256"),
                    emptyList()
                ),
                previous.scope
            ),
            resource = previous.resource,
            scope = response.text("scope").takeIf { it.isNotBlank() } ?: previous.scope
        )
    }

    private suspend fun tokenRequest(
        endpoint: String,
        fields: Map<String, String>,
        registration: ClientRegistration
    ): JsonObject {
        val form = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
            when (registration.tokenEndpointAuthMethod) {
                "", "none" -> add("client_id", registration.clientId)
                "client_secret_post" -> {
                    add("client_id", registration.clientId)
                    add("client_secret", registration.clientSecret
                        ?: error("Authorization server requires client_secret_post but no client secret is available."))
                }
            }
        }.build()
        val builder = http.request(endpoint).post(form)
        if (registration.tokenEndpointAuthMethod == "client_secret_basic") {
            val secret = registration.clientSecret
                ?: error("Authorization server requires client_secret_basic but no client secret is available.")
            val credentials = Base64.getEncoder().encodeToString(
                "${registration.clientId}:$secret".toByteArray(Charsets.UTF_8)
            )
            builder.header("Authorization", "Basic $credentials")
        } else {
            require(
                registration.tokenEndpointAuthMethod in listOf("", "none", "client_secret_post")
            ) { "Unsupported token endpoint authentication method: ${registration.tokenEndpointAuthMethod}" }
        }
        return http.json(builder.build())
    }

    private fun tokenState(
        response: JsonObject,
        previous: McpOAuthTokenState?,
        registration: ClientRegistration,
        discovery: OAuthDiscovery,
        resource: String,
        scope: String?
    ): McpOAuthTokenState {
        val accessToken = response.text("access_token")
        require(accessToken.isNotBlank()) { "OAuth token response has no access_token." }
        val tokenType = response.text("token_type").ifBlank { "Bearer" }
        require(tokenType.equals("Bearer", true)) { "Unsupported OAuth token type: $tokenType" }
        val expiresIn = response["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return McpOAuthTokenState(
            accessToken = accessToken,
            refreshToken = response.text("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
            tokenType = tokenType,
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it.coerceAtLeast(0) * 1000 },
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod,
            authorizationServer = discovery.authorizationServer,
            authorizationEndpoint = discovery.metadata.authorizationEndpoint,
            tokenEndpoint = discovery.metadata.tokenEndpoint,
            resource = resource,
            scope = response.text("scope").takeIf { it.isNotBlank() } ?: scope
        )
    }

    private suspend fun fetchJsonOrNull(url: String): JsonObject? {
        val target = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        requireSecureOAuthUrl(target, "metadata endpoint")
        return http.withResponse(http.request(url).get().build()) { response ->
            if (!response.isSuccessful) return@withResponse null
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotEmpty() && !contentType.contains("json")) return@withResponse null
            runCatching {
                Json.parseToJsonElement(http.readJson(response)) as? JsonObject
            }.getOrNull()
        }
    }

    private fun protectedResourceMetadataUrl(endpoint: HttpUrl, includePath: Boolean): String {
        val suffix = if (includePath && endpoint.encodedPath != "/") endpoint.encodedPath else ""
        return endpoint.newBuilder()
            .encodedPath("/.well-known/oauth-protected-resource$suffix")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun authorizationMetadataUrls(issuer: HttpUrl): List<String> {
        val path = issuer.encodedPath.takeUnless { it == "/" }.orEmpty()
        val root = issuer.newBuilder().encodedPath("/").query(null).fragment(null)
        return buildList {
            add(root.build().newBuilder()
                .encodedPath("/.well-known/oauth-authorization-server$path").build().toString())
            add(root.build().newBuilder()
                .encodedPath("/.well-known/openid-configuration$path").build().toString())
            if (path.isNotEmpty()) {
                add(issuer.newBuilder()
                    .encodedPath(path.trimEnd('/') + "/.well-known/openid-configuration")
                    .query(null).fragment(null).build().toString())
            }
        }.distinct()
    }

    private fun canonicalResource(value: String): String {
        val url = value.toHttpUrl()
        require(url.fragment == null) { "MCP resource URL must not contain a fragment." }
        val path = if (url.encodedPath == "/") "" else url.encodedPath
        return url.newBuilder()
            .encodedPath(if (path.isEmpty()) "/" else path)
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .let { if (path.isEmpty()) it.removeSuffix("/") else it }
    }

    private fun requireSecureOAuthUrl(url: HttpUrl, label: String) {
        val loopback = url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1"
        require(url.isHttps || (url.scheme == "http" && loopback)) {
            "OAuth $label must use HTTPS (HTTP is allowed only for loopback addresses)."
        }
    }

    private fun bearerParameter(header: String, name: String): String? {
        val escaped = Regex.escape(name)
        val quoted = Regex("(?:^|[,\\s])$escaped\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted
        return Regex("(?:^|[,\\s])$escaped\\s*=\\s*([^,\\s]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)
    }

    private fun JsonObject.arrayStrings(name: String): List<String> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

    private fun sameUri(a: String, b: String): Boolean =
        runCatching { a.toHttpUrl() == b.toHttpUrl() }.getOrDefault(a == b)

    private fun randomValue(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private data class OAuthDiscovery(
        val authorizationServer: String,
        val metadata: AuthorizationServerMetadata,
        val scope: String?
    )

    private data class AuthorizationServerMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val codeChallengeMethods: List<String>,
        val tokenEndpointAuthMethods: List<String>
    )

    private data class ClientRegistration(
        val clientId: String,
        val clientSecret: String? = null,
        val tokenEndpointAuthMethod: String = "none"
    )

    companion object {
        const val REDIRECT_URI = "http://127.0.0.1:53682/oauth-callback"
    }
}
