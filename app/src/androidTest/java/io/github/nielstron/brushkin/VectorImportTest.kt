package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class VectorImportTest {
    private val square = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 4 2"><rect width="2" height="2" fill="#ff0000"/></svg>"""

    @Test fun smallSvgRendersSharpAtMinimumSize() {
        val r = ImageImport.firstFrame(square.toByteArray())
        assertEquals(VectorImport.MIN_SVG_SIZE, r.bitmap.width); assertEquals(VectorImport.MIN_SVG_SIZE / 2, r.bitmap.height)
        assertEquals(Color.RED, r.bitmap.getPixel(10, 10)); assertEquals(0, Color.alpha(r.bitmap.getPixel(r.bitmap.width - 10, 10)))
        r.bitmap.recycle()
    }

    @Test fun svgWithoutViewBoxScalesAndSvgzOpens() {
        val noBox = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="#00ff00"/></svg>"""
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(noBox.toByteArray()) } }.toByteArray()
        val stack = ImageImport.open(gz, "icon.svgz").stack
        assertEquals(VectorImport.MIN_SVG_SIZE, stack.width)
        val px = stack.layers.single().pixels.toBitmap()
        assertEquals(Color.GREEN, px.getPixel(stack.width - 5, stack.height - 5)); px.recycle()
    }

    @Test fun svgObeysSizeLimit() {
        val r = ImageImport.firstFrame(square.toByteArray(), maxDim = 300, vectorSize = 4000)
        assertEquals(300, r.bitmap.width); r.bitmap.recycle()
    }

    private fun pdf(pages: Int): ByteArray {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLUE }
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(72, 144, i + 1).create())
            page.canvas.drawRect(0f, 0f, 36f, 36f, paint)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    @Test fun pdfPagesBecomeLayersOnWhite() {
        val doc = ImageImport.open(pdf(3), "doc.pdf")
        val s = doc.stack
        assertEquals(VectorImport.PDF_DPI, s.width); assertEquals(VectorImport.PDF_DPI * 2, s.height)
        assertEquals(listOf("Page 3", "Page 2", "Page 1"), s.layers.map { it.name })
        assertEquals(listOf(false, false, true), s.layers.map { it.visible })
        assertEquals(VectorImport.PDF_DPI, s.exportMetadata.resolution)
        val top = s.layers.last().pixels.toBitmap()
        assertEquals(Color.BLUE, top.getPixel(5, 5)); assertEquals(Color.WHITE, top.getPixel(5, s.height - 5)); top.recycle()
        assertNotNull(doc.note)
    }

    @Test fun pdfInsertsFirstPageTransparent() {
        val r = ImageImport.firstFrame(pdf(2))
        assertEquals(VectorImport.PDF_DPI, r.bitmap.width); assertEquals(Color.BLUE, r.bitmap.getPixel(5, 5))
        assertEquals(0, Color.alpha(r.bitmap.getPixel(5, r.bitmap.height - 5))); r.bitmap.recycle()
    }

    @Test fun chosenSizeAndPagesAreUsed() {
        val source = VectorImport.probe(pdf(4))!!
        assertEquals(VectorImport.Kind.PDF, source.kind); assertEquals(4, source.pageCount); assertEquals(72f, source.width)
        // 300 dpi, pages 2-3.
        val doc = ImageImport.openVector(source, VectorImport.Options(300, 600, 1..2, background = false), "doc.pdf")
        assertEquals(300, doc.stack.width); assertEquals(600, doc.stack.height)
        assertEquals(listOf("Page 3", "Page 2"), doc.stack.layers.map { it.name })
        assertEquals(300, doc.stack.exportMetadata.resolution)
        val svg = VectorImport.probe(square.toByteArray())!!
        assertEquals(40f, svg.width); assertEquals(20f, svg.height)
        val r = ImageImport.vector(svg, VectorImport.Options(80, 40))
        assertEquals(80, r.bitmap.width); assertEquals(Color.RED, r.bitmap.getPixel(5, 5)); r.bitmap.recycle()
    }

    @Test fun rasterImagesAreNotVectors() {
        val png = ImageExportOptions(format = ImageFormat.PNG).encode(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))
        assertNull(VectorImport.probe(png))
    }
}
