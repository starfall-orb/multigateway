package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.multigateway.data.adapter.AccountProviderAdapterRegistry
import org.starfall.multigateway.data.adapter.claudecode.ClaudeCodeAdapter
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.*

@RunWith(RobolectricTestRunner::class)
class AccountAdapterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun antigravityResolvesCanonicalIdsToBackendRoutes() {
        val models = org.starfall.multigateway.data.adapter.antigravity.AntigravityModels
        assertEquals("gemini-pro-agent", models.resolve("gemini-3.1-pro"))
        assertEquals("gemini-3.1-pro-low", models.resolve("gemini-3.1-pro", "low"))
        assertEquals("gemini-3-flash-agent", models.resolve("gemini-3.5-flash"))
        assertEquals("claude-opus-4-6-thinking", models.resolve("claude-opus-4-6"))
        assertEquals("claude-sonnet-4-6", models.resolve("claude-sonnet-4-6-thinking"))
        assertEquals("custom-model", models.resolve("custom-model"))
    }

    @Test fun allAccountProvidersUseOAuthAndAreRegistered() {
        val registry = AccountProviderAdapterRegistry(context, AttachmentResolver(context))
        for (type in listOf(ProviderType.OPENAI_CODEX, ProviderType.CLAUDE_CODE, ProviderType.ANTIGRAVITY, ProviderType.GITHUB_COPILOT)) {
            assertTrue(type.isAccountProvider)
            assertEquals(AuthMethod.OAUTH, type.defaultAuthorization().method)
            assertNotNull(registry.get(type))
            assertEquals("", Authorization(AuthMethod.OAUTH, value = "private-token").token)
        }
    }

    @Test fun claudeNormalizationPreservesUserSystemAndTools() {
        val adapter = ClaudeCodeAdapter(context, AttachmentResolver(context))
        val provider = LlmProviderInfo("test", "Claude", ProviderType.CLAUDE_CODE, baseUrl = ProviderType.CLAUDE_CODE.defaultBaseUrl)
        val body = obj("system" to str("User instructions"), "tools" to JsonArray(listOf(obj("name" to str("lookup")))))
        val result = adapter.normalizeToolRequest(provider, provider, body, "User instructions")
        assertEquals(body["tools"], result["tools"])
        val blocks = result.getValue("system").jsonArray
        assertTrue(blocks.first().jsonObject.text("text").contains("Claude Code"))
        assertEquals("User instructions", blocks.last().jsonObject.text("text"))
    }

    @Test fun copilotSetsInitiatorAndVisionHeadersFromRequest() {
        val adapter = org.starfall.multigateway.data.adapter.githubcopilot.GitHubCopilotAdapter(context, AttachmentResolver(context))
        val source = LlmProviderInfo("copilot", "Copilot", ProviderType.GITHUB_COPILOT, baseUrl = ProviderType.GITHUB_COPILOT.defaultBaseUrl)
        val wire = source.copy(type = ProviderType.OPENAI)
        val userBody = obj("messages" to JsonArray(listOf(obj("role" to str("user"), "content" to JsonArray(listOf(
            obj("type" to str("image_url"), "image_url" to obj("url" to str("data:image/png;base64,aA==")))
        ))))))
        val user = adapter.prepareRequestProvider(source, wire, userBody)
        assertEquals("user", user.config.headers["x-initiator"])
        assertEquals("true", user.config.headers["Copilot-Vision-Request"])
        val tool = adapter.prepareRequestProvider(source, wire, obj("messages" to JsonArray(listOf(obj("role" to str("tool"))))))
        assertEquals("agent", tool.config.headers["x-initiator"])
        assertNull(tool.config.headers["Copilot-Vision-Request"])
        val responses = adapter.normalizeToolRequest(source, wire.copy(type = ProviderType.OPENAI_RESPONSES), obj(), "User prompt")
        assertEquals("User prompt", responses.text("instructions"))
        assertEquals(false, responses.getValue("store").jsonPrimitive.boolean)
    }

    @Test fun codeAssistStreamUnwrapsEnvelopeAndPreservesFunctionCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plan\",\"thought\":true},{\"text\":\"hello\"},{\"functionCall\":{\"name\":\"lookup\",\"args\":{}}}]}}]}}\n\n"))
            val provider = LlmProviderInfo("test", "Test", ProviderType.GOOGLE, baseUrl = server.url("/").toString(),
                auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer test"))
            val text = StringBuilder(); val reasoning = StringBuilder()
            val response = ToolHttp().modelResponse(server.url("/v1internal:generateContent").toString(), obj(), provider, true,
                { text.append(it) }, { reasoning.append(it) }, { it.getValue("response").jsonObject })
            assertEquals("hello", text.toString()); assertEquals("plan", reasoning.toString())
            val parts = response.getValue("candidates").jsonArray.first().jsonObject.getValue("content").jsonObject.getValue("parts").jsonArray
            assertTrue(parts.any { it.jsonObject["functionCall"] != null })
            val request = server.takeRequest()
            assertEquals("/v1internal:streamGenerateContent?alt=sse", request.path)
            assertEquals("Bearer test", request.getHeader("Authorization"))
            assertNull(request.getHeader("x-goog-api-key"))
        }
    }

    @Test fun oauthPollingAllowsPendingWithoutWeakeningNormalErrors() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("{\"error\":\"authorization_pending\"}")) }
            val http = ToolHttp()
            val request = http.request(server.url("/token").toString()).get().build()
            assertEquals("authorization_pending", http.json(request, allowOAuthError = true).text("error"))
            try { http.json(request); fail("Normal provider errors must still fail") } catch (_: IllegalStateException) { }
        }
    }
}
