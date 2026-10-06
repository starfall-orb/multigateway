package org.starfall.multigateway

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.*
import java.nio.file.Files

class VideoProtocolTest {
    @Test fun protocolsAreDetectedByApiHost() {
        val proxy = LlmProviderInfo("p", "proxy", ProviderType.OPENAI, baseUrl = "https://proxy.example/v1")
        assertEquals(VideoApi.OPENAI, videoApi(proxy))
        assertEquals(VideoApi.OPENAI, videoApi(proxy.copy(baseUrl = "https://h3video.cc.cd/v1")))
        assertEquals(VideoApi.AGNES, videoApi(proxy.copy(baseUrl = "https://apihub.agnes-ai.com/v1")))
    }

    private fun provider(server: MockWebServer, path: String = "/v1") = LlmProviderInfo(
        "p", "video", ProviderType.OPENAI, baseUrl = server.url(path).toString(),
        auth = Authorization(value = "test-secret")
    )
    private fun response(body: String) = MockResponse().setBody(body)
    private fun video() = MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(
        ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }))

    @Test fun modelNamesDoNotEnableCustomProtocolsOrSettings() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("generic-video-models").toFile()
        try {
            val generation = VideoGeneration(ToolHttp(ToolFiles(root), agnesTestClient()), 0)
            for (model in listOf("h3-6s", "h3-10s", "h3-15s", "agnes-video-2.5")) {
                assertTrue(videoOptionFields(provider(server), model).isEmpty())
                assertTrue(runCatching { validateVideoOptions(provider(server), model, obj("cf_token" to str("token"))) }.isFailure)
                assertTrue(runCatching { validateVideoOptions(provider(server), model, obj("image_url" to str("https://example.com/image.png"))) }.isFailure)
                server.enqueue(response("""{"id":"job","status":"completed"}"""))
                server.enqueue(video())
                generation.generate(provider(server, "/"), model, "move", null, obj(), null)
                val request = server.takeRequest()
                assertEquals("/videos", request.path)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
                val body = request.body.readUtf8()
                assertTrue(body.contains(model))
                assertFalse(body.contains("aspect_ratio"))
                assertFalse(body.contains("seconds"))
                assertEquals("/videos/job/content", server.takeRequest().path)
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun agnesUsesRequiredModeAndVideoIdInsteadOfTaskId() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-poll").toFile()
        try {
            for (model in listOf("agnes-video-2.5", "agnes-video-2.5-flash", "custom-video")) {
                server.enqueue(response("""{"id":"wrong_task","task_id":"also_wrong","video_id":"video_right","status":"queued"}"""))
                server.enqueue(response("""{"status":"in_progress","error":null}"""))
                server.enqueue(response("""{"status":"completed","metadata":{"url":"https://cdn.example/video.mp4"},"error":null}"""))
                val result = VideoGeneration(ToolHttp(ToolFiles(root), agnesTestClient()), 0).generate(agnesTestProvider(server, "/proxy/v1"), model, "move", null, obj(), null)
                val sent = server.takeRequest()
                assertEquals("/proxy/v1/videos", sent.path)
                val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals("text", body.text("mode"))
                assertEquals("5", body.text("seconds"))
                assertTrue(body["seconds"]!!.jsonPrimitive.isString)
                assertEquals("720P", body.text("size"))
                assertFalse(body.containsKey("num_frames"))
                repeat(2) {
                    val poll = server.takeRequest()
                    assertEquals("/proxy/agnesapi?video_id=video_right&model_name=$model", poll.path)
                    assertEquals("Bearer test-secret", poll.getHeader("Authorization"))
                }
                assertEquals("https://cdn.example/video.mp4", result.text("url"))
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun agnesDownloadsCompletedUrlWithoutSendingCredentialsToCdn() = runBlocking {
        val server = MockWebServer().apply { start() }
        val cdn = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-download").toFile()
        try {
            server.enqueue(response(obj("status" to str("completed"), "url" to str(cdn.url("/video.mp4").toString())).toString()))
            cdn.enqueue(video())
            val result = SystemMediaTools(ToolHttp(ToolFiles(root), agnesTestClient())).generate("generate_video", agnesTestProvider(server),
                "agnes-video-2.5", "move", videoOptions = obj("seconds" to JsonPrimitive(8), "size" to str("1080P")),
                inputImageUrl = "https://example.com/frame.png")
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("keyframe", sent.text("mode"))
            assertEquals("https://example.com/frame.png", sent.text("first_frame"))
            assertEquals("8", sent.text("seconds"))
            assertEquals("1080P", sent.text("size"))
            assertNull(cdn.takeRequest().getHeader("Authorization"))
            assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".mp4"))
        } finally { server.shutdown(); cdn.shutdown(); root.deleteRecursively() }
    }

    @Test fun unsupportedAgnesUploadFailsBeforeCreatingTask() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("video-input-errors").toFile()
        try {
            val files = ToolFiles(root)
            val generation = VideoGeneration(ToolHttp(files, agnesTestClient()), 0)
            val p = agnesTestProvider(server)
            val image = resolveInputImage("tool-file:" + files.save(referencePng.inputStream()), files)
            assertTrue(runCatching { generation.generate(p, "agnes-video-2.5", "move", image, obj(), null) }.exceptionOrNull()!!.message!!.contains("public image URLs"))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun failedCancelledMissingIdsAndTimeoutNeverDownloadContent() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("video-errors").toFile()
        try {
            val generation = VideoGeneration(ToolHttp(ToolFiles(root), agnesTestClient()), 0, 100)
            for (body in listOf("""{"id":"job","status":"failed"}""", """{"id":"job","status":"cancelled"}""",
                """{"id":"../escape","status":"completed"}""", """{"status":"queued"}""")) {
                server.enqueue(response(body))
                assertTrue(runCatching { generation.generate(provider(server), "sora-2", "move", null, obj(), null) }.isFailure)
                assertEquals("/v1/videos", server.takeRequest().path)
            }
            server.enqueue(response("""{"video_id":"video_1","status":"queued"}"""))
            // No poll response: cancellation must interrupt the in-flight request.
            assertTrue(runCatching { generation.generate(agnesTestProvider(server), "agnes-video-2.5", "move", null, obj(), null) }.exceptionOrNull() is TimeoutCancellationException)
            assertEquals("/v1/videos", server.takeRequest().path)
            assertEquals("/agnesapi?video_id=video_1&model_name=agnes-video-2.5", server.takeRequest().path)
            assertEquals(6, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun referenceModesAndFlashLimitsAreValidated() {
        val p = LlmProviderInfo("p", "Agnes", ProviderType.OPENAI, baseUrl = "https://apihub.agnes-ai.com/v1")
        val images = JsonArray(listOf(str("https://example.com/image.png")))
        val options = obj("images" to images, "seconds" to str("8"))
        validateVideoOptions(p, "agnes-video-2.5-flash", options)
        assertEquals("reference", agnesVideoRequest("agnes-video-2.5-flash", "move", options, null).text("mode"))
        listOf(
            obj("size" to str("1080P")), obj("seconds" to JsonPrimitive(13)), obj("num_frames" to JsonPrimitive(121)),
            obj("mode" to str("img2video")), obj("mode" to JsonNull), obj("mode" to str("")), obj("first_frame" to str("file:///photo.png")),
            obj("mode" to str("text"), "images" to images),
            obj("images" to JsonArray(List(6) { str("https://example.com/image.png") }))
        ).forEach { assertTrue(it.toString(), runCatching { validateVideoOptions(p, "agnes-video-2.5-flash", it) }.isFailure) }
        assertNotNull(mediaToolSchema("generate_video")["properties"]!!.jsonObject["input_image_url"])
        listOf(JsonNull, JsonPrimitive(7), str(""), str("file:///photo.png"), str("https://user:pass@example.com/photo.png")).forEach {
            assertTrue(runCatching { mediaInputImageUrlArgument(obj("input_image_url" to it)) }.isFailure)
        }
    }

    @Test fun optionsRoundTripAndRemainSeparatePerModelWithoutBreakingOldSettings() {
        val old = Json.decodeFromString<SystemToolConfig>("""{"providerId":"p","modelId":"agnes-video-2.5-flash","imageOptionsByModel":{}}""")
        assertEquals(obj(), old.videoOptions)
        val saved = old.withVideoOptions(obj("first_frame" to str("https://example.com/a.png")))
            .copy(modelId = "agnes-video-2.5").withVideoOptions(obj("seconds" to JsonPrimitive(8)))
        val restored = Json.decodeFromString<SystemToolConfig>(Json.encodeToString(SystemToolConfig.serializer(), saved))
        assertEquals(8, restored.videoOptions["seconds"]!!.jsonPrimitive.int)
        assertEquals("https://example.com/a.png", restored.copy(modelId = "agnes-video-2.5-flash").videoOptions.text("first_frame"))
        assertEquals(obj(), restored.copy(providerId = "other").videoOptions)
    }

    @Test fun genericVideoGatewaysCanStillReturnNestedMediaWithoutJobId() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("generic-video").toFile()
        try {
            server.enqueue(response(obj("status" to str("completed"), "data" to JsonArray(listOf(obj("url" to str(server.url("/video.mp4").toString()))))).toString()))
            server.enqueue(video())
            val result = SystemMediaTools(ToolHttp(ToolFiles(root), agnesTestClient())).generate("generate_video", provider(server), "generic-video", "move")
            assertEquals("/v1/videos", server.takeRequest().path)
            assertEquals("/video.mp4", server.takeRequest().path)
            assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".mp4"))
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
