package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** SVG and PDF have no pixel size of their own, so they are rasterized at a size the user picks. */
object VectorImport {
    /** Without a choice, smaller drawings (icons) are rendered up to this size, since vectors stay sharp. */
    const val MIN_SVG_SIZE = 1024
    /** Without a choice, PDF pages are rendered at this resolution. */
    const val PDF_DPI = 150
    /** Opening a PDF makes a layer per page, up to this many. */
    const val MAX_PDF_PAGES = 30

    enum class Kind { SVG, PDF }

    /**
     * A vector file and its natural size: CSS pixels for SVG, points (1/72 inch) of the first page
     * for PDF.
     */
    class Source(val kind: Kind, val data: ByteArray, val markup: String?, val width: Float, val height: Float, val pageCount: Int)

    /** [width]×[height] is the size of the first page (it sets the scale for all pages). [pages] are 0-based. */
    data class Options(val width: Int, val height: Int, val pages: IntRange = 0..0, val background: Boolean = false)

    /** A file waiting for the user to choose how to render it. */
    class Pending(val source: Source, val name: String, val insert: Boolean)

    fun isPdf(data: ByteArray): Boolean {
        // The header may follow up to 1024 bytes of junk.
        val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }

    /** Uncompresses .svgz; returns the markup when [data] is an SVG document. */
    fun svgText(data: ByteArray): String? {
        val bytes = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte())
            runCatching { GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() } }.getOrNull() ?: return null
        else data
        val head = String(bytes, 0, minOf(bytes.size, 65536), Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        if (!head.startsWith("<") || !head.contains("<svg")) return null
        return String(bytes, Charsets.UTF_8)
    }

    /** Null for anything that isn't SVG or PDF. */
    fun probe(data: ByteArray): Source? {
        svgText(data)?.let { markup ->
            val (w, h) = svgSize(SVG.getFromString(markup))
            return Source(Kind.SVG, data, markup, w, h, 1)
        }
        if (!isPdf(data)) return null
        return pdf(data) { r ->
            if (r.pageCount == 0) error("the PDF has no pages")
            r.openPage(0).use { Source(Kind.PDF, data, null, it.width.toFloat(), it.height.toFloat(), r.pageCount) }
        }
    }

    /** What is used when nobody is asked: at least [minDim] for SVGs, [PDF_DPI] for PDFs. */
    fun defaults(s: Source, insert: Boolean, minDim: Int = MIN_SVG_SIZE): Options {
        val k = when (s.kind) {
            Kind.SVG -> maxOf(1f, minDim / maxOf(s.width, s.height))
            Kind.PDF -> PDF_DPI / 72f
        }
        val pages = if (insert || s.kind == Kind.SVG) 0..0 else 0 until minOf(s.pageCount, MAX_PDF_PAGES)
        return Options(maxOf(1, (s.width * k).roundToInt()), maxOf(1, (s.height * k).roundToInt()), pages, background = s.kind == Kind.PDF && !insert)
    }

    private fun svgSize(svg: SVG): Pair<Float, Float> {
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

    /** PdfRenderer needs a seekable file. */
    private inline fun <T> pdf(data: ByteArray, block: (PdfRenderer) -> T): T {
        val file = File.createTempFile("import", ".pdf")
        try {
            file.writeBytes(data)
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: SecurityException) {
                fd.close(); throw IllegalStateException("the PDF is password-protected")
            }
            return renderer.use(block)
        } finally { file.delete() }
    }

    /** [dpi]: pixels per inch actually used (PDF points and SVG pixels both count as 1/72 inch at 72). */
    class Result(val width: Int, val height: Int, val pages: Int, val scaledDown: Boolean, val dpi: Int)

    /**
     * Renders the chosen pages, all at the same scale, each on a canvas as large as the largest of
     * them, and hands them to [page] one at a time (it owns the bitmap). Each stays within [maxDim]
     * and all of them together within [maxPixels]; the scale is reduced if needed.
     */
    fun render(s: Source, o: Options, maxDim: Int = 8192, maxPixels: Long = 40_000_000, page: (Int, Bitmap) -> Unit): Result {
        fun fit(w: Float, h: Float, n: Int): Float =
            minOf(1f, maxDim / maxOf(w, h), sqrt(maxPixels / (w.toDouble() * h * n)).toFloat())
        fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { if (o.background) eraseColor(Color.WHITE) }
        if (s.kind == Kind.SVG) {
            val svg = SVG.getFromString(s.markup!!)
            // Without a viewBox the drawing would not scale with the viewport.
            if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, s.width, s.height)
            svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
            val cap = fit(o.width.toFloat(), o.height.toFloat(), 1)
            val w = maxOf(1, (o.width * cap).roundToInt()); val h = maxOf(1, (o.height * cap).roundToInt())
            val b = bitmap(w, h)
            svg.renderToCanvas(Canvas(b), RenderOptions().viewPort(0f, 0f, w.toFloat(), h.toFloat()))
            page(0, b)
            return Result(w, h, 1, cap < 1f, (w / s.width * 72f).roundToInt())
        }
        return pdf(s.data) { r ->
            val first = o.pages.first.coerceIn(0, r.pageCount - 1)
            val pages = first..minOf(o.pages.last, r.pageCount - 1, first + MAX_PDF_PAGES - 1)
            val k0 = o.width / s.width
            var pw = 0f; var ph = 0f
            for (i in pages) r.openPage(i).use { pw = maxOf(pw, it.width.toFloat()); ph = maxOf(ph, it.height.toFloat()) }
            val cap = fit(pw * k0, ph * k0, pages.count())
            val k = k0 * cap
            val w = maxOf(1, (pw * k).roundToInt()); val h = maxOf(1, (ph * k).roundToInt())
            for (i in pages) {
                val b = bitmap(w, h)
                try { r.openPage(i).use { it.render(b, null, Matrix().apply { setScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
                catch (t: Throwable) { b.recycle(); throw t }
                page(i, b)
            }
            Result(w, h, pages.count(), cap < 1f, (k * 72f).roundToInt())
        }
    }
}
