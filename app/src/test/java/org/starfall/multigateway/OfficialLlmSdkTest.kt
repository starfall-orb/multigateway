package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.service.OfficialLlmSdk
import java.util.concurrent.TimeUnit

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class OfficialLlmSdkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sdk by lazy { OfficialLlmSdk(AttachmentResolver(context)) }
    private val messages = listOf(StoredMessage(id = "1", role = ChatRole.USER,
        versions = listOf(MessageVersion(content = "Hello"))))

    private fun provider(server: MockWebServer, type: ProviderType, key: String = "test-key") = LlmProviderInfo(
        id = key, name = "Test", type = type,
        baseUrl = server.url(if (type == ProviderType.GOOGLE) "/v1beta" else "/v1").toString(),
        auth = Authorization(key = key))

    private fun sse(data: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(data)
    private fun openAiResponse(text: String) = sse("data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"custom-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n")

    @Test fun runtimeSwitchKeepsEndpointAndCredentialsSeparate() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(openAiResponse("first")); second.enqueue(openAiResponse("second"))
            for ((server, key, expected) in listOf(Triple(first, "key-a", "first"), Triple(second, "key-b", "second"))) {
                val result = sdk.streamOpenAi(provider(server, ProviderType.OPENAI, key), "custom-model", messages, "system", 0.5, 0.9, 99).toList()
                assertEquals(expected, result.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer $key", request.getHeader("Authorization"))
                assertTrue(request.body.readUtf8().contains("custom-model"))
            }
        } }
    }

    @Test fun queryAuthDoesNotInventBearerCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(openAiResponse("ok"))
            val provider = provider(server, ProviderType.OPENAI).copy(auth = Authorization(AuthMethod.QUERY_PARAM, "token", "secret"))
            sdk.streamOpenAi(provider, "custom", messages, "", null, null, 50).toList()
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("secret", request.requestUrl!!.queryParameter("token"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun anthropicUsesNativePathAndParsesSdkEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
            assertEquals("hello", sdk.streamAnthropic(provider(server, ProviderType.ANTHROPIC), "custom-claude", messages, "", null, null, 50)
                .toList().filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/messages", request.path)
            assertEquals("test-key", request.getHeader("x-api-key"))
        }
    }

    @Test fun anthropicEffortUsesAdaptiveThinkingOnTheWire() = runBlocking {
        MockWebServer().use { server ->
            val model = "claude-opus-4-6"
            server.enqueue(sse("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
            val base = provider(server, ProviderType.ANTHROPIC)
            val configured = base.copy(config = base.config.copy(modelConfigs = mapOf(model to
                ModelConfiguration(supportsThinking = true, reasoningEffort = "xhigh"))))
            sdk.streamAnthropic(configured, model, messages, "", 0.7, 0.9, 512, 40).toList()
            val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(body["thinking"]!!.jsonObject.containsKey("budget_tokens"))
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
            assertFalse(body.containsKey("top_k"))
        }
    }

    @Test fun anthropicEffortPreservesLegacyBudgetEncoding() = runBlocking {
        MockWebServer().use { server ->
            val model = "claude-sonnet-4-5"
            server.enqueue(sse("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
            val base = provider(server, ProviderType.ANTHROPIC)
            val configured = base.copy(config = base.config.copy(modelConfigs = mapOf(model to
                ModelConfiguration(supportsThinking = true, reasoningEffort = "xhigh"))))
            sdk.streamAnthropic(configured, model, messages, "", null, null, 6000).toList()
            val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(5999, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
            assertFalse(body.containsKey("output_config"))
        }
    }

    @Test fun googlePreservesApiVersionAndCustomAuth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse("data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hello\"}]}}]}\n\n"))
            val provider = provider(server, ProviderType.GOOGLE).copy(auth = Authorization(AuthMethod.CUSTOM_HEADER, "X-Gateway-Key", "secret"))
            assertEquals("hello", sdk.streamGoogle(provider, "models/custom-gemini", messages, "", null, null, 50)
                .toList().filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1beta/models/custom-gemini:streamGenerateContent", request.requestUrl!!.encodedPath)
            assertEquals("secret", request.getHeader("X-Gateway-Key"))
            assertNull(request.getHeader("x-goog-api-key"))
        }
    }

    @Test fun rejectedAnthropicCredentialsFailConnectionTest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid key\"}}"))
            assertTrue(sdk.testConnection(provider(server, ProviderType.ANTHROPIC)).isFailure)
            assertEquals("/v1/models", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        }
    }
    @Test fun cancellingAStreamDoesNotWaitForTheServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val start = System.nanoTime()
            try {
                kotlinx.coroutines.withTimeout(1000) {
                    sdk.streamOpenAi(provider(server, ProviderType.OPENAI), "custom", messages, "", null, null, 50).toList()
                }
                fail("Expected cancellation")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            }
        }
    }

}
