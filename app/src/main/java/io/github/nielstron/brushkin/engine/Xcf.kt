package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * GIMP's native XCF format.
 *
 * Reading supports versions 0–22: RGB / grayscale / indexed images, 8/16/32-bit integer and
 * 16/32/64-bit float precision (linear data is converted to sRGB), uncompressed, RLE and zlib tiles,
 * layer offsets, opacity, visibility, modes, lock alpha, layer masks and layer groups (as
 * Brushkin groups). Writing produces version 11 files (8-bit sRGB, zlib) with masks and groups, or
 * version 22 when a layer has effects GIMP can run. Pixels are never baked: effects stay live, as
 * GIMP layer effects where GIMP can run the filter and otherwise in a "brushkin-effects" parasite.
 * Parasites also carry text layers and export metadata; GIMP keeps parasites it doesn't know.
 */
object Xcf {
    private const val PROP_END = 0
    private const val PROP_COLORMAP = 1
    private const val PROP_OPACITY = 6
    private const val PROP_MODE = 7
    private const val PROP_VISIBLE = 8
    private const val PROP_LINKED = 9
    private const val PROP_LOCK_POSITION = 32
    private const val PROP_LOCK_ALPHA = 10
    private const val PROP_APPLY_MASK = 11
    private const val PROP_ACTIVE_LAYER = 2
    private const val PROP_OFFSETS = 15
    private const val PROP_COMPRESSION = 17
    private const val PROP_GUIDES = 18
    private const val PROP_SELECTION = 4
    private const val PROP_COLOR = 16
    private const val PROP_GROUP_ITEM = 29
    private const val PROP_ITEM_PATH = 30
    private const val PROP_FLOAT_OPACITY = 33
    private const val PROP_LOCK_CONTENT = 28
    private const val PROP_PARASITES = 21
    private const val PROP_FILTER_REGION = 44
    private const val PROP_FILTER_ARGUMENT = 45
    private const val PROP_FILTER_CLIP = 46
    private const val MODE_REPLACE = 62
    private const val ARG_INT = 1
    private const val ARG_BOOL = 2
    private const val ARG_FLOAT = 3
    private const val ARG_ENUM = 5
    private const val ARG_UINT = 7
    private const val EFFECTS_PARASITE = "brushkin-effects"
    private const val TEXT_PARASITE = "brushkin-text"
    private const val METADATA_PARASITE = "brushkin-export-metadata"
    /** GIMP discards these from a file: it has its own versions and hides GEGL's. */
    private val GIMP_HIDDEN = setOf("gegl:threshold", "gegl:posterize", "gegl:polar-coordinates")
    /** GIMP discards a filter saved with another version; every other listed operation is 0:0. */
    private val GIMP_VERSIONS = mapOf("gegl:exposure" to "1:0")

    private fun gimpOperation(e: LayerEffect): String? =
        GeglOperations.operation(e.name)?.takeIf { e.paramsVersion >= 2 && it !in GIMP_HIDDEN }

    private class Reader(val b: ByteBuffer, val version: Int) {
        val wide = version >= 11
        fun u32(): Int = b.int
        fun ptr(): Long = if (wide) b.long else (b.int.toLong() and 0xFFFFFFFFL)
        fun str(): String {
            val n = u32()
            if (n == 0) return ""
            val bytes = ByteArray(n); b.get(bytes)
            return String(bytes, 0, (n - 1).coerceAtLeast(0), Charsets.UTF_8)
        }
        fun seek(p: Long) { b.position(p.toInt()) }
    }

    /** Bytes per channel + whether values are linear light + kind (0 int, 1 half, 2 float). */
    private class Precision(val bpc: Int, val linear: Boolean, val kind: Int)

    private fun precision(version: Int, p: Int): Precision = when {
        version < 4 -> Precision(1, false, 0)
        version == 4 -> when (p) { 0 -> Precision(1, false, 0); 1 -> Precision(2, false, 0); 2 -> Precision(4, false, 0); 3 -> Precision(2, true, 1); 4 -> Precision(4, true, 2); else -> error("Unknown precision $p") }
        version in 5..6 -> when (p) {
            100 -> Precision(1, true, 0); 150 -> Precision(1, false, 0); 200 -> Precision(2, true, 0); 250 -> Precision(2, false, 0)
            300 -> Precision(4, true, 0); 350 -> Precision(4, false, 0); 400 -> Precision(2, true, 1); 450 -> Precision(2, false, 1)
            500 -> Precision(4, true, 2); 550 -> Precision(4, false, 2); else -> error("Unknown precision $p")
        }
        else -> when (p) {
            100 -> Precision(1, true, 0); 150 -> Precision(1, false, 0); 200 -> Precision(2, true, 0); 250 -> Precision(2, false, 0)
            300 -> Precision(4, true, 0); 350 -> Precision(4, false, 0); 500 -> Precision(2, true, 1); 550 -> Precision(2, false, 1)
            600 -> Precision(4, true, 2); 650 -> Precision(4, false, 2)
            700 -> Precision(8, true, 3); 750 -> Precision(8, false, 3)
            else -> error("Unknown precision $p")
        }
    }

    private class XLayer(
        val name: String, val w: Int, val h: Int, val type: Int, var opacity: Float = 1f, var visible: Boolean = true,
        var mode: Int = 0, var ox: Int = 0, var oy: Int = 0, var lockAlpha: Boolean = false, var applyMask: Boolean = true,
        var linked: Boolean = false, var lockPosition: Boolean = false,
        var group: Boolean = false, var path: IntArray? = null, var hierarchy: Long = 0, var mask: Long = 0, var active: Boolean = false,
        var parasites: Map<String, String> = emptyMap(), var effects: List<LayerEffect> = emptyList(),
    )

    /** Brushkin's own parasites; other apps' may be binary. */
    private fun parasites(r: Reader, b: ByteBuffer, end: Int): Map<String, String> {
        val found = HashMap<String, String>()
        while (b.position() < end) {
            val name = r.str(); r.u32()
            val data = ByteArray(r.u32()); b.get(data)
            if (name.startsWith("brushkin-")) found[name] = String(data, Charsets.UTF_8)
        }
        return found
    }

    /** Null for a filter Brushkin doesn't have. */
    private fun readEffect(r: Reader, b: ByteBuffer, version: Int): LayerEffect? {
        r.str(); r.str()
        val operation = r.str()
        if (version >= 22) r.str()
        var visible = true
        val args = HashMap<String, Float>()
        while (true) {
            val type = r.u32(); val len = r.u32()
            val start = b.position()
            when (type) {
                PROP_END -> break
                PROP_VISIBLE -> visible = r.u32() != 0
                PROP_FILTER_ARGUMENT -> {
                    val name = r.str()
                    when (r.u32()) {
                        ARG_INT, ARG_BOOL, ARG_ENUM -> args[name] = r.u32().toFloat()
                        ARG_UINT -> args[name] = (r.u32().toLong() and 0xFFFFFFFFL).toFloat()
                        ARG_FLOAT -> args[name] = b.float
                    }
                }
            }
            b.position(start + len)
        }
        val title = GeglOperations.title(operation) ?: return null
        val params = GeglOperations.properties(operation).map { args[it.getString("key")] ?: it.getDouble("default").toFloat() }
        return LayerEffect(title, params, visible = visible)
    }

    /** Effects in the order they apply; ones only Brushkin runs go back where they were. */
    private fun allEffects(live: List<LayerEffect>, own: String?): List<LayerEffect> {
        val all = live.toMutableList()
        runCatching {
            val saved = org.json.JSONArray(own ?: return all)
            for (i in 0 until saved.length()) saved.getJSONObject(i).let {
                all.add(it.getInt("index").coerceAtMost(all.size), LayerEffect.fromJson(it.getJSONObject("effect")))
            }
        }
        return all
    }

    fun read(bytes: ByteArray): Stack {
        val b = ByteBuffer.wrap(bytes) // big-endian
        val magic = String(bytes, 0, 9, Charsets.US_ASCII)
        require(magic == "gimp xcf ") { "Not a GIMP XCF file" }
        val ver = String(bytes, 9, 4, Charsets.US_ASCII)
        val version = if (ver == "file") 0 else ver.removePrefix("v").toIntOrNull() ?: error("Unknown XCF version $ver")
        b.position(14)
        val r = Reader(b, version)
        val w = r.u32(); val h = r.u32(); val base = r.u32()
        val prec = precision(version, if (version >= 4) r.u32() else 150)
        var compression = 0
        var colormap: IntArray? = null
        val guides = ArrayList<Guide>()
        var imageParasites = emptyMap<String, String>()
        // Image properties.
        while (true) {
            val type = r.u32(); val len = r.u32()
            val start = b.position()
            when (type) {
                PROP_END -> break
                PROP_PARASITES -> imageParasites = parasites(r, b, start + len)
                PROP_COMPRESSION -> compression = b.get().toInt()
                // Guides: (int32 position, int8 orientation: 1 horizontal, 2 vertical) each.
                PROP_GUIDES -> repeat(len / 5) { val pos = r.u32(); val o = b.get().toInt(); if (o == 1 || o == 2) guides.add(Guide(o == 2, pos)) }
                PROP_COLORMAP -> { val n = r.u32(); colormap = IntArray(n) { (0xFF shl 24) or ((b.get().toInt() and 0xFF) shl 16) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() and 0xFF) } }
            }
            b.position(start + len)
        }
        // Value 3 ("fractal") is reserved in the format but no GIMP version ever wrote it.
        if (compression == 3) error("This XCF file uses an unknown compression")
        val layerPtrs = ArrayList<Long>()
        while (true) { val p = r.ptr(); if (p == 0L) break; layerPtrs.add(p) }
        val channelPtrs = ArrayList<Long>()
        while (true) { val p = r.ptr(); if (p == 0L) break; channelPtrs.add(p) }

        val xl = layerPtrs.map { p ->
            r.seek(p)
            val lw = r.u32(); val lh = r.u32(); val type = r.u32(); val name = r.str()
            val l = XLayer(name, lw, lh, type)
            while (true) {
                val t = r.u32(); val len = r.u32()
                val start = b.position()
                when (t) {
                    PROP_END -> break
                    PROP_OPACITY -> l.opacity = r.u32() / 255f
                    PROP_FLOAT_OPACITY -> l.opacity = b.float
                    PROP_VISIBLE -> l.visible = r.u32() != 0
                    PROP_MODE -> l.mode = r.u32()
                    PROP_OFFSETS -> { l.ox = r.u32(); l.oy = r.u32() }
                    PROP_LOCK_ALPHA -> l.lockAlpha = r.u32() != 0
                    PROP_LINKED -> l.linked = r.u32() != 0
                    PROP_LOCK_POSITION -> l.lockPosition = r.u32() != 0
                    PROP_APPLY_MASK -> l.applyMask = r.u32() != 0
                    PROP_GROUP_ITEM -> l.group = true
                    PROP_ITEM_PATH -> l.path = IntArray(len / 4) { r.u32() }
                    PROP_ACTIVE_LAYER -> l.active = true
                    PROP_PARASITES -> l.parasites = parasites(r, b, start + len)
                }
                b.position(start + len)
            }
            l.hierarchy = r.ptr(); l.mask = r.ptr()
            val effectPtrs = ArrayList<Long>()
            if (version >= 20) while (true) { val e = r.ptr(); if (e == 0L) break; effectPtrs.add(e) }
            // GIMP lists the last-applied effect first.
            val live = effectPtrs.asReversed().mapNotNull { r.seek(it); readEffect(r, b, version) }
            l.effects = allEffects(live, l.parasites[EFFECTS_PARASITE])
            l
        }

        fun pixels(l: XLayer): Triple<TiledBitmap, Map<Long, Bitmap>, android.graphics.Rect?> {
            val px = readHierarchy(r, l.hierarchy, compression, prec, l.type, colormap, base)
            val t = Bitmap.createBitmap(l.w.coerceAtLeast(1), l.h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            t.setPixels(px, 0, l.w, 0, 0, l.w, l.h)
            return LayerExtent.fromPlaced(t, l.ox, l.oy, w, h)
        }

        fun mask(l: XLayer): TiledBitmap? {
            if (l.mask == 0L) return null
            r.seek(l.mask)
            val mw = r.u32(); val mh = r.u32(); r.str()
            while (true) { val t = r.u32(); val len = r.u32(); if (t == PROP_END) break; b.position(b.position() + len) }
            val hier = r.ptr()
            val gray = readHierarchy(r, hier, compression, prec, 2, null, 1) // grayscale channel
            val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            bmp.setPixels(gray.map { it or 0xFF000000.toInt() }.toIntArray(), 0, mw, 0, 0, mw, mh)
            return TiledBitmap.fromBitmap(bmp, w, h, l.ox, l.oy, fill = MASK_WHITE)
        }

        // Build the tree. XCF lists items top-to-bottom; ITEM_PATH gives each item's position
        // (indices at each depth). Without paths everything is top level.
        fun toLayer(l: XLayer, children: List<Layer>?): Layer {
            val (px, outside, bounds) = if (l.group) Triple(TiledBitmap(w, h), emptyMap(), null) else pixels(l)
            val mode = modeOf(l.mode).let { if (it == LayerMode.PASS_THROUGH && !l.group) LayerMode.NORMAL else it }
            return Layer(
                name = l.name.ifEmpty { "Layer" },
                pixels = px, outside = outside, bounds = bounds,
                visible = l.visible, opacity = l.opacity.coerceIn(0f, 1f), mode = mode, lockAlpha = l.lockAlpha,
                lockPosition = l.lockPosition, linked = l.linked,
                mask = mask(l), maskEnabled = l.applyMask,
                effects = l.effects, text = l.parasites[TEXT_PARASITE]?.let { TextSpec.fromJson(it) },
                children = children,
            )
        }

        fun build(prefix: List<Int>): List<Layer> {
            val out = ArrayList<Layer>()
            for ((i, l) in xl.withIndex()) {
                val path = l.path?.toList() ?: listOf(i)
                if (path.size != prefix.size + 1 || path.subList(0, prefix.size) != prefix) continue
                out.add(toLayer(l, if (l.group) build(path) else null))
            }
            return out.asReversed() // bottom-most first
        }
        val tree = if (xl.any { it.path != null }) build(emptyList()) else xl.map { toLayer(it, if (it.group) emptyList() else null) }.asReversed()
        val layers = tree.ifEmpty { listOf(Layer(name = "Background", pixels = TiledBitmap(w, h))) }
        val activeIdx = xl.indexOfFirst { it.active }
        val activeName = if (activeIdx >= 0) xl[activeIdx].name else null
        val active = flatten(layers).firstOrNull { it.name == activeName && it.children == null } ?: flatten(layers).last { it.children == null }
        // Channels: saved selections, plus the selection itself (marked PROP_SELECTION).
        var selection: Selection? = null
        val channels = ArrayList<SavedChannel>()
        for (p in channelPtrs) runCatching {
            r.seek(p)
            val cw = r.u32(); val ch = r.u32(); val name = r.str()
            var opacity = 0.5f; var visible = false; var color = 0xFFE5484D.toInt(); var isSel = false
            while (true) {
                val t = r.u32(); val len = r.u32(); val start = b.position()
                when (t) {
                    PROP_END -> break
                    PROP_OPACITY -> opacity = r.u32() / 255f
                    PROP_VISIBLE -> visible = r.u32() != 0
                    PROP_SELECTION -> isSel = true
                    PROP_COLOR -> color = (0xFF shl 24) or ((b.get().toInt() and 0xFF) shl 16) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() and 0xFF)
                }
                b.position(start + len)
            }
            if (cw != w || ch != h) return@runCatching
            val gray = readHierarchy(r, r.ptr(), compression, prec, 2, null, 1)
            if (isSel) selection = Selection.fromBytes(ByteArray(gray.size) { (gray[it] and 0xFF).toByte() }, w, h)
            else channels.add(SavedChannel(name = name.ifEmpty { "Channel" }, mask = TiledBitmap.fromPixels(IntArray(gray.size) { (gray[it] and 0xFF) * 0x010101 or (0xFF shl 24) }, w, h, MASK_BLACK), color = color, opacity = opacity, visible = visible))
        }
        return Stack(w, h, layers, active.id, selection = selection, guides = guides, channels = channels,
            exportMetadata = imageParasites[METADATA_PARASITE]?.let { io.github.nielstron.brushkin.export.ExportMetadata.fromJson(it) } ?: io.github.nielstron.brushkin.export.ExportMetadata())
    }

    private fun flatten(l: List<Layer>): List<Layer> = l.flatMap { listOf(it) + flatten(it.children ?: emptyList()) }

    /** GIMP 2.10+/3 layer modes (and legacy ones) → Brushkin modes. */
    private fun modeOf(m: Int): LayerMode = when (m) {
        3, 30 -> LayerMode.MULTIPLY
        4, 31 -> LayerMode.SCREEN
        5, 23 -> LayerMode.OVERLAY
        6, 32 -> LayerMode.DIFFERENCE
        7, 33 -> LayerMode.ADDITION
        9, 35 -> LayerMode.DARKEN
        10, 36 -> LayerMode.LIGHTEN
        11, 24, 37 -> LayerMode.HUE
        12, 25, 38 -> LayerMode.SATURATION
        13, 26, 39 -> LayerMode.COLOR
        14, 27, 40, 56 -> LayerMode.LUMINOSITY
        16, 42 -> LayerMode.DODGE
        17, 43 -> LayerMode.BURN
        18, 44 -> LayerMode.HARD_LIGHT
        19, 45 -> LayerMode.SOFT_LIGHT
        52 -> LayerMode.EXCLUSION
        61 -> LayerMode.PASS_THROUGH
        else -> LayerMode.NORMAL
    }

    private fun xcfMode(m: LayerMode): Int = when (m) {
        LayerMode.NORMAL -> 28; LayerMode.MULTIPLY -> 30; LayerMode.SCREEN -> 31; LayerMode.OVERLAY -> 23
        LayerMode.DIFFERENCE -> 32; LayerMode.ADDITION -> 33; LayerMode.DARKEN -> 35; LayerMode.LIGHTEN -> 36
        LayerMode.HUE -> 37; LayerMode.SATURATION -> 38; LayerMode.COLOR -> 39; LayerMode.LUMINOSITY -> 56
        LayerMode.DODGE -> 42; LayerMode.BURN -> 43; LayerMode.HARD_LIGHT -> 44; LayerMode.SOFT_LIGHT -> 45
        LayerMode.EXCLUSION -> 52; LayerMode.PASS_THROUGH -> 61
    }

    /** Decode a hierarchy to ARGB ints. [type]: 0 RGB, 1 RGBA, 2 GRAY, 3 GRAYA, 4 INDEXED, 5 INDEXEDA. */
    private fun readHierarchy(r: Reader, ptr: Long, compression: Int, prec: Precision, type: Int, colormap: IntArray?, @Suppress("UNUSED_PARAMETER") base: Int): IntArray {
        r.seek(ptr)
        val w = r.u32(); val h = r.u32(); val bpp = r.u32()
        val levelPtr = r.ptr()
        r.seek(levelPtr)
        r.u32(); r.u32() // level width/height
        val channels = when (type) { 0 -> 3; 1 -> 4; 2 -> 1; 3 -> 2; 4 -> 1; 5 -> 2; else -> error("Unknown layer type $type") }
        val bpc = if (type >= 4) 1 else prec.bpc
        require(bpp == channels * bpc) { "Unexpected bytes per pixel $bpp" }
        val out = IntArray(w * h)
        val tilesX = (w + 63) / 64; val tilesY = (h + 63) / 64
        val ptrs = LongArray(tilesX * tilesY) { r.ptr() }
        val toSrgb = if (prec.linear) IntArray(256) { (255 * linToSrgb(it / 255f)).roundToInt() } else null
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val tw = minOf(64, w - tx * 64); val th = minOf(64, h - ty * 64)
            val n = tw * th
            val next = if (ty * tilesX + tx + 1 < ptrs.size) ptrs[ty * tilesX + tx + 1] else -1L
            r.seek(ptrs[ty * tilesX + tx])
            val data = when (compression) {
                0 -> ByteArray(n * bpp).also { r.b.get(it) }
                1 -> rle(r.b, n, bpp)
                2 -> {
                    val len = if (next > 0) (next - ptrs[ty * tilesX + tx]).toInt() else r.b.remaining()
                    val comp = ByteArray(minOf(len, r.b.remaining())); r.b.get(comp)
                    Inflater().run { setInput(comp); val o = ByteArray(n * bpp); var off = 0; while (off < o.size && !finished()) off += inflate(o, off, o.size - off); end(); o }
                }
                else -> error("Unsupported compression $compression")
            }
            // Pixels are interleaved, channels big-endian.
            for (i in 0 until n) {
                val px = tx * 64 + i % tw; val py = ty * 64 + i / tw
                fun ch(c: Int): Int {
                    val o = i * bpp + c * bpc
                    var v: Float = when {
                        bpc == 1 -> (data[o].toInt() and 0xFF) / 255f
                        prec.kind == 1 -> halfToFloat(((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF))
                        bpc == 2 -> (((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)) / 65535f
                        prec.kind == 3 -> java.lang.Double.longBitsToDouble((0 until 8).fold(0L) { acc, k -> (acc shl 8) or (data[o + k].toLong() and 0xFF) }).toFloat()
                        prec.kind == 2 -> java.lang.Float.intBitsToFloat(((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF))
                        else -> ((((data[o].toLong() and 0xFF) shl 24) or ((data[o + 1].toLong() and 0xFF) shl 16) or ((data[o + 2].toLong() and 0xFF) shl 8) or (data[o + 3].toLong() and 0xFF)) / 4294967295.0).toFloat()
                    }
                    v = v.coerceIn(0f, 1f)
                    return if (prec.linear && bpc > 1 && c < channels - (if (type == 1 || type == 3) 1 else 0)) (255 * linToSrgb(v)).roundToInt()
                    else (v * 255).roundToInt()
                }
                val argb = when (type) {
                    0, 1 -> {
                        var rr = ch(0); var gg = ch(1); var bb = ch(2)
                        if (toSrgb != null && bpc == 1) { rr = toSrgb[rr]; gg = toSrgb[gg]; bb = toSrgb[bb] }
                        ((if (type == 1) ch(3) else 255) shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                    2, 3 -> {
                        var g = ch(0)
                        if (toSrgb != null && bpc == 1) g = toSrgb[g]
                        ((if (type == 3) ch(1) else 255) shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    else -> {
                        val idx = data[i * bpp].toInt() and 0xFF
                        val c = colormap?.getOrNull(idx) ?: 0xFF000000.toInt()
                        if (type == 5) ((data[i * bpp + 1].toInt() and 0xFF) shl 24) or (c and 0xFFFFFF) else c
                    }
                }
                out[py * w + px] = argb
            }
        }
        return out
    }

    private fun rle(b: ByteBuffer, n: Int, bpp: Int): ByteArray {
        // Each channel byte plane is RLE-encoded separately; output is interleaved.
        val out = ByteArray(n * bpp)
        for (c in 0 until bpp) {
            var i = 0
            while (i < n) {
                var p = b.get().toInt() and 0xFF
                if (p >= 128) {
                    val count = if (p == 128) (b.short.toInt() and 0xFFFF) else 256 - p
                    repeat(count) { out[(i++) * bpp + c] = b.get() }
                } else {
                    val count = if (p == 127) (b.short.toInt() and 0xFFFF) else p + 1
                    val v = b.get()
                    repeat(count) { out[(i++) * bpp + c] = v }
                    p = 0
                }
            }
        }
        return out
    }

    private fun linToSrgb(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.toDouble().pow(1 / 2.4).toFloat() - 0.055f

    private fun halfToFloat(h: Int): Float {
        val s = (h shr 15) and 1; val e = (h shr 10) and 0x1F; val m = h and 0x3FF
        val v = when (e) { 0 -> m / 1024f * 2f.pow(-14); 31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN; else -> (1 + m / 1024f) * 2f.pow(e - 15) }
        return if (s == 1) -v else v
    }

    // ------------------------------------------------------------------ writing

    /** Writes an 8-bit sRGB zlib file: v011 for GIMP 2.10 and up, or v022 (GIMP 3) when it has live effects. */
    fun write(stack: Stack, os: OutputStream) {
        // Pointers are patched after the fact, so build into a growable byte buffer.
        val out = Bytes()
        val liveEffects = stack.all.any { l -> l.effects.any { gimpOperation(it) != null } }
        out.str(if (liveEffects) "gimp xcf v022" else "gimp xcf v011"); out.u8(0)
        out.u32(stack.width); out.u32(stack.height); out.u32(0); out.u32(150)
        out.prop(PROP_COMPRESSION) { u8(2) }
        if (stack.guides.isNotEmpty()) out.prop(PROP_GUIDES) { for (g in stack.guides) { u32(g.pos); u8(if (g.vertical) 2 else 1) } }
        out.prop(PROP_PARASITES) { parasite(METADATA_PARASITE, stack.exportMetadata.toJson()) }
        out.u32(PROP_END); out.u32(0)
        // Flatten the tree top-to-bottom with item paths.
        data class Item(val layer: Layer, val path: List<Int>)
        val items = ArrayList<Item>()
        fun walk(list: List<Layer>, prefix: List<Int>) {
            list.asReversed().forEachIndexed { i, l ->
                items.add(Item(l, prefix + i))
                l.children?.let { walk(it, prefix + i) }
            }
        }
        walk(stack.layers, emptyList())
        val layerPtrPos = IntArray(items.size)
        for (i in items.indices) { layerPtrPos[i] = out.size; out.u64(0) }
        out.u64(0) // end of layers
        // Saved channels, then the selection as GIMP's selection channel.
        val chans: List<Triple<String, IntArray, SavedChannel?>> = stack.channels.map { Triple(it.name, it.mask.toPixels(), it) } +
            listOfNotNull(stack.selection?.let { sel -> val bb = sel.bytes(); Triple("Selection Mask", IntArray(bb.size) { (bb[it].toInt() and 0xFF) * 0x010101 or (0xFF shl 24) }, null) })
        val chanPtrPos = IntArray(chans.size)
        for (i in chans.indices) { chanPtrPos[i] = out.size; out.u64(0) }
        out.u64(0) // end of channels
        if (liveEffects) out.u64(0) // end of paths (from v018)
        items.forEachIndexed { i, it ->
            out.patch64(layerPtrPos[i], out.size.toLong())
            writeLayer(out, it.layer, it.path, stack, it.layer.id == stack.activeId, liveEffects)
        }
        chans.forEachIndexed { i, (name, px, c) ->
            out.patch64(chanPtrPos[i], out.size.toLong())
            out.u32(stack.width); out.u32(stack.height); out.xstr(name)
            out.prop(PROP_OPACITY) { u32(((c?.opacity ?: 0.5f) * 255).roundToInt()) }
            out.prop(PROP_VISIBLE) { u32(if (c?.visible == true) 1 else 0) }
            val col = c?.color ?: 0xFF000000.toInt()
            out.prop(PROP_COLOR) { u8((col shr 16) and 0xFF); u8((col shr 8) and 0xFF); u8(col and 0xFF) }
            if (c == null) out.prop(PROP_SELECTION) {}
            out.u32(PROP_END); out.u32(0)
            val hp = out.size; out.u64(0)
            out.patch64(hp, out.size.toLong())
            writeHierarchy(out, px, stack.width, stack.height, 1)
        }
        os.write(out.toByteArray())
    }

    private class Bytes {
        private var a = ByteArray(1 shl 16)
        var size = 0; private set
        private fun ensure(n: Int) { if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n)) }
        fun u8(v: Int) { ensure(1); a[size++] = v.toByte() }
        fun u32(v: Int) { ensure(4); a[size++] = (v ushr 24).toByte(); a[size++] = (v ushr 16).toByte(); a[size++] = (v ushr 8).toByte(); a[size++] = v.toByte() }
        fun u64(v: Long) { u32((v ushr 32).toInt()); u32(v.toInt()) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, a, size, b.size); size += b.size }
        fun str(s: String) { bytes(s.toByteArray(Charsets.US_ASCII)) }
        fun xstr(s: String) { val b = s.toByteArray(Charsets.UTF_8); u32(b.size + 1); bytes(b); u8(0) }
        fun patch64(pos: Int, v: Long) { for (k in 0 until 8) a[pos + k] = (v ushr (56 - 8 * k)).toByte() }
        fun prop(type: Int, body: Bytes.() -> Unit) {
            u32(type); val lenPos = size; u32(0); val start = size; body()
            val len = size - start
            a[lenPos] = (len ushr 24).toByte(); a[lenPos + 1] = (len ushr 16).toByte(); a[lenPos + 2] = (len ushr 8).toByte(); a[lenPos + 3] = len.toByte()
        }
        fun parasite(name: String, text: String) { xstr(name); u32(1); val b = text.toByteArray(Charsets.UTF_8); u32(b.size); bytes(b) } // 1: persistent
        fun toByteArray() = a.copyOf(size)
    }

    private fun writeLayer(out: Bytes, l: Layer, path: List<Int>, s: Stack, active: Boolean, liveEffects: Boolean) {
        // Layers keep their own size and offset (smaller than the canvas, or reaching past it).
        val reg = if (l.children != null) android.graphics.Rect(0, 0, s.width, s.height) else LayerExtent.saveRegion(l, s.width, s.height)
        val lw = reg.width(); val lh = reg.height()
        out.u32(lw); out.u32(lh); out.u32(1); out.xstr(l.name)
        if (active) out.prop(PROP_ACTIVE_LAYER) {}
        out.prop(PROP_OPACITY) { u32((l.opacity * 255).roundToInt()) }
        out.prop(PROP_FLOAT_OPACITY) { f32(l.opacity) }
        out.prop(PROP_VISIBLE) { u32(if (l.visible) 1 else 0) }
        out.prop(PROP_LINKED) { u32(if (l.linked) 1 else 0) }
        if (l.lockPosition) out.prop(PROP_LOCK_POSITION) { u32(1) }
        out.prop(PROP_LOCK_ALPHA) { u32(if (l.lockAlpha) 1 else 0) }
        out.prop(PROP_APPLY_MASK) { u32(if (l.maskEnabled) 1 else 0) }
        out.prop(PROP_OFFSETS) { u32(reg.left); u32(reg.top) }
        out.prop(PROP_MODE) { u32(xcfMode(l.mode)) }
        if (l.children != null) out.prop(PROP_GROUP_ITEM) {}
        if (path.size > 1) out.prop(PROP_ITEM_PATH) { path.forEach { u32(it) } }
        val live = l.effects.filter { gimpOperation(it) != null }
        val own = org.json.JSONArray()
        l.effects.forEachIndexed { i, e -> if (gimpOperation(e) == null) own.put(org.json.JSONObject().put("index", i).put("effect", e.toJson())) }
        if (own.length() > 0 || l.text != null) out.prop(PROP_PARASITES) {
            if (own.length() > 0) parasite(EFFECTS_PARASITE, own.toString())
            l.text?.let { parasite(TEXT_PARASITE, it.toJson()) }
        }
        out.u32(PROP_END); out.u32(0)
        val hierPos = out.size; out.u64(0)
        val maskPos = out.size; out.u64(0)
        val effectPos = out.size
        if (liveEffects) repeat(live.size + 1) { out.u64(0) }
        out.patch64(hierPos, out.size.toLong())
        val px = if (l.children != null) IntArray(lw * lh)
            else if (reg == android.graphics.Rect(0, 0, s.width, s.height) && l.outside.isEmpty()) l.pixels.toPixels()
            else LayerExtent.render(l, reg).let { b -> IntArray(lw * lh).also { b.getPixels(it, 0, lw, 0, 0, lw, lh) } }
        writeHierarchy(out, px, lw, lh, 4)
        // GIMP lists the last-applied effect first.
        live.asReversed().forEachIndexed { i, e ->
            out.patch64(effectPos + 8 * i, out.size.toLong())
            writeEffect(out, e)
        }
        val m = l.mask ?: return
        out.patch64(maskPos, out.size.toLong())
        out.u32(lw); out.u32(lh); out.xstr("${l.name} mask")
        out.prop(PROP_OPACITY) { u32(255) }
        out.prop(PROP_VISIBLE) { u32(0) }
        out.u32(PROP_END); out.u32(0)
        val mh = out.size; out.u64(0)
        out.patch64(mh, out.size.toLong())
        // The mask matches the layer's extent; beyond the canvas it's white (fully shown).
        val mpx = if (lw == s.width && lh == s.height && reg.left == 0 && reg.top == 0) m.toPixels() else {
            val mb = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888); mb.eraseColor(MASK_WHITE)
            val c = android.graphics.Canvas(mb); c.translate(-reg.left.toFloat(), -reg.top.toFloat()); Compositor.drawTiles(c, m, null, null)
            IntArray(lw * lh).also { mb.getPixels(it, 0, lw, 0, 0, lw, lh) }
        }
        writeHierarchy(out, mpx, lw, lh, 1)
    }

    private fun writeEffect(out: Bytes, e: LayerEffect) {
        val operation = gimpOperation(e)!!
        out.xstr(""); out.xstr("gimp-image"); out.xstr(operation); out.xstr(GIMP_VERSIONS[operation] ?: "0:0")
        out.prop(PROP_VISIBLE) { u32(if (e.visible) 1 else 0) }
        out.prop(PROP_FLOAT_OPACITY) { f32(1f) }
        out.prop(PROP_MODE) { u32(MODE_REPLACE) }
        out.prop(PROP_FILTER_CLIP) { u32(0) }
        out.prop(PROP_FILTER_REGION) { u32(1) } // the whole layer
        GeglOperations.properties(operation).forEachIndexed { i, p ->
            val value = e.params.getOrNull(i) ?: return@forEachIndexed
            out.prop(PROP_FILTER_ARGUMENT) {
                xstr(p.getString("key"))
                when (p.getString("kind")) {
                    "double" -> { u32(ARG_FLOAT); f32(value) }
                    "int" -> { u32(ARG_INT); u32(value.toInt()) }
                    "uint" -> { u32(ARG_UINT); u32(value.toLong().toInt()) }
                    "boolean" -> { u32(ARG_BOOL); u32(if (value != 0f) 1 else 0) }
                    else -> { u32(ARG_ENUM); u32(value.toInt()) }
                }
            }
        }
        out.u32(PROP_END); out.u32(0)
        out.u64(0) // no effect mask
    }

    private fun writeHierarchy(out: Bytes, px: IntArray, w: Int, h: Int, bpp: Int) {
        out.u32(w); out.u32(h); out.u32(bpp)
        val levelPos = out.size; out.u64(0); out.u64(0)
        out.patch64(levelPos, out.size.toLong())
        out.u32(w); out.u32(h)
        val tilesX = (w + 63) / 64; val tilesY = (h + 63) / 64
        val tilePos = IntArray(tilesX * tilesY) { val p = out.size; out.u64(0); p }
        out.u64(0)
        val deflater = Deflater(6)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val tw = minOf(64, w - tx * 64); val th = minOf(64, h - ty * 64)
            val raw = ByteArray(tw * th * bpp)
            var o = 0
            for (y in 0 until th) for (x in 0 until tw) {
                val c = px[(ty * 64 + y) * w + tx * 64 + x]
                if (bpp == 4) { raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte() }
                else raw[o++] = luma(c).toByte()
            }
            deflater.reset(); deflater.setInput(raw); deflater.finish()
            val chunk = ByteArray(raw.size + 64)
            val baos = ByteArrayOutputStream()
            while (!deflater.finished()) { val n = deflater.deflate(chunk); baos.write(chunk, 0, n) }
            out.patch64(tilePos[ty * tilesX + tx], out.size.toLong())
            out.bytes(baos.toByteArray())
        }
        deflater.end()
    }
}
