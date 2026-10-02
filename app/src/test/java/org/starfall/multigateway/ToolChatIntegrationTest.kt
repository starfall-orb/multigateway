package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.*
import org.starfall.multigateway.data.service.*
import java.nio.file.Files

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ToolChatIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun modelCallsImageToolAndResumesWithStreamedAnswerWithoutBase64InChat() = runBlocking {
        val server=MockWebServer();server.start()
        val root=Files.createTempDirectory("tool-chat-test").toFile()
        var rounds=0
        var mediaCalls=0
        var imageRequest=""
        val bytes=ByteArray(24000){42};bytes[0]=0x89.toByte();bytes[1]=0x50
        val b64=java.util.Base64.getEncoder().encodeToString(bytes)
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                return if(request.path=="/v1/images/generations") {
                    mediaCalls++
                    imageRequest=request.body.readUtf8()
                    MockResponse().addHeader("Content-Type","application/json").setBody("{\"data\":[{\"b64_json\":\"$b64\"}]}")
                } else if(request.path=="/v1/chat/completions") {
                    rounds++
                    if(rounds==1) MockResponse().addHeader("Content-Type","text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"generate_image\",\"arguments\":\"{\\\"prompt\\\":\\\"a tree\\\"}\"}}]}}]}\n\ndata: [DONE]\n\n")
                    else {
                        val body=request.body.readUtf8()
                        assertTrue(body.contains("tool_call_id"));assertFalse(body.contains(b64))
                        MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Image ready.\"}}]}\n\ndata: [DONE]\n\n")
                    }
                } else MockResponse().setResponseCode(404)
            }
        }
        try {
            val provider=LlmProviderInfo("p","local",ProviderType.OPENAI,baseUrl=server.url("/v1").toString(),config=ProviderConfiguration(
                modelIds=listOf("chat","image"),modelConfigs=mapOf("chat" to ModelConfiguration(supportsToolCalls=true),"image" to ModelConfiguration(modelType=ModelType.IMAGE_GENERATION))))
            val files=ToolFiles(root);val http=ToolHttp(files)
            val engine=ToolChat(http,McpService(http),LlmService(context))
            val events=engine.generate(provider,"chat",listOf(StoredMessage("u",ChatRole.USER,listOf(MessageVersion("Draw a tree")))),"",emptyList(),listOf(provider),{emptyMap()},{ToolSettings(system=mapOf("generate_image" to SystemToolConfig(true,"p","image").withImageOptions(buildJsonObject { put("quality","high"); put("output_format","webp") })))}).toList()
            assertEquals(1,mediaCalls);assertEquals(2,rounds)
            val imageBody=Json.parseToJsonElement(imageRequest).jsonObject
            assertEquals("high",imageBody["quality"]!!.jsonPrimitive.content)
            assertEquals("webp",imageBody["output_format"]!!.jsonPrimitive.content)
            assertEquals("Image ready.",events.filterIsInstance<GenerationEvent.Text>().joinToString(""){it.text})
            val tool=events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("success",tool.status);assertTrue(tool.files.any{it.endsWith(".png")})
            assertFalse(events.toString().contains(b64))
            assertArrayEquals(bytes,files.list().first{it.extension=="png"}.readBytes())
        } finally {server.shutdown();root.deleteRecursively()}
    }
}
