package io.github.nielstron.brushkin.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------- helpers

private fun a(c: Int) = c ushr 24
private fun r(c: Int) = (c shr 16) and 0xFF
private fun g(c: Int) = (c shr 8) and 0xFF
private fun b(c: Int) = c and 0xFF
private fun argb(a: Int, r: Int, g: Int, b: Int) =
    (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

private val TO_LIN = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
private fun toSrgb(v: Float): Int {
    val c = v.coerceIn(0f, 1f)
    return ((if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f) * 255f).roundToInt()
}

/** Bilinear sample with premultiplied interpolation; outside the image is transparent. */
private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
    val x0 = kotlin.math.floor(x - 0.5f).toInt(); val y0 = kotlin.math.floor(y - 0.5f).toInt()
    val fx = x - 0.5f - x0; val fy = y - 0.5f - y0
    var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
    for (j in 0..1) for (i in 0..1) {
        val px = x0 + i; val py = y0 + j
        if (px < 0 || py < 0 || px >= w || py >= h) continue
        val wgt = (if (i == 0) 1 - fx else fx) * (if (j == 0) 1 - fy else fy)
        val c = src[py * w + px]; val al = a(c) * wgt
        sa += al; sr += r(c) * al; sg += g(c) * al; sb += b(c) * al
    }
    if (sa <= 0.001f) return 0
    return argb(sa.roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
}

/** A filter that remaps each output pixel to a source position. */
private abstract class Distort(override val name: String) : Filter {
    override val wholeImage get() = true
    abstract fun map(x: Float, y: Float, w: Int, h: Int, out: FloatArray)
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { o ->
        parallelRows(h) { y0, y1 ->
            val p = FloatArray(2)
            for (y in y0 until y1) for (x in 0 until w) {
                map(x + 0.5f, y + 0.5f, w, h, p)
                o[y * w + x] = sample(src, w, h, p[0], p[1])
            }
        }
    }
}

// ---------------------------------------------------------------------------- light & shadow

/** Shadow of the layer's own alpha, offset and blurred, placed underneath it. */
class DropShadowFilter(private val dx: Float, private val dy: Float, private val blur: Float, private val opacity: Float, private val color: Int) : Filter {
    override val name = "Drop Shadow"
    override val margin get() = (abs(dx) + abs(dy) + blur * 2).roundToInt() + 4
    override fun scaled(k: Float) = DropShadowFilter(dx * k, dy * k, blur * k, opacity, color)
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val ox = dx.roundToInt(); val oy = dy.roundToInt()
        val shadow = IntArray(src.size)
        val cr = r(color); val cg = g(color); val cb = b(color)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - ox; val sy = y - oy
            if (sx in 0 until w && sy in 0 until h) shadow[y * w + x] = argb((a(src[sy * w + sx]) * opacity).roundToInt(), cr, cg, cb)
        }
        val blurred = blurArgb(shadow, w, h, blur.roundToInt())
        return IntArray(src.size) { i -> over(src[i], blurred[i]) }
    }
}

/** Source-over of [top] onto [bottom] (unpremultiplied ints). */
fun over(top: Int, bottom: Int): Int {
    val ta = a(top) / 255f; val ba = a(bottom) / 255f
    val oa = ta + ba * (1 - ta)
    if (oa <= 0f) return 0
    fun ch(t: Int, bo: Int) = ((t * ta + bo * ba * (1 - ta)) / oa).roundToInt()
    return argb((oa * 255).roundToInt(), ch(r(top), r(bottom)), ch(g(top), g(bottom)), ch(b(top), b(bottom)))
}

class VignetteFilter(private val radius: Float, private val softness: Float, private val strength: Float, private val color: Int) : Filter {
    override val name = "Vignette"
    override val wholeImage get() = true
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { o ->
        val cx = w / 2f; val cy = h / 2f
        val norm = hypot(cx, cy)
        val inner = (radius - softness).coerceAtLeast(0f)
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy) / norm
                val t = if (d <= inner) 0f else ((d - inner) / max(softness, 1e-3f)).coerceIn(0f, 1f)
                val k = t * t * (3 - 2 * t) * strength
                val c = src[y * w + x]
                fun ch(v: Int, cv: Int) = (v + (cv - v) * k).roundToInt()
                o[y * w + x] = argb(a(c), ch(r(c), r(color)), ch(g(c), g(color)), ch(b(c), b(color)))
            }
        }
    }
}

// ---------------------------------------------------------------------------- blur

class MotionBlurFilter(private val length: Float, private val angle: Float) : Filter {
    override val name = "Motion Blur"
    override val margin get() = length.roundToInt() + 2
    override fun scaled(k: Float) = MotionBlurFilter(length * k, angle)
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val n = max(1, length.roundToInt())
        val ux = cos(angle * PI / 180).toFloat(); val uy = sin(angle * PI / 180).toFloat()
        return IntArray(src.size).also { o ->
            parallelRows(h) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until w) {
                    var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                    for (i in 0..n) {
                        val t = i.toFloat() / n - 0.5f
                        val px = (x + ux * t * length).roundToInt().coerceIn(0, w - 1)
                        val py = (y + uy * t * length).roundToInt().coerceIn(0, h - 1)
                        val c = src[py * w + px]; val al = a(c).toFloat()
                        sa += al; sr += r(c) * al; sg += g(c) * al; sb += b(c) * al
                    }
                    o[y * w + x] = if (sa <= 0f) 0 else argb((sa / (n + 1)).roundToInt(), (sr / sa).roundToInt(), (sg / sa).roundToInt(), (sb / sa).roundToInt())
                }
            }
        }
    }
}

/** Edge-preserving smoothing (iterated bilateral filter). */
class NoiseReductionFilter(private val strength: Int) : Filter {
    override val name = "Noise Reduction"
    override val margin get() = strength * 2 + 2
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        if (strength < 1) return src.copyOf()
        var cur = src
        repeat(strength.coerceIn(1, 10)) {
            val prev = cur
            cur = IntArray(prev.size).also { o ->
                parallelRows(h) { y0, y1 ->
                    for (y in y0 until y1) for (x in 0 until w) {
                        val c0 = prev[y * w + x]
                        var ws = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                        for (j in -2..2) for (i in -2..2) {
                            val px = (x + i).coerceIn(0, w - 1); val py = (y + j).coerceIn(0, h - 1)
                            val c = prev[py * w + px]
                            val dc = abs(r(c) - r(c0)) + abs(g(c) - g(c0)) + abs(b(c) - b(c0))
                            val wt = exp(-(dc * dc) / 1800f - (i * i + j * j) / 8f)
                            ws += wt; sr += r(c) * wt; sg += g(c) * wt; sb += b(c) * wt
                        }
                        o[y * w + x] = argb(a(c0), (sr / ws).roundToInt(), (sg / ws).roundToInt(), (sb / ws).roundToInt())
                    }
                }
            }
        }
        return cur
    }
}

// ---------------------------------------------------------------------------- colors

/** GIMP's Color to Alpha: remove [color], keeping what differs from it. */
class ColorToAlphaFilter(private val color: Int) : Filter {
    override val name = "Color to Alpha"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { o ->
        val k = floatArrayOf(r(color) / 255f, g(color) / 255f, b(color) / 255f)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                val v = floatArrayOf(r(c) / 255f, g(c) / 255f, b(c) / 255f)
                var al = 0f
                for (ch in 0..2) {
                    val d = v[ch] - k[ch]
                    val lim = if (d > 0) 1 - k[ch] else k[ch]
                    if (lim > 1e-4f) al = max(al, abs(d) / lim)
                }
                if (al <= 1e-4f) { o[i] = 0; continue }
                fun out(ch: Int) = (((v[ch] - k[ch]) / al + k[ch]) * 255).roundToInt()
                o[i] = argb((a(c) * al).roundToInt(), out(0), out(1), out(2))
            }
        }
    }
}

class ShadowsHighlightsFilter(private val shadows: Float, private val highlights: Float, private val radius: Float) : Filter {
    override val name = "Shadows-Highlights"
    override val margin get() = (radius * 2).roundToInt() + 4
    override fun scaled(k: Float) = ShadowsHighlightsFilter(shadows, highlights, radius * k)
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        // Local brightness decides how much each pixel is lifted (shadows) or pulled down (highlights).
        val lum = IntArray(src.size) { val l = luma(src[it]); (255 shl 24) or (l shl 16) or (l shl 8) or l }
        val local = blurArgb(lum, w, h, radius.roundToInt())
        return IntArray(src.size).also { o ->
            parallelRows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val c = src[i]; val l = (local[i] and 0xFF) / 255f
                    val lift = shadows * (1 - l) * (1 - l)
                    val pull = highlights * l * l
                    fun ch(v: Int): Int {
                        val x = v / 255f
                        val y = x + lift * (1 - x) - pull * x
                        return (y * 255).roundToInt()
                    }
                    o[i] = argb(a(c), ch(r(c)), ch(g(c)), ch(b(c)))
                }
            }
        }
    }
}

class ExposureFilter(private val stops: Float, private val black: Float) : Filter {
    override val name = "Exposure"
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val k = 2f.pow(stops)
        val lut = IntArray(256) { toSrgb((TO_LIN[it] - black) * k / (1 - black).coerceAtLeast(0.01f)) }
        return LutFilter(name, lut, LutFilter.ID, LutFilter.ID, LutFilter.ID).apply(src, w, h)
    }
}

/** Paint the image in one hue, keeping its lightness (GIMP Colorize). */
class ColorizeFilter(private val hue: Float, private val saturation: Float, private val lightness: Float) : Filter {
    override val name = "Colorize"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { o ->
        parallelRows(h) { y0, y1 ->
            val hsl = FloatArray(3)
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                var l = luma(c) / 255f
                l = if (lightness > 0) l + (1 - l) * lightness else l * (1 + lightness)
                hsl[0] = hue; hsl[1] = saturation; hsl[2] = l
                o[i] = (androidx.core.graphics.ColorUtils.HSLToColor(hsl) and 0xFFFFFF) or (c and 0xFF000000.toInt())
            }
        }
    }
}

/** Each output channel as a weighted sum of the input channels. */
class ChannelMixerFilter(private val m: FloatArray, private val preserveLuminosity: Boolean) : Filter {
    override val name = "Channel Mixer"
    override fun apply(src: IntArray, w: Int, h: Int) = IntArray(src.size).also { o ->
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = src[i]
                val ri = r(c); val gi = g(c); val bi = b(c)
                var ro = m[0] * ri + m[1] * gi + m[2] * bi
                var go = m[3] * ri + m[4] * gi + m[5] * bi
                var bo = m[6] * ri + m[7] * gi + m[8] * bi
                if (preserveLuminosity) {
                    val before = 0.2126f * ri + 0.7152f * gi + 0.0722f * bi
                    val after = 0.2126f * ro + 0.7152f * go + 0.0722f * bo
                    if (after > 0.5f) { val k = before / after; ro *= k; go *= k; bo *= k }
                }
                o[i] = argb(a(c), ro.roundToInt(), go.roundToInt(), bo.roundToInt())
            }
        }
    }
}

/** Histogram-based automatic adjustments; they need the whole image's statistics. */
class AutoLevelsFilter(override val name: String, private val perChannel: Boolean, private val clip: Float) : Filter {
    override val wholeImage get() = true
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        val hist = Array(3) { IntArray(256) }
        var n = 0
        for (c in src) { if (a(c) == 0) continue; hist[0][r(c)]++; hist[1][g(c)]++; hist[2][b(c)]++; n++ }
        if (n == 0) return src.copyOf()
        val cut = (n * clip).toInt()
        fun bounds(hs: IntArray): Pair<Int, Int> {
            var lo = 0; var acc = 0
            while (lo < 255 && acc + hs[lo] <= cut) { acc += hs[lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > lo && acc + hs[hi] <= cut) { acc += hs[hi]; hi-- }
            return lo to hi
        }
        val bs = (0..2).map { bounds(hist[it]) }
        val joint = bs.minOf { it.first } to bs.maxOf { it.second }
        val luts = (0..2).map { ch ->
            val (lo, hi) = if (perChannel) bs[ch] else joint
            IntArray(256) { (((it - lo).toFloat() / max(1, hi - lo)) * 255).roundToInt().coerceIn(0, 255) }
        }
        return LutFilter(name, LutFilter.ID, luts[0], luts[1], luts[2]).apply(src, w, h)
    }
}

// ---------------------------------------------------------------------------- distortions

class RippleFilter(private val amplitude: Float, private val period: Float, private val vertical: Boolean) : Filter by object : Distort("Ripple") {
    override fun map(x: Float, y: Float, w: Int, h: Int, out: FloatArray) {
        if (vertical) { out[0] = x; out[1] = y + amplitude * sin(2 * PI * x / period).toFloat() }
        else { out[0] = x + amplitude * sin(2 * PI * y / period).toFloat(); out[1] = y }
    }
} {
    override fun scaled(k: Float): Filter = RippleFilter(amplitude * k, period * k, vertical)
}

class WhirlPinchFilter(private val whirl: Float, private val pinch: Float, private val radius: Float) : Filter by object : Distort("Whirl and Pinch") {
    override fun map(x: Float, y: Float, w: Int, h: Int, out: FloatArray) {
        val cx = w / 2f; val cy = h / 2f
        val rad = radius * min(cx, cy)
        val dx = x - cx; val dy = y - cy
        val d = hypot(dx, dy)
        if (d >= rad || rad <= 0f) { out[0] = x; out[1] = y; return }
        val t = d / rad
        // Pinch scales the distance; whirl rotates more toward the center.
        val p = if (pinch == 0f) 1f else (sin(PI / 2 * t).toFloat()).pow(-pinch)
        val ang = whirl * PI.toFloat() / 180f * (1 - t) * (1 - t)
        val sx = dx * p; val sy = dy * p
        out[0] = cx + sx * cos(ang) - sy * sin(ang)
        out[1] = cy + sx * sin(ang) + sy * cos(ang)
    }
}

class LensDistortionFilter(private val main: Float, private val edge: Float, private val zoom: Float) : Filter by object : Distort("Lens Distortion") {
    override fun map(x: Float, y: Float, w: Int, h: Int, out: FloatArray) {
        val cx = w / 2f; val cy = h / 2f
        val norm = sqrt(cx * cx + cy * cy)
        val nx = (x - cx) / norm; val ny = (y - cy) / norm
        val r2 = nx * nx + ny * ny
        val k = (1 + main * r2 + edge * r2 * r2) / (1 + zoom)
        out[0] = cx + nx * k * norm; out[1] = cy + ny * k * norm
    }
}
