package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import io.github.nielstron.brushkin.engine.Ora

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"), JPEG("JPEG", "jpg", "image/jpeg"), WEBP("WebP", "webp", "image/webp")
}

data class ImageExportOptions(
    val format: ImageFormat = ImageFormat.PNG,
    val quality: Int = 92,
    val lossless: Boolean = true,
) {
    fun encode(source: Bitmap): ByteArray = Ora.encode(source, format.extension, quality, lossless)
}
