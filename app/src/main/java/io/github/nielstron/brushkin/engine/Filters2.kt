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

class MotionBlurFilter(length: Float, angle: Float) : Filter by GeglFilter("Motion Blur", "gegl:motion-blur-linear",
    mapOf("length" to length.toDouble(), "angle" to angle.toDouble()), setOf("length"))

/** GEGL edge-preserving noise reduction, as used by GIMP. */
class NoiseReductionFilter(strength: Int) : Filter by GeglFilter("Noise Reduction", "gegl:noise-reduction",
    mapOf("iterations" to strength.toDouble()))

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

class ShadowsHighlightsFilter(shadows: Float, highlights: Float, radius: Float) : Filter by GeglFilter("Shadows-Highlights", "gegl:shadows-highlights",
    mapOf("shadows" to shadows * 100.0, "highlights" to highlights * -100.0, "radius" to radius.toDouble()), setOf("radius"))

class ExposureFilter(stops: Float, black: Float) : Filter by GeglFilter("Exposure", "gegl:exposure",
    mapOf("exposure" to stops.toDouble(), "black-level" to black.toDouble()))

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

class RippleFilter(amplitude: Float, period: Float, vertical: Boolean) : Filter by GeglFilter("Ripple", "gegl:ripple",
    mapOf("amplitude" to amplitude.toDouble(), "period" to period.toDouble(), "angle" to if (vertical) 90.0 else 0.0), setOf("amplitude", "period"))

class WhirlPinchFilter(whirl: Float, pinch: Float, radius: Float) : Filter by GeglFilter("Whirl and Pinch", "gegl:whirl-pinch",
    mapOf("whirl" to whirl.toDouble(), "pinch" to pinch.toDouble(), "radius" to radius.toDouble()))

class LensDistortionFilter(main: Float, edge: Float, zoom: Float) : Filter by GeglFilter("Lens Distortion", "gegl:lens-distortion",
    mapOf("main" to main * 100.0, "edge" to edge * 100.0, "zoom" to zoom * 100.0))
