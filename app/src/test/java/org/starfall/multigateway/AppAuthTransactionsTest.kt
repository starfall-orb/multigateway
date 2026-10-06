package org.starfall.multigateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.adapter.common.AppAuthTransactions
import org.starfall.multigateway.data.tools.ToolHttp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppAuthTransactionsTest {
    @Test fun authorizationCarriesResourceAndGeneratesIndependentPkceSessions() {
        fun request() = AppAuthTransactions.authorization("https://example.com/authorize", "https://example.com/token",
            "client", "http://127.0.0.1:54321/callback", "mcp:read", mapOf("resource" to "https://example.com/mcp", "prompt" to "consent"))
        val first = request()
        val second = request()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
        assertEquals("S256", first.codeVerifierChallengeMethod)
        assertEquals("consent", first.toUri().getQueryParameter("prompt"))
        assertEquals("https://example.com/mcp", first.toUri().getQueryParameter("resource"))
        assertEquals(first.codeVerifierChallenge, first.toUri().getQueryParameter("code_challenge"))
    }

    @Test fun refreshKeepsResourceAndClientSecretPostFields() {
        val request = AppAuthTransactions.token("https://example.com/token", "client", mapOf(
            "grant_type" to "refresh_token", "refresh_token" to "refresh", "resource" to "https://example.com/mcp"))
        val values = AppAuthTransactions.fields(request, AppAuthTransactions.authentication("client_secret_post", "secret"))
        assertEquals("refresh", values["refresh_token"])
        assertEquals("https://example.com/mcp", values["resource"])
        assertEquals("client", values["client_id"])
        assertEquals("secret", values["client_secret"])
        assertFalse(values.containsKey("code_verifier"))
    }

    @Test fun jsonTokenEndpointKeepsCompatibilityFieldsAndBearerFallback() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"account_id":"account"}"""))
            val request = AppAuthTransactions.token(server.url("/token").toString(), "client", mapOf(
                "grant_type" to "authorization_code", "code" to "code", "redirect_uri" to "http://127.0.0.1/callback",
                "code_verifier" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "resource" to "resource"))
            val response = AppAuthTransactions.exchange(ToolHttp(), request, jsonBody = true)
            assertEquals("Bearer", response["token_type"]?.jsonPrimitive?.content)
            assertEquals("account", response["account_id"]?.jsonPrimitive?.content)
            val sent = server.takeRequest()
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
            assertTrue(sent.body.readUtf8().contains("\"resource\":\"resource\""))
        } finally { server.shutdown() }
    }
}
