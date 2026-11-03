package io.github.nielstron.brushkin.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Depth-of-field blur: pixels at the depth of the focus point stay sharp, the rest blur more the
 * further their depth is from it. [depth] is a [dw]×[dh] map over the whole image (0 = far,
 * 1 = near), [fx]/[fy] the focus point as fractions of the image size, [radius] the strongest blur
 * (px), [range] how much depth around the focus stays sharp (0…1).
 */
class DepthBlurFilter(
    private val depth: FloatArray, private val dw: Int, private val dh: Int,
    private val fx: Float, private val fy: Float, private val radius: Float, private val range: Float,
) : Filter {
    override val name = "Depth of Field"
    override val wholeImage get() = true
    override fun scaled(k: Float) = DepthBlurFilter(depth, dw, dh, fx, fy, radius * k, range)

    private fun depthAt(u: Float, v: Float): Float {
        val x = (u * dw - 0.5f).coerceIn(0f, dw - 1f); val y = (v * dh - 0.5f).coerceIn(0f, dh - 1f)
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = minOf(dw - 1, x0 + 1); val y1 = minOf(dh - 1, y0 + 1)
        val tx = x - x0; val ty = y - y0
        return (depth[y0 * dw + x0] * (1 - tx) + depth[y0 * dw + x1] * tx) * (1 - ty) + (depth[y1 * dw + x0] * (1 - tx) + depth[y1 * dw + x1] * tx) * ty
    }

    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        if (radius < 0.5f) return src.copyOf()
        // Focus depth: median of a small patch, so a tap on an edge still picks the object.
        val patch = ArrayList<Float>()
        for (j in -2..2) for (i in -2..2) patch.add(depthAt(fx + i * 0.006f, fy + j * 0.006f))
        val focus = patch.sorted()[patch.size / 2]
        // Blur radius per pixel, as a fraction of [radius].
        val amount = FloatArray(w * h)
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val v = (y + 0.5f) / h
                for (x in 0 until w) {
                    val d = abs(depthAt((x + 0.5f) / w, v) - focus) - range * 0.5f
                    amount[y * w + x] = (d / 0.35f).coerceIn(0f, 1f)
                }
            }
        }
        // Blend between a few blurred copies (keeping only two at a time) by each pixel's amount.
        val levels = floatArrayOf(0f, 0.08f, 0.2f, 0.4f, 0.7f, 1f)
        val out = src.copyOf()
        var prev = src
        for (li in 1 until levels.size) {
            val lo = levels[li - 1]; val hi = levels[li]
            val cur = blurArgb(src, w, h, (radius * hi).roundToInt().coerceAtLeast(1))
            parallelRows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val a = amount[i]
                    if (a <= lo || a > hi) continue
                    out[i] = lerpArgb(prev[i], cur[i], (((a - lo) / (hi - lo)) * 255).roundToInt())
                }
            }
            prev = cur
        }
        return out
    }
}
