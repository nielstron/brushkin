package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

/**
 * Masking with ALPHA_8 bitmaps.
 *
 * `Canvas.drawBitmap` treats an alpha-only bitmap as *coverage* for the paint's color, so with
 * DST_IN it keeps the destination unchanged everywhere. Drawing the mask through a shader instead
 * makes its values the source alpha, which is what masking needs.
 */
object MaskOps {
    private val src = RectF()
    private val m = Matrix()

    fun apply(canvas: Canvas, mask: Bitmap, dst: RectF, mode: PorterDuff.Mode = PorterDuff.Mode.DST_IN) {
        src.set(0f, 0f, mask.width.toFloat(), mask.height.toFloat())
        val p = Paint().apply {
            isFilterBitmap = true
            shader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setRectToRect(RectF(src), dst, Matrix.ScaleToFit.FILL) })
            }
            xfermode = PorterDuffXfermode(mode)
        }
        canvas.drawRect(dst, p)
        if (mode == PorterDuff.Mode.DST_IN) {
            // Outside the mask everything is masked out.
            canvas.save()
            canvas.clipOutRect(dst)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.restore()
        }
    }

    /** DST_IN with [mask] at (left, top), leaving everything outside the mask's rect untouched. */
    fun multiply(canvas: Canvas, mask: Bitmap, left: Float, top: Float) {
        val p = Paint().apply {
            shader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setTranslate(left, top) }) }
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(left, top, left + mask.width, top + mask.height, p)
    }

    fun apply(canvas: Canvas, mask: Bitmap, left: Float, top: Float, mode: PorterDuff.Mode = PorterDuff.Mode.DST_IN) =
        apply(canvas, mask, RectF(left, top, left + mask.width, top + mask.height), mode)
}
