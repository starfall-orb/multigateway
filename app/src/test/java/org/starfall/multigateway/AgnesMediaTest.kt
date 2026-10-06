package org.starfall.multigateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.*
import java.net.InetAddress
import java.nio.file.Files
import java.util.Base64

// Route the documented Agnes hostname to the mock server, preserving URL selection.
internal fun agnesTestClient(): OkHttpClient = ToolHttp().client.newBuilder().dns(object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == "apihub.agnes-ai.com") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(hostname)
}).build()

internal fun agnesTestProvider(server: MockWebServer, path: String = "/v1") = LlmProviderInfo(
    "p", "Agnes", ProviderType.OPENAI,
    baseUrl = server.url(path).newBuilder().host("apihub.agnes-ai.com").build().toString(),
    auth = Authorization(value = "test-secret")
)

class AgnesMediaTest {
    @Test fun bothProtocolsAndSettingsAreSelectedByUrlOnly() {
        val agnes = LlmProviderInfo("p", "Agnes", ProviderType.OPENAI, baseUrl = " https://apihub.agnes-ai.com/v1/ ")
        val generic = agnes.copy(baseUrl = "https://proxy.example/v1")
        for (model in listOf("custom", "agnes-image-2.5-flash", "agnes-video-2.5")) {
            assertTrue(isAgnesProvider(agnes))
            assertEquals(VideoApi.AGNES, videoApi(agnes))
            assertEquals(VideoApi.OPENAI, videoApi(generic))
            assertTrue(imageOptionFields(agnes, model).any { it.path == "ratio" })
            assertFalse(imageOptionFields(generic, model).any { it.path == "ratio" })
        }
        for (url in listOf("https://apihub.agnes-ai.com.evil.example/v1", "https://evil.example/apihub.agnes-ai.com", "https://agnes-ai.com/v1")) {
            assertFalse(isAgnesProvider(agnes.copy(baseUrl = url)))
        }
    }

    @Test fun imagesUseJsonGenerationEndpointForTextAndMultipleAttachmentsAndSaveBase64() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-images").toFile()
        try {
            val files = ToolFiles(root)
            val reference = "tool-file:" + files.save(referencePng.inputStream(), "image/png")
            val tools = SystemMediaTools(ToolHttp(files, agnesTestClient()))
            for (model in listOf("agnes-image-2.0-flash", "agnes-image-2.1-flash", "agnes-image-2.5-flash", "custom-image")) {
                for (editing in listOf(false, true)) {
                    server.enqueue(MockResponse().setBody(obj("data" to JsonArray(listOf(obj(
                        "url" to JsonNull, "b64_json" to str(Base64.getEncoder().encodeToString(referencePng))
                    )))).toString()))
                    val options = if (editing) obj("size" to str("2K"), "ratio" to str("16:9"), "extra_body" to obj("response_format" to str("b64_json")))
                        else obj("return_base64" to JsonPrimitive(true))
                    val result = tools.generate("generate_image", agnesTestProvider(server, "/"), model, "draw",
                        imageOptions = options, inputImages = if (editing) listOf(reference, reference) else emptyList())
                    val sent = server.takeRequest()
                    assertEquals("/v1/images/generations", sent.path)
                    assertEquals("Bearer test-secret", sent.getHeader("Authorization"))
                    assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
                    val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                    assertEquals(model, body.text("model"))
                    assertEquals("draw", body.text("prompt"))
                    assertFalse("n" in body)
                    assertFalse("image" in body)
                    assertFalse("response_format" in body)
                    if (editing) {
                        assertEquals("2K", body.text("size"))
                        assertEquals("16:9", body.text("ratio"))
                        assertEquals("b64_json", body.optionAt("extra_body.response_format")!!.jsonPrimitive.content)
                        val inputs = body.optionAt("extra_body.image")!!.jsonArray
                        assertEquals(2, inputs.size)
                        inputs.forEach { assertArrayEquals(referencePng, Base64.getDecoder().decode(it.jsonPrimitive.content.substringAfter(','))) }
                    } else {
                        assertEquals("1024x1024", body.text("size"))
                        assertTrue(body["return_base64"]!!.jsonPrimitive.boolean)
                        assertNull(body.optionAt("extra_body.image"))
                    }
                    val name = result["files"]!!.jsonArray.single().jsonPrimitive.content.removePrefix("tool-file:")
                    assertArrayEquals(referencePng, root.resolve(name).readBytes())
                    assertFalse(result.toString().contains(Base64.getEncoder().encodeToString(referencePng)))
                }
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun imageUrlInputsAndCdnOutputsDoNotForwardAuthorization() = runBlocking {
        val server = MockWebServer().apply { start() }
        val cdn = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-cdn").toFile()
        try {
            server.enqueue(MockResponse().setBody(obj("data" to JsonArray(listOf(obj("url" to str(cdn.url("/output.png").toString()))))).toString()))
            cdn.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(referencePng)))
            SystemMediaTools(ToolHttp(ToolFiles(root), agnesTestClient())).generate("generate_image", agnesTestProvider(server, "/v1/images/generations"),
                "custom-image", "draw", imageOptions = obj("extra_body" to obj("image" to JsonArray(listOf(str("https://example.com/photo.png"))))))
            val sent = server.takeRequest()
            assertEquals("/v1/images/generations", sent.path)
            assertEquals("https://example.com/photo.png", Json.parseToJsonElement(sent.body.readUtf8()).jsonObject.optionAt("extra_body.image")!!.jsonArray.single().jsonPrimitive.content)
            assertNull(cdn.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown(); cdn.shutdown(); root.deleteRecursively() }
    }

    @Test fun invalidImageSettingsAndConflictingInputsFailBeforeNetwork() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-invalid").toFile()
        try {
            val files = ToolFiles(root)
            val reference = "tool-file:" + files.save(referencePng.inputStream(), "image/png")
            val tools = SystemMediaTools(ToolHttp(files, agnesTestClient()))
            val provider = agnesTestProvider(server)
            for (options in listOf(
                obj("response_format" to str("url")), obj("n" to JsonPrimitive(2)), obj("ratio" to str("auto")),
                obj("return_base64" to str("true")), obj("extra_body" to str("invalid")),
                obj("extra_body" to obj("response_format" to str("png"))),
                obj("extra_body" to obj("image" to JsonArray(listOf(str("file:///photo.png")))))
            )) {
                assertTrue(runCatching { tools.generate("generate_image", provider, "custom", "draw", imageOptions = options) }.isFailure)
            }
            assertTrue(runCatching { tools.generate("generate_image", provider, "custom", "draw",
                imageOptions = obj("extra_body" to obj("image" to JsonArray(listOf(str("https://example.com/photo.png"))))), inputImages = listOf(reference)) }.isFailure)
            assertTrue(runCatching { tools.generate("generate_image", provider, "custom", "draw",
                imageOptions = obj("return_base64" to JsonPrimitive(true)), inputImages = listOf(reference)) }.isFailure)
            val large = root.resolve("large.png").apply { java.io.RandomAccessFile(this, "rw").use { it.write(referencePng); it.setLength(16L * 1024 * 1024) } }
            assertTrue(runCatching { agnesImageRequest("custom", "draw", obj(), listOf(MediaInputImage(large, "image/png"))) }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
