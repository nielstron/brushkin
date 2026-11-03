package io.github.nielstron.brushkin.engine

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Intelligent Scissors (live wire): the cheapest 8-connected path between two points, where
 * pixels on strong edges are cheap. Only a box around the two points is searched.
 */
object Scissors {
    /** Returns the path from [ax],[ay] to [bx],[by] as x,y pairs in image coordinates. */
    fun liveWire(px: IntArray, w: Int, h: Int, ax: Int, ay: Int, bx: Int, by: Int): FloatArray {
        val ax0 = ax.coerceIn(0, w - 1); val ay0 = ay.coerceIn(0, h - 1)
        val bx0 = bx.coerceIn(0, w - 1); val by0 = by.coerceIn(0, h - 1)
        val dist = hypot(bx0 - ax0, by0 - ay0)
        val margin = max(24, (dist * 0.35f).toInt())
        val r = Rect(min(ax0, bx0) - margin, min(ay0, by0) - margin, max(ax0, bx0) + margin + 1, max(ay0, by0) + margin + 1)
        r.intersect(0, 0, w, h)
        val rw = r.width(); val rh = r.height(); val n = rw * rh
        val cost = edgeCost(px, w, r)
        val d = FloatArray(n) { Float.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        val heap = LongHeap(1024)
        val start = (ay0 - r.top) * rw + (ax0 - r.left)
        val goal = (by0 - r.top) * rw + (bx0 - r.left)
        d[start] = 0f; heap.push(0f, start)
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1); val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        val len = floatArrayOf(SQRT2, 1f, SQRT2, 1f, 1f, SQRT2, 1f, SQRT2)
        while (heap.size > 0) {
            val i = heap.pop()
            if (done[i]) continue
            done[i] = true
            if (i == goal) break
            val x = i % rw; val y = i / rw
            for (k in 0 until 8) {
                val nx = x + dx[k]; val ny = y + dy[k]
                if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                val j = ny * rw + nx
                if (done[j]) continue
                val nd = d[i] + cost[j] * len[k]
                if (nd < d[j]) { d[j] = nd; prev[j] = i; heap.push(nd, j) }
            }
        }
        val pts = ArrayList<Int>()
        var c = goal
        while (c >= 0) { pts.add(c); if (c == start) break; c = prev[c] }
        if (pts.last() != start) return floatArrayOf(ax0 + 0.5f, ay0 + 0.5f, bx0 + 0.5f, by0 + 0.5f)
        pts.reverse()
        return FloatArray(pts.size * 2) { k -> val p = pts[k / 2]; if (k % 2 == 0) r.left + p % rw + 0.5f else r.top + p / rw + 0.5f }
    }

    /** Per-pixel cost in [r]: low on strong (Sobel) edges, high in flat areas. */
    private fun edgeCost(px: IntArray, w: Int, r: Rect): FloatArray {
        val rw = r.width(); val rh = r.height()
        val lum = FloatArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val c = px[(r.top + y) * w + r.left + x]
            val a = (c ushr 24) / 255f
            // Transparent pixels count as their alpha so layer edges are found too.
            lum[y * rw + x] = ((((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) * a + (1 - a) * 128f)
        }
        val g = FloatArray(rw * rh)
        var gmax = 1e-3f
        for (y in 0 until rh) for (x in 0 until rw) {
            fun l(xx: Int, yy: Int) = lum[yy.coerceIn(0, rh - 1) * rw + xx.coerceIn(0, rw - 1)]
            val gx = l(x + 1, y - 1) + 2 * l(x + 1, y) + l(x + 1, y + 1) - l(x - 1, y - 1) - 2 * l(x - 1, y) - l(x - 1, y + 1)
            val gy = l(x - 1, y + 1) + 2 * l(x, y + 1) + l(x + 1, y + 1) - l(x - 1, y - 1) - 2 * l(x, y - 1) - l(x + 1, y - 1)
            val m = sqrt(gx * gx + gy * gy)
            g[y * rw + x] = m
            if (m > gmax) gmax = m
        }
        // Normalize by a high percentile of the real edges, so every clear edge is cheap (not just
        // the very strongest one in the box).
        val edges = g.filter { it > gmax * 0.05f }.sorted()
        val ref = if (edges.isEmpty()) gmax else edges[(edges.size * 0.7f).toInt().coerceAtMost(edges.size - 1)]
        for (i in g.indices) { val t = 1f - (g[i] / ref).coerceAtMost(1f); g[i] = 0.01f + t * t }
        return g
    }

    private const val SQRT2 = 1.4142135f
    private fun hypot(x: Int, y: Int) = sqrt((x * x + y * y).toFloat())

    /** Min-heap of (non-negative float key, int value) packed into longs. */
    private class LongHeap(cap: Int) {
        private var a = LongArray(cap)
        var size = 0; private set
        fun push(key: Float, v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            var i = size++
            val e = (java.lang.Float.floatToIntBits(key).toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)
            while (i > 0) { val p = (i - 1) / 2; if (a[p] <= e) break; a[i] = a[p]; i = p }
            a[i] = e
        }
        fun pop(): Int {
            val top = a[0]
            val last = a[--size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && a[c + 1] < a[c]) c++
                if (a[c] >= last) break
                a[i] = a[c]; i = c
            }
            if (size > 0) a[i] = last
            return top.toInt()
        }
    }
}

/**
 * Foreground select: from a rough outline drawn around an object. The background color model is
 * learned from a ring just outside the outline; pixels inside that clearly don't match it seed the
 * foreground model; then every pixel inside is assigned to the closer model and speckles are voted
 * away. Returns a 0/255 mask.
 */
object ForegroundSelect {
    fun extract(px: IntArray, w: Int, h: Int, rough: ByteArray, band: Int): ByteArray {
        // Work only in the outline's bounding box (plus the ring).
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (rough[y * w + x].toInt() != 0) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        val out = ByteArray(px.size)
        if (r < 0) return out
        l = max(0, l - band - 2); t = max(0, t - band - 2); r = min(w - 1, r + band + 2); b = min(h - 1, b + band + 2)
        val rw = r - l + 1; val rh = b - t + 1; val n = rw * rh
        val p = IntArray(n) { px[(t + it / rw) * w + l + it % rw] }
        val inside = ByteArray(n) { rough[(t + it / rw) * w + l + it % rw] }
        val grown = Selection.blurBytes(inside, rw, rh, band * 2)

        val step = max(1, n / 40_000)
        val ring = ArrayList<Int>()
        for (i in 0 until n step step) if (inside[i].toInt() == 0 && grown[i].toInt() != 0) ring.add(p[i])
        val bg = if (ring.isEmpty()) emptyList() else kmeans(ring, 8)
        if (bg.isEmpty()) { for (i in 0 until n) out[(t + i / rw) * w + l + i % rw] = inside[i]; return out }

        // Pixels inside that the background model can't explain seed the foreground model.
        val seed = ArrayList<Int>()
        for (i in 0 until n step step) if (inside[i].toInt() != 0 && minDist(p[i], bg) > SEED * SEED) seed.add(p[i])
        if (seed.isEmpty()) return out
        val fg = kmeans(seed, 8)

        val m = ByteArray(n)
        for (i in 0 until n) if (inside[i].toInt() != 0 && minDist(p[i], fg) < minDist(p[i], bg)) m[i] = -1
        // Majority vote in a small window removes speckles; stay inside the outline.
        val sm = Selection.blurBytes(m, rw, rh, 4)
        for (i in 0 until n) if (inside[i].toInt() != 0 && (sm[i].toInt() and 0xFF) >= 128) out[(t + i / rw) * w + l + i % rw] = -1
        return out
    }

    /** RGB distance beyond which a pixel clearly isn't background. */
    private const val SEED = 40f

    private fun minDist(c: Int, centers: List<FloatArray>): Float {
        val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
        var best = Float.MAX_VALUE
        for (m in centers) { val d = (r - m[0]) * (r - m[0]) + (g - m[1]) * (g - m[1]) + (b - m[2]) * (b - m[2]); if (d < best) best = d }
        return best
    }

    private fun kmeans(samples: List<Int>, k0: Int): List<FloatArray> {
        val k = min(k0, samples.size)
        val c = Array(k) { j -> samples[j * samples.size / k].let { floatArrayOf(((it shr 16) and 0xFF).toFloat(), ((it shr 8) and 0xFF).toFloat(), (it and 0xFF).toFloat()) } }
        repeat(8) {
            val sum = Array(k) { FloatArray(4) }
            for (s in samples) {
                val r = ((s shr 16) and 0xFF).toFloat(); val g = ((s shr 8) and 0xFF).toFloat(); val b = (s and 0xFF).toFloat()
                var bi = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) { val d = abs(r - c[j][0]) + abs(g - c[j][1]) + abs(b - c[j][2]); if (d < bd) { bd = d; bi = j } }
                sum[bi][0] += r; sum[bi][1] += g; sum[bi][2] += b; sum[bi][3] += 1f
            }
            for (j in 0 until k) if (sum[j][3] > 0) for (q in 0..2) c[j][q] = sum[j][q] / sum[j][3]
        }
        return c.toList()
    }
}
