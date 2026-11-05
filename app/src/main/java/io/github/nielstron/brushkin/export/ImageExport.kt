package io.github.nielstron.brushkin.export

import android.graphics.Bitmap

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"), JPEG("JPEG", "jpg", "image/jpeg"), WEBP("WebP", "webp", "image/webp"), GIF("GIF", "gif", "image/gif")
}

enum class PngPixelFormat(val label: String) {
    AUTO("Automatic"), RGB8("8 bpc RGB"), RGBA8("8 bpc RGBA"), RGB16("16 bpc RGB"), RGBA16("16 bpc RGBA"),
    GRAY8("8 bpc Grayscale"), GRAYA8("8 bpc Grayscale-alpha"), GRAY16("16 bpc Grayscale"), GRAYA16("16 bpc Grayscale-alpha")
}
enum class JpegSampling(val label: String) { FULL("4:4:4 (best quality)"), HORIZONTAL("4:2:2 horizontal"), VERTICAL("4:2:2 vertical"), BOTH("4:2:0 (smallest file)") }
enum class JpegDct(val label: String) { INTEGER("Integer"), FAST("Fast integer"), FLOAT("Floating point") }
enum class WebpSource(val label: String) { DEFAULT("Default"), PICTURE("Picture"), PHOTO("Photo"), DRAWING("Drawing"), ICON("Icon"), TEXT("Text") }

enum class FrameDisposal(val label: String) { UNSPECIFIED("I don't care"), COMBINE("Cumulative layers (combine)"), REPLACE("One frame per layer (replace)") }

data class ImageExportOptions(
    val format: ImageFormat = ImageFormat.PNG,
    val quality: Int = 85,
    val lossless: Boolean = false,
    val pngFormat: PngPixelFormat = PngPixelFormat.AUTO,
    val compression: Int = 9,
    val interlace: Boolean = false,
    val cmyk: Boolean = false,
    val smoothing: Int = 0,
    val progressive: Boolean = true,
    val arithmetic: Boolean = false,
    val optimize: Boolean = true,
    val restartRows: Int = 0,
    val subsampling: JpegSampling = JpegSampling.BOTH,
    val dct: JpegDct = JpegDct.INTEGER,
    val webpQuality: Int = 90,
    val alphaQuality: Int = 100,
    val webpSource: WebpSource = WebpSource.DEFAULT,
    val sharpYuv: Boolean = false,
    val useOriginalQuality: Boolean = false,
    val saveExif: Boolean = true,
    val saveXmp: Boolean = true,
    val saveIptc: Boolean = true,
    val saveProfile: Boolean = true,
    val saveThumbnail: Boolean = false,
    val saveComment: Boolean = false,
    val comment: String = "",
    val saveBackground: Boolean = false,
    val saveOffset: Boolean = false,
    val saveResolution: Boolean = true,
    val resolution: Int = 72,
    val saveCreationTime: Boolean = false,
    val animation: Boolean = false,
    val loopForever: Boolean = true,
    val minimizeSize: Boolean = true,
    val keyframeDistance: Int = 50,
    val frameDelay: Int = 100,
    val forceFrameDelay: Boolean = false,
    val repeats: Int = 1,
    val disposal: FrameDisposal = FrameDisposal.UNSPECIFIED,
    val forceDisposal: Boolean = false,
) {
    fun toJson() = org.json.JSONObject().apply {
        put("format", format.name)
        put("quality", quality)
        put("lossless", lossless)
        put("pngFormat", pngFormat.name)
        put("compression", compression)
        put("interlace", interlace)
        put("cmyk", cmyk)
        put("smoothing", smoothing)
        put("progressive", progressive)
        put("arithmetic", arithmetic)
        put("optimize", optimize)
        put("restartRows", restartRows)
        put("subsampling", subsampling.name)
        put("dct", dct.name)
        put("webpQuality", webpQuality)
        put("alphaQuality", alphaQuality)
        put("webpSource", webpSource.name)
        put("sharpYuv", sharpYuv)
        put("useOriginalQuality", useOriginalQuality)
        put("saveExif", saveExif)
        put("saveXmp", saveXmp)
        put("saveIptc", saveIptc)
        put("saveProfile", saveProfile)
        put("saveThumbnail", saveThumbnail)
        put("saveComment", saveComment)
        put("comment", comment)
        put("saveBackground", saveBackground)
        put("saveOffset", saveOffset)
        put("saveResolution", saveResolution)
        put("resolution", resolution)
        put("saveCreationTime", saveCreationTime)
        put("animation", animation)
        put("loopForever", loopForever)
        put("minimizeSize", minimizeSize)
        put("keyframeDistance", keyframeDistance)
        put("frameDelay", frameDelay)
        put("forceFrameDelay", forceFrameDelay)
        put("repeats", repeats)
        put("disposal", disposal.name)
        put("forceDisposal", forceDisposal)
    }.toString()

    companion object {
        fun fromJson(text: String): ImageExportOptions {
            val j = org.json.JSONObject(text); val defaults = ImageExportOptions()
            return ImageExportOptions(
                format = if (j.has("format")) ImageFormat.valueOf(j.getString("format")) else defaults.format,
                quality = if (j.has("quality")) j.getInt("quality") else defaults.quality,
                lossless = if (j.has("lossless")) j.getBoolean("lossless") else defaults.lossless,
                pngFormat = if (j.has("pngFormat")) PngPixelFormat.valueOf(j.getString("pngFormat")) else defaults.pngFormat,
                compression = if (j.has("compression")) j.getInt("compression") else defaults.compression,
                interlace = if (j.has("interlace")) j.getBoolean("interlace") else defaults.interlace,
                cmyk = if (j.has("cmyk")) j.getBoolean("cmyk") else defaults.cmyk,
                smoothing = if (j.has("smoothing")) j.getInt("smoothing") else defaults.smoothing,
                progressive = if (j.has("progressive")) j.getBoolean("progressive") else defaults.progressive,
                arithmetic = if (j.has("arithmetic")) j.getBoolean("arithmetic") else defaults.arithmetic,
                optimize = if (j.has("optimize")) j.getBoolean("optimize") else defaults.optimize,
                restartRows = if (j.has("restartRows")) j.getInt("restartRows") else defaults.restartRows,
                subsampling = if (j.has("subsampling")) JpegSampling.valueOf(j.getString("subsampling")) else defaults.subsampling,
                dct = if (j.has("dct")) JpegDct.valueOf(j.getString("dct")) else defaults.dct,
                webpQuality = if (j.has("webpQuality")) j.getInt("webpQuality") else defaults.webpQuality,
                alphaQuality = if (j.has("alphaQuality")) j.getInt("alphaQuality") else defaults.alphaQuality,
                webpSource = if (j.has("webpSource")) WebpSource.valueOf(j.getString("webpSource")) else defaults.webpSource,
                sharpYuv = if (j.has("sharpYuv")) j.getBoolean("sharpYuv") else defaults.sharpYuv,
                useOriginalQuality = if (j.has("useOriginalQuality")) j.getBoolean("useOriginalQuality") else defaults.useOriginalQuality,
                saveExif = if (j.has("saveExif")) j.getBoolean("saveExif") else defaults.saveExif,
                saveXmp = if (j.has("saveXmp")) j.getBoolean("saveXmp") else defaults.saveXmp,
                saveIptc = if (j.has("saveIptc")) j.getBoolean("saveIptc") else defaults.saveIptc,
                saveProfile = if (j.has("saveProfile")) j.getBoolean("saveProfile") else defaults.saveProfile,
                saveThumbnail = if (j.has("saveThumbnail")) j.getBoolean("saveThumbnail") else defaults.saveThumbnail,
                saveComment = if (j.has("saveComment")) j.getBoolean("saveComment") else defaults.saveComment,
                comment = if (j.has("comment")) j.getString("comment") else defaults.comment,
                saveBackground = if (j.has("saveBackground")) j.getBoolean("saveBackground") else defaults.saveBackground,
                saveOffset = if (j.has("saveOffset")) j.getBoolean("saveOffset") else defaults.saveOffset,
                saveResolution = if (j.has("saveResolution")) j.getBoolean("saveResolution") else defaults.saveResolution,
                resolution = if (j.has("resolution")) j.getInt("resolution") else defaults.resolution,
                saveCreationTime = if (j.has("saveCreationTime")) j.getBoolean("saveCreationTime") else defaults.saveCreationTime,
                animation = if (j.has("animation")) j.getBoolean("animation") else defaults.animation,
                loopForever = if (j.has("loopForever")) j.getBoolean("loopForever") else defaults.loopForever,
                minimizeSize = if (j.has("minimizeSize")) j.getBoolean("minimizeSize") else defaults.minimizeSize,
                keyframeDistance = if (j.has("keyframeDistance")) j.getInt("keyframeDistance") else defaults.keyframeDistance,
                frameDelay = if (j.has("frameDelay")) j.getInt("frameDelay") else defaults.frameDelay,
                forceFrameDelay = if (j.has("forceFrameDelay")) j.getBoolean("forceFrameDelay") else defaults.forceFrameDelay,
                repeats = if (j.has("repeats")) j.getInt("repeats") else defaults.repeats,
                disposal = if (j.has("disposal")) FrameDisposal.valueOf(j.getString("disposal")) else defaults.disposal,
                forceDisposal = if (j.has("forceDisposal")) j.getBoolean("forceDisposal") else defaults.forceDisposal,
            )
        }
    }

    fun encode(source: Bitmap, metadata: ExportMetadata = ExportMetadata()): ByteArray {
        val w = source.width; val h = source.height
        val pixels = IntArray(w*h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        return when (format) {
            ImageFormat.PNG -> NativeExport.png(pixels, w, h, pngFormat.ordinal, compression, interlace)
            ImageFormat.JPEG -> NativeExport.jpeg(pixels, w, h, quality, smoothing, progressive, arithmetic, optimize, restartRows, subsampling.ordinal, dct.ordinal, metadata.jpegHeader.takeIf { useOriginalQuality }, cmyk, resolution)
            ImageFormat.GIF -> encodeStaticGif(source, this)
            ImageFormat.WEBP -> NativeExport.webp(pixels, w, h, lossless, webpQuality, alphaQuality, webpSource.ordinal, sharpYuv)
        }
    }
}
