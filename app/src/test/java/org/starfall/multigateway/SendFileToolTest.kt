package org.starfall.multigateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.tools.*
import java.nio.file.Files

class SendFileToolTest {
    @Test fun sendsExistingOtherFileWithoutCopyingAndRejectsMissingFiles() = runBlocking {
        val directory = Files.createTempDirectory("send-file").toFile()
        try {
            val files = ToolFiles(directory)
            val pdf = files.save("%PDF-1.4 test".byteInputStream(), "application/pdf")
            val sender = SendFileTool(ToolHttp(files))
            val result = sender.execute(obj("type" to str("other"), "uri" to str("tool-file:$pdf")))
            assertTrue(pdf.endsWith(".pdf"))
            assertEquals("tool-file:$pdf", result.text("uri"))
            assertEquals(1, files.list().size)
            assertTrue(runCatching { sender.execute(obj("type" to str("other"), "uri" to str("tool-file:missing.pdf"))) }.isFailure)
            assertTrue(runCatching { sender.execute(obj("type" to str("invalid"), "uri" to str("tool-file:$pdf"))) }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun downloadsUrlOnceWithoutProviderCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        val directory = Files.createTempDirectory("send-file-http").toFile()
        val bytes = ByteArray(32).also { it[0] = 0x89.toByte(); it[1] = 0x50 }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertNull(request.getHeader("Authorization"))
                return MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes))
            }
        }
        try {
            val files = ToolFiles(directory)
            val sender = SendFileTool(ToolHttp(files))
            val args = obj("type" to str("image"), "uri" to str(server.url("/image").toString()))
            val first = sender.execute(args)
            assertEquals(first, sender.execute(args))
            assertEquals(1, server.requestCount)
            assertArrayEquals(bytes, files.resolve(first.text("uri").removePrefix("tool-file:"))!!.readBytes())
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
