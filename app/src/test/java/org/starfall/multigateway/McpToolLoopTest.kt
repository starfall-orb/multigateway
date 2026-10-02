package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.adapter.common.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.multigateway.data.adapter.codex.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.*
import org.starfall.multigateway.data.tools.*
import java.nio.file.Files

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class McpToolLoopTest {
    @Before fun prepareKeystore() {
        installTestAndroidKeyStore()
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("png", "image/png")
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun allProviderAdaptersExecuteMcpAndReturnToolResultToModel() = runBlocking {
        for(type in ProviderType.entries) {
            val server=MockWebServer();server.start()
            val root=Files.createTempDirectory("mcp-loop").toFile()
            val image = java.io.File(root, "sample.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            var rounds=0;var calls=0
            server.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method=="DELETE") return MockResponse().setResponseCode(204)
                    if (request.path?.endsWith("/models") == true)
                        return MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("""{"data":[{"id":"chat","supported_endpoints":["/chat/completions"]}]}""")
                    var body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    fun json(value:JsonElement)=MockResponse().addHeader("Content-Type","application/json").setBody(value.toString())
                    if(request.path=="/mcp") {
                        val method=body["method"]!!.jsonPrimitive.content
                        if(method=="notifications/initialized")return MockResponse().setResponseCode(202)
                        val result=when(method){
                            "initialize"->buildJsonObject{put("protocolVersion","2025-06-18")}
                            "tools/list"->buildJsonObject{put("tools",buildJsonArray{add(buildJsonObject{put("name","echo");put("inputSchema",buildJsonObject{put("type","object")})})})}
                            "tools/call"->{calls++;buildJsonObject{put("content",buildJsonArray{add(buildJsonObject{put("type","text");put("text","hello_from_mcp")})})}}
                            else->error(method)
                        }
                        return json(buildJsonObject{put("jsonrpc","2.0");put("id",body["id"]!!);put("result",result)})
                    }
                    if (type == ProviderType.ANTIGRAVITY) body = body.getValue("request").jsonObject
                    assertTrue(type.name + " attachment", body.toString().contains("AQID"))
                    rounds++
                    if(rounds==2)assertTrue(type.name,body.toString().contains("hello_from_mcp"))
                    val first=rounds==1
                    val tools=body["tools"]!!.jsonArray
                    val name=when(type){
                        ProviderType.ANTHROPIC, ProviderType.CLAUDE_CODE -> tools[0].jsonObject["name"]!!.jsonPrimitive.content
                        ProviderType.OPENAI_RESPONSES, ProviderType.OPENAI_CODEX -> tools[0].jsonObject["name"]?.jsonPrimitive?.content ?: tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        ProviderType.GOOGLE, ProviderType.ANTIGRAVITY->tools[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
                        else->tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    }
                    val call=buildJsonObject{put("id","c1");put("type","function");put("function",buildJsonObject{put("name",name);if(type==ProviderType.OLLAMA)put("arguments",buildJsonObject{}) else put("arguments","{}")})}
                    val message=buildJsonObject{put("role","assistant");put("content",if(first) "" else "done");if(first)put("tool_calls",buildJsonArray{add(call)})}
                    val response = when(type){
                        ProviderType.OPENAI, ProviderType.GITHUB_COPILOT->buildJsonObject{put("choices",buildJsonArray{add(buildJsonObject{put("message",message)})})}
                        ProviderType.OPENAI_RESPONSES, ProviderType.OPENAI_CODEX->buildJsonObject{
                            put("status","completed")
                            put("output",buildJsonArray{
                                add(if(first) buildJsonObject{
                                    put("type","function_call");put("call_id","c1");put("name",name);put("arguments","{}")
                                } else buildJsonObject{
                                    put("type","message");put("content",buildJsonArray{add(buildJsonObject{put("type","output_text");put("text","done")})})
                                })
                            })
                        }
                        ProviderType.OLLAMA->buildJsonObject{put("message",message)}
                        ProviderType.ANTHROPIC, ProviderType.CLAUDE_CODE->buildJsonObject{put("content",buildJsonArray{add(if(first)buildJsonObject{put("type","tool_use");put("id","c1");put("name",name);put("input",buildJsonObject{})}else buildJsonObject{put("type","text");put("text","done")})})}
                        ProviderType.GOOGLE, ProviderType.ANTIGRAVITY->buildJsonObject{put("candidates",buildJsonArray{add(buildJsonObject{put("content",buildJsonObject{put("parts",buildJsonArray{add(if(first)buildJsonObject{put("functionCall",buildJsonObject{put("name",name);put("args",buildJsonObject{})});put("thoughtSignature","sig")}else buildJsonObject{put("text","done")})})})})})}
                        else->error("Unsupported provider type: $type")
                    }
                    return json(if (type == ProviderType.ANTIGRAVITY) obj("response" to response) else response)
                }
            }
            try {
                val auth = if (type == ProviderType.OPENAI_CODEX) {
                    CodexTokenStore(context).save("p", CodexTokenState("test_token", "test_refresh", accountId = "acc"))
                    Authorization(AuthMethod.OAUTH, key = "test@example.com", value = OpenAICodexAdapter.AUTH_MARKER)
                } else if (type.isAccountProvider) {
                    AccountTokenStore(context, type.name.lowercase()).save("p",
                        AccountTokenState("test_token", "test_refresh", projectId = "test-project"))
                    Authorization(AuthMethod.OAUTH, value = "account:${type.name.lowercase()}:v1")
                } else type.defaultAuthorization()
                val p=LlmProviderInfo("p","P",type,auth=auth,baseUrl=server.url(if(type==ProviderType.GOOGLE)"/v1beta" else if(type==ProviderType.OLLAMA)"/api" else "/v1").toString(),config=ProviderConfiguration(supportStream=false,modelConfigs=mapOf("chat" to ModelConfiguration(supportsToolCalls=true))))
                val m=McpInfo("s","MCP",McpProtocol.STREAMABLE_HTTP,server.url("/mcp").toString())
                val http=ToolHttp(ToolFiles(root))
                val events=ToolChat(http,McpService(http),LlmService(context)).generate(p,"chat",listOf(StoredMessage("u",ChatRole.USER,listOf(MessageVersion("Call echo", files = listOf(image.absolutePath))))),"",listOf(m),listOf(p),{mapOf("s" to McpAccess(true))},{ToolSettings()}).toList()
                assertEquals(type.name,1,calls);assertEquals(type.name,2,rounds)
                assertEquals(type.name,"done",events.filterIsInstance<GenerationEvent.Text>().joinToString(""){it.text})
                assertEquals(type.name,"success",events.filterIsInstance<GenerationEvent.Tool>().last().activity.status)
            }finally{server.shutdown();root.deleteRecursively()}
        }
    }
}
