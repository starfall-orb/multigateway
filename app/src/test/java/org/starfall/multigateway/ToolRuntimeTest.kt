package org.starfall.multigateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.McpService
import org.starfall.multigateway.data.tools.*
import org.starfall.multigateway.ui.chat.ChatGeneration
import java.io.StringReader
import java.nio.file.Files

class ToolRuntimeTest {
    @Test fun audioResultsKeepPlayableExtensionsAndDataUrlsAreStored() = runBlocking {
        val root = Files.createTempDirectory("audio-files-test").toFile()
        try {
            val store = ToolFiles(root)
            val samples = mapOf(
                "mp3" to "ID3", "wav" to "RIFF0000WAVE", "ogg" to "OggS",
                "flac" to "fLaC", "m4a" to "0000ftypM4A "
            )
            samples.forEach { (extension, header) ->
                val bytes = header.toByteArray() + ByteArray(100)
                val name = store.save(bytes.inputStream())
                assertTrue("Expected $extension, got $name", name.endsWith(".$extension"))
                assertArrayEquals(bytes, store.resolve(name)!!.readBytes())
            }
            val audio = "RIFF0000WAVE".toByteArray() + ByteArray(32)
            val encoded = java.util.Base64.getEncoder().encodeToString(audio)
            val result = store.sanitize(StringReader("{\"url\":\"data:audio/wav;base64,$encoded\"}"))
            val reference = Json.parseToJsonElement(result).jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(reference.startsWith("tool-file:"))
            assertTrue(reference.endsWith(".wav"))
            assertArrayEquals(audio, store.resolve(reference.removePrefix("tool-file:"))!!.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun permissionsNeverOverrideDisabledProfileOrIndividualTool() {
        assertFalse(toolAllowed(null, true, true, "write"))
        assertFalse(toolAllowed(McpAccess(false), true, true, "write"))
        assertFalse(toolAllowed(McpAccess(true, mapOf("write" to false)), true, true, "write"))
        assertFalse(toolAllowed(McpAccess(true), false, true, "write"))
        assertTrue(toolAllowed(McpAccess(true), null, true, "read"))
    }
    @Test fun base64IsStoredAsFileAndMissingFileIsSafe() = runBlocking {
        val root=Files.createTempDirectory("tool-files-test").toFile()
        try {
            val store=ToolFiles(root)
            val bytes=ByteArray(120000){42};bytes[0]=0x89.toByte();bytes[1]=0x50
            val encoded=java.util.Base64.getEncoder().encodeToString(bytes)
            val sanitized=store.sanitize(StringReader("{\"data\":[{\"b64_json\":\"$encoded\"}],\"text\":\"ok\"}"))
            assertTrue(sanitized.length<300)
            val name=Json.parseToJsonElement(sanitized).jsonObject["data"]!!.jsonArray[0].jsonObject["b64_json"]!!.jsonPrimitive.content.removePrefix("tool-file:")
            assertArrayEquals(bytes,store.resolve(name)!!.readBytes())
            assertNull(store.resolve("../outside"))
            store.delete(listOf(name));assertNull(store.resolve(name))
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun hugeTextIsBoundedAndMalformedJsonDoesNotRemainOnDisk() = runBlocking {
        val root=Files.createTempDirectory("tool-text-test").toFile()
        try {
            val store=ToolFiles(root)
            val output=store.sanitize(StringReader("{\"text\":\"${"x".repeat(100000)}\"}"))
            assertTrue(output.length<66000)
            assertTrue(Json.parseToJsonElement(output).jsonObject.containsKey("text"))
            runCatching{store.sanitize(StringReader("{\"b64_json\":\"${"A".repeat(20000)}"))}
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
            assertEquals(1,store.list().size)
        } finally {root.deleteRecursively()}
    }
    @Test fun mcpUsesHandshakeSessionHeadersAndRealToolsCall() = runBlocking {
        val server=MockWebServer(); server.start()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                if(request.method=="DELETE") return MockResponse().setResponseCode(204)
                val value=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id=value["id"]
                return when(value["method"]?.jsonPrimitive?.content) {
                    "initialize" -> MockResponse().addHeader("Mcp-Session-Id","test-session").addHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> MockResponse().addHeader("Content-Type","text/event-stream").setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n")
                    "tools/call" -> MockResponse().addHeader("Content-Type","application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}")
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        try {
            val session=McpService().session(McpInfo("s","server",McpProtocol.STREAMABLE_HTTP,server.url("/mcp").toString()))
            assertEquals("echo",session.tools().single().originalName)
            assertTrue(session.call("echo",buildJsonObject{}).toString().contains("hello"))
            session.close()
            val init=server.takeRequest();assertTrue(init.getHeader("Accept")!!.contains("text/event-stream"))
            val notification=server.takeRequest();assertEquals("test-session",notification.getHeader("Mcp-Session-Id"))
            assertEquals("2025-06-18",notification.getHeader("MCP-Protocol-Version"))
        } finally {server.shutdown()}
    }
    @Test fun stoppingGenerationPersistsCancelledToolActivity() = runBlocking {
        var saved:Conversation?=null
        val gen=ChatGeneration(this,{saved=it},{})
        val conv=Conversation("c","c",0,0,messages=listOf(StoredMessage("a",ChatRole.MODEL,listOf(MessageVersion()))))
        gen.startEvents(conv,"a",flow {emit(GenerationEvent.Tool(ToolActivity("t","slow")));awaitCancellation()})
        yield();gen.stopAndJoin()
        assertEquals("cancelled",saved!!.messages.single().activeVersion.toolActivity.single().status)
    }
}
