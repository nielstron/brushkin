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
            var n = 0
            // stack.xml lists items top-most first; groups are nested <stack> elements.
            fun emit(list: List<Layer>) {
                for (l in list.asReversed()) {
                    val common = "name=\"${esc(l.name)}\" opacity=\"${l.opacity}\" visibility=\"${if (l.visible) "visible" else "hidden"}\" composite-op=\"${l.mode.oraName}\"" +
                        (if (l.id == stack.activeId) " selected=\"true\"" else "")
                    if (l.isGroup) {
                        xml.append("<stack $common isolation=\"isolate\">\n")
                        emit(l.children!!)
                        xml.append("</stack>\n")
                        continue
                    }
                    val i = n++
                    val src = "data/layer$i.png"
                    xml.append("<layer $common src=\"$src\" x=\"0\" y=\"0\"")
                    if (l.lockAlpha) xml.append(" alpha-preserve=\"true\"")
                    l.text?.let { xml.append(" brushkin-text=\"${esc(it.toJson())}\"") }
                    // Masks aren't part of the OpenRaster spec; other apps ignore these attributes.
                    val m = l.mask
                    if (m != null) xml.append(" brushkin-mask=\"data/layer${i}_mask.png\" brushkin-mask-enabled=\"${l.maskEnabled}\"")
                    xml.append("/>\n")
                    zip.putNextEntry(ZipEntry(src))
                    l.pixels.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    if (m != null) {
                        zip.putNextEntry(ZipEntry("data/layer${i}_mask.png"))
                        m.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                    }
                }
            }
            emit(stack.layers)
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
        var activeId = -1L
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        // Each open <stack> collects its items (top-most first, as in the file).
        val open = ArrayList<Pair<Map<String, String>, ArrayList<Layer>>>()
        var root: ArrayList<Layer>? = null
        fun attrs(): Map<String, String> = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
        fun common(a: Map<String, String>) = Triple(a["visibility"] != "hidden", a["opacity"]?.toFloatOrNull() ?: 1f, LayerMode.fromOra(a["composite-op"]))
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "image" -> { w = parser.getAttributeValue(null, "w").toInt(); h = parser.getAttributeValue(null, "h").toInt() }
                    "stack" -> open.add(attrs() to ArrayList())
                    "layer" -> {
                        val a = attrs()
                        val src = a["src"] ?: continue
                        val bytes = files[src] ?: continue
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                        val x = a["x"]?.toFloatOrNull()?.toInt() ?: 0
                        val y = a["y"]?.toFloatOrNull()?.toInt() ?: 0
                        val (vis, op, mode) = common(a)
                        val l = Layer(
                            name = a["name"] ?: "Layer",
                            pixels = TiledBitmap.fromBitmap(bmp, w, h, x, y),
                            visible = vis, opacity = op, mode = mode,
                            lockAlpha = a["alpha-preserve"] == "true",
                            mask = a["brushkin-mask"]?.let { files[it] }
                                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
                                ?.let { TiledBitmap.fromBitmap(it, w, h, x, y, fill = MASK_WHITE) },
                            maskEnabled = a["brushkin-mask-enabled"] != "false",
                            text = a["brushkin-text"]?.let { TextSpec.fromJson(it) },
                        )
                        if (a["selected"] == "true") activeId = l.id
                        open.lastOrNull()?.second?.add(l)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "stack") {
                    val (a, items) = open.removeAt(open.size - 1)
                    val children = items.asReversed().toList() // bottom-most first
                    if (open.isEmpty()) root = ArrayList(children)
                    else {
                        val (vis, op, mode) = common(a)
                        val g = Layer(name = a["name"] ?: "Layer Group", pixels = TiledBitmap(w, h), visible = vis, opacity = op, mode = mode, children = children)
                        if (a["selected"] == "true") activeId = g.id
                        open.last().second.add(g)
                    }
                }
            }
        }
        val layers = root ?: ArrayList()
        if (w <= 0 || h <= 0) error("Invalid image size")
        if (layers.isEmpty()) layers.add(Layer(name = "Background", pixels = TiledBitmap(w, h)))
        val st = Stack(w, h, layers, activeId)
        return if (st.find(activeId) != null) st else st.copy(activeId = st.all.last { !it.isGroup }.id)
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
