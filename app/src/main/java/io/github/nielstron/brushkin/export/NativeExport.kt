package io.github.nielstron.brushkin.export

/** Native entry points expose the same codec settings used by GIMP's file plug-ins. */
object NativeExport {
    init { System.loadLibrary("brushkin_export") }
    external fun jpeg(pixels: IntArray, width: Int, height: Int, quality: Int, smoothing: Int,
        progressive: Boolean, arithmetic: Boolean, optimize: Boolean, restart: Int, sampling: Int, dct: Int, original: ByteArray?, cmyk: Boolean, resolution: Int): ByteArray
    external fun png(pixels: IntArray, width: Int, height: Int, format: Int, compression: Int, interlace: Boolean): ByteArray
    external fun gifStart(width: Int, height: Int, repeats: Int, comment: String): Long
    external fun gifAdd(handle: Long, pixels: IntArray, width: Int, height: Int, delay: Int, disposal: Int, interlace: Boolean)
    external fun gifFinish(handle: Long): ByteArray
    external fun gifClose(handle: Long)
    external fun animationStart(width: Int, height: Int, loop: Boolean, minimize: Boolean, distance: Int): Long
    external fun animationAdd(handle: Long, pixels: IntArray, width: Int, height: Int, timestamp: Int, lossless: Boolean, quality: Int, alphaQuality: Int, preset: Int, sharp: Boolean)
    external fun animationFinish(handle: Long, timestamp: Int): ByteArray
    external fun animationClose(handle: Long)
    external fun webpMetadata(encoded: ByteArray, exif: ByteArray?, xmp: ByteArray?, profile: ByteArray?): ByteArray
    external fun webp(pixels: IntArray, width: Int, height: Int, lossless: Boolean, quality: Int, alphaQuality: Int, preset: Int, sharp: Boolean): ByteArray
}
