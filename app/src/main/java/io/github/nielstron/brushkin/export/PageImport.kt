package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Files that need choices before they become pixels, as GIMP asks for them: SVG (no pixel size:
 * size and resolution), PDF (size, resolution, pages, how to open them) and multi-page TIFF (pages).
 */
object PageImport {
    /** Without a choice, smaller drawings (icons) are rendered up to this size, since vectors stay sharp. */
    const val MIN_SVG_SIZE = 1024
    /** CSS pixels per inch: an SVG's own size is a physical size at this resolution. */
    const val SVG_DPI = 96
    /** Without a choice, PDF pages are rendered at this resolution. */
    const val PDF_DPI = 150
    /** Opening makes a layer or image per page, up to this many. */
    const val MAX_PAGES = 100
    /** Pages selected when nobody chooses. */
    const val DEFAULT_PAGES = 30

    /** Whether PDF passwords and annotation rendering are available (Android 15). */
    val pdfExtras get() = Build.VERSION.SDK_INT >= 35

    enum class Kind { SVG, PDF, TIFF }

    /** [width]×[height]: SVG pixels at [SVG_DPI], PDF points (1/72 inch) or TIFF pixels. [directory] is TIFF's. */
    data class Page(val width: Float, val height: Float, val directory: Int = 0)

    class Source(val kind: Kind, val data: ByteArray, val pages: List<Page>, val password: String? = null) {
        val width get() = pages[0].width
        val height get() = pages[0].height
        val pageCount get() = pages.size
        internal val markup: String? = if (kind == Kind.SVG) svgText(data) else null
        /** Inches per unit of [width]: SVG pixels are 1/96 inch, PDF points 1/72; TIFF has none here. */
        val inchesPerUnit get() = when (kind) { Kind.SVG -> 1f / SVG_DPI; Kind.PDF -> 1f / 72f; Kind.TIFF -> 0f }
    }

    /**
     * [width]×[height]: the first page in pixels; the other pages are scaled alike. [resolution] is
     * stored with the image. [pages] are 0-based, in file order.
     */
    data class Options(
        val width: Int, val height: Int, val resolution: Int = 72,
        val pages: List<Int> = listOf(0),
        /** Each page as an image (tab) of its own instead of a layer. */
        val asImages: Boolean = false,
        /** The last page as the bottom layer or first image. */
        val reverse: Boolean = false,
        /** Fill transparent areas with white. */
        val background: Boolean = false,
        val annotations: Boolean = true,
    )

    /** A file waiting for the user's choices. */
    class Pending(val source: Source, val name: String, val insert: Boolean)

    /** An encrypted PDF waiting for its password; [wrong] after a wrong one. */
    class Locked(val data: ByteArray, val name: String, val insert: Boolean, val wrong: Boolean)

    /** The PDF is encrypted; ask for its password and probe again. */
    class PasswordRequired(val wrong: Boolean) : Exception(if (wrong) "wrong password" else "the PDF is password-protected")

    fun isPdf(data: ByteArray): Boolean {
        // The header may follow up to 1024 bytes of junk.
        val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }

    fun isTiff(data: ByteArray) = data.size >= 8 && (
        (data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() && (data[2].toInt() == 42 || data[2].toInt() == 43) && data[3].toInt() == 0) ||
            (data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() && data[2].toInt() == 0 && (data[3].toInt() == 42 || data[3].toInt() == 43)))

    /** Uncompresses .svgz; returns the markup when [data] is an SVG document. */
    fun svgText(data: ByteArray): String? {
        val bytes = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte())
            runCatching { GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() } }.getOrNull() ?: return null
        else data
        val head = String(bytes, 0, minOf(bytes.size, 65536), Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        if (!head.startsWith("<") || !head.contains("<svg")) return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Null for anything else (DNG camera raws, though TIFF files, are left to Android).
     * @throws PasswordRequired for an encrypted PDF.
     */
    fun probe(data: ByteArray, password: String? = null): Source? {
        svgText(data)?.let { markup ->
            val (w, h) = svgSize(SVG.getFromString(markup))
            return Source(Kind.SVG, data, listOf(Page(w, h)))
        }
        if (isPdf(data)) return Reader.pdf(data, password) { r ->
            if (r.pageCount == 0) error("the PDF has no pages")
            Source(Kind.PDF, data, (0 until r.pageCount).map { i -> r.openPage(i).use { Page(it.width.toFloat(), it.height.toFloat()) } }, password)
        }
        if (isTiff(data)) {
            val info = NativeDecode.tiffPages(data)
            if (info[0] < 0) return null
            if (info[0] == 0) error("the TIFF has no images")
            return Source(Kind.TIFF, data, (0 until info[0]).map { Page(info[2 + it * 3].toFloat(), info[3 + it * 3].toFloat(), info[1 + it * 3]) })
        }
        return null
    }

    /** Whether to ask before opening: always for SVG and PDF, for TIFF only with several pages. */
    fun asks(s: Source) = s.kind != Kind.TIFF || s.pageCount > 1

    /**
     * What is used when nobody is asked: SVGs at their own physical size, at a resolution that makes
     * them at least [minDim] pixels; PDFs at [PDF_DPI].
     */
    fun defaults(s: Source, insert: Boolean, minDim: Int = MIN_SVG_SIZE): Options {
        val (k, dpi) = when (s.kind) {
            Kind.SVG -> maxOf(1f, minDim / maxOf(s.width, s.height)).let { it to (SVG_DPI * it).roundToInt() }
            Kind.PDF -> PDF_DPI / 72f to PDF_DPI
            Kind.TIFF -> 1f to 72
        }
        val pages = if (insert) listOf(0) else (0 until minOf(s.pageCount, DEFAULT_PAGES)).toList()
        return Options(maxOf(1, (s.width * k).roundToInt()), maxOf(1, (s.height * k).roundToInt()), dpi, pages, background = s.kind == Kind.PDF && !insert)
    }

    internal fun svgSize(svg: SVG): Pair<Float, Float> {
        val box = svg.documentViewBox
        var w = svg.documentWidth; var h = svg.documentHeight
        // Missing (or percentage) sizes come from the viewBox, keeping its aspect ratio.
        if (box != null && box.width() > 0 && box.height() > 0) when {
            w <= 0 && h <= 0 -> { w = box.width(); h = box.height() }
            w <= 0 -> w = h * box.width() / box.height()
            h <= 0 -> h = w * box.height() / box.width()
        }
        return if (w <= 0 || h <= 0) 512f to 512f else w to h
    }

    /** Draws pages of [source]; keep it open while drawing several (the dialog's thumbnails, all chosen pages). */
    class Reader(val source: Source) : Closeable {
        private var file: File? = null
        private var pdf: PdfRenderer? = null

        init {
            if (source.kind == Kind.PDF) {
                val f = File.createTempFile("import", ".pdf")
                try { f.writeBytes(source.data); pdf = open(f, source.password); file = f } catch (t: Throwable) { f.delete(); throw t }
            }
        }

        /**
         * Page [index] stretched to [w]×[h] (SVG and PDF; TIFF pages come at their own size, shrunk
         * by a whole factor if they're larger), white where transparent if [o] asks.
         */
        @Synchronized fun page(index: Int, w: Int, h: Int, o: Options): Bitmap {
            val page = source.pages[index]
            val b = when (source.kind) {
                Kind.SVG -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    // Sized at CSS resolution; the chosen resolution is already in [w]×[h].
                    val svg = SVG.getFromString(source.markup!!).apply { renderDPI = SVG_DPI.toFloat() }
                    val (nw, nh) = svgSize(svg)
                    // Scaling the canvas (not the viewport) also stretches when the proportions changed.
                    val c = Canvas(b).apply { scale(w / nw, h / nh) }
                    svg.renderToCanvas(c, RenderOptions().viewPort(0f, 0f, nw, nh))
                }
                Kind.PDF -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    pdf!!.openPage(index).use { p ->
                        val m = Matrix().apply { setScale(w / page.width, h / page.height) }
                        if (pdfExtras) p.render(b, null, m, renderParams(o.annotations))
                        else p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                Kind.TIFF -> {
                    val px = NativeDecode.tiff(source.data, page.directory, maxOf(w, h), w.toLong() * h)
                    Bitmap.createBitmap(px, 2, px[0], px[0], px[1], Bitmap.Config.ARGB_8888).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true).also { _ -> it.recycle() } }
                }
            }
            if (o.background) {
                val white = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                Canvas(white).drawBitmap(b, 0f, 0f, null); b.recycle()
                return white
            }
            return b
        }

        /** A small preview of page [index] for choosing pages. */
        fun thumbnail(index: Int, size: Int): Bitmap {
            val p = source.pages[index]
            val k = size / maxOf(p.width, p.height)
            val w = maxOf(1, (p.width * k).roundToInt()); val h = maxOf(1, (p.height * k).roundToInt())
            return page(index, w, h, Options(w, h, SVG_DPI, background = source.kind == Kind.PDF))
        }

        override fun close() { pdf?.close(); file?.delete() }

        companion object {
            private fun open(f: File, password: String?): PdfRenderer {
                val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    return if (password != null && pdfExtras) PdfRenderer(fd, android.graphics.pdf.LoadParams.Builder().setPassword(password).build())
                    else PdfRenderer(fd)
                } catch (e: SecurityException) {
                    fd.close()
                    if (!pdfExtras) throw IllegalStateException("the PDF is password-protected, which needs Android 15 to open")
                    throw PasswordRequired(wrong = password != null)
                } catch (t: Throwable) { fd.close(); throw t }
            }

            internal fun <T> pdf(data: ByteArray, password: String?, block: (PdfRenderer) -> T): T {
                val f = File.createTempFile("import", ".pdf")
                try { f.writeBytes(data); return open(f, password).use(block) } finally { f.delete() }
            }

            @androidx.annotation.RequiresApi(35)
            private fun renderParams(annotations: Boolean) = android.graphics.pdf.RenderParams.Builder(android.graphics.pdf.RenderParams.RENDER_MODE_FOR_DISPLAY)
                .setRenderFlags(if (annotations) android.graphics.pdf.RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or android.graphics.pdf.RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS else 0).build()
        }
    }

    /** [dpi]: the resolution actually used, lower than chosen when shrunk to fit in memory. */
    class Result(val scaledDown: Boolean, val dpi: Int)

    /**
     * Draws the chosen pages one at a time into [page] (which owns the bitmap), all at the same
     * scale. Each stays within [maxDim]; all of them together (or each, when they open as separate
     * images) within [maxPixels]. The scale is reduced if needed.
     */
    fun render(s: Source, o: Options, maxDim: Int = 8192, maxPixels: Long = 40_000_000, page: (Int, Bitmap) -> Unit): Result {
        val chosen = o.pages.filter { it in s.pages.indices }.distinct().take(MAX_PAGES).ifEmpty { listOf(0) }
        val sx = o.width / s.width; val sy = o.height / s.height
        val budget = if (o.asImages) maxPixels else maxPixels / chosen.size
        var cap = 1f
        for (i in chosen) {
            val p = s.pages[i]
            val w = p.width * sx; val h = p.height * sy
            cap = minOf(cap, maxDim / maxOf(w, h), sqrt(budget / (w.toDouble() * h)).toFloat())
        }
        Reader(s).use { r ->
            for (i in chosen) {
                val p = s.pages[i]
                page(i, r.page(i, maxOf(1, (p.width * sx * cap).roundToInt()), maxOf(1, (p.height * sy * cap).roundToInt()), o))
            }
        }
        return Result(cap < 1f, (o.resolution * cap).roundToInt())
    }
}
