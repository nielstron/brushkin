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
            // Guides as "v120 h80 …" in an extension attribute other apps ignore.
            val guides = if (stack.guides.isEmpty()) "" else " brushkin-guides=\"${stack.guides.joinToString(" ") { (if (it.vertical) "v" else "h") + it.pos }}\""
            xml.append("<image version=\"0.0.5\" w=\"${stack.width}\" h=\"${stack.height}\"$guides>\n<stack>\n")
            var n = 0
            // stack.xml lists items top-most first; groups are nested <stack> elements.
            fun emit(list: List<Layer>) {
                for (l in list.asReversed()) {
                    val common = "name=\"${esc(l.name)}\" opacity=\"${l.opacity}\" visibility=\"${if (l.visible) "visible" else "hidden"}\" composite-op=\"${l.mode.oraName}\"" +
                        (if (l.id == stack.activeId) " selected=\"true\"" else "")
                    if (l.isGroup) {
                        xml.append("<stack $common isolation=\"${if (l.mode == LayerMode.PASS_THROUGH) "auto" else "isolate"}\">\n")
                        emit(l.children!!)
                        xml.append("</stack>\n")
                        continue
                    }
                    val i = n++
                    val src = "data/layer$i.png"
                    // Layers keep their own extent: a smaller boundary, or content past the canvas.
                    val reg = LayerExtent.saveRegion(l, stack.width, stack.height)
                    val whole = reg == android.graphics.Rect(0, 0, stack.width, stack.height) && l.outside.isEmpty()
                    xml.append("<layer $common src=\"$src\" x=\"${reg.left}\" y=\"${reg.top}\"")
                    if (l.lockPosition) xml.append(" brushkin-lock-position=\"true\"")
                    if (l.linked) xml.append(" brushkin-linked=\"true\"")
                    if (l.effects.isNotEmpty()) xml.append(" brushkin-effects=\"${esc(LayerEffect.listToJson(l.effects))}\"")
                    if (l.lockAlpha) xml.append(" alpha-preserve=\"true\"")
                    l.text?.let { xml.append(" brushkin-text=\"${esc(it.toJson())}\"") }
                    // Masks aren't part of the OpenRaster spec; other apps ignore these attributes.
                    val m = l.mask
                    if (m != null) xml.append(" brushkin-mask=\"data/layer${i}_mask.png\" brushkin-mask-enabled=\"${l.maskEnabled}\"")
                    xml.append("/>\n")
                    zip.putNextEntry(ZipEntry(src))
                    (if (whole) l.pixels.toBitmap() else LayerExtent.render(l, reg)).compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    if (m != null) {
                        zip.putNextEntry(ZipEntry("data/layer${i}_mask.png"))
                        m.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                    }
                }
            }
            emit(stack.layers)
            xml.append("</stack>\n")
            // Saved channels (an extension other OpenRaster apps skip).
            for ((k, ch) in stack.channels.withIndex()) {
                val src = "data/channel$k.png"
                xml.append("<brushkin-channel name=\"${esc(ch.name)}\" src=\"$src\" color=\"#%08X\" opacity=\"${ch.opacity}\" visible=\"${ch.visible}\"/>\n".format(ch.color))
                zip.putNextEntry(ZipEntry(src)); ch.mask.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
            }
            xml.append("</image>\n")
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
        val guides = ArrayList<Guide>()
        val channels = ArrayList<SavedChannel>()
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
                    "brushkin-channel" -> {
                        val a = attrs()
                        val bytes = a["src"]?.let { files[it] } ?: continue
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                        channels.add(SavedChannel(name = a["name"] ?: "Channel", mask = TiledBitmap.fromBitmap(bmp, w, h, fill = MASK_BLACK),
                            color = a["color"]?.removePrefix("#")?.toLongOrNull(16)?.toInt() ?: 0xFFE5484D.toInt(),
                            opacity = a["opacity"]?.toFloatOrNull() ?: 0.5f, visible = a["visible"] == "true"))
                    }
                    "image" -> {
                        w = parser.getAttributeValue(null, "w").toInt(); h = parser.getAttributeValue(null, "h").toInt()
                        parser.getAttributeValue(null, "brushkin-guides")?.split(" ")?.forEach { t ->
                            t.drop(1).toIntOrNull()?.let { pos -> if (t[0] == 'v' || t[0] == 'h') guides.add(Guide(t[0] == 'v', pos)) }
                        }
                    }
                    "stack" -> open.add(attrs() to ArrayList())
                    "layer" -> {
                        val a = attrs()
                        val src = a["src"] ?: continue
                        val bytes = files[src] ?: continue
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                        val x = a["x"]?.toFloatOrNull()?.toInt() ?: 0
                        val y = a["y"]?.toFloatOrNull()?.toInt() ?: 0
                        val (vis, op, mode) = common(a)
                        val (px, outside, bounds) = LayerExtent.fromPlaced(bmp, x, y, w, h)
                        val l = Layer(
                            name = a["name"] ?: "Layer",
                            pixels = px, outside = outside, bounds = bounds,
                            lockPosition = a["brushkin-lock-position"] == "true", linked = a["brushkin-linked"] == "true",
                            effects = a["brushkin-effects"]?.let { runCatching { LayerEffect.listFromJson(it) }.getOrNull() } ?: emptyList(),
                            visible = vis, opacity = op, mode = mode,
                            lockAlpha = a["alpha-preserve"] == "true",
                            mask = a["brushkin-mask"]?.let { files[it] }
                                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
                                // Masks are stored canvas-sized at the origin, whatever the layer's extent.
                                ?.let { TiledBitmap.fromBitmap(it, w, h, 0, 0, fill = MASK_WHITE) },
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
                        val g = Layer(name = a["name"] ?: "Layer Group", pixels = TiledBitmap(w, h), visible = vis, opacity = op, mode = if (a["isolation"] == "auto") LayerMode.PASS_THROUGH else mode, children = children)
                        if (a["selected"] == "true") activeId = g.id
                        open.last().second.add(g)
                    }
                }
            }
        }
        val layers = root ?: ArrayList()
        if (w <= 0 || h <= 0) error("Invalid image size")
        if (layers.isEmpty()) layers.add(Layer(name = "Background", pixels = TiledBitmap(w, h)))
        val st = Stack(w, h, layers, activeId, guides = guides, channels = channels)
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
