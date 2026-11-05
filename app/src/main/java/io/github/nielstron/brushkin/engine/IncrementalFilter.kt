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

    override fun render(source: TiledBitmap): TiledBitmap {
        for (i in source.tiles.indices) {
            val tile=source.tiles[i]
            val version=source.versions.get(i)
            if (!first && identities[i] === tile && versions[i] == version) continue
            val x=source.tileX(i); val y=source.tileY(i)
            val w=minOf(TILE,source.width-x); val h=minOf(TILE,source.height-y)
            val pixels=IntArray(w*h) { source.fill }
            tile?.getPixels(pixels,0,w,0,0,w,h)
            NativeGegl.updateSession(handle,x,y,w,h,pixels)
            identities[i]=tile; versions[i]=version
        }
        first=false
        val pixels=NativeGegl.renderSession(handle)
        val x=pixels[0]; val y=pixels[1]; val w=pixels[2]; val h=pixels[3]
        if (w == 0 || h == 0) return output
        val result=output.copy()
        for (i in result.tiles.indices) result.versions.set(i,output.versions.get(i))
        val dirty=Rect(x,y,x+w,y+h)
        result.forTilesIn(dirty) { i ->
            val tx=result.tileX(i); val ty=result.tileY(i)
            val l=maxOf(x,tx); val t=maxOf(y,ty)
            val r=minOf(x+w,tx+TILE); val b=minOf(y+h,ty+TILE)
            val tile=TiledBitmap.copyTile(result.tiles[i])
            tile.setPixels(pixels,4+(t-y)*w+l-x,w,l-tx,t-ty,r-l,b-t)
            // Some operations (notably recursive Gaussian blur) invalidate entire rows/columns.
            // Keep identical output tiles so the next effect still sees only actual changes.
            val old=result.tiles[i]
            if (old != null && old.sameAs(tile)) {
                tile.recycle()
            } else {
                val check=IntArray(TILE*TILE)
                tile.getPixels(check,0,TILE,0,0,TILE,TILE)
                val empty=TiledBitmap.isFill(check,0)
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
