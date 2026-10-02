package org.starfall.multigateway

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.text
import java.net.ServerSocket
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class OAuthLoopbackDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun adapter(port: Int, tokenUrl: String, browser: CompletableDeferred<String>) =
        object : OAuthAccountAdapter(context, AttachmentResolver(context), ProviderType.ANTIGRAVITY,
            "test-client", "https://example.com/authorize", tokenUrl, "scope",
            port, "/oauth-callback", callbackHost = "127.0.0.1") {
            override suspend fun openBrowser(url: String) { browser.complete(url) }
            override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo) = provider
            override suspend fun fetchModels(provider: LlmProviderInfo) = listOf("chat")
        }

    private fun provider() = LlmProviderInfo("loopback-device-test", "Test", ProviderType.ANTIGRAVITY,
        baseUrl = ProviderType.ANTIGRAVITY.defaultBaseUrl, auth = ProviderType.ANTIGRAVITY.defaultAuthorization())

    private suspend fun request(port: Int, path: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }

    @Test fun callbackIsListeningBeforeBrowserOpensAndUsesSameRedirectForExchange() = runBlocking {
        MockWebServer().use { tokenServer ->
            tokenServer.enqueue(MockResponse().setBody("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}"""))
            val port = 51121
            val browser = CompletableDeferred<String>()
            val adapter = adapter(port, tokenServer.url("/token").toString(), browser)
            val pending = async(Dispatchers.IO) { adapter.authorize(provider()) }
            val url = Uri.parse(withTimeout(5000) { browser.await() })
            val redirect = Uri.parse(url.getQueryParameter("redirect_uri"))
            assertEquals("127.0.0.1", redirect.host)
            assertEquals(port, redirect.port)
            assertEquals("/oauth-callback", redirect.path)
            assertTrue(url.getQueryParameter("code_verifier") == null)
            assertEquals(43, url.getQueryParameter("code_challenge")!!.length)
            // The browser URL was issued only after bind; invalid requests do not consume authorization.
            assertTrue(request(port, "/favicon.ico").startsWith("HTTP/1.1 400"))
            assertTrue(request(port, "/oauth-callback?state=wrong&code=bad").startsWith("HTTP/1.1 400"))
            val state = url.getQueryParameter("state")!!
            assertTrue(request(port, "/oauth-callback?state=$state&code=valid-code").startsWith("HTTP/1.1 200"))
            val authorized = withTimeout(5000) { pending.await() }.getOrThrow()
            assertEquals("account:antigravity:v1", authorized.auth.value)
            val exchange = tokenServer.takeRequest().body.readUtf8()
            val fields = exchange.split("&").associate {
                val pair = it.split("=", limit = 2)
                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals(redirect.toString(), fields["redirect_uri"])
            assertEquals("valid-code", fields["code"])
            assertEquals(43, fields["code_verifier"]!!.length)
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("127.0.0.1", port))
            }.use { assertEquals(port, it.localPort) }
            adapter.clearCredentials(provider().id)
        }
    }

    @Test fun cancellationClosesTheListeningPort() = runBlocking {
        val port = 51121
        val browser = CompletableDeferred<String>()
        val adapter = adapter(port, "https://example.com/token", browser)
        val pending = async(Dispatchers.IO) { adapter.authorize(provider()) }
        withTimeout(5000) { browser.await() }
        assertTrue(request(port, "/favicon.ico").startsWith("HTTP/1.1 400"))
        pending.cancel()
        try { withTimeout(5000) { pending.await() }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        // await() on a cancelled Deferred need not wait for finally, so join before rebinding.
        pending.join()
        ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("127.0.0.1", port))
            }.use { assertEquals(port, it.localPort) }
    }
    @Test fun androidBrowserCanReachTheActiveLoopbackCallback() = runBlocking {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        assertFalse("Unlock the test device before running the real browser callback test.", keyguard.isKeyguardLocked)
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            MockWebServer().use { tokenServer ->
                tokenServer.enqueue(MockResponse().setBody("""{"access_token":"test-browser-access","refresh_token":"test-refresh","expires_in":3600}"""))
                val port = 51121
                val adapter = object : OAuthAccountAdapter(context, AttachmentResolver(context), ProviderType.ANTIGRAVITY,
                    "test-client", "https://example.com/authorize", tokenServer.url("/token").toString(),
                    "scope", port, "/oauth-callback", callbackHost = "127.0.0.1") {
                    override suspend fun openBrowser(url: String) {
                        val authorization = Uri.parse(url)
                        val callback = Uri.parse(authorization.getQueryParameter("redirect_uri")).buildUpon()
                            .appendQueryParameter("state", authorization.getQueryParameter("state"))
                            .appendQueryParameter("code", "test-browser-code").build()
                        // Open the real browser directly at the simulated provider redirect; no real account login.
                        withContext(Dispatchers.Main) {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, callback)
                                .setPackage("com.android.chrome")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo) = provider
                    override suspend fun fetchModels(provider: LlmProviderInfo) = listOf("chat")
                }
                val authorized = withTimeout(90_000) { adapter.authorize(provider()) }.getOrThrow()
                assertEquals("account:antigravity:v1", authorized.auth.value)
                assertTrue(tokenServer.takeRequest().body.readUtf8().contains("test-browser-code"))
                adapter.clearCredentials(provider().id)
            }
        }
    }

}
