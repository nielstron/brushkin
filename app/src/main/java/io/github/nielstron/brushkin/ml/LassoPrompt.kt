package io.github.nielstron.brushkin.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.nielstron.brushkin.engine.Distance
import io.github.nielstron.brushkin.engine.Selection
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A rough loop drawn around an object, as prompts for [ObjectSelectClient]: the loop's bounding box,
 * a point on the object (the spot deepest inside the loop) and points that aren't part of it (the
 * parts of the box clearly outside the loop, e.g. its corners around a round object). On test images
 * with loose, wobbly loops this beat the box alone (mean IoU 0.91 vs 0.87 where it was tuned, 0.87 vs
 * 0.51 on held-out images) and picking among the model's candidate masks by their overlap with the loop.
 * Known miss: for thin or L-shaped objects in a loose loop the deepest spot can be background (the
 * inside corner of an L), and the background gets picked; a tap on the object (after "New object")
 * fixes it. Scoring several prompt variants against the loop helped only a little, so it isn't done.
 */
object LassoPrompt {
    /** Cells across the box's longer side on the grid the loop is analysed on. */
    private const val GRID = 64
    /** Most points marking what's outside the loop (the decoder takes 7 points next to a box). */
    private const val OUTSIDE = 3

    /** [loop]: x/y pairs in image pixels (it may run off the [w]×[h] image). */
    fun of(loop: FloatArray, w: Int, h: Int): Pair<RectF, List<ObjectSelect.Point>> {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in loop.indices step 2) { l = minOf(l, loop[i]); r = maxOf(r, loop[i]); t = minOf(t, loop[i + 1]); b = maxOf(b, loop[i + 1]) }
        l = l.coerceIn(0f, w.toFloat()); r = r.coerceIn(0f, w.toFloat()); t = t.coerceIn(0f, h.toFloat()); b = b.coerceIn(0f, h.toFloat())
        val box = RectF(l, t, r, b)
        // The loop on a coarse grid over the box, with a one-cell border that counts as outside it.
        val g = GRID / maxOf(r - l, b - t, 1f)
        val gw = ceil((r - l) * g).toInt() + 2; val gh = ceil((b - t) * g).toInt() + 2
        val raster = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        Canvas(raster).drawPath(Path().apply {
            moveTo((loop[0] - l) * g + 1, (loop[1] - t) * g + 1)
            for (i in 2 until loop.size step 2) lineTo((loop[i] - l) * g + 1, (loop[i + 1] - t) * g + 1)
            close()
        }, Paint().apply { color = 0xFF000000.toInt() })
        val cells = Selection.maskBytes(raster, gw, gh)
        raster.recycle()
        fun border(i: Int) = i % gw == 0 || i / gw == 0 || i % gw == gw - 1 || i / gw == gh - 1
        val inside = BooleanArray(gw * gh) { !border(it) && cells[it].toInt() != 0 }
        fun at(i: Int, positive: Boolean) = ObjectSelect.Point((i % gw - 0.5f) / g + l, (i / gw - 0.5f) / g + t, positive)

        val points = ArrayList<ObjectSelect.Point>()
        val deep = Distance.squared(gw, gh) { !inside[it] }
        val center = deep.indices.maxBy { deep[it] }
        if (deep[center] > 0f) points.add(at(center, true))
        // Only parts at least 3 cells (~5% of the box) from the loop and the box edge count as outside.
        val out = Distance.squared(gw, gh) { inside[it] || border(it) }
        repeat(OUTSIDE) {
            val i = out.indices.maxBy { out[it] }
            val d = sqrt(out[i])
            if (d < 3f) return@repeat
            points.add(at(i, false))
            // The next one elsewhere: clear the neighbourhood of this one.
            val x = i % gw; val y = i / gw
            for (j in out.indices) if (hypot((j % gw - x).toFloat(), (j / gw - y).toFloat()) < 2.5f * d) out[j] = 0f
        }
        return box to points
    }

    /** Whether ([x], [y]) is inside [loop] or within [margin] of it. */
    fun near(loop: FloatArray, x: Float, y: Float, margin: Float): Boolean {
        var inside = false
        val n = loop.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = loop[2 * i]; val yi = loop[2 * i + 1]; val xj = loop[2 * j]; val yj = loop[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            // Distance to the segment i–j.
            val dx = xj - xi; val dy = yj - yi
            val len2 = dx * dx + dy * dy
            val k = if (len2 == 0f) 0f else (((x - xi) * dx + (y - yi) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(x - xi - k * dx, y - yi - k * dy) <= margin) return true
            j = i
        }
        return inside
    }
}
