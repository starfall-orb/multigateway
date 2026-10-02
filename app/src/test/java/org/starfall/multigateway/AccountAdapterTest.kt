package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.adapter.githubcopilot.copilotProtocol
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.multigateway.data.adapter.AccountProviderAdapterRegistry
import org.starfall.multigateway.data.adapter.claudecode.ClaudeCodeAdapter
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.*

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class AccountAdapterTest {
    @Before fun prepareKeystore() = installTestAndroidKeyStore()
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
    @Test fun refreshRotatesCredentialsAndPreservesIdentityAndProject() {
        val old = AccountTokenState("old", "refresh", "Bearer", 100, "account", "user@example.com", "project")
        val rotated = accountTokenFromResponse(obj("access_token" to str("new"),
            "refresh_token" to str("rotated"), "expires_in" to JsonPrimitive(3600)), old, now = 1000)
        assertEquals("new", rotated.accessToken)
        assertEquals("rotated", rotated.refreshToken)
        assertEquals(3601000L, rotated.expiresAt)
        assertEquals(old.accountId, rotated.accountId)
        assertEquals(old.email, rotated.email)
        assertEquals(old.projectId, rotated.projectId)
        val partial = accountTokenFromResponse(obj("access_token" to str("newer")), rotated)
        assertEquals("rotated", partial.refreshToken)
        assertNull(partial.expiresAt)
    }

    @Test fun encryptedCredentialsAreIsolatedByProviderAndAdapter() {
        val claude = AccountTokenStore(context, "test_claude")
        val google = AccountTokenStore(context, "test_google")
        val first = AccountTokenState("private-access-one", "private-refresh-one")
        val second = AccountTokenState("private-access-two", "private-refresh-two")
        claude.save("one", first); claude.save("two", second); google.save("one", second)
        assertEquals(first, claude.load("one")); assertEquals(second, claude.load("two"))
        assertEquals(second, google.load("one"))
        val encrypted = context.getSharedPreferences("multigateway.test_claude.oauth", Context.MODE_PRIVATE)
            .getString("one", "")!!
        assertFalse(encrypted.contains(first.accessToken))
        assertFalse(encrypted.contains(first.refreshToken))
        claude.delete("one")
        assertNull(claude.load("one")); assertEquals(second, claude.load("two"))
        assertEquals(second, google.load("one"))
    }

    @Test fun copilotSelectsProtocolsAndRecognizesToolResultContinuation() {
        assertEquals(ProviderType.ANTHROPIC, copilotProtocol("claude-test", listOf("/v1/messages")))
        assertEquals(ProviderType.OPENAI_RESPONSES, copilotProtocol("gpt-10.1", emptyList()))
        assertEquals(ProviderType.OPENAI, copilotProtocol("gpt-5-mini", listOf("/chat/completions")))
        assertEquals(ProviderType.OPENAI_RESPONSES, copilotProtocol("custom", listOf("/responses")))
        val adapter = org.starfall.multigateway.data.adapter.githubcopilot.GitHubCopilotAdapter(context, AttachmentResolver(context))
        val source = LlmProviderInfo("copilot", "Copilot", ProviderType.GITHUB_COPILOT, baseUrl = "https://api.githubcopilot.com")
        val result = adapter.prepareRequestProvider(source, source.copy(type = ProviderType.ANTHROPIC),
            obj("messages" to JsonArray(listOf(obj("role" to str("user"), "content" to JsonArray(listOf(
                obj("type" to str("tool_result"), "tool_use_id" to str("call"), "content" to str("result"))
            )))))))
        assertEquals("agent", result.config.headers["x-initiator"])
        assertEquals("user", adapter.prepareRequestProvider(source, source, obj()).config.headers["x-initiator"])
    }

    @Test fun attachmentsUseSelectedWireContentTypes() {
        val message = obj("content" to str("look"), "_attachments" to JsonArray(listOf(
            obj("mimeType" to str("image/png"), "data" to str("aA==")))))
        val response = toolMessageContent(message, ProviderType.OPENAI_RESPONSES).jsonArray
        assertEquals("input_text", response[0].jsonObject.text("type"))
        assertEquals("input_image", response[1].jsonObject.text("type"))
        assertEquals("data:image/png;base64,aA==", response[1].jsonObject.text("image_url"))
        assertEquals("image", toolAttachmentParts(message, ProviderType.ANTHROPIC)[0].jsonObject.text("type"))
        assertNotNull(toolAttachmentParts(message, ProviderType.GOOGLE)[0].jsonObject["inlineData"])
        assertEquals("image_url", toolAttachmentParts(message, ProviderType.OPENAI)[0].jsonObject.text("type"))
        val pdf = obj("_attachments" to JsonArray(listOf(obj("mimeType" to str("application/pdf"), "data" to str("aA==")))))
        assertEquals("document", toolAttachmentParts(pdf, ProviderType.ANTHROPIC)[0].jsonObject.text("type"))
    }

    @Test fun nonStreamingEnvelopePreservesReasoningSignatureAndTools() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"plan"},{"functionCall":{"name":"lookup","args":{}},"thoughtSignature":"signed"}]}}]}}"""))
            val provider = LlmProviderInfo("test", "Test", ProviderType.GOOGLE, baseUrl = server.url("/").toString())
            val response = ToolHttp().modelResponse(server.url("/generate").toString(), obj(), provider, false,
                {}, {}, { it.getValue("response").jsonObject })
            val parts = response["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            assertEquals("plan", parts[0].jsonObject.text("text"))
            assertEquals("signed", parts[1].jsonObject.text("thoughtSignature"))
            assertNotNull(parts[1].jsonObject["functionCall"])
        }
    }

    @Test fun cancelledHttpRequestPropagatesCancellation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val request = ToolHttp().request(server.url("/token").toString()).get().build()
            val job = async { ToolHttp().json(request) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            job.cancel()
            try { job.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
    }

    @Test fun accountAdaptersGenerateRegularChatWithAndWithoutStreaming() = runBlocking {
        for (type in listOf(ProviderType.CLAUDE_CODE, ProviderType.ANTIGRAVITY, ProviderType.GITHUB_COPILOT)) {
            for (stream in listOf(false, true)) {
                MockWebServer().use { server ->
                    val id = "regular-${type.name}-$stream"
                    AccountTokenStore(context, type.name.lowercase()).save(id,
                        AccountTokenState("test-access", "test-refresh", projectId = "test-project"))
                    val body = when (type) {
                        ProviderType.CLAUDE_CODE -> """{"content":[{"type":"thinking","thinking":"plan","signature":"signed"},{"type":"text","text":"hello"}]}"""
                        ProviderType.ANTIGRAVITY -> """{"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"plan"},{"text":"hello"}]}}]}}"""
                        else -> """{"choices":[{"message":{"role":"assistant","content":"hello","reasoning_content":"plan"}}]}"""
                    }
                    val eventBody = when (type) {
                        ProviderType.CLAUDE_CODE -> listOf(
                            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}""",
                            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}"""
                        ).joinToString("") { "data: $it\n\n" }
                        ProviderType.ANTIGRAVITY -> "data: $body\n\n"
                        else -> """data: {"choices":[{"delta":{"content":"hello","reasoning_content":"plan"}}]}""" + "\n\ndata: [DONE]\n\n"
                    }
                    if (type == ProviderType.GITHUB_COPILOT)
                        server.enqueue(MockResponse().setBody("""{"data":[{"id":"chat","supported_endpoints":["/chat/completions"]}]}"""))
                    server.enqueue(MockResponse().setHeader("Content-Type",
                        if (stream) "text/event-stream" else "application/json").setBody(if (stream) eventBody else body))
                    val provider = LlmProviderInfo(id, type.displayName, type,
                        auth = Authorization(AuthMethod.OAUTH, value = "account:${type.name.lowercase()}:v1"),
                        baseUrl = server.url("/v1").toString(), config = ProviderConfiguration(supportStream = stream))
                    val adapter = AccountProviderAdapterRegistry(context, AttachmentResolver(context)).get(type)!!
                    val events = adapter.streamEvents(provider, "chat",
                        listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("hi")))),
                        "User instructions", 4000).toList()
                    assertEquals("$type stream=$stream", "hello",
                        events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
                    assertEquals("$type stream=$stream", "plan",
                        events.filterIsInstance<GenerationEvent.Reasoning>().joinToString("") { it.text })
                    if (type == ProviderType.GITHUB_COPILOT) server.takeRequest()
                    val request = server.takeRequest()
                    assertEquals("Bearer test-access", request.getHeader("Authorization"))
                    assertNull(request.getHeader("x-api-key")); assertNull(request.getHeader("x-goog-api-key"))
                    assertTrue(request.body.readUtf8().contains("User instructions"))
                    if (type == ProviderType.CLAUDE_CODE)
                        assertTrue(events.filterIsInstance<GenerationEvent.Reasoning>().any { it.signature == "signed" })
                    if (type == ProviderType.ANTIGRAVITY)
                        assertEquals(if (stream) "/v1/v1internal:streamGenerateContent?alt=sse"
                            else "/v1/v1internal:generateContent", request.path)
                }
            }
        }
    }

}
