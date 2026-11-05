package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import io.github.nielstron.brushkin.engine.Ora
import kotlin.math.roundToInt

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"), JPEG("JPEG", "jpg", "image/jpeg"), WEBP("WebP", "webp", "image/webp")
}

data class ImageExportOptions(
    val format: ImageFormat = ImageFormat.PNG,
    val quality: Int = 92,
    val lossless: Boolean = true,
    val scale: Int = 100,
) {
    fun dimensions(width: Int, height: Int) =
        (width * scale / 100f).roundToInt().coerceAtLeast(1) to (height * scale / 100f).roundToInt().coerceAtLeast(1)

    fun encode(source: Bitmap): ByteArray {
        val (w, h) = dimensions(source.width, source.height)
        val resized = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        return try { Ora.encode(resized, format.extension, quality, lossless) }
        finally { if (resized !== source) resized.recycle() }
    }
}
