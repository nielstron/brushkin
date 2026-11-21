package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PageImportTest {
    private val square = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 4 2"><rect width="2" height="2" fill="#ff0000"/></svg>"""
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    private fun near(expected: Int, actual: Int, tolerance: Int = 3) =
        listOf(24, 16, 8, 0).all { abs((expected ushr it and 255) - (actual ushr it and 255)) <= tolerance }

    @Test fun smallSvgRendersSharpAtMinimumSize() {
        val r = ImageImport.firstFrame(square.toByteArray())
        assertEquals(PageImport.MIN_SVG_SIZE, r.bitmap.width); assertEquals(PageImport.MIN_SVG_SIZE / 2, r.bitmap.height)
        assertEquals(Color.RED, r.bitmap.getPixel(10, 10)); assertEquals(0, Color.alpha(r.bitmap.getPixel(r.bitmap.width - 10, 10)))
        r.bitmap.recycle()
    }

    @Test fun svgWithoutViewBoxScalesAndSvgzOpens() {
        val noBox = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="#00ff00"/></svg>"""
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(noBox.toByteArray()) } }.toByteArray()
        val stack = ImageImport.open(gz, "icon.svgz").stack
        assertEquals(PageImport.MIN_SVG_SIZE, stack.width)
        val px = stack.layers.single().pixels.toBitmap()
        assertEquals(Color.GREEN, px.getPixel(stack.width - 5, stack.height - 5)); px.recycle()
    }

    @Test fun svgObeysSizeLimit() {
        val r = ImageImport.firstFrame(square.toByteArray(), maxDim = 300, vectorSize = 4000)
        assertEquals(300, r.bitmap.width); r.bitmap.recycle()
    }

    @Test fun svgStretchesWhenProportionsChange() {
        val s = PageImport.probe(square.toByteArray())!!
        assertEquals(40f, s.width); assertEquals(20f, s.height)
        // Twice as tall as it should be: the red left half still fills the full height.
        val r = ImageImport.page(s, PageImport.Options(40, 40, 96))
        assertEquals(40, r.bitmap.height); assertEquals(Color.RED, r.bitmap.getPixel(5, 35)); assertEquals(0, Color.alpha(r.bitmap.getPixel(35, 35)))
        r.bitmap.recycle()
    }

    @Test fun svgPhysicalSizeAtChosenResolution() {
        val inch = """<svg xmlns="http://www.w3.org/2000/svg" width="1in" height="0.5in"><rect width="100%" height="100%" fill="#0000ff"/></svg>"""
        val s = PageImport.probe(inch.toByteArray())!!
        assertEquals(96f, s.width, 0.5f); assertEquals(1f, s.width * s.inchesPerUnit, 0.01f)
        // One inch at 300 ppi.
        val doc = ImageImport.openPages(s, PageImport.Options(300, 150, 300), "inch.svg").single()
        assertEquals(300, doc.stack.width); assertEquals(300, doc.stack.exportMetadata.resolution)
        val b = doc.stack.layers.single().pixels.toBitmap()
        assertEquals(Color.BLUE, b.getPixel(295, 145)); b.recycle()
    }

    @Test fun smallSvgDefaultsKeepPhysicalSize() {
        val o = PageImport.defaults(PageImport.probe(square.toByteArray())!!, insert = false)
        // 40 px wide is drawn 1024 px wide by raising the resolution, not by stretching.
        assertEquals(1024, o.width); assertTrue(abs(96 * 1024 / 40 - o.resolution) <= 1)
    }

    private fun pdf(pages: Int, heights: (Int) -> Int = { 144 }): ByteArray {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLUE }
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(72, heights(i), i + 1).create())
            page.canvas.drawRect(0f, 0f, 36f, 36f, paint)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    @Test fun pdfPagesBecomeLayersOnWhite() {
        val doc = ImageImport.open(pdf(3), "doc.pdf")
        val s = doc.stack
        assertEquals(PageImport.PDF_DPI, s.width); assertEquals(PageImport.PDF_DPI * 2, s.height)
        assertEquals(listOf("Page 3", "Page 2", "Page 1"), s.layers.map { it.name })
        assertEquals(listOf(false, false, true), s.layers.map { it.visible })
        assertEquals(PageImport.PDF_DPI, s.exportMetadata.resolution)
        val top = s.layers.last().pixels.toBitmap()
        assertEquals(Color.BLUE, top.getPixel(5, 5)); assertEquals(Color.WHITE, top.getPixel(5, s.height - 5)); top.recycle()
        assertNull(doc.note)
    }

    @Test fun pdfInsertsFirstPageTransparent() {
        val r = ImageImport.firstFrame(pdf(2))
        assertEquals(PageImport.PDF_DPI, r.bitmap.width); assertEquals(Color.BLUE, r.bitmap.getPixel(5, 5))
        assertEquals(0, Color.alpha(r.bitmap.getPixel(5, r.bitmap.height - 5))); r.bitmap.recycle()
    }

    @Test fun pdfChosenPagesSizeAndOrder() {
        val source = PageImport.probe(pdf(4) { if (it == 2) 288 else 144 })!!
        assertEquals(PageImport.Kind.PDF, source.kind); assertEquals(4, source.pageCount); assertEquals(72f, source.width)
        // 300 ppi, pages 2 and 3 (the latter twice as tall), reversed: page 3 on top... at the bottom.
        val docs = ImageImport.openPages(source, PageImport.Options(300, 600, 300, listOf(1, 2), reverse = true), "doc.pdf")
        val s = docs.single().stack
        assertEquals(300, s.width); assertEquals(1200, s.height)
        assertEquals(listOf("Page 2", "Page 3"), s.layers.map { it.name })
        assertEquals(listOf(false, true), s.layers.map { it.visible })
        assertEquals(300, s.exportMetadata.resolution)
    }

    @Test fun pdfPagesAsImages() {
        val source = PageImport.probe(pdf(3))!!
        val docs = ImageImport.openPages(source, PageImport.Options(72, 144, 72, listOf(0, 2), asImages = true), "doc.pdf")
        assertEquals(listOf("doc-1.png", "doc-3.png"), docs.map { it.name })
        assertTrue(docs.all { it.stack.width == 72 && it.stack.layers.size == 1 })
    }

    @Test fun pdfManyPagesShareTheMemoryBudget() {
        val source = PageImport.probe(pdf(10))!!
        val docs = ImageImport.openPages(source, PageImport.Options(2160, 4320, 2160, (0 until 10).toList()), "doc.pdf")
        val s = docs.single().stack
        assertTrue(s.width.toLong() * s.height * 10 <= 40_100_000L); assertTrue(s.exportMetadata.resolution < 2160)
        assertNotNull(docs.single().note)
    }

    @Test fun tiffPagesBecomeLayers() {
        val data = asset("tiff/pages.tif")
        val source = PageImport.probe(data)!!
        assertEquals(PageImport.Kind.TIFF, source.kind); assertEquals(3, source.pageCount)
        assertTrue(PageImport.asks(source))
        val s = ImageImport.open(data, "pages.tif").stack
        // As large as the largest page (40×20 and 30×30).
        assertEquals(40, s.width); assertEquals(30, s.height)
        assertEquals(listOf("Page 3", "Page 2", "Page 1"), s.layers.map { it.name })
        val colors = s.layers.map { l -> l.pixels.toBitmap().let { b -> b.getPixel(1, 1).also { b.recycle() } } }
        assertEquals(listOf(Color.BLUE, Color.GREEN, Color.RED), colors)
    }

    @Test fun tiffCompressionsAndFormats() {
        for ((name, expected) in listOf(
            "alpha-deflate.tif" to Color.RED, "jpeg.tif" to Color.rgb(200, 100, 50),
            "gray-packbits.tif" to Color.rgb(128, 128, 128), "gray16.tif" to Color.WHITE)) {
            val data = asset("tiff/$name")
            assertFalse(name, PageImport.asks(PageImport.probe(data)!!))
            val r = ImageImport.firstFrame(data)
            assertTrue("$name: ${Integer.toHexString(r.bitmap.getPixel(1, 1))}", near(expected, r.bitmap.getPixel(1, 1), if (name.startsWith("jpeg")) 6 else 1))
            r.bitmap.recycle()
        }
        val alpha = ImageImport.firstFrame(asset("tiff/alpha-deflate.tif")).bitmap
        assertTrue(Integer.toHexString(alpha.getPixel(12, 4)), near(Color.argb(128, 0, 0, 255), alpha.getPixel(12, 4), 2)); alpha.recycle()
    }

    @Test fun tiffObeysSizeLimit() {
        val r = ImageImport.firstFrame(asset("tiff/jpeg.tif"), maxDim = 20)
        assertTrue(r.bitmap.width <= 20 && r.bitmap.height <= 20); r.bitmap.recycle()
    }

    @Test fun dngAndRasterImagesAreLeftToAndroid() {
        assertNull(PageImport.probe(asset("tiff/fake.dng")))
        val png = ImageExportOptions(format = ImageFormat.PNG).encode(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))
        assertNull(PageImport.probe(png))
    }

    @Test fun thumbnailsFitTheirBox() {
        PageImport.Reader(PageImport.probe(pdf(2))!!).use { r ->
            val t = r.thumbnail(1, 64)
            assertEquals(64, t.height); assertEquals(32, t.width); t.recycle()
        }
    }
}
