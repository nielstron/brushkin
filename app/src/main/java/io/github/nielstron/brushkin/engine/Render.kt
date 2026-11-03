package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** A bitmap being placed or transformed (Insert, Paste, Transform tool). */
class Floating(
    val bitmap: Bitmap,
    /** Maps bitmap pixels to doc coordinates. Mutated by the UI thread only. */
    val matrix: Matrix,
    /** Layer the result goes back into; null = becomes a new layer. */
    val targetLayerId: Long?,
    val name: String,
    /** For Transform: the layer content outside the lifted selection. */
    val remainder: TiledBitmap? = null,
) {
    fun corners(): FloatArray {
        val pts = floatArrayOf(0f, 0f, bitmap.width.toFloat(), 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat())
        matrix.mapPoints(pts)
        return pts
    }
}

/**
 * Draws a layer stack. Used both by the on-screen hardware canvas and by software canvases for
 * export and color sampling, so what you see is exactly what you export.
 */
object Compositor {
    private val tilePaint = ThreadLocal.withInitial { Paint() }
    private val layerPaint = ThreadLocal.withInitial { Paint() }

    fun draw(
        canvas: Canvas,
        stack: Stack,
        visible: RectF? = null,
        filter: Boolean = true,
        preview: Pair<Long, TiledBitmap>? = null,
        floating: Floating? = null,
    ) {
        val tp = tilePaint.get()!!
        tp.isFilterBitmap = filter
        tp.isAntiAlias = false
        val lp = layerPaint.get()!!
        for (layer in stack.layers) {
            if (!layer.visible) continue
            val isFloatTarget = floating != null && floating.targetLayerId == layer.id
            val pixels = when {
                // The lifted layer shows only what was left behind; the floating part is drawn on top.
                isFloatTarget -> floating!!.remainder
                preview != null && preview.first == layer.id -> preview.second
                else -> layer.pixels
            }
            val needsLayer = layer.opacity < 1f || layer.mode.blend != null
            if (needsLayer) {
                lp.reset()
                lp.alpha = (layer.opacity * 255).toInt()
                layer.mode.blend?.let { lp.blendMode = it }
                canvas.saveLayer(0f, 0f, stack.width.toFloat(), stack.height.toFloat(), lp)
            }
            if (pixels != null) drawTiles(canvas, pixels, visible, tp)
            if (isFloatTarget) drawFloating(canvas, floating!!, filter)
            if (needsLayer) canvas.restore()
        }
        if (floating != null && floating.targetLayerId == null) drawFloating(canvas, floating, filter)
    }

    fun drawTiles(canvas: Canvas, t: TiledBitmap, visible: RectF?, paint: Paint?) {
        val tiles = t.tiles
        for (i in tiles.indices) {
            val b = tiles[i] ?: continue
            val x = t.tileX(i).toFloat(); val y = t.tileY(i).toFloat()
            if (visible != null && (x > visible.right || y > visible.bottom || x + TILE < visible.left || y + TILE < visible.top)) continue
            canvas.drawBitmap(b, x, y, paint)
        }
    }

    private val floatPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    fun drawFloating(canvas: Canvas, f: Floating, filter: Boolean) {
        floatPaint.isFilterBitmap = filter
        canvas.drawBitmap(f.bitmap, f.matrix, floatPaint)
    }

    /** Flattened image on a software canvas. */
    fun flatten(stack: Stack, background: Int? = null): Bitmap {
        val out = Bitmap.createBitmap(stack.width, stack.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (background != null) c.drawColor(background)
        draw(c, stack)
        return out
    }
}
