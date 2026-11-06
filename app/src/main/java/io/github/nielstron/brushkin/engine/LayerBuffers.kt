package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.WeakReference

/**
 * A layer's pixels as a native GEGL buffer (straight-alpha R'G'B'A u8: the same bytes
 * [NativeGegl.render] builds from a whole-image array).
 *
 * The buffer remembers, per tile, which bitmap (by identity) it uploaded at which tile version.
 * Tiles are copy-on-write, so every edit either swaps in a different bitmap (a stroke's private
 * copy, a filter result, undo/redo swapping the old references back) or bumps the tile's version
 * after writing into its private copy. A tile whose identity and version both match therefore
 * still holds exactly the uploaded pixels, and [sync] uploads only the others. The references are
 * weak so the buffer never keeps old tiles alive; a collected tile simply no longer matches.
 */
class LayerBuffer(val width: Int, val height: Int, val fill: Int) : AutoCloseable {
    private val cols = (width + TILE - 1) / TILE
    private val handle = NativeGegl.createBuffer(width, height)
    /** Per tile: the bitmap last uploaded ([EMPTY] for a missing tile), or null if never written. */
    private val uploaded = arrayOfNulls<WeakReference<Bitmap>>(cols * ((height + TILE - 1) / TILE))
    private val versions = IntArray(uploaded.size)

    fun fits(t: TiledBitmap) = t.width == width && t.height == height && t.fill == fill

    /** Uploads the tiles of [t] that changed since the last sync; returns how many. */
    fun sync(t: TiledBitmap, scratch: IntArray): Int {
        require(fits(t))
        var count = 0
        for (i in t.tiles.indices) {
            // Read the version before the pixels: a write finishing meanwhile bumps it again.
            val version = t.versions.get(i)
            val tile = t.tiles[i]
            val last = uploaded[i]
            if (tile == null) {
                // A new GEGL buffer reads as transparent black, which is what a missing tile is.
                if (last === EMPTY || (last == null && fill == 0)) { uploaded[i] = EMPTY; continue }
            } else if (last != null && last.get() === tile && versions[i] == version) continue
            val x = t.tileX(i); val y = t.tileY(i)
            val w = minOf(TILE, width - x); val h = minOf(TILE, height - y)
            if (tile == null) scratch.fill(fill, 0, w * h) else tile.getPixels(scratch, 0, w, 0, 0, w, h)
            NativeGegl.uploadBuffer(handle, x, y, w, h, scratch)
            uploaded[i] = if (tile == null) EMPTY else WeakReference(tile)
            versions[i] = version
            count++
        }
        return count
    }

    /** Renders [f] over the whole buffer; the caller frees the result with [NativeGegl.destroyRendered]. */
    fun render(f: GeglFilter, profile: Boolean): Long = NativeGegl.renderBuffer(handle, f.operation,
        f.parameters.keys.toTypedArray(), f.parameters.values.toDoubleArray(), NativeGegl.allowOpenCl, profile)

    override fun close() = NativeGegl.destroyBuffer(handle)

    private companion object {
        val EMPTY = WeakReference<Bitmap>(null)
    }
}

/**
 * Renders GEGL filters tile by tile: dirty tiles go up into a [LayerBuffer], the result comes back
 * one tile at a time into the new tiles. No whole-image Java array is involved.
 *
 * Persistent buffers are kept for the two layers (TiledBitmaps) filtered most recently, so applying
 * a filter again (after an undo, or after painting a few strokes) uploads only what changed. They
 * are freed when evicted, when their layer is deleted or its bitmap collected, and when a new
 * document replaces the old one.
 */
object LayerBuffers {
    private const val MAX = 2
    private const val TAG = "BrushkinProfile"
    /** Tiles uploaded by the last [apply] (for tests and profiling). */
    @Volatile var lastUploadedTiles = -1
        private set
    /** Most recently used last. */
    private val buffers = ArrayList<Pair<WeakReference<TiledBitmap>, LayerBuffer>>()

    fun apply(target: TiledBitmap, f: GeglFilter, sel: Bitmap?, persistent: Boolean): TiledBitmap {
        val profile = Log.isLoggable(TAG, Log.DEBUG)
        val t0 = System.nanoTime()
        val scratch = IntArray(TILE * TILE)
        var uploaded = 0
        var tSync = 0L
        fun renderWith(b: LayerBuffer): Long {
            uploaded = b.sync(target, scratch)
            tSync = System.nanoTime()
            return b.render(f, profile)
        }
        val rendered = if (persistent) synchronized(this) { renderWith(acquire(target)) }
            else LayerBuffer(target.width, target.height, target.fill).use { renderWith(it) }
        val tRender = System.nanoTime()
        lastUploadedTiles = uploaded
        try {
            return writeBack(target, rendered, sel, scratch).also {
                if (profile) {
                    val end = System.nanoTime()
                    Log.i(TAG, "layer ${f.operation} ${target.width}x${target.height} persistent=$persistent uploadedTiles=$uploaded/${target.tiles.size} " +
                        "sync=%.2f render=%.2f writeBack=%.2f total=%.2f".format((tSync - t0) / 1e6, (tRender - tSync) / 1e6, (end - tRender) / 1e6, (end - t0) / 1e6))
                }
            }
        } finally { NativeGegl.destroyRendered(rendered) }
    }

    /** Frees the persistent buffer of [t], if any (its layer was deleted). */
    fun release(t: TiledBitmap) = synchronized(this) {
        buffers.removeAll { (owner, b) -> (owner.get() === t).also { if (it) b.close() } }
    }

    fun clear() = synchronized(this) {
        buffers.forEach { it.second.close() }
        buffers.clear()
    }

    private fun acquire(t: TiledBitmap): LayerBuffer {
        buffers.removeAll { (owner, b) -> (owner.get() == null).also { if (it) b.close() } }
        val i = buffers.indexOfFirst { it.first.get() === t }
        if (i >= 0) {
            val e = buffers.removeAt(i)
            if (e.second.fits(t)) { buffers.add(e); return e.second }
            e.second.close()
        }
        while (buffers.size >= MAX) buffers.removeAt(0).second.close()
        return LayerBuffer(t.width, t.height, t.fill).also { buffers.add(WeakReference(t) to it) }
    }

    /**
     * New tiles from a render, exactly as the banded path builds them: blended with [src] under
     * [sel], set into fresh tiles, and dropped where they equal the fill.
     */
    private fun writeBack(src: TiledBitmap, rendered: Long, sel: Bitmap?, px: IntArray): TiledBitmap {
        val w = src.width; val h = src.height
        val result = TiledBitmap(w, h, src.fill)
        val mask = sel?.let { Selection.maskBytes(Bitmap.createBitmap(it, 0, 0, w, h), w, h) }
        val orig = if (mask != null) IntArray(TILE * TILE) else null
        for (i in result.tiles.indices) {
            val x = result.tileX(i); val y = result.tileY(i)
            val tw = minOf(TILE, w - x); val th = minOf(TILE, h - y)
            val n = tw * th
            NativeGegl.readRendered(rendered, x, y, tw, th, px)
            if (mask != null && orig != null) {
                val t = src.tiles[i]
                if (t == null) orig.fill(src.fill, 0, n) else t.getPixels(orig, 0, tw, 0, 0, tw, th)
                for (r in 0 until th) for (c in 0 until tw) {
                    val k = r * tw + c
                    when (val m = mask[(y + r) * w + x + c].toInt() and 0xFF) {
                        0 -> px[k] = orig[k]
                        255 -> {}
                        else -> px[k] = lerpArgb(orig[k], px[k], m)
                    }
                }
            }
            result.touched(i)
            // setPixels keeps alpha, and opaque pixels round-trip exactly through premultiplication,
            // so testing the straight pixels gives the same answer as reading the tile back.
            if (isFill(px, n, src.fill)) continue
            val tile = TiledBitmap.newTile()
            if (src.fill != 0) tile.eraseColor(src.fill)
            tile.setPixels(px, 0, tw, 0, 0, tw, th)
            result.tiles[i] = tile
        }
        return result
    }

    private fun isFill(px: IntArray, n: Int, fill: Int): Boolean {
        if (fill == 0) { for (k in 0 until n) if ((px[k] ushr 24) != 0) return false }
        else for (k in 0 until n) if (px[k] != fill) return false
        return true
    }
}
