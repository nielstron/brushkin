package io.github.nielstron.brushkin.engine

import android.graphics.Color
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Runs [block] over row ranges on all cores. */
inline fun parallelRows(h: Int, crossinline block: (y0: Int, y1: Int) -> Unit) {
    val chunks = min(h, Runtime.getRuntime().availableProcessors() * 4).coerceAtLeast(1)
    IntStream.range(0, chunks).parallel().forEach { c ->
        val y0 = h * c / chunks
        val y1 = h * (c + 1) / chunks
        if (y1 > y0) block(y0, y1)
    }
}

enum class Channel(val label: String) { VALUE("Value"), RED("Red"), GREEN("Green"), BLUE("Blue"), ALPHA("Alpha") }

/** A curve is a list of control points in 0..1, sorted by x. */
typealias Curve = List<Pair<Float, Float>>

val IDENTITY_CURVE: Curve = listOf(0f to 0f, 1f to 1f)

/** Monotone cubic (Fritsch–Carlson) interpolation, sampled to a 256-entry LUT. */
fun curveLut(points: Curve): IntArray {
    val p = points.sortedBy { it.first }
    val n = p.size
    val lut = IntArray(256)
    if (n < 2) { for (i in 0..255) lut[i] = i; return lut }
    val xs = FloatArray(n) { p[it].first }; val ys = FloatArray(n) { p[it].second }
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it]) }
    val m = FloatArray(n)
    m[0] = d[0]; m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0f else (d[i - 1] + d[i]) / 2
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]; val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9) { val t = 3 / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
    }
    for (i in 0..255) {
        val x = i / 255f
        val y = when {
            x <= xs[0] -> ys[0]
            x >= xs[n - 1] -> ys[n - 1]
            else -> {
                var k = 0
                while (k < n - 2 && x > xs[k + 1]) k++
                val h = xs[k + 1] - xs[k]
                val t = (x - xs[k]) / h
                val t2 = t * t; val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
            }
        }
        lut[i] = (y * 255).roundToInt().coerceIn(0, 255)
    }
    return lut
}

sealed interface Filter {
    val name: String
    fun apply(src: IntArray, w: Int, h: Int): IntArray
}

/** Per-channel LUT filter used by Curves, Brightness-Contrast, Invert, Threshold, Posterize. */
open class LutFilter(override val name: String, private val v: IntArray, private val r: IntArray, private val g: IntArray, private val b: IntArray, private val a: IntArray? = null) : Filter {
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                val al = c ushr 24
                val rr = v[r[(c shr 16) and 0xFF]]; val gg = v[g[(c shr 8) and 0xFF]]; val bb = v[b[c and 0xFF]]
                out[i] = ((a?.get(al) ?: al) shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return out
    }

    companion object {
        val ID = IntArray(256) { it }
        fun curves(curves: Map<Channel, Curve>) = LutFilter(
            "Curves",
            curveLut(curves[Channel.VALUE] ?: IDENTITY_CURVE), curveLut(curves[Channel.RED] ?: IDENTITY_CURVE),
            curveLut(curves[Channel.GREEN] ?: IDENTITY_CURVE), curveLut(curves[Channel.BLUE] ?: IDENTITY_CURVE),
            curves[Channel.ALPHA]?.let { curveLut(it) },
        )

        fun brightnessContrast(brightness: Float, contrast: Float): LutFilter {
            // GIMP semantics: both in -1..1.
            val slope = kotlin.math.tan((contrast.coerceIn(-0.99f, 0.99f) + 1) * Math.PI / 4).toFloat()
            val lut = IntArray(256) {
                var v = it / 255f
                v = if (brightness < 0) v * (1 + brightness) else v + (1 - v) * brightness
                v = (v - 0.5f) * slope + 0.5f
                (v * 255).roundToInt().coerceIn(0, 255)
            }
            return LutFilter("Brightness-Contrast", lut, ID, ID, ID)
        }

        /** GIMP Levels on the value channel: input range + gamma, then output range. */
        fun levels(inLow: Float, inHigh: Float, gamma: Float, outLow: Float, outHigh: Float): LutFilter {
            val lut = IntArray(256) {
                val v = ((it - inLow) / (inHigh - inLow).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val g = Math.pow(v.toDouble(), 1.0 / gamma.coerceAtLeast(0.01f)).toFloat()
                (outLow + g * (outHigh - outLow)).roundToInt().coerceIn(0, 255)
            }
            return LutFilter("Levels", lut, ID, ID, ID)
        }

        fun invert() = IntArray(256) { 255 - it }.let { LutFilter("Invert", it, ID, ID, ID) }
        fun posterize(levels: Int) = IntArray(256) { ((it / 255f * (levels - 1)).roundToInt() * 255f / (levels - 1)).roundToInt() }
            .let { LutFilter("Posterize", it, ID, ID, ID) }
    }
}

class ThresholdFilter(private val low: Int, private val high: Int) : Filter {
    override val name = "Threshold"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { out ->
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                val l = luma(c)
                out[i] = (c and 0xFF000000.toInt()) or (if (l in low..high) 0xFFFFFF else 0)
            }
        }
    }
}

class DesaturateFilter : Filter {
    override val name = "Desaturate"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { out ->
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]; val l = luma(c)
                out[i] = (c and 0xFF000000.toInt()) or (l shl 16) or (l shl 8) or l
            }
        }
    }
}

class HueSaturationFilter(private val hue: Float, private val saturation: Float, private val lightness: Float) : Filter {
    override val name = "Hue-Saturation"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { out ->
        parallelRows(h) { y0, y1 ->
            val hsl = FloatArray(3)
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                if (c ushr 24 == 0) { out[i] = c; continue }
                androidx.core.graphics.ColorUtils.colorToHSL(c, hsl)
                hsl[0] = ((hsl[0] + hue) % 360 + 360) % 360
                hsl[1] = (if (saturation > 0) hsl[1] + (1 - hsl[1]) * saturation else hsl[1] * (1 + saturation)).coerceIn(0f, 1f)
                hsl[2] = (if (lightness > 0) hsl[2] + (1 - hsl[2]) * lightness else hsl[2] * (1 + lightness)).coerceIn(0f, 1f)
                out[i] = (androidx.core.graphics.ColorUtils.HSLToColor(hsl) and 0xFFFFFF) or (c and 0xFF000000.toInt())
            }
        }
    }
}

class ColorBalanceFilter(private val cyanRed: Float, private val magentaGreen: Float, private val yellowBlue: Float) : Filter {
    override val name = "Color Balance"
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val r = IntArray(256) { (it + cyanRed * 100).roundToInt().coerceIn(0, 255) }
        val g = IntArray(256) { (it + magentaGreen * 100).roundToInt().coerceIn(0, 255) }
        val b = IntArray(256) { (it + yellowBlue * 100).roundToInt().coerceIn(0, 255) }
        return LutFilter(name, LutFilter.ID, r, g, b).apply(src, w, h)
    }
}

class GaussianBlurFilter(private val radius: Float) : Filter {
    override val name = "Gaussian Blur"
    override fun apply(src: IntArray, w: Int, h: Int) = blurArgb(src, w, h, radius.roundToInt())
}

class UnsharpMaskFilter(private val radius: Float, private val amount: Float) : Filter {
    override val name = "Unsharp Mask"
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val blur = blurArgb(src, w, h, radius.roundToInt())
        val out = IntArray(src.size)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]; val bl = blur[i]
                fun ch(sh: Int): Int {
                    val o = (c shr sh) and 0xFF; val b = (bl shr sh) and 0xFF
                    return (o + (o - b) * amount).roundToInt().coerceIn(0, 255)
                }
                out[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }
}

class PixelizeFilter(private val block: Int) : Filter {
    override val name = "Pixelize"
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        val bs = block.coerceAtLeast(1)
        val by = (h + bs - 1) / bs
        IntStream.range(0, by).parallel().forEach { bj ->
            val y0 = bj * bs; val y1 = min(h, y0 + bs)
            var x0 = 0
            while (x0 < w) {
                val x1 = min(w, x0 + bs)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = src[y * w + x]; val al = c ushr 24
                    a += al; r += ((c shr 16) and 0xFF) * al; g += ((c shr 8) and 0xFF) * al; b += (c and 0xFF) * al; n++
                }
                val c = if (a == 0L) 0 else Color.argb((a / n).toInt(), (r / a).toInt(), (g / a).toInt(), (b / a).toInt())
                for (y in y0 until y1) for (x in x0 until x1) out[y * w + x] = c
                x0 = x1
            }
        }
        return out
    }
}

class NoiseFilter(private val amount: Float, private val seed: Long = 42) : Filter {
    override val name = "RGB Noise"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { out ->
        parallelRows(h) { y0, y1 ->
            val rnd = Random(seed + y0)
            val a = amount * 255
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                fun ch(sh: Int) = (((c shr sh) and 0xFF) + (rnd.nextFloat() - 0.5f) * 2 * a).roundToInt().coerceIn(0, 255)
                out[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }
}

fun luma(c: Int) = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8)

/** Premultiplied 3-pass box blur on ARGB pixels, one channel per core. */
fun blurArgb(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius < 1) return src.copyOf()
    val r = max(1, radius / 2)
    val n = w * h
    val ch = Array(4) { IntArray(n) }
    for (i in 0 until n) {
        val c = src[i]; val a = c ushr 24
        ch[0][i] = a
        ch[1][i] = ((c shr 16) and 0xFF) * a / 255
        ch[2][i] = ((c shr 8) and 0xFF) * a / 255
        ch[3][i] = (c and 0xFF) * a / 255
    }
    IntStream.range(0, 4).parallel().forEach { k ->
        val a = ch[k]; val tmp = IntArray(n)
        repeat(3) { Selection.boxH(a, tmp, w, h, r); Selection.boxV(tmp, a, w, h, r) }
    }
    val out = IntArray(n)
    parallelRows(h) { y0, y1 ->
        for (i in y0 * w until y1 * w) {
            val a = ch[0][i]
            out[i] = if (a == 0) 0 else Color.argb(a, min(255, ch[1][i] * 255 / a), min(255, ch[2][i] * 255 / a), min(255, ch[3][i] * 255 / a))
        }
    }
    return out
}

/** Blend [filtered] over [orig] by the selection mask (255 = fully filtered). */
fun maskBlend(orig: IntArray, filtered: IntArray, mask: ByteArray?, w: Int, h: Int): IntArray {
    if (mask == null) return filtered
    val out = IntArray(orig.size)
    parallelRows(h) { y0, y1 ->
        for (i in y0 * w until y1 * w) {
            val m = mask[i].toInt() and 0xFF
            out[i] = when (m) {
                0 -> orig[i]
                255 -> filtered[i]
                else -> lerpArgb(orig[i], filtered[i], m)
            }
        }
    }
    return out
}

fun lerpArgb(a: Int, b: Int, t255: Int): Int {
    fun ch(sh: Int) = (((a shr sh) and 0xFF) * (255 - t255) + ((b shr sh) and 0xFF) * t255) / 255
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Scanline flood fill. Returns a 0/255 mask of pixels connected to (sx, sy) whose color is within
 * [threshold] (max channel difference, alpha included) of the seed color.
 */
fun floodFill(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, threshold: Int, contiguous: Boolean = true): ByteArray {
    val mask = ByteArray(w * h)
    if (sx !in 0 until w || sy !in 0 until h) return mask
    val seed = px[sy * w + sx]
    fun match(c: Int): Boolean {
        if (c == seed) return true
        return abs((c ushr 24) - (seed ushr 24)) <= threshold &&
            abs(((c shr 16) and 0xFF) - ((seed shr 16) and 0xFF)) <= threshold &&
            abs(((c shr 8) and 0xFF) - ((seed shr 8) and 0xFF)) <= threshold &&
            abs((c and 0xFF) - (seed and 0xFF)) <= threshold
    }
    if (!contiguous) {
        parallelRows(h) { y0, y1 -> for (i in y0 * w until y1 * w) if (match(px[i])) mask[i] = -1 }
        return mask
    }
    var stack = IntArray(1024)
    var sp = 0
    fun push(v: Int) { if (sp == stack.size) stack = stack.copyOf(sp * 2); stack[sp++] = v }
    push(sy * w + sx)
    while (sp > 0) {
        val idx = stack[--sp]
        val y = idx / w
        var x = idx % w
        if (mask[idx].toInt() != 0) continue
        while (x > 0 && mask[y * w + x - 1].toInt() == 0 && match(px[y * w + x - 1])) x--
        var spanUp = false; var spanDown = false
        while (x < w && mask[y * w + x].toInt() == 0 && match(px[y * w + x])) {
            mask[y * w + x] = -1
            if (y > 0) {
                val up = (y - 1) * w + x
                val m = mask[up].toInt() == 0 && match(px[up])
                if (m && !spanUp) { push(up); spanUp = true } else if (!m) spanUp = false
            }
            if (y < h - 1) {
                val dn = (y + 1) * w + x
                val m = mask[dn].toInt() == 0 && match(px[dn])
                if (m && !spanDown) { push(dn); spanDown = true } else if (!m) spanDown = false
            }
            x++
        }
    }
    return mask
}
