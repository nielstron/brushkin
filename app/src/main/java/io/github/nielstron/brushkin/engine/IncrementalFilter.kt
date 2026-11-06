package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Rect

/** Owned by a bounded effect pipeline; never shared with another rendering job. */
interface IncrementalFilter : AutoCloseable {
    fun render(source: TiledBitmap): TiledBitmap
}

internal class GeglIncremental(width: Int, height: Int, operation: String, parameters: Map<String, Double>) : IncrementalFilter {
    private val handle = NativeGegl.createSession(width,height,operation,parameters.keys.toTypedArray(),parameters.values.toDoubleArray(),
        operation in setOf("gegl:exposure", "gegl:median-blur", "gegl:motion-blur-linear",
            "gegl:red-eye-removal", "gegl:noise-rgb", "gegl:noise-hsv", "gegl:noise-cie-lch",
            "gegl:noise-hurl", "gegl:value-invert", "gegl:rgb-clip"))
    private var output = TiledBitmap(width,height)
    private val identities = arrayOfNulls<Bitmap>(output.tiles.size)
    private val versions = IntArray(output.tiles.size) { -1 }
    private var first = true

    // One tile of pixels at a time, reused: uploads, results and emptiness checks.
    private val scratch = IntArray(TILE*TILE)
    private val check = IntArray(TILE*TILE)

    override fun render(source: TiledBitmap): TiledBitmap {
        for (i in source.tiles.indices) {
            val tile=source.tiles[i]
            val version=source.versions.get(i)
            if (!first && identities[i] === tile && versions[i] == version) continue
            val x=source.tileX(i); val y=source.tileY(i)
            val w=minOf(TILE,source.width-x); val h=minOf(TILE,source.height-y)
            if (tile == null) scratch.fill(source.fill,0,w*h) else tile.getPixels(scratch,0,w,0,0,w,h)
            NativeGegl.updateSession(handle,x,y,w,h,scratch)
            identities[i]=tile; versions[i]=version
        }
        first=false
        val rect=NativeGegl.renderSession(handle)
        val x=rect[0]; val y=rect[1]; val w=rect[2]; val h=rect[3]
        if (w == 0 || h == 0) return output
        val result=output.copy()
        for (i in result.tiles.indices) result.versions.set(i,output.versions.get(i))
        val dirty=Rect(x,y,x+w,y+h)
        result.forTilesIn(dirty) { i ->
            val tx=result.tileX(i); val ty=result.tileY(i)
            val l=maxOf(x,tx); val t=maxOf(y,ty)
            val r=minOf(x+w,tx+TILE); val b=minOf(y+h,ty+TILE)
            val n=(r-l)*(b-t)
            NativeGegl.readSession(handle,l,t,r-l,b-t,scratch)
            val tile=TiledBitmap.copyTile(result.tiles[i])
            tile.setPixels(scratch,0,r-l,l-tx,t-ty,r-l,b-t)
            // Some operations (notably recursive Gaussian blur) invalidate entire rows/columns.
            // Keep identical output tiles so the next effect still sees only actual changes.
            val old=result.tiles[i]
            if (old != null && old.sameAs(tile)) {
                tile.recycle()
            } else {
                // A visible pixel in the new part means the tile isn't empty; only otherwise
                // read the whole tile back.
                var empty=(0 until n).none { (scratch[it] ushr 24) != 0 }
                if (empty) {
                    tile.getPixels(check,0,TILE,0,0,TILE,TILE)
                    empty=TiledBitmap.isFill(check,0)
                }
                if (empty) tile.recycle()
                if (old != null || !empty) {
                    result.tiles[i]=if (empty) null else tile
                    result.touched(i)
                }
            }
        }
        output=result
        return result
    }

    override fun close() { NativeGegl.destroySession(handle) }
}

/**
 * Re-runs a per-pixel [filter] only on source tiles whose identity or version changed, so a live
 * point effect (Levels, Curves, ...) costs per stroke batch what the stroke touched, not the image.
 * Each tile gets exactly what [applyFilterBanded] (without selection) would write there.
 */
internal class PointIncremental(private val filter: Filter) : IncrementalFilter {
    private var output: TiledBitmap? = null
    private var identities = arrayOfNulls<Bitmap>(0)
    private var versions = IntArray(0)

    override fun render(source: TiledBitmap): TiledBitmap {
        val previous = output?.takeIf { it.width == source.width && it.height == source.height && it.fill == source.fill }
        if (previous == null) {
            identities = arrayOfNulls(source.tiles.size)
            versions = IntArray(source.tiles.size) { -1 }
        }
        var result: TiledBitmap? = null
        for (i in source.tiles.indices) {
            val tile = source.tiles[i]
            // Version first: a write that lands during getPixels bumps it and is redone next time.
            val version = source.versions.get(i)
            if (identities[i] === tile && versions[i] == version) continue
            val x = source.tileX(i); val y = source.tileY(i)
            val w = minOf(TILE, source.width - x); val h = minOf(TILE, source.height - y)
            val pixels = IntArray(w * h) { source.fill }
            tile?.getPixels(pixels, 0, w, 0, 0, w, h)
            val filtered = filter.apply(pixels, w, h)
            val out = TiledBitmap.newTile()
            if (source.fill != 0) out.eraseColor(source.fill)
            out.setPixels(filtered, 0, w, 0, 0, w, h)
            val check = IntArray(TILE * TILE)
            out.getPixels(check, 0, TILE, 0, 0, TILE, TILE)
            val empty = TiledBitmap.isFill(check, source.fill)
            if (empty) out.recycle()
            val r = result ?: (previous?.copy()?.also { c -> for (k in c.tiles.indices) c.versions.set(k, previous.versions.get(k)) }
                ?: TiledBitmap(source.width, source.height, source.fill)).also { result = it }
            r.tiles[i] = if (empty) null else out
            r.touched(i)
            identities[i] = tile; versions[i] = version
        }
        val r = result ?: previous ?: TiledBitmap(source.width, source.height, source.fill)
        output = r
        return r
    }

    override fun close() {}
}
