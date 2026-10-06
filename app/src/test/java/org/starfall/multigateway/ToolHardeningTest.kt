package org.starfall.multigateway

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.McpService
import org.starfall.multigateway.data.tools.*
import java.io.StringReader
import java.nio.file.Files

class ToolHardeningTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun rejected(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is NullPointerException)
        assertFalse(error is NoSuchElementException)
    }
    @Test fun malformedProvidersFailClearly() {
        listOf("{}", "{\"choices\":[]}", "{\"choices\":[{}]}", "{\"choices\":[null]}", "{\"error\":{\"message\":\"denied\"}}")
            .forEach { rejected { providerTurn(ProviderType.OPENAI,json(it)) } }
        rejected { providerTurn(ProviderType.OLLAMA,json("{\"message\":false}")) }
        rejected { providerTurn(ProviderType.ANTHROPIC,json("{\"content\":{}}")) }
        rejected { providerTurn(ProviderType.GOOGLE,json("{\"candidates\":[]}")) }
        rejected { providerTurn(ProviderType.GOOGLE,json("{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":null}]}}]}")) }
    }
    @Test fun malformedCallsNeverExecute() {
        listOf("{}", "{\"function\":{}}", "{\"id\":\"a\",\"function\":{\"name\":\"x\",\"arguments\":[]}}",
            "{\"id\":\"a\",\"function\":{\"name\":\"x\",\"arguments\":\"broken\"}}")
            .forEach { rejected { providerTurn(ProviderType.OPENAI,json("{\"choices\":[{\"message\":{\"tool_calls\":[$it]}}]}")) } }
    }
    @Test fun ollamaCallsReceiveIdsAndArgumentsStayObjects() {
        val turn = providerTurn(ProviderType.OLLAMA,json("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"x\",\"arguments\":{}}}]}}"))
        assertTrue(turn.requireArray("tool_calls").single().requireObject().text("id").isNotBlank())
    }
    @Test fun baseUrlsPreserveCustomPrefixes() {
        listOf("https://api.openai.com/v1", "https://host/foo/v1", "https://host/v1beta", "http://localhost:11434/api")
            .forEach { base -> assertEquals(base,providerBase(LlmProviderInfo("p","p",ProviderType.OPENAI,baseUrl="$base/"))) }
        assertEquals("https://host/foo/v1",providerBase(LlmProviderInfo("p","p",ProviderType.OPENAI,baseUrl="https://host/foo/v1/chat/completions")))
        rejected { providerBase(LlmProviderInfo("p","p",ProviderType.OPENAI,baseUrl="file:///tmp")) }
    }
    @Test fun budgetsIncludeTotalAndMedia() {
        val total = ToolBudget(); repeat(24) { total.consume(null) }; rejected { total.consume(null) }
        val media = ToolBudget(); repeat(2) { media.consume("generate_image") }; rejected { media.consume("generate_image") }
        media.consume("generate_video"); rejected { media.consume("generate_video") }
    }
    @Test fun summaryRemainsJsonAndFullResultIsSaved() = runBlocking {
        val root=Files.createTempDirectory("summary").toFile()
        try {
            val files=ToolFiles(root); val original=obj("text" to str("quoted \" ".repeat(5000)))
            val summary=summarizeToolResult(original,files)
            assertTrue(summary.content["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(summary.content,Json.parseToJsonElement(summary.content.toString()))
            assertEquals(original,Json.parseToJsonElement(files.resolve(summary.files.last())!!.readText()))
            assertTrue(summary.preview.length<500)
        } finally { root.deleteRecursively() }
    }
    @Test fun dataUrlsSmallAndLargeBecomeFiles() = runBlocking {
        val root=Files.createTempDirectory("data-url").toFile()
        try {
            val files=ToolFiles(root)
            for (size in listOf(100,120000)) {
                val bytes=ByteArray(size){42};bytes[0]=0x89.toByte();bytes[1]=0x50
                val b64=java.util.Base64.getEncoder().encodeToString(bytes)
                val result=json(files.sanitize(StringReader("{\"url\":\"data:image/png;base64,$b64\"}")))
                assertArrayEquals(bytes,files.resolve(result.text("url").removePrefix("tool-file:"))!!.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun longTextIsStoredInFull() = runBlocking {
        val root=Files.createTempDirectory("long-text").toFile()
        try {
            val files=ToolFiles(root); val text="x".repeat(100000)
            val result=json(files.sanitize(StringReader("{\"text\":\"$text\"}")))
            assertEquals(text,files.resolve(result.text("text").removePrefix("tool-file:"))!!.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun authKeepsLegacyTokenAndNamedHeaderDistinct() {
        assertEquals("old",Authorization(key="old").token)
        val p=LlmProviderInfo("p","p",ProviderType.OPENAI,Authorization(AuthMethod.CUSTOM_HEADER,"X-Token","secret"),baseUrl="https://host")
        assertEquals("secret",ToolHttp().request(p.baseUrl,p).build().header("X-Token"))
        assertNull(ToolHttp().request(p.baseUrl,p).build().header("Authorization"))
    }
    @Test fun httpErrorsRedactEchoedToken() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"Invalid token supersecret\"}}"))
            val http=ToolHttp(); val p=LlmProviderInfo("p","p",ProviderType.OPENAI,Authorization(value="supersecret"),baseUrl=server.url("/").toString())
            val e=runCatching { http.json(http.request(p.baseUrl,p).build()) }.exceptionOrNull()
            assertNotNull(e); assertFalse(e!!.message.orEmpty().contains("supersecret")); assertTrue(e.message.orEmpty().contains("401"))
        } finally {server.shutdown()}
    }
    @Test fun sessionExpirationReconnectsWithoutReplayingTool() = runBlocking {
        val server=MockWebServer();server.start(); var calls=0;var inits=0
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="DELETE") return MockResponse().setResponseCode(204)
                if(request.method=="GET") return MockResponse().setResponseCode(405)
                val v=json(request.body.readUtf8());val id=v["id"]
                return when(v.text("method")) {
                    "initialize" -> {inits++; MockResponse().addHeader("Mcp-Session-Id","session$inits").setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")}
                    "notifications/initialized" -> MockResponse().setResponseCode(204)
                    "tools/call" -> {calls++;if(calls==1) MockResponse().setResponseCode(404) else MockResponse().setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[]}}")}
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        try {
            val session=McpService().session(McpInfo("s","s",McpProtocol.STREAMABLE_HTTP,server.url("/mcp").toString()))
            try {assertTrue(runCatching { session.call("write",obj()) }.isFailure);assertEquals(1,calls)
                session.call("write",obj());assertEquals(2,inits);assertEquals(2,calls)
            } finally {session.close()}
        } finally {server.shutdown()}
    }
    @Test fun mcpRejectsInvalidRepliesAndRepeatedCursors() = runBlocking {
        for (mode in listOf("id", "error", "empty", "sse-end", "malformed", "cursor")) {
            val server=MockWebServer();server.start()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method=="DELETE") return MockResponse().setResponseCode(204)
                if(request.method=="GET") return MockResponse().setResponseCode(405)
                    val v=json(request.body.readUtf8());val id=v["id"]
                    return when(v.text("method")) {
                        "initialize" -> MockResponse().setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
                        "notifications/initialized" -> MockResponse().setResponseCode(202)
                        else -> when(mode) {
                            "id" -> MockResponse().setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":\"wrong\",\"result\":{\"content\":[]}}")
                            "error" -> MockResponse().setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"message\":\"denied\"}}")
                            "empty" -> MockResponse().setResponseCode(204)
                            "sse-end" -> MockResponse().addHeader("Content-Type","text/event-stream").setBody(": keepalive\n\n")
                            "malformed" -> MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: {broken}\n\n")
                            else -> MockResponse().setHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[],\"nextCursor\":\"same\"}}")
                        }
                    }
                }
            }
            try {
                val session=McpService().session(McpInfo("s","s",McpProtocol.STREAMABLE_HTTP,server.url("/mcp").toString()))
                try {assertTrue(mode,runCatching { withTimeout(3000) { session.tools() } }.isFailure)} finally {session.close()}
            } finally {server.shutdown()}
        }
    }
    @Test fun downloadRejectsHtmlAndCancellationRemovesPartialFile() = runBlocking {
        val root=Files.createTempDirectory("download").toFile();val server=MockWebServer();server.start()
        try {
            val files=ToolFiles(root);val http=ToolHttp(files)
            server.enqueue(MockResponse().addHeader("Content-Type","text/html").setBody("login required"))
            assertTrue(runCatching { http.download(server.url("/html").toString()) }.isFailure)
            server.enqueue(MockResponse().addHeader("Content-Type","image/png").setBody("x".repeat(20000)).throttleBody(1,1,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { withTimeout(200) { http.download(server.url("/slow").toString()) } }.exceptionOrNull() is CancellationException)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {server.shutdown();root.deleteRecursively()}
    }

}
