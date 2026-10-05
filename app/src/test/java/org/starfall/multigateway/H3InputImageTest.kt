package org.starfall.multigateway

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.multigateway.data.tools.*
import java.nio.file.Files
import java.util.Base64
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class H3InputImageTest {
    @Test fun largeImagesAreCompressedWithoutModifyingOriginalToolFile() {
        val file = Files.createTempFile("h3-source", ".png").toFile()
        val bitmap = Bitmap.createBitmap(1800, 2200, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(42)
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or (0xff shl 24) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val originalLength = file.length()
            val originalHash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            assertTrue(originalLength > H3_UPLOAD_BYTES)
            val data = h3InputImage(MediaInputImage(file, "image/png"))
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            val compressed = Base64.getDecoder().decode(data.substringAfter(','))
            assertTrue(compressed.size <= H3_UPLOAD_BYTES)
            val result = BitmapFactory.decodeByteArray(compressed, 0, compressed.size)
            assertTrue(result.width <= 1280 && result.height <= 1280)
            assertEquals(1800f / 2200, result.width.toFloat() / result.height, 0.01f)
            result.recycle()
            assertEquals(originalLength, file.length())
            assertArrayEquals(originalHash, java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { bitmap.recycle(); file.delete() }
    }
}
