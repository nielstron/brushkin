package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Opens OpenRaster (.ora) files — a zip of PNG layers plus stack.xml. Brushkin saves XCF; this
 * remains for autosaves and projects from older versions, and for files from other apps.
 */
object Ora {
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
        val st = Stack(w, h, layers, activeId, guides = guides, channels = channels,
            exportMetadata = files["brushkin/export-metadata.json"]?.let { io.github.nielstron.brushkin.export.ExportMetadata.fromJson(it.toString(Charsets.UTF_8)) } ?: io.github.nielstron.brushkin.export.ExportMetadata())
        return if (st.find(activeId) != null) st else st.copy(activeId = st.all.last { !it.isGroup }.id)
    }

    fun encode(bmp: Bitmap, format: String, quality: Int = 92, lossless: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        when (format) {
            "jpg" -> {
                // JPEG has no alpha; composite over white first.
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
                flat.recycle()
            }
            "webp" -> {
                // Android 10 uses quality 100 for lossless WebP; the named format arrived in 11.
                @Suppress("DEPRECATION")
                val webp = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                } else Bitmap.CompressFormat.WEBP
                bmp.compress(webp, if (lossless) 100 else quality.coerceAtMost(99), out)
            }
            else -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }
}
