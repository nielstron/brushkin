package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** Live filter result shown in place of a layer's pixels or mask. */
class Preview(
    val layerId: Long, val mask: Boolean, val tiles: TiledBitmap, /** Doc pixels per preview pixel. */ val scale: Float = 1f,
    /** Filtering every layer: the previews of the other layers' pixels. */
    val others: Map<Long, TiledBitmap> = emptyMap(),
) {
    fun forLayer(id: Long): Preview? = if (id == layerId) this else others[id]?.let { Preview(id, false, it, scale) }
}

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
    /** Placing or transforming text: becomes / stays an editable text layer. */
    val text: TextSpec? = null,
    /** The whole (flattened) image is being transformed: layers aren't drawn, and applying transforms them all. */
    val wholeImage: Boolean = false,
) {
    /** The placement when lifted, so Move can apply an offset to it. */
    val initial = Matrix(matrix)
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
    /** On-screen pixels of a layer, with its non-destructive effects (set by the engine). */
    @Volatile var displayPixels: ((Layer) -> TiledBitmap)? = null

    private val tilePaint = ThreadLocal.withInitial { Paint() }
    private val whitePaint = Paint().apply { color = android.graphics.Color.WHITE }
    /** Mask tiles: luminance → alpha, then keep only that much of the layer. */
    private val maskPaint = ThreadLocal.withInitial {
        Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
            ))
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
    }

    fun draw(
        canvas: Canvas,
        stack: Stack,
        visible: RectF? = null,
        filter: Boolean = true,
        preview: Preview? = null,
        floating: Floating? = null,
        /** Doc units per screen pixel; mask tiles overlap by half of it so no edge pixel escapes the mask. */
        pixelSize: Float = 0f,
        hiddenId: Long = -1,
    ) {
        val tp = tilePaint.get()!!
        tp.isFilterBitmap = filter
        tp.isAntiAlias = false
        drawList(canvas, stack, stack.layers, visible, filter, preview, floating, pixelSize, tp, hiddenId)
        if (floating != null && floating.targetLayerId == null) drawFloating(canvas, floating, filter)
    }

    private fun drawList(
        canvas: Canvas, stack: Stack, list: List<Layer>, visible: RectF?, filter: Boolean,
        preview: Preview?, floating: Floating?, pixelSize: Float, tp: Paint, hiddenId: Long = -1,
    ) {
        val w = stack.width.toFloat(); val h = stack.height.toFloat()
        for (layer in Stack.expandPassThrough(list)) {
            if (!layer.visible || layer.id == hiddenId) continue
            val pv = preview?.forLayer(layer.id)
            val mask = if (!layer.masked) null else if (pv != null && pv.mask) pv.tiles else layer.mask
            val isGroup = layer.isGroup
            // Groups are always composited in isolation (GIMP's default, non pass-through).
            val needsLayer = isGroup || layer.opacity < 1f || layer.mode.blend != null || mask != null
            if (needsLayer) {
                val lp = Paint()
                lp.alpha = (layer.opacity * 255).toInt()
                layer.mode.blend?.let { lp.blendMode = it }
                canvas.saveLayer(0f, 0f, w, h, lp)
            }
            if (isGroup) {
                drawList(canvas, stack, layer.children!!, visible, filter, preview, floating, pixelSize, tp, hiddenId)
            } else {
                val isFloatTarget = floating != null && floating.targetLayerId == layer.id
                val pixels = when {
                    // The lifted layer shows only what was left behind; the floating part is drawn on top.
                    isFloatTarget -> floating!!.remainder
                    pv != null && !pv.mask -> pv.tiles
                    else -> displayPixels?.invoke(layer) ?: layer.pixels
                }
                if (pixels != null) {
                    if (pv != null && !pv.mask && pv.scale != 1f) drawScaled(canvas, pv, tp, pixelSize)
                    else drawTiles(canvas, pixels, visible, tp)
                }
                if (isFloatTarget) drawFloating(canvas, floating!!, filter)
            }
            if (mask != null) {
                // Mask tiles are opaque, so overlapping them inside their own layer is harmless and
                // closes GPU seams; the whole mask is then applied once (luminance → alpha, DST_IN).
                canvas.saveLayer(0f, 0f, w, h, maskPaint.get()!!)
                canvas.drawRect(0f, 0f, w, h, whitePaint)
                if (pv != null && pv.mask && pv.scale != 1f) drawScaled(canvas, pv, tp, pixelSize)
                else drawTiles(canvas, mask, visible, tp, pixelSize * 0.5f)
                canvas.restore()
            }
            if (needsLayer) canvas.restore()
        }
    }

    private val dst = ThreadLocal.withInitial { RectF() }

    /** Low-resolution previews are drawn scaled up, slightly overlapped to avoid seams. */
    private fun drawScaled(canvas: Canvas, pv: Preview, paint: Paint, pixelSize: Float) {
        canvas.save()
        canvas.scale(pv.scale, pv.scale)
        val prev = paint.isFilterBitmap
        paint.isFilterBitmap = true
        drawTiles(canvas, pv.tiles, null, paint, (pixelSize / pv.scale).coerceAtLeast(0.5f / pv.scale))
        paint.isFilterBitmap = prev
        canvas.restore()
    }

    fun drawTiles(canvas: Canvas, t: TiledBitmap, visible: RectF?, paint: Paint?, pad: Float = 0f) {
        val tiles = t.tiles
        val r = dst.get()!!
        for (i in tiles.indices) {
            val b = tiles[i] ?: continue
            val x = t.tileX(i).toFloat(); val y = t.tileY(i).toFloat()
            if (visible != null && (x > visible.right || y > visible.bottom || x + TILE < visible.left || y + TILE < visible.top)) continue
            if (pad == 0f) canvas.drawBitmap(b, x, y, paint)
            else { r.set(x - pad, y - pad, x + TILE + pad, y + TILE + pad); canvas.drawBitmap(b, null, r, paint) }
        }
    }

    private val floatPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    fun drawFloating(canvas: Canvas, f: Floating, filter: Boolean) {
        floatPaint.isFilterBitmap = filter
        canvas.drawBitmap(f.bitmap, f.matrix, floatPaint)
    }

    /**
     * Flattened image, composited in **linear light** like the GPU display and GIMP 2.10+: layers
     * are drawn into a half-float linear-sRGB bitmap (Skia blends in the destination's color
     * space), then converted back to 8-bit sRGB. [region] limits the work to part of the image.
     */
    fun flatten(stack: Stack, background: Int? = null, region: android.graphics.Rect? = null): Bitmap {
        val r = region ?: android.graphics.Rect(0, 0, stack.width, stack.height)
        val lin = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.RGBA_F16, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val c = Canvas(lin)
        if (background != null) c.drawColor(background)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        drawLinear(c, stack, stack.layers, RectF(r))
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(lin, 0f, 0f, null)
        return out
    }

    /** The whole image scaled down to at most [maxDim] pixels across, without compositing at full size first. */
    fun thumbnail(stack: Stack, maxDim: Int): Bitmap {
        val k = minOf(1f, maxDim.toFloat() / maxOf(stack.width, stack.height))
        val w = (stack.width * k).toInt().coerceAtLeast(1); val h = (stack.height * k).toInt().coerceAtLeast(1)
        // Composited at twice the size and averaged down, so thin lines stay smooth.
        val zoom = if (k <= 0.5f) 2 else 1
        val lin = Bitmap.createBitmap(w * zoom, h * zoom, Bitmap.Config.RGBA_F16, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val c = Canvas(lin)
        c.scale(w.toFloat() * zoom / stack.width, h.toFloat() * zoom / stack.height)
        // Tiles overlap by a pixel so their scaled edges leave no seams.
        drawLinear(c, stack, stack.layers, RectF(0f, 0f, stack.width.toFloat(), stack.height.toFloat()), tilePad = stack.width.toFloat() / (w * zoom))
        val srgb = Bitmap.createBitmap(w * zoom, h * zoom, Bitmap.Config.ARGB_8888)
        Canvas(srgb).drawBitmap(lin, 0f, 0f, null)
        lin.recycle()
        if (zoom == 1) return srgb
        return Bitmap.createScaledBitmap(srgb, w, h, true).also { srgb.recycle() }
    }

    private fun drawLinear(canvas: Canvas, stack: Stack, list: List<Layer>, visible: RectF, tilePad: Float = 0f) {
        val w = stack.width.toFloat(); val h = stack.height.toFloat()
        val tp = Paint().apply { isFilterBitmap = false }
        for (layer in Stack.expandPassThrough(list)) {
            if (!layer.visible) continue
            val mask = if (layer.masked) layer.mask else null
            val needsLayer = layer.isGroup || layer.opacity < 1f || layer.mode.blend != null || mask != null
            if (needsLayer) {
                val lp = Paint()
                lp.alpha = (layer.opacity * 255).toInt()
                layer.mode.blend?.let { lp.blendMode = it }
                canvas.saveLayer(0f, 0f, w, h, lp)
            }
            if (layer.isGroup) drawLinear(canvas, stack, layer.children!!, visible, tilePad)
            else drawTiles(canvas, Effects.pixelsSync(layer), visible, tp, tilePad)
            if (mask != null) {
                // Mask values are coverage, not color: turn each tile into an alpha mask first so
                // no color conversion touches them.
                for (i in mask.tiles.indices) {
                    val t = mask.tiles[i] ?: continue
                    val x = mask.tileX(i).toFloat(); val y = mask.tileY(i).toFloat()
                    if (x > visible.right || y > visible.bottom || x + TILE < visible.left || y + TILE < visible.top) continue
                    MaskOps.multiply(canvas, toAlpha(t), x, y)
                }
            }
            if (needsLayer) canvas.restore()
        }
    }

    private fun toAlpha(t: Bitmap): Bitmap {
        val px = IntArray(TILE * TILE); t.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
        val a = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)
        val bytes = ByteArray(a.rowBytes * TILE)
        for (y in 0 until TILE) for (x in 0 until TILE) bytes[y * a.rowBytes + x] = luma(px[y * TILE + x]).toByte()
        a.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return a
    }
}
