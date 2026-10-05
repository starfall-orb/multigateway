package org.starfall.multigateway.data.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.Base64

// The standalone server's /health advertises max_upload_mb=0.35 and
// max_dimension=1280. Keep the original tool file intact for subsequent tools.
internal const val H3_UPLOAD_BYTES = (0.35 * 1024 * 1024).toInt()

internal fun h3InputImage(image: MediaInputImage): String {
    if (image.file.length() <= H3_UPLOAD_BYTES) {
        return "data:${image.mimeType};base64," + Base64.getEncoder().encodeToString(image.file.readBytes())
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(image.file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "H3 source image could not be decoded." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
    val decoded = BitmapFactory.decodeFile(image.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("H3 source image could not be decoded.")
    var bitmap: Bitmap? = null
    try {
        val scale = minOf(1f, 1280f / maxOf(decoded.width, decoded.height))
        bitmap = Bitmap.createBitmap((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), null)
        }
        decoded.recycle()
        repeat(10) {
            for (quality in listOf(80, 60, 40, 30)) {
                val output = ByteArrayOutputStream()
                check(bitmap!!.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "H3 source image could not be compressed." }
                val bytes = output.toByteArray()
                if (bytes.size <= H3_UPLOAD_BYTES) return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
            }
            val smaller = Bitmap.createScaledBitmap(bitmap!!, (bitmap!!.width * 0.8f).toInt().coerceAtLeast(1), (bitmap!!.height * 0.8f).toInt().coerceAtLeast(1), true)
            if (smaller !== bitmap) bitmap!!.recycle()
            bitmap = smaller
        }
        error("H3 source image could not be reduced to the upload-size limit.")
    } finally {
        if (!decoded.isRecycled) decoded.recycle()
        bitmap?.recycle()
    }
}
