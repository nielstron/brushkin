package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

const val TILE = 256

/**
 * Pixel storage split into [TILE]×[TILE] bitmaps. Empty tiles are null, which keeps memory and
 * draw calls proportional to painted area.
 *
 * Tiles are treated as copy-on-write: an edit never mutates a tile that history or a previous
 * snapshot may reference. Instead it calls [writable], which swaps in a private copy the first
 * time a tile is touched during that edit. This makes undo a matter of swapping references, and it
 * means the UI thread only ever draws bitmaps that are either finished or owned by the edit in
 * progress.
 */
class TiledBitmap(val width: Int, val height: Int, /** Color of a missing tile: transparent for layers, white for masks. */ val fill: Int = 0) {
    val cols = (width + TILE - 1) / TILE
    val rows = (height + TILE - 1) / TILE
    val tiles: Array<Bitmap?> = arrayOfNulls(cols * rows)

    fun index(cx: Int, cy: Int) = cy * cols + cx
    fun tileX(i: Int) = (i % cols) * TILE
    fun tileY(i: Int) = (i / cols) * TILE

    /** Indices of all tiles intersecting [r] (doc coordinates). */
    inline fun forTilesIn(r: Rect, block: (Int) -> Unit) {
        val x0 = (r.left.coerceAtLeast(0)) / TILE
        val y0 = (r.top.coerceAtLeast(0)) / TILE
        val x1 = ((r.right - 1).coerceAtMost(width - 1)) / TILE
        val y1 = ((r.bottom - 1).coerceAtMost(height - 1)) / TILE
        if (r.right <= 0 || r.bottom <= 0 || r.left >= width || r.top >= height) return
        for (cy in y0..y1) for (cx in x0..x1) block(index(cx, cy))
    }

    fun copy(): TiledBitmap {
        val t = TiledBitmap(width, height, fill)
        // Sharing references is safe because of copy-on-write.
        System.arraycopy(tiles, 0, t.tiles, 0, tiles.size)
        return t
    }

    /** Render all tiles into a single bitmap. */
    fun toBitmap(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (fill != 0) out.eraseColor(fill)
        val c = Canvas(out)
        for (i in tiles.indices) {
            val t = tiles[i] ?: continue
            c.drawBitmap(t, tileX(i).toFloat(), tileY(i).toFloat(), null)
        }
        return out
    }

    fun toPixels(): IntArray {
        val px = IntArray(width * height)
        if (fill != 0) px.fill(fill)
        for (i in tiles.indices) {
            val t = tiles[i] ?: continue
            val x = tileX(i); val y = tileY(i)
            val w = minOf(TILE, width - x); val h = minOf(TILE, height - y)
            t.getPixels(px, y * width + x, width, 0, 0, w, h)
        }
        return px
    }

    fun isEmpty() = tiles.all { it == null }

    companion object {
        fun newTile(): Bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)

        fun copyTile(src: Bitmap?, fill: Int = 0): Bitmap = src?.copy(Bitmap.Config.ARGB_8888, true) ?: newTile().also { if (fill != 0) it.eraseColor(fill) }

        fun fromBitmap(src: Bitmap, width: Int = src.width, height: Int = src.height, dx: Int = 0, dy: Int = 0, fill: Int = 0): TiledBitmap {
            val t = TiledBitmap(width, height, fill)
            val tmp = IntArray(TILE * TILE)
            for (cy in 0 until t.rows) for (cx in 0 until t.cols) {
                val x = cx * TILE; val y = cy * TILE
                val sx = x - dx; val sy = y - dy
                val l = maxOf(0, sx); val tp = maxOf(0, sy)
                val r = minOf(src.width, sx + TILE); val b = minOf(src.height, sy + TILE)
                if (l >= r || tp >= b) continue
                val tile = newTile()
                if (fill != 0) tile.eraseColor(fill)
                val c = Canvas(tile)
                c.drawBitmap(src, Rect(l, tp, r, b), Rect(l - sx, tp - sy, r - sx, b - sy), null)
                // Drop tiles that equal the fill (fully transparent layer tiles, all-white mask tiles).
                tile.getPixels(tmp, 0, TILE, 0, 0, TILE, TILE)
                if (!isFill(tmp, fill)) t.tiles[t.index(cx, cy)] = tile
            }
            return t
        }

        fun isFill(px: IntArray, fill: Int) = if (fill == 0) px.all { (it ushr 24) == 0 } else px.all { it == fill }

        fun fromPixels(px: IntArray, width: Int, height: Int, fill: Int = 0): TiledBitmap {
            val t = TiledBitmap(width, height, fill)
            for (cy in 0 until t.rows) for (cx in 0 until t.cols) {
                val x = cx * TILE; val y = cy * TILE
                val w = minOf(TILE, width - x); val h = minOf(TILE, height - y)
                var any = false
                loop@ for (yy in y until y + h) {
                    val row = yy * width
                    for (xx in x until x + w) {
                        val c = px[row + xx]
                        if (if (fill == 0) (c ushr 24) != 0 else c != fill) { any = true; break@loop }
                    }
                }
                if (!any) continue
                val tile = newTile()
                if (fill != 0) tile.eraseColor(fill)
                tile.setPixels(px, y * width + x, width, 0, 0, w, h)
                t.tiles[t.index(cx, cy)] = tile
            }
            return t
        }

        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    }
}

/**
 * Tracks the tiles one edit has touched: the original reference (for undo) and the fresh private
 * copy it writes to.
 */
class TileEditSession(val target: TiledBitmap) {
    val before = HashMap<Int, Bitmap?>()

    fun writable(i: Int): Bitmap {
        if (!before.containsKey(i)) {
            val orig = target.tiles[i]
            before[i] = orig
            target.tiles[i] = TiledBitmap.copyTile(orig, target.fill)
        }
        return target.tiles[i]!!
    }

    fun original(i: Int): Bitmap? = if (before.containsKey(i)) before[i] else target.tiles[i]

    fun touched(i: Int) = before.containsKey(i)

    fun cancel() {
        for ((i, b) in before) target.tiles[i] = b
        before.clear()
    }

    /** Finalizes the edit, dropping tiles that ended up fully transparent. */
    fun finish(): Map<Int, Pair<Bitmap?, Bitmap?>> {
        val tmp = IntArray(TILE * TILE)
        val out = HashMap<Int, Pair<Bitmap?, Bitmap?>>()
        for ((i, b) in before) {
            var after = target.tiles[i]
            if (after != null) {
                after.getPixels(tmp, 0, TILE, 0, 0, TILE, TILE)
                if (TiledBitmap.isFill(tmp, target.fill)) { after = null; target.tiles[i] = null }
            }
            if (after !== b) out[i] = b to after
        }
        return out
    }
}
