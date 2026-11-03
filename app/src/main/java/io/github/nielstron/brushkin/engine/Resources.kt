package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** An image brush (GIMP .gbr, or the cells of an animated .gih). Frames are coverage masks. */
class ImageBrush(val name: String, val frames: List<Bitmap>, val spacing: Float, val file: String? = null)

/**
 * A gradient as GIMP segments. Colors may refer to the foreground/background color, so the
 * gradient is resolved into concrete stops only when it's used.
 */
class GradientRes(val name: String, val segments: List<Segment>, val file: String? = null) {
    /** [kind]: 0 fixed, 1 FG, 2 FG transparent, 3 BG, 4 BG transparent (GIMP's color types). */
    data class Segment(val left: Float, val middle: Float, val right: Float, val c0: Int, val c1: Int, val blend: Int = 0, val k0: Int = 0, val k1: Int = 0)

    /** Colors and positions for a shader, sampling curved blend functions. */
    fun stops(fg: Int, bg: Int, reverse: Boolean = false): Pair<IntArray, FloatArray> {
        val cols = ArrayList<Int>(); val pos = ArrayList<Float>()
        for (s in segments) {
            val a = resolve(s.c0, s.k0, fg, bg); val b = resolve(s.c1, s.k1, fg, bg)
            val n = if (s.blend == 0 && s.middle == (s.left + s.right) / 2) 1 else 8
            for (k in 0..n) {
                val x = s.left + (s.right - s.left) * k / n
                cols.add(lerpColor(a, b, blendFactor(s, x))); pos.add(x)
            }
        }
        val c = cols.toIntArray(); val p = pos.toFloatArray()
        if (reverse) { c.reverse(); p.reverse(); for (i in p.indices) p[i] = 1f - p[i] }
        return c to p
    }

    companion object {
        fun resolve(c: Int, kind: Int, fg: Int, bg: Int) = when (kind) {
            1 -> fg or 0xFF000000.toInt(); 2 -> fg and 0x00FFFFFF; 3 -> bg or 0xFF000000.toInt(); 4 -> bg and 0x00FFFFFF
            else -> c
        }

        /** GIMP's blend functions (linear, curved, sine, sphere increasing/decreasing, step). */
        fun blendFactor(s: Segment, x: Float): Float {
            val len = (s.right - s.left).coerceAtLeast(1e-6f)
            val pos = ((x - s.left) / len).coerceIn(0f, 1f)
            val mid = ((s.middle - s.left) / len).coerceIn(1e-4f, 1 - 1e-4f)
            // Linear position, bent so the middle point maps to 0.5.
            val lin = if (pos <= mid) 0.5f * pos / mid else 0.5f + 0.5f * (pos - mid) / (1 - mid)
            return when (s.blend) {
                1 -> pos.toDouble().pow(ln(0.5) / ln(mid.toDouble())).toFloat()
                2 -> ((sin(-PI / 2 + PI * lin) + 1) / 2).toFloat()
                3 -> sqrt(1 - (lin - 1) * (lin - 1))
                4 -> 1 - sqrt(1 - lin * lin)
                5 -> if (pos < mid) 0f else 1f
                else -> lin
            }
        }

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = (((a ushr sh) and 0xFF) + (((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * t).roundToInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Built-in gradients that follow the current colors. */
        val FG_BG = GradientRes("FG to BG", listOf(Segment(0f, 0.5f, 1f, 0, 0, 0, 1, 3)))
        val FG_TRANSPARENT = GradientRes("FG to transparent", listOf(Segment(0f, 0.5f, 1f, 0, 0, 0, 1, 2)))
    }
}

class PaletteRes(val name: String, val colors: List<Int>, val file: String? = null)

class PatternRes(val name: String, val bitmap: Bitmap, val file: String? = null)

/** A MyPaint brush (.myb), mapped onto Brushkin's dab engine. */
class MyPaintRes(val name: String, val settings: BrushSettings, val file: String? = null)

/** Parsers and writers for GIMP's and MyPaint's resource files. */
object GimpFormats {
    private fun u32(b: ByteBuffer) = b.int

    /** .gbr: header, name, then 8-bit grayscale (coverage) or RGBA pixels. */
    fun readGbr(bytes: ByteArray, fallbackName: String): ImageBrush {
        val b = ByteBuffer.wrap(bytes)
        return readGbrAt(b, fallbackName)
    }

    private fun readGbrAt(b: ByteBuffer, fallbackName: String): ImageBrush {
        val start = b.position()
        val headerSize = u32(b); val version = u32(b)
        val w = u32(b); val h = u32(b); val depth = u32(b)
        require(version in 1..3 && w in 1..8192 && h in 1..8192 && (depth == 1 || depth == 4)) { "Not a GIMP brush" }
        var spacing = 25
        if (version >= 2) { val magic = ByteArray(4); b.get(magic); require(String(magic) == "GIMP") { "Not a GIMP brush" }; spacing = u32(b) }
        val nameLen = headerSize - (b.position() - start)
        val name = if (nameLen > 0) { val n = ByteArray(nameLen); b.get(n); String(n, Charsets.UTF_8).trimEnd('\u0000').ifBlank { fallbackName } } else fallbackName
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = ByteArray(mask.rowBytes * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (depth == 1) b.get().toInt() and 0xFF else {
                // Color brushes paint with the current color here: their alpha is the shape.
                b.position(b.position() + 3); b.get().toInt() and 0xFF
            }
            px[y * mask.rowBytes + x] = v.toByte()
        }
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(px))
        return ImageBrush(name, listOf(mask), spacing / 100f)
    }

    /** .gih: a text header (name, cell count and selection parameters) followed by .gbr cells. */
    fun readGih(bytes: ByteArray, fallbackName: String): ImageBrush {
        var p = 0
        fun line(): String { val s = p; while (p < bytes.size && bytes[p] != '\n'.code.toByte()) p++; val l = String(bytes, s, p - s, Charsets.UTF_8); p++; return l }
        val name = line().trim().ifBlank { fallbackName }
        val params = line()
        val n = params.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: error("Not a GIMP brush pipe")
        val b = ByteBuffer.wrap(bytes); b.position(p)
        val frames = ArrayList<Bitmap>(); var spacing = 0.25f
        repeat(n) { if (b.remaining() > 28) { val br = readGbrAt(b, name); frames.addAll(br.frames); spacing = br.spacing } }
        require(frames.isNotEmpty()) { "Empty brush pipe" }
        return ImageBrush(name, frames, spacing)
    }

    /** .pat: header ("GPAT"), name, then gray, gray+alpha, RGB or RGBA pixels. */
    fun readPat(bytes: ByteArray, fallbackName: String): PatternRes {
        val b = ByteBuffer.wrap(bytes)
        val headerSize = u32(b); val version = u32(b); val w = u32(b); val h = u32(b); val depth = u32(b)
        require(version == 1 && w in 1..8192 && h in 1..8192 && depth in 1..4) { "Not a GIMP pattern" }
        val magic = ByteArray(4); b.get(magic); require(String(magic) == "GPAT") { "Not a GIMP pattern" }
        val nameLen = headerSize - 24
        val name = if (nameLen > 0) { val n = ByteArray(nameLen); b.get(n); String(n, Charsets.UTF_8).trimEnd('\u0000').ifBlank { fallbackName } } else fallbackName
        val px = IntArray(w * h) {
            when (depth) {
                1 -> { val g = b.get().toInt() and 0xFF; Color.rgb(g, g, g) }
                2 -> { val g = b.get().toInt() and 0xFF; val a = b.get().toInt() and 0xFF; Color.argb(a, g, g, g) }
                3 -> Color.rgb(b.get().toInt() and 0xFF, b.get().toInt() and 0xFF, b.get().toInt() and 0xFF)
                else -> { val r = b.get().toInt() and 0xFF; val g = b.get().toInt() and 0xFF; val bl = b.get().toInt() and 0xFF; Color.argb(b.get().toInt() and 0xFF, r, g, bl) }
            }
        }
        return PatternRes(name, Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888))
    }

    fun writePat(name: String, bmp: Bitmap): ByteArray {
        val nb = (name + "\u0000").toByteArray(Charsets.UTF_8)
        val w = bmp.width; val h = bmp.height
        val b = ByteBuffer.allocate(24 + nb.size + w * h * 4)
        b.putInt(24 + nb.size).putInt(1).putInt(w).putInt(h).putInt(4).put("GPAT".toByteArray()).put(nb)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (c in px) b.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
        return b.array()
    }

    /** Writes a grayscale .gbr (v2) from a coverage mask. */
    fun writeGbr(name: String, mask: ByteArray, w: Int, h: Int, spacing: Int = 20): ByteArray {
        val nb = (name + "\u0000").toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(28 + nb.size + w * h)
        b.putInt(28 + nb.size).putInt(2).putInt(w).putInt(h).putInt(1).put("GIMP".toByteArray()).putInt(spacing).put(nb).put(mask, 0, w * h)
        return b.array()
    }

    /** .ggr: "GIMP Gradient", Name, segment count, then one segment per line. */
    fun readGgr(text: String, fallbackName: String): GradientRes {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.firstOrNull() == "GIMP Gradient") { "Not a GIMP gradient" }
        var i = 1
        val name = if (lines.getOrNull(i)?.startsWith("Name:") == true) lines[i++].removePrefix("Name:").trim() else fallbackName
        val n = lines[i++].toInt()
        val segs = (0 until n).map {
            val v = lines[i++].split(Regex("\\s+"))
            val f = v.take(11).map { it.toFloat() }
            fun col(o: Int) = Color.argb((f[o + 3] * 255).roundToInt().coerceIn(0, 255), (f[o] * 255).roundToInt().coerceIn(0, 255), (f[o + 1] * 255).roundToInt().coerceIn(0, 255), (f[o + 2] * 255).roundToInt().coerceIn(0, 255))
            GradientRes.Segment(f[0], f[1], f[2], col(3), col(7), v.getOrNull(11)?.toIntOrNull() ?: 0, v.getOrNull(13)?.toIntOrNull() ?: 0, v.getOrNull(14)?.toIntOrNull() ?: 0)
        }
        return GradientRes(name, segs)
    }

    fun writeGgr(g: GradientRes): String = buildString {
        append("GIMP Gradient\nName: ${g.name}\n${g.segments.size}\n")
        for (s in g.segments) {
            fun col(c: Int) = "%.6f %.6f %.6f %.6f".format(java.util.Locale.ROOT, Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f)
            append("%.6f %.6f %.6f ".format(java.util.Locale.ROOT, s.left, s.middle, s.right)).append(col(s.c0)).append(' ').append(col(s.c1))
            append(" ${s.blend} 0 ${s.k0} ${s.k1}\n")
        }
    }

    /** .gpl: "GIMP Palette", optional Name/Columns, then "r g b name" lines. */
    fun readGpl(text: String, fallbackName: String): PaletteRes {
        val lines = text.lines()
        require(lines.firstOrNull()?.trim() == "GIMP Palette") { "Not a GIMP palette" }
        var name = fallbackName
        val cols = ArrayList<Int>()
        for (l in lines.drop(1)) {
            val t = l.trim()
            when {
                t.startsWith("Name:") -> name = t.removePrefix("Name:").trim()
                t.startsWith("Columns:") || t.startsWith("#") || t.isEmpty() -> {}
                else -> {
                    val v = t.split(Regex("\\s+"))
                    if (v.size >= 3) { val r = v[0].toIntOrNull(); val g = v[1].toIntOrNull(); val b = v[2].toIntOrNull(); if (r != null && g != null && b != null) cols.add(Color.rgb(r, g, b)) }
                }
            }
        }
        return PaletteRes(name, cols)
    }

    fun writeGpl(p: PaletteRes): String = buildString {
        append("GIMP Palette\nName: ${p.name}\nColumns: 8\n#\n")
        for (c in p.colors) append("%3d %3d %3d\t#%06X\n".format(Color.red(c), Color.green(c), Color.blue(c), c and 0xFFFFFF))
    }

    /**
     * .myb (MyPaint 1.x JSON): base values and pressure/speed/random inputs of the main settings,
     * translated to Brushkin's brush parameters. Effects without an equivalent are ignored.
     */
    fun readMyb(text: String, fallbackName: String): MyPaintRes {
        val o = JSONObject(text)
        val st = o.optJSONObject("settings") ?: error("Not a MyPaint brush")
        fun base(k: String, def: Float) = st.optJSONObject(k)?.optDouble("base_value", def.toDouble())?.toFloat() ?: def
        fun input(k: String, inp: String): Float {
            // Strength of an input mapping: the y range of its curve.
            val pts = st.optJSONObject(k)?.optJSONObject("inputs")?.optJSONArray(inp) ?: return 0f
            var lo = 0f; var hi = 0f
            for (i in 0 until pts.length()) { val y = pts.getJSONArray(i).getDouble(1).toFloat(); lo = minOf(lo, y); hi = maxOf(hi, y) }
            return if (kotlin.math.abs(hi) >= kotlin.math.abs(lo)) hi else lo
        }
        // Size at full pressure (Brushkin scales down from there with lighter pressure).
        val radius = exp(base("radius_logarithmic", 2f) + input("radius_logarithmic", "pressure").coerceAtLeast(0f))
        val dabs = base("dabs_per_actual_radius", 2f) + base("dabs_per_basic_radius", 0f)
        val opaque = base("opaque", 1f).coerceIn(0.02f, 1f)
        val ratio = base("elliptical_dab_ratio", 1f).coerceAtLeast(1f)
        val s = BrushSettings(
            size = (radius * 2).coerceIn(1f, 1000f),
            opacity = opaque,
            flow = (base("opaque_multiply", 0f).let { if (it > 0f) it else 1f } * opaque).coerceIn(0.02f, 1f).let { if (input("opaque_multiply", "pressure") > 0f) 1f else it },
            hardness = base("hardness", 0.8f).coerceIn(0f, 1f),
            spacing = (1f / (2f * dabs.coerceAtLeast(0.2f))).coerceIn(0.02f, 1f),
            aspect = (1f / ratio).coerceIn(0.05f, 1f),
            angle = base("elliptical_dab_angle", 0f),
            pressureSize = input("radius_logarithmic", "pressure") > 0.05f,
            pressureOpacity = input("opaque_multiply", "pressure") > 0.05f || input("opaque", "pressure") > 0.05f,
            smoothing = (base("slow_tracking", 0f) / 10f).coerceIn(0f, 0.9f),
            randomSize = (base("radius_by_random", 0f)).coerceIn(0f, 1f),
            velocitySize = (input("radius_logarithmic", "speed1") + input("radius_logarithmic", "speed2")).coerceIn(-1f, 1f),
            randomOpacity = input("opaque", "random").coerceIn(0f, 1f),
            shape = if (ratio > 1.2f) TipShape.FLAT else TipShape.ROUND,
        )
        return MyPaintRes(fallbackName, s)
    }
}

/**
 * Imported and user-made resources, kept as their original files under [dir] so they survive
 * restarts and can be exported again. All loading happens off the UI thread.
 */
class ResourceStore(private val dir: File) {
    val brushes = MutableStateFlow<List<ImageBrush>>(emptyList())
    val gradients = MutableStateFlow(listOf(GradientRes.FG_BG, GradientRes.FG_TRANSPARENT))
    val palettes = MutableStateFlow<List<PaletteRes>>(emptyList())
    val patterns = MutableStateFlow<List<PatternRes>>(emptyList())
    val mypaint = MutableStateFlow<List<MyPaintRes>>(emptyList())

    /** Brushes looked up by name from the paint engine. */
    val brushByName = ConcurrentHashMap<String, ImageBrush>()

    fun load() {
        dir.mkdirs()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f -> runCatching { add(f.name, f.readBytes(), f.name) } }
        if (mypaint.value.isEmpty()) for ((n, json) in BUILTIN_MYPAINT) mypaint.value = mypaint.value + GimpFormats.readMyb(json, n)
    }

    /** Parses a resource by extension; returns what kind it was. Throws on unknown/invalid files. */
    fun import(fileName: String, bytes: ByteArray): String {
        val safe = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "resource" }
        var target = File(dir, safe); var k = 2
        while (target.exists()) target = File(dir, safe.substringBeforeLast('.') + " $k." + safe.substringAfterLast('.')).also { k++ }
        val kind = add(target.name, bytes, target.name)
        dir.mkdirs(); target.writeBytes(bytes)
        return kind
    }

    private fun add(fileName: String, bytes: ByteArray, file: String): String {
        val base = fileName.substringBeforeLast('.')
        return when (fileName.substringAfterLast('.').lowercase()) {
            "gbr" -> GimpFormats.readGbr(bytes, base).let { ImageBrush(it.name, it.frames, it.spacing, file) }.also { addBrush(it) }.let { "brush" }
            "gih" -> GimpFormats.readGih(bytes, base).let { ImageBrush(it.name, it.frames, it.spacing, file) }.also { addBrush(it) }.let { "brush" }
            "ggr" -> GimpFormats.readGgr(String(bytes), base).let { GradientRes(it.name, it.segments, file) }.also { gradients.value = gradients.value + it }.let { "gradient" }
            "gpl" -> GimpFormats.readGpl(String(bytes), base).let { PaletteRes(it.name, it.colors, file) }.also { palettes.value = palettes.value + it }.let { "palette" }
            "pat" -> GimpFormats.readPat(bytes, base).let { PatternRes(it.name, it.bitmap, file) }.also { patterns.value = patterns.value + it }.let { "pattern" }
            "myb" -> GimpFormats.readMyb(String(bytes), base).let { MyPaintRes(it.name, it.settings, file) }.also { mypaint.value = mypaint.value + it }.let { "MyPaint brush" }
            else -> error("Brushkin can import .gbr, .gih, .ggr, .gpl, .pat and .myb files")
        }
    }

    private fun addBrush(b: ImageBrush) { brushByName[b.name] = b; brushes.value = brushes.value.filter { it.name != b.name } + b }

    /** Saves (or replaces) a user palette. */
    fun savePalette(p: PaletteRes, replacing: PaletteRes? = null): PaletteRes {
        val file = replacing?.file ?: uniqueFile(p.name, "gpl")
        File(dir, file).writeText(GimpFormats.writeGpl(p))
        val saved = PaletteRes(p.name, p.colors, file)
        palettes.value = palettes.value.map { if (it === replacing) saved else it }.let { if (replacing == null) it + saved else it }
        return saved
    }

    fun saveGradient(g: GradientRes, replacing: GradientRes? = null): GradientRes {
        val file = replacing?.file ?: uniqueFile(g.name, "ggr")
        File(dir, file).writeText(GimpFormats.writeGgr(g))
        val saved = GradientRes(g.name, g.segments, file)
        gradients.value = gradients.value.map { if (it === replacing) saved else it }.let { if (replacing == null || replacing.file == null) it + saved else it }
        return saved
    }

    fun saveBrush(name: String, mask: ByteArray, w: Int, h: Int): ImageBrush {
        val file = uniqueFile(name, "gbr")
        val bytes = GimpFormats.writeGbr(name, mask, w, h)
        File(dir, file).writeBytes(bytes)
        return GimpFormats.readGbr(bytes, name).let { ImageBrush(it.name, it.frames, it.spacing, file) }.also { addBrush(it) }
    }

    fun savePattern(name: String, bmp: Bitmap): PatternRes {
        val file = uniqueFile(name, "pat")
        File(dir, file).writeBytes(GimpFormats.writePat(name, bmp))
        return PatternRes(name, bmp, file).also { patterns.value = patterns.value + it }
    }

    /** Replaces a pattern's pixels and/or name (its file is rewritten). */
    fun updatePattern(old: PatternRes, name: String, bmp: Bitmap): PatternRes {
        old.file?.let { File(dir, it).delete() }
        val file = uniqueFile(name, "pat")
        File(dir, file).writeBytes(GimpFormats.writePat(name, bmp))
        val p = PatternRes(name, bmp, file)
        patterns.value = patterns.value.map { if (it === old) p else it }
        return p
    }

    fun delete(file: String?) {
        if (file == null) return
        File(dir, file).delete()
        brushes.value.firstOrNull { it.file == file }?.let { b -> brushByName.remove(b.name) }
        brushes.value = brushes.value.filter { it.file != file }
        gradients.value = gradients.value.filter { it.file != file || it.file == null }
        palettes.value = palettes.value.filter { it.file != file }
        patterns.value = patterns.value.filter { it.file != file }
        mypaint.value = mypaint.value.filter { it.file != file }
    }

    private fun uniqueFile(name: String, ext: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "untitled" }
        var f = "$safe.$ext"; var k = 2
        while (File(dir, f).exists()) { f = "$safe $k.$ext"; k++ }
        return f
    }

    companion object {
        /** A few MyPaint-style brushes so the tool works before anything is imported. */
        val BUILTIN_MYPAINT = listOf(
            "Pencil" to """{"version":3,"settings":{"radius_logarithmic":{"base_value":0.4,"inputs":{"pressure":[[0,0],[1,0.4]]}},"opaque":{"base_value":0.7},"opaque_multiply":{"base_value":0,"inputs":{"pressure":[[0,0],[1,1]]}},"hardness":{"base_value":0.9},"dabs_per_actual_radius":{"base_value":4},"radius_by_random":{"base_value":0.1}}}""",
            "Charcoal" to """{"version":3,"settings":{"radius_logarithmic":{"base_value":1.6,"inputs":{"pressure":[[0,0],[1,0.5]]}},"opaque":{"base_value":0.5,"inputs":{"random":[[0,0],[1,0.5]]}},"hardness":{"base_value":0.5},"dabs_per_actual_radius":{"base_value":3},"radius_by_random":{"base_value":0.4}}}""",
            "Ink pen" to """{"version":3,"settings":{"radius_logarithmic":{"base_value":1.0,"inputs":{"pressure":[[0,0],[1,0.6]],"speed1":[[0,0],[1,-0.5]]}},"opaque":{"base_value":1},"hardness":{"base_value":0.95},"dabs_per_actual_radius":{"base_value":6},"slow_tracking":{"base_value":3}}}""",
            "Calligraphy" to """{"version":3,"settings":{"radius_logarithmic":{"base_value":2.0,"inputs":{"pressure":[[0,0],[1,0.4]]}},"opaque":{"base_value":1},"hardness":{"base_value":0.9},"dabs_per_actual_radius":{"base_value":5},"elliptical_dab_ratio":{"base_value":5},"elliptical_dab_angle":{"base_value":45}}}""",
        )
    }
}

/** Pattern editing operations (GIMP has these spread over filters; here they're one editor). */
object PatternOps {
    fun scale(b: Bitmap, k: Float): Bitmap =
        Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceIn(2, 2048), (b.height * k).roundToInt().coerceIn(2, 2048), true)

    /** Shifts by half the size, wrapping around: shows how the tile's edges meet. */
    fun offsetHalf(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        for (dy in listOf(0, -h)) for (dx in listOf(0, -w)) c.drawBitmap(b, (w / 2 + dx).toFloat(), (h / 2 + dy).toFloat(), null)
        return out
    }

    /**
     * GIMP's Tile Seamless: blend with a half-width-shifted copy (weighted away from the left/right
     * seam), then the same vertically.
     */
    fun seamless(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        var a = IntArray(w * h); b.getPixels(a, 0, w, 0, 0, w, h)
        fun pass(src: IntArray, horizontal: Boolean): IntArray {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val ox = if (horizontal) (x + w / 2) % w else x; val oy = if (horizontal) y else (y + h / 2) % h
                val t = if (horizontal) 1f - kotlin.math.abs(2f * (x + 0.5f) / w - 1f) else 1f - kotlin.math.abs(2f * (y + 0.5f) / h - 1f)
                out[y * w + x] = GradientRes.lerpColor(src[oy * w + ox], src[y * w + x], t)
            }
            return out
        }
        a = pass(pass(a, true), false)
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Generated patterns in two colors: 0 checker, 1 stripes, 2 dots, 3 diagonal stripes. */
    fun generate(kind: Int, cell: Int, fg: Int, bg: Int): Bitmap {
        val n = cell.coerceIn(2, 512); val s = n * 2
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); c.drawColor(bg)
        val p = android.graphics.Paint().apply { color = fg; isAntiAlias = true }
        when (kind) {
            0 -> { c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p); c.drawRect(n.toFloat(), n.toFloat(), s.toFloat(), s.toFloat(), p) }
            1 -> c.drawRect(0f, 0f, n.toFloat(), s.toFloat(), p)
            2 -> { c.drawCircle(n / 2f, n / 2f, n * 0.3f, p); c.drawCircle(n * 1.5f, n * 1.5f, n * 0.3f, p) }
            else -> {
                // Diagonal bands that wrap seamlessly.
                val path = android.graphics.Path()
                for (k in -2..2) { val o = k * s.toFloat(); path.moveTo(o, 0f); path.lineTo(o + n, 0f); path.lineTo(o + n + s, s.toFloat()); path.lineTo(o + s, s.toFloat()); path.close() }
                c.drawPath(path, p)
            }
        }
        return out
    }
}

/** The app-wide resource store (resources are shared by all documents, like in GIMP). */
object Library {
    @Volatile var store: ResourceStore? = null
}
