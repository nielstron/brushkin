package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * OpenRaster (.ora) — a zip of PNG layers plus stack.xml. It's the layered format GIMP, Krita and
 * MyPaint all read and write, so projects move freely between Brushkin and desktop GIMP.
 */
object Ora {
    fun write(stack: Stack, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // The mimetype entry must come first and be stored uncompressed.
            val mime = "image/openraster".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime); zip.closeEntry()

            val xml = StringBuilder()
            xml.append("<?xml version='1.0' encoding='UTF-8'?>\n")
            xml.append("<image version=\"0.0.5\" w=\"${stack.width}\" h=\"${stack.height}\">\n<stack>\n")
            // stack.xml lists layers top-most first.
            stack.layers.asReversed().forEachIndexed { i, l ->
                val src = "data/layer$i.png"
                xml.append("<layer name=\"${esc(l.name)}\" src=\"$src\" x=\"0\" y=\"0\" opacity=\"${l.opacity}\" visibility=\"${if (l.visible) "visible" else "hidden"}\" composite-op=\"${l.mode.oraName}\"")
                if (l.id == stack.activeId) xml.append(" selected=\"true\"")
                if (l.lockAlpha) xml.append(" alpha-preserve=\"true\"")
                xml.append("/>\n")
                zip.putNextEntry(ZipEntry(src))
                l.pixels.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
            xml.append("</stack>\n</image>\n")
            zip.putNextEntry(ZipEntry("stack.xml")); zip.write(xml.toString().toByteArray()); zip.closeEntry()

            val merged = Compositor.flatten(stack)
            zip.putNextEntry(ZipEntry("mergedimage.png")); merged.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
            val ts = minOf(256f / stack.width, 256f / stack.height, 1f)
            val thumb = Bitmap.createScaledBitmap(merged, (stack.width * ts).toInt().coerceAtLeast(1), (stack.height * ts).toInt().coerceAtLeast(1), true)
            zip.putNextEntry(ZipEntry("Thumbnails/thumbnail.png")); thumb.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
        }
    }

    fun read(input: InputStream): Stack {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = zip.readBytes()
            }
        }
        val xml = files["stack.xml"] ?: error("Not an OpenRaster file")
        val parser = Xml.newPullParser()
        parser.setInput(xml.inputStream(), "UTF-8")
        var w = 0; var h = 0
        val layers = ArrayList<Layer>()
        var activeId = -1L
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "image" -> { w = parser.getAttributeValue(null, "w").toInt(); h = parser.getAttributeValue(null, "h").toInt() }
                "layer" -> {
                    val src = parser.getAttributeValue(null, "src") ?: continue
                    val bytes = files[src] ?: continue
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                    val x = parser.getAttributeValue(null, "x")?.toFloatOrNull()?.toInt() ?: 0
                    val y = parser.getAttributeValue(null, "y")?.toFloatOrNull()?.toInt() ?: 0
                    val l = Layer(
                        name = parser.getAttributeValue(null, "name") ?: "Layer",
                        pixels = TiledBitmap.fromBitmap(bmp, w, h, x, y),
                        visible = parser.getAttributeValue(null, "visibility") != "hidden",
                        opacity = parser.getAttributeValue(null, "opacity")?.toFloatOrNull() ?: 1f,
                        mode = LayerMode.fromOra(parser.getAttributeValue(null, "composite-op")),
                        lockAlpha = parser.getAttributeValue(null, "alpha-preserve") == "true",
                    )
                    if (parser.getAttributeValue(null, "selected") == "true") activeId = l.id
                    layers.add(l)
                }
            }
        }
        if (w <= 0 || h <= 0) error("Invalid image size")
        if (layers.isEmpty()) layers.add(Layer(name = "Background", pixels = TiledBitmap(w, h)))
        layers.reverse()
        return Stack(w, h, layers, if (activeId >= 0) activeId else layers.last().id)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    fun encode(bmp: Bitmap, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        when (format) {
            "jpg" -> {
                // JPEG has no alpha; composite over white first.
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                flat.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            "webp" -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            else -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }
}
