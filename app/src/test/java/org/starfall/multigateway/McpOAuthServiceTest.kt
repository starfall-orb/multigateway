package org.starfall.multigateway

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.McpAuthMethod
import org.starfall.multigateway.data.model.McpAuthorization
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.service.McpOAuthService

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class McpOAuthServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepareKeystore() = installTestAndroidKeyStore()

    @Test
    fun oauthUsesDiscoveryPkceResourceDcrAndRefresh() = runBlocking {
        val server = MockWebServer()
        server.start()
        val resource = server.url("/mcp").toString()
        val issuer = server.url("/auth").toString().trimEnd('/')
        val authorizeUrl = server.url("/authorize").toString()
        val tokenUrl = server.url("/token").toString()
        val registrationUrl = server.url("/register").toString()
        val protectedMetadata = server.url("/prm").toString()
        val firstTokenRequest = AtomicReference<Map<String, String>>()
        val refreshTokenRequest = AtomicReference<Map<String, String>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/mcp" -> MockResponse()
                    .setResponseCode(401)
                    .addHeader(
                        "WWW-Authenticate",
                        "Bearer resource_metadata=\"$protectedMetadata\", scope=\"mcp:read\""
                    )
                "/prm" -> jsonResponse(
                    """{"resource":"$resource","authorization_servers":["$issuer"],"scopes_supported":["fallback"]}"""
                )
                "/.well-known/oauth-authorization-server/auth" -> jsonResponse(
                    """{"issuer":"$issuer","authorization_endpoint":"$authorizeUrl","token_endpoint":"$tokenUrl","registration_endpoint":"$registrationUrl","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"]}"""
                )
                "/register" -> jsonResponse(
                    """{"client_id":"dynamic-client","token_endpoint_auth_method":"none"}"""
                )
                "/token" -> {
                    val form = form(request.body.readUtf8())
                    if (form["grant_type"] == "refresh_token") {
                        refreshTokenRequest.set(form)
                        jsonResponse(
                            """{"access_token":"access-2","refresh_token":"refresh-2","token_type":"Bearer","expires_in":3600,"scope":"mcp:read"}"""
                        )
                    } else {
                        firstTokenRequest.set(form)
                        jsonResponse(
                            """{"access_token":"access-1","refresh_token":"refresh-1","token_type":"Bearer","expires_in":0,"scope":"mcp:read"}"""
                        )
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        try {
            val opened = AtomicReference<Uri>()
            val oauth = McpOAuthService(context, openBrowser = { url ->
                val uri = Uri.parse(url)
                opened.set(uri)
                Thread {
                    val callback = McpOAuthService.REDIRECT_URI +
                        "?code=authorization-code&state=" +
                        Uri.encode(uri.getQueryParameter("state"))
                    (URL(callback).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2_000
                        readTimeout = 2_000
                        inputStream.use { it.readBytes() }
                        disconnect()
                    }
                }.start()
            })
            val info = McpInfo(
                id = "oauth-test",
                name = "OAuth MCP",
                url = resource,
                auth = McpAuthorization(method = McpAuthMethod.OAUTH2)
            )

            val authorized = oauth.authorize(info)
            assertTrue(authorized.auth.oauthAuthorized)
            assertNull(authorized.auth.value)
            assertNull(authorized.auth.oauthClientId)

            val authorization = opened.get()
            assertEquals("code", authorization.getQueryParameter("response_type"))
            assertEquals("dynamic-client", authorization.getQueryParameter("client_id"))
            assertEquals(McpOAuthService.REDIRECT_URI, authorization.getQueryParameter("redirect_uri"))
            assertEquals("S256", authorization.getQueryParameter("code_challenge_method"))
            assertFalse(authorization.getQueryParameter("code_challenge").isNullOrBlank())
            assertEquals(resource, authorization.getQueryParameter("resource"))
            assertEquals("mcp:read", authorization.getQueryParameter("scope"))

            val exchange = firstTokenRequest.get()
            assertEquals("authorization_code", exchange["grant_type"])
            assertEquals("authorization-code", exchange["code"])
            assertEquals("dynamic-client", exchange["client_id"])
            assertEquals(McpOAuthService.REDIRECT_URI, exchange["redirect_uri"])
            assertEquals(resource, exchange["resource"])
            assertFalse(exchange["code_verifier"].isNullOrBlank())

            // expires_in=0 forces the normal runtime path to use the refresh token.
            assertEquals("access-2", oauth.accessToken(authorized))
            val refresh = refreshTokenRequest.get()
            assertEquals("refresh_token", refresh["grant_type"])
            assertEquals("refresh-1", refresh["refresh_token"])
            assertEquals("dynamic-client", refresh["client_id"])
            assertEquals(resource, refresh["resource"])

            val wrongResource = authorized.copy(url = server.url("/other-mcp").toString())
            val mismatch = runCatching { oauth.accessToken(wrongResource) }.exceptionOrNull()
            assertTrue(mismatch?.message?.contains("URL changed") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oauthUsesOriginResourceAdvertisedForNestedMcpEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        val endpoint = server.url("/mcp/oauth").toString()
        val resource = server.url("/").toString().trimEnd('/')
        val issuer = server.url("/auth").toString().trimEnd('/')
        val authorizeUrl = server.url("/authorize").toString()
        val tokenUrl = server.url("/token").toString()
        val protectedMetadata = server.url("/prm").toString()
        val tokenRequest = AtomicReference<Map<String, String>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/mcp/oauth" -> MockResponse()
                    .setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"$protectedMetadata\"")
                "/prm" -> jsonResponse(
                    """{"resource":"$resource","authorization_servers":["$issuer"],"scopes_supported":["profile","email"]}"""
                )
                "/.well-known/oauth-authorization-server/auth" -> jsonResponse(
                    """{"issuer":"$issuer","authorization_endpoint":"$authorizeUrl","token_endpoint":"$tokenUrl","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"]}"""
                )
                "/token" -> {
                    tokenRequest.set(form(request.body.readUtf8()))
                    jsonResponse("""{"access_token":"context7-token","token_type":"Bearer","expires_in":3600}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        try {
            val opened = AtomicReference<Uri>()
            val oauth = McpOAuthService(context, openBrowser = { url ->
                val uri = Uri.parse(url)
                opened.set(uri)
                Thread {
                    val callback = McpOAuthService.REDIRECT_URI +
                        "?code=context7-code&state=" + Uri.encode(uri.getQueryParameter("state"))
                    (URL(callback).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2_000
                        readTimeout = 2_000
                        inputStream.use { it.readBytes() }
                        disconnect()
                    }
                }.start()
            })
            val info = McpInfo(
                id = "context7-style",
                name = "Context7 style",
                url = endpoint,
                auth = McpAuthorization(method = McpAuthMethod.OAUTH2, oauthClientId = "configured-client")
            )

            val authorized = oauth.authorize(info)
            assertTrue(authorized.auth.oauthAuthorized)
            assertEquals(resource, opened.get().getQueryParameter("resource"))
            assertEquals(resource, tokenRequest.get()["resource"])
            assertEquals("context7-token", oauth.accessToken(authorized))

            val changedEndpoint = authorized.copy(url = server.url("/different-mcp").toString())
            val mismatch = runCatching { oauth.accessToken(changedEndpoint) }.exceptionOrNull()
            assertTrue(mismatch?.message?.contains("URL changed") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oauthRejectsProtectedResourceOutsideMcpEndpointScope() = runBlocking {
        val server = MockWebServer()
        server.start()
        val endpoint = server.url("/mcp/oauth").toString()
        val unrelatedResource = server.url("/other").toString().trimEnd('/')
        val protectedMetadata = server.url("/prm").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/mcp/oauth" -> MockResponse()
                    .setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"$protectedMetadata\"")
                "/prm" -> jsonResponse(
                    """{"resource":"$unrelatedResource","authorization_servers":["${server.url("/auth").toString().trimEnd('/')}"]}"""
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        try {
            val oauth = McpOAuthService(context, openBrowser = { error("Browser must not open") })
            val info = McpInfo(
                id = "resource-mismatch",
                name = "Mismatch",
                url = endpoint,
                auth = McpAuthorization(method = McpAuthMethod.OAUTH2, oauthClientId = "client")
            )
            val error = runCatching { oauth.authorize(info) }.exceptionOrNull()
            assertTrue(error?.message?.contains("different MCP resource") == true)
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun form(body: String): Map<String, String> = body
        .split('&')
        .filter { it.isNotBlank() }
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], Charsets.UTF_8.name()) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
}
