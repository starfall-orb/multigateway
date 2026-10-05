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
import java.util.Base64

class VideoProtocolTest {
    @Test fun protocolsAreDetectedAutomaticallyByModelOrApiHost() {
        val proxy = LlmProviderInfo("p", "proxy", ProviderType.OPENAI, baseUrl = "https://proxy.example/v1")
        assertEquals(VideoApi.H3, videoApi(proxy, "h3-10s"))
        assertEquals(VideoApi.AGNES, videoApi(proxy, "agnes-video-2.5-flash"))
        assertEquals(VideoApi.H3, videoApi(proxy.copy(baseUrl = "https://h3video.cc.cd/v1"), "custom"))
        assertEquals(VideoApi.AGNES, videoApi(proxy.copy(baseUrl = "https://apihub.agnes-ai.com/v1"), "custom"))
        assertEquals(VideoApi.OPENAI, videoApi(proxy, "MiniMax-H3"))
        assertEquals(VideoApi.OPENAI, videoApi(proxy, "sora-2"))
    }

    private fun provider(server: MockWebServer, path: String = "/v1") = LlmProviderInfo(
        "p", "video", ProviderType.OPENAI, baseUrl = server.url(path).toString(),
        auth = Authorization(value = "test-secret")
    )
    private fun response(body: String) = MockResponse().setBody(body)
    private fun video() = MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(
        ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }))

    @Test fun standaloneH3UsesJsonImageAndDurationMatchingEachModel() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("h3-video").toFile()
        try {
            val files = ToolFiles(root)
            val input = "tool-file:" + files.save(referencePng.inputStream(), "image/png")
            for (duration in listOf(6, 10, 15)) {
                server.enqueue(response("""{"id":"h3_job","status":"completed","content_url":"https://untrusted.example/secret"}"""))
                server.enqueue(video())
                val result = SystemMediaTools(ToolHttp(files)).generate("generate_video", provider(server, "/"),
                    "h3-${duration}s", "animate", inputImage = input)
                val sent = server.takeRequest()
                assertEquals("/v1/videos", sent.path)
                assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
                val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals(duration, body["seconds"]!!.jsonPrimitive.int)
                assertEquals("9:16", body.text("aspect_ratio"))
                assertEquals("h3-${duration}s", body.text("model"))
                assertArrayEquals(referencePng, Base64.getDecoder().decode(body.text("image").substringAfter(',')))
                assertTrue(body.text("image").startsWith("data:image/png;base64,"))
                val content = server.takeRequest()
                assertEquals("/v1/videos/h3_job/content", content.path)
                assertEquals("Bearer test-secret", content.getHeader("Authorization"))
                assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".mp4"))
            }
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun h3PollsQueuedAndRunningTasksAndKeepsProxyPrefix() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("h3-poll").toFile()
        try {
            val http = ToolHttp(ToolFiles(root))
            server.enqueue(response("""{"id":"job","status":"queued"}"""))
            server.enqueue(response("""{"id":"job","status":"running"}"""))
            server.enqueue(response("""{"id":"job","status":"completed"}"""))
            server.enqueue(video())
            VideoGeneration(http, 0).generate(provider(server, "/proxy/v1"), "h3-10s", "move", null,
                obj("cf_token" to str("human-token")), "https://example.com/photo.png")
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("https://example.com/photo.png", body.text("image"))
            assertEquals("human-token", body.text("cf_token"))
            assertEquals("/proxy/v1/videos", request.path)
            repeat(2) { assertEquals("/proxy/v1/videos/job", server.takeRequest().path) }
            assertEquals("/proxy/v1/videos/job/content", server.takeRequest().path)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun agnesUsesRequiredModeAndVideoIdInsteadOfTaskId() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("agnes-poll").toFile()
        try {
            for (model in listOf("agnes-video-2.5", "agnes-video-2.5-flash")) {
                server.enqueue(response("""{"id":"wrong_task","task_id":"also_wrong","video_id":"video_right","status":"queued"}"""))
                server.enqueue(response("""{"status":"in_progress","error":null}"""))
                server.enqueue(response("""{"status":"completed","metadata":{"url":"https://cdn.example/video.mp4"},"error":null}"""))
                val result = VideoGeneration(ToolHttp(ToolFiles(root)), 0).generate(provider(server, "/proxy/v1"), model, "move", null, obj(), null)
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
            val result = SystemMediaTools(ToolHttp(ToolFiles(root))).generate("generate_video", provider(server),
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

    @Test fun missingH3ImageAndUnsupportedAgnesUploadFailBeforeCreatingTask() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("video-input-errors").toFile()
        try {
            val files = ToolFiles(root)
            val generation = VideoGeneration(ToolHttp(files), 0)
            val p = provider(server)
            val image = resolveInputImage("tool-file:" + files.save(referencePng.inputStream()), files)
            assertTrue(runCatching { generation.generate(p, "h3-6s", "move", null, obj(), null) }.exceptionOrNull()!!.message!!.contains("requires a source image"))
            assertTrue(runCatching { generation.generate(p, "agnes-video-2.5", "move", image, obj(), null) }.exceptionOrNull()!!.message!!.contains("public image URLs"))
            assertTrue(runCatching { generation.generate(p, "agnes-video-v2.0", "move", null, obj(), null) }.exceptionOrNull()!!.message!!.contains("retired"))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun failedCancelledMissingIdsAndTimeoutNeverDownloadContent() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("video-errors").toFile()
        try {
            val generation = VideoGeneration(ToolHttp(ToolFiles(root)), 0, 100)
            for (body in listOf("""{"id":"job","status":"failed"}""", """{"id":"job","status":"cancelled"}""",
                """{"id":"../escape","status":"completed"}""", """{"status":"queued"}""")) {
                server.enqueue(response(body))
                assertTrue(runCatching { generation.generate(provider(server), "h3-6s", "move", null, obj(), "https://example.com/image.png") }.isFailure)
                assertEquals("/v1/videos", server.takeRequest().path)
            }
            server.enqueue(response("""{"video_id":"video_1","status":"queued"}"""))
            // No poll response: cancellation must interrupt the in-flight request.
            assertTrue(runCatching { generation.generate(provider(server), "agnes-video-2.5", "move", null, obj(), null) }.exceptionOrNull() is TimeoutCancellationException)
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
            obj("mode" to str("img2video")), obj("first_frame" to str("file:///photo.png")),
            obj("mode" to str("text"), "images" to images),
            obj("images" to JsonArray(List(6) { str("https://example.com/image.png") }))
        ).forEach { assertTrue(it.toString(), runCatching { validateVideoOptions(p, "agnes-video-2.5-flash", it) }.isFailure) }
        assertNotNull(mediaToolSchema("generate_video")["properties"]!!.jsonObject["input_image_url"])
        listOf(JsonNull, JsonPrimitive(7), str(""), str("file:///photo.png"), str("https://user:pass@example.com/photo.png")).forEach {
            assertTrue(runCatching { mediaInputImageUrlArgument(obj("input_image_url" to it)) }.isFailure)
        }
    }

    @Test fun optionsRoundTripAndRemainSeparatePerModelWithoutBreakingOldSettings() {
        val old = Json.decodeFromString<SystemToolConfig>("""{"providerId":"p","modelId":"h3-6s","imageOptionsByModel":{}}""")
        assertEquals(obj(), old.videoOptions)
        val saved = old.withVideoOptions(obj("image_url" to str("https://example.com/a.png")))
            .copy(modelId = "agnes-video-2.5").withVideoOptions(obj("seconds" to JsonPrimitive(8)))
        val restored = Json.decodeFromString<SystemToolConfig>(Json.encodeToString(SystemToolConfig.serializer(), saved))
        assertEquals(8, restored.videoOptions["seconds"]!!.jsonPrimitive.int)
        assertEquals("https://example.com/a.png", restored.copy(modelId = "h3-6s").videoOptions.text("image_url"))
        assertEquals(obj(), restored.copy(providerId = "other").videoOptions)
    }

    @Test fun genericVideoGatewaysCanStillReturnNestedMediaWithoutJobId() = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("generic-video").toFile()
        try {
            server.enqueue(response(obj("status" to str("completed"), "data" to JsonArray(listOf(obj("url" to str(server.url("/video.mp4").toString()))))).toString()))
            server.enqueue(video())
            val result = SystemMediaTools(ToolHttp(ToolFiles(root))).generate("generate_video", provider(server), "generic-video", "move")
            assertEquals("/v1/videos", server.takeRequest().path)
            assertEquals("/video.mp4", server.takeRequest().path)
            assertTrue(result["files"]!!.jsonArray.single().jsonPrimitive.content.endsWith(".mp4"))
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
