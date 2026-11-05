package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Layers larger than the canvas. Pixels on the canvas live in the layer's [TiledBitmap] as usual;
 * pixels beyond the canvas edges are kept as extra tiles in [Layer.outside], keyed by their tile
 * coordinates on the same 256 px grid (which may be negative). Nothing renders them (they're off
 * the canvas), but moving, transforming, resizing the canvas and saving keep them.
 */
object LayerExtent {
    fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
    fun cx(k: Long) = (k shr 32).toInt()
    fun cy(k: Long) = k.toInt()

    /** Bounds of everything on the layer, on and off the canvas (null if empty). */
    fun bounds(l: Layer): Rect? {
        var r = l.pixels.contentBounds()?.let { Rect(it) }
        for ((k, t) in l.outside) {
            val b = alphaBounds(t) ?: continue
            b.offset(cx(k) * TILE, cy(k) * TILE)
            if (r == null) r = b else r.union(b)
        }
        return r
    }

    /** Draws every tile of the layer (canvas and outside) onto [c], which is in image coordinates. */
    fun draw(c: Canvas, l: Layer, paint: Paint? = null) {
        c.save()
        c.clipRect(0, 0, l.pixels.width, l.pixels.height)
        Compositor.drawTiles(c, l.pixels, null, null)
        c.restore()
        for ((k, t) in l.outside) c.drawBitmap(t, cx(k) * TILE.toFloat(), cy(k) * TILE.toFloat(), paint)
    }

    /**
     * Tiles outside a [w]×[h] canvas covered by [area], each rendered by [render] (which draws in
     * image coordinates). Fully transparent tiles are dropped.
     */
    fun outsideTiles(area: Rect, w: Int, h: Int, render: (Canvas) -> Unit): Map<Long, Bitmap> {
        val out = HashMap<Long, Bitmap>()
        val x0 = Math.floorDiv(area.left, TILE); val x1 = Math.floorDiv(area.right - 1, TILE)
        val y0 = Math.floorDiv(area.top, TILE); val y1 = Math.floorDiv(area.bottom - 1, TILE)
        // Don't let a far-flung transform allocate without bound.
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 4096) return out
        for (ty in y0..y1) for (tx in x0..x1) {
            if (tx >= 0 && ty >= 0 && (tx + 1) * TILE <= w && (ty + 1) * TILE <= h) continue
            val tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
            val c = Canvas(tile); c.translate(-tx * TILE.toFloat(), -ty * TILE.toFloat())
            // A boundary tile may contain both canvas and off-canvas pixels. Store only the latter.
            c.clipOutRect(0, 0, w, h)
            render(c)
            if (alphaBounds(tile) != null) out[key(tx, ty)] = tile
        }
        return out
    }

    /** Re-lays a layer's content (all of it) through [m] onto a [w]×[h] canvas. */
    fun transform(l: Layer, m: Matrix, w: Int, h: Int): Pair<TiledBitmap, Map<Long, Bitmap>> {
        val p = Paint().apply { isFilterBitmap = true }
        val canvasPart = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvasPart); c.concat(m); draw(c, l, p)
        val src = bounds(l) ?: return TiledBitmap(w, h) to emptyMap()
        val r = RectF(src); m.mapRect(r)
        val area = Rect(); r.roundOut(area)
        val outside = outsideTiles(area, w, h) { cc -> cc.concat(m); draw(cc, l, p) }
        return TiledBitmap.fromBitmap(canvasPart) to outside
    }

    private fun alphaBounds(b: Bitmap): Rect? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var btm = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) != 0) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; btm = y }
        return if (r < 0) null else Rect(l, t, r + 1, btm + 1)
    }

    /** A layer image placed at ([x], [y]) on a [w]×[h] canvas: canvas tiles, off-canvas tiles, boundary. */
    fun fromPlaced(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Triple<TiledBitmap, Map<Long, Bitmap>, Rect?> {
        val r = Rect(x, y, x + bmp.width, y + bmp.height)
        val px = TiledBitmap.fromBitmap(bmp, w, h, x, y)
        val outside = if (Rect(0, 0, w, h).let { r.left >= it.left && r.top >= it.top && r.right <= it.right && r.bottom <= it.bottom }) emptyMap()
            else outsideTiles(r, w, h) { c -> c.drawBitmap(bmp, x.toFloat(), y.toFloat(), null) }
        // Keep larger boundaries too, including their transparent padding.
        val bounds = if (r == Rect(0, 0, w, h)) null else r
        return Triple(px, outside, bounds)
    }

    /** The layer's extent for saving: its boundary, grown by any off-canvas tiles. */
    fun saveRegion(l: Layer, w: Int, h: Int): Rect {
        val r = Rect(l.bounds ?: Rect(0, 0, w, h))
        for (k in l.outside.keys) r.union(Rect(cx(k) * TILE, cy(k) * TILE, cx(k) * TILE + TILE, cy(k) * TILE + TILE))
        return r
    }

    /** The layer's pixels in [r]. */
    fun render(l: Layer, r: Rect): Bitmap {
        val out = Bitmap.createBitmap(r.width().coerceAtLeast(1), r.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.translate(-r.left.toFloat(), -r.top.toFloat())
        l.bounds?.let { c.clipRect(it) }
        draw(c, l)
        return out
    }

    /** Clears everything outside [r] (a layer boundary smaller than the canvas). */
    fun clipTo(t: TiledBitmap, r: Rect): TiledBitmap {
        val out = TiledBitmap(t.width, t.height, t.fill)
        for (i in t.tiles.indices) {
            val tile = t.tiles[i] ?: continue
            val tr = Rect(t.tileX(i), t.tileY(i), t.tileX(i) + TILE, t.tileY(i) + TILE)
            if (r.contains(tr)) { out.tiles[i] = tile; continue }
            if (!Rect.intersects(r, tr)) continue
            val copy = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
            val c = Canvas(copy)
            c.clipRect(r.left - tr.left, r.top - tr.top, r.right - tr.left, r.bottom - tr.top)
            c.drawBitmap(tile, 0f, 0f, null)
            out.tiles[i] = copy
        }
        return out
    }
}
