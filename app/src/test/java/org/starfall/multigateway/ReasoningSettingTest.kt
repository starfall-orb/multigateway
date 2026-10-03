package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.service.OfficialLlmSdk
import java.util.concurrent.TimeUnit

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ReasoningSettingTest {
    @Test fun conversationOffOverridesModelLevelAndAutoRestoresIt() {
        val config = ModelConfiguration(reasoningEffort = "high")
        assertTrue(config.withConversationReasoning("none").reasoningDisabled)
        assertEquals(config, config.withConversationReasoning(null))
        assertEquals("low", config.withConversationReasoning("low").reasoningEffort)
        val saved = Json.encodeToString(config.withConversationReasoning("none"))
        assertTrue(Json.decodeFromString<ModelConfiguration>(saved).reasoningDisabled)
    }

    @Test fun offReachesNativeProviderRequests() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sdk = OfficialLlmSdk(AttachmentResolver(context))
        val messages = listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion(content = "Hello"))))
        for (type in listOf(ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.GOOGLE)) {
            MockWebServer().use { server ->
                val event = when (type) {
                    ProviderType.ANTHROPIC -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    ProviderType.GOOGLE -> "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}}]}\n\n"
                    else -> "data: [DONE]\n\n"
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event))
                val provider = LlmProviderInfo("p", "P", type, Authorization(value = "key"),
                    baseUrl = server.url(if (type == ProviderType.GOOGLE) "/v1beta" else "/v1").toString(),
                    config = ProviderConfiguration(modelConfigs = mapOf("custom" to ModelConfiguration(reasoningEffort = "none"))))
                when (type) {
                    ProviderType.ANTHROPIC -> sdk.streamAnthropic(provider, "custom", messages, "", null, null, 2048).toList()
                    ProviderType.GOOGLE -> sdk.streamGoogle(provider, "custom", messages, "", null, null, 2048).toList()
                    else -> sdk.streamOpenAi(provider, "custom", messages, "", null, null, 2048, "none").toList()
                }
                val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
                when (type) {
                    ProviderType.ANTHROPIC -> assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                    ProviderType.GOOGLE -> assertEquals(0, body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
                    else -> assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test fun reasoningBudgetFollowsConfiguredEffortAndTokenLimit() {
        assertEquals(1024, ModelConfiguration(reasoningEffort = "low").reasoningBudget(10_000))
        assertEquals(2048, ModelConfiguration(reasoningEffort = "medium").reasoningBudget(10_000))
        assertEquals(4096, ModelConfiguration(reasoningEffort = "high").reasoningBudget(10_000))
        assertEquals(8192, ModelConfiguration(reasoningEffort = "xhigh").reasoningBudget(10_000))
        assertEquals(1499, ModelConfiguration(reasoningEffort = "xhigh").reasoningBudget(1500))
    }

}
