package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil
import kotlin.math.sqrt

/** Euclidean distance transform (Felzenszwalb & Huttenlocher), exact and linear in the pixel count. */
object Distance {
    private const val FAR = 1e20f

    /** Squared distance from every pixel to the nearest pixel for which [target] holds. */
    fun squared(w: Int, h: Int, target: (Int) -> Boolean): FloatArray {
        val d = FloatArray(w * h) { if (target(it)) 0f else FAR }
        val n = maxOf(w, h)
        val f = FloatArray(n); val out = FloatArray(n); val v = IntArray(n); val z = FloatArray(n + 1)
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = d[y * w + x]
            line(f, h, out, v, z)
            for (y in 0 until h) d[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            System.arraycopy(d, y * w, f, 0, w)
            line(f, w, out, v, z)
            System.arraycopy(out, 0, d, y * w, w)
        }
        return d
    }

    /** 1-D pass: the lower envelope of the parabolas rooted at each sample. */
    private fun line(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        fun meet(q: Int, p: Int) = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
        var k = 0
        v[0] = 0; z[0] = Float.NEGATIVE_INFINITY; z[1] = Float.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s = meet(q, v[k])
            while (s <= z[k]) { k--; s = meet(q, v[k]) }
            k++; v[k] = q; z[k] = s; z[k + 1] = Float.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toFloat()
            d[q] = dq * dq + f[v[k]]
        }
    }
}

/**
 * Sticker cutouts: tidying an object mask (the model's speckles and pinholes, what lies outside the
 * loop drawn around the object) and drawing the sticker itself with an outline and a drop shadow.
 */
object Sticker {
    class Rendered(val bitmap: Bitmap, /** Transparent margin around the cutout on each side (px). */ val pad: Int)

    /**
     * [mask] (0 or nonzero per pixel) without what lies outside [lasso] (x/y pairs, scaled by [sx],
     * [sy]) grown by [margin] px, without islands under 1% of the largest part, and with holes under
     * 0.2% of the object filled. Null if nothing is left.
     */
    fun tidy(mask: ByteArray, w: Int, h: Int, lasso: FloatArray?, sx: Float, sy: Float, margin: Float): ByteArray? {
        val m = mask.copyOf()
        if (lasso != null) {
            val region = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val path = Path().apply {
                moveTo(lasso[0] * sx, lasso[1] * sy)
                for (i in 2 until lasso.size step 2) lineTo(lasso[i] * sx, lasso[i + 1] * sy)
                close()
            }
            val c = Canvas(region)
            c.drawPath(path, Paint().apply { color = 0xFF000000.toInt() })
            c.drawPath(path, Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * margin; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND })
            val inside = Selection.maskBytes(region, w, h)
            for (i in m.indices) if (inside[i].toInt() == 0) m[i] = 0
        }
        val labels = IntArray(w * h)
        val fg = components(w, h, labels, eight = true) { m[it].toInt() != 0 }
        val largest = fg.maxOfOrNull { it.area } ?: return null
        val total = fg.sumOf { it.area }
        val island = BooleanArray(fg.size + 1) { it > 0 && fg[it - 1].area < largest / 100 }
        for (i in m.indices) if (island[labels[i]]) m[i] = 0
        labels.fill(0)
        val bg = components(w, h, labels, eight = false) { m[it].toInt() == 0 }
        val hole = BooleanArray(bg.size + 1) { it > 0 && !bg[it - 1].border && bg[it - 1].area < total / 500 }
        for (i in m.indices) if (hole[labels[i]]) m[i] = -1
        return m
    }

    private class Component(val area: Int, val border: Boolean)

    /** Connected regions of the pixels where [member] holds (flood fill), labelled 1, 2, … in [labels]. */
    private fun components(w: Int, h: Int, labels: IntArray, eight: Boolean, member: (Int) -> Boolean): List<Component> {
        val out = ArrayList<Component>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (labels[start] != 0 || !member(start)) continue
            val label = out.size + 1
            var n = 0; var area = 0; var border = false
            labels[start] = label; stack[n++] = start
            while (n > 0) {
                val i = stack[--n]; area++
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                for (dy in -1..1) for (dx in -1..1) {
                    if ((dx == 0 && dy == 0) || (!eight && dx != 0 && dy != 0)) continue
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (labels[j] == 0 && member(j)) { labels[j] = label; stack[n++] = j }
                }
            }
            out.add(Component(area, border))
        }
        return out
    }

    /**
     * [cutout] as a sticker: on an [outline] px wide border of [color] that follows its shape (and
     * fills gaps narrower than twice that), with a soft drop shadow underneath when [shadow] is set.
     */
    fun render(cutout: Bitmap, outline: Float, color: Int, shadow: Boolean): Rendered {
        val size = maxOf(cutout.width, cutout.height)
        val blur = if (shadow) maxOf(2f, outline * 0.8f, size * 0.012f) else 0f
        val drop = blur * 0.5f
        val pad = ceil(outline + 2.5f * blur + drop + 2f).toInt()
        val w = cutout.width + 2 * pad; val h = cutout.height + 2 * pad
        val alpha = ByteArray(w * h)
        val row = IntArray(cutout.width)
        for (y in 0 until cutout.height) {
            cutout.getPixels(row, 0, cutout.width, 0, y, cutout.width, 1)
            val o = (y + pad) * w + pad
            for (x in row.indices) alpha[o + x] = (row[x] ushr 24).toByte()
        }
        val shape = if (outline < 0.5f) alpha else {
            // Border alpha from the distance to the object's 50% edge, antialiased over one pixel.
            val d = Distance.squared(w, h) { (alpha[it].toInt() and 0xFF) >= 128 }
            ByteArray(w * h) { i ->
                val a = ((outline + 0.5f - sqrt(d[i])) * 255f).coerceIn(0f, 255f).toInt()
                maxOf(a, alpha[i].toInt() and 0xFF).toByte()
            }
        }
        val shapeBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { Selection.writeMaskBytes(it, shape, w, h) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (shadow) {
            val offset = IntArray(2)
            val soft = shapeBmp.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }, offset)
            c.drawBitmap(soft, offset[0].toFloat(), offset[1] + drop, Paint().apply { this.color = 0x66000000 })
            soft.recycle()
        }
        if (outline >= 0.5f) c.drawBitmap(shapeBmp, 0f, 0f, Paint().apply { this.color = color or 0xFF000000.toInt() })
        c.drawBitmap(cutout, pad.toFloat(), pad.toFloat(), null)
        shapeBmp.recycle()
        return Rendered(out, pad)
    }
}
