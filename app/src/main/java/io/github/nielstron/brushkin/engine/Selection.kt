package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.ByteBuffer

enum class SelectMode { REPLACE, ADD, SUBTRACT, INTERSECT }

/**
 * An immutable selection mask (0–255 per pixel, so feathered selections work like in GIMP),
 * plus precomputed [bounds] and an [outline] path for drawing marching ants.
 */
class Selection private constructor(
    val width: Int,
    val height: Int,
    val mask: Bitmap,
    val bounds: Rect,
    val outline: Path,
) {
    fun bytes(): ByteArray = maskBytes(mask, width, height)

    fun isEmpty() = bounds.isEmpty

    companion object {
        fun fromMask(mask: Bitmap): Selection? {
            val w = mask.width; val h = mask.height
            val bytes = maskBytes(mask, w, h)
            val bounds = boundsOf(bytes, w, h) ?: return null
            return Selection(w, h, mask, bounds, outlineOf(bytes, w, h, bounds))
        }

        fun fromBytes(bytes: ByteArray, w: Int, h: Int): Selection? {
            val bounds = boundsOf(bytes, w, h) ?: return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            writeMaskBytes(bmp, bytes, w, h)
            return Selection(w, h, bmp, bounds, outlineOf(bytes, w, h, bounds))
        }

        fun all(w: Int, h: Int): Selection {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            bmp.eraseColor(0xFF000000.toInt())
            val p = Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW) }
            return Selection(w, h, bmp, Rect(0, 0, w, h), p)
        }

        /** Combine a new shape [path] with [old] according to [mode]. */
        fun combine(old: Selection?, path: Path, w: Int, h: Int, mode: SelectMode, antialias: Boolean, feather: Float): Selection? {
            val shape = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(shape).drawPath(path, Paint().apply { isAntiAlias = antialias; color = 0xFF000000.toInt() })
            var shapeMask = shape
            if (feather > 0.5f) {
                val b = blurBytes(maskBytes(shape, w, h), w, h, feather.toInt())
                shapeMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { writeMaskBytes(it, b, w, h) }
            }
            return combineMask(old, shapeMask, mode)
        }

        fun combineMask(old: Selection?, shapeMask: Bitmap, mode: SelectMode): Selection? {
            val w = shapeMask.width; val h = shapeMask.height
            if (mode == SelectMode.REPLACE) return fromMask(shapeMask)
            if (old == null) return if (mode == SelectMode.ADD) fromMask(shapeMask) else null
            val out = old.mask.copy(Bitmap.Config.ALPHA_8, true)
            val c = Canvas(out)
            when (mode) {
                SelectMode.ADD -> c.drawBitmap(shapeMask, 0f, 0f, Paint())
                SelectMode.SUBTRACT -> MaskOps.apply(c, shapeMask, 0f, 0f, PorterDuff.Mode.DST_OUT)
                SelectMode.INTERSECT -> MaskOps.apply(c, shapeMask, 0f, 0f, PorterDuff.Mode.DST_IN)
                SelectMode.REPLACE -> {}
            }
            return fromMask(out)
        }

        fun maskBytes(mask: Bitmap, w: Int, h: Int): ByteArray {
            val rb = mask.rowBytes
            val buf = ByteBuffer.allocate(rb * h)
            mask.copyPixelsToBuffer(buf)
            val raw = buf.array()
            if (rb == w) return raw
            val out = ByteArray(w * h)
            for (y in 0 until h) System.arraycopy(raw, y * rb, out, y * w, w)
            return out
        }

        fun writeMaskBytes(mask: Bitmap, bytes: ByteArray, w: Int, h: Int) {
            val rb = mask.rowBytes
            val src = if (rb == w) bytes else ByteArray(rb * h).also { for (y in 0 until h) System.arraycopy(bytes, y * w, it, y * rb, w) }
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(src))
        }

        private fun boundsOf(b: ByteArray, w: Int, h: Int): Rect? {
            var l = w; var t = h; var r = -1; var btm = -1
            for (y in 0 until h) {
                val row = y * w
                var rowAny = false
                for (x in 0 until w) if (b[row + x].toInt() != 0) {
                    rowAny = true
                    if (x < l) l = x
                    break
                }
                if (!rowAny) continue
                for (x in w - 1 downTo 0) if (b[row + x].toInt() != 0) { if (x > r) r = x; break }
                if (y < t) t = y
                btm = y
            }
            return if (r < 0) null else Rect(l, t, r + 1, btm + 1)
        }

        /**
         * Trace the 50% boundary of the mask as axis-aligned segments. Large masks are traced on
         * a downsampled grid so the path stays cheap to stroke every frame.
         */
        private fun outlineOf(b: ByteArray, w: Int, h: Int, bounds: Rect): Path {
            val f = maxOf(1, (maxOf(bounds.width(), bounds.height()) + 1023) / 1024)
            val gw = (w + f - 1) / f; val gh = (h + f - 1) / f
            fun inside(gx: Int, gy: Int): Boolean {
                if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) return false
                val x = minOf(w - 1, gx * f + f / 2); val y = minOf(h - 1, gy * f + f / 2)
                return (b[y * w + x].toInt() and 0xFF) >= 128
            }
            val gx0 = maxOf(0, bounds.left / f - 1); val gx1 = minOf(gw, bounds.right / f + 2)
            val gy0 = maxOf(0, bounds.top / f - 1); val gy1 = minOf(gh, bounds.bottom / f + 2)
            val p = Path()
            val ff = f.toFloat()
            // Horizontal edges.
            for (gy in gy0..gy1) {
                var runStart = -1
                for (gx in gx0..gx1) {
                    val edge = gx < gx1 && inside(gx, gy) != inside(gx, gy - 1)
                    if (edge && runStart < 0) runStart = gx
                    if (!edge && runStart >= 0) {
                        p.moveTo(runStart * ff, gy * ff); p.lineTo(gx * ff, gy * ff); runStart = -1
                    }
                }
            }
            // Vertical edges.
            for (gx in gx0..gx1) {
                var runStart = -1
                for (gy in gy0..gy1) {
                    val edge = gy < gy1 && inside(gx, gy) != inside(gx - 1, gy)
                    if (edge && runStart < 0) runStart = gy
                    if (!edge && runStart >= 0) {
                        p.moveTo(gx * ff, runStart * ff); p.lineTo(gx * ff, gy * ff); runStart = -1
                    }
                }
            }
            return p
        }

        /** Three-pass box blur ≈ gaussian, on an 8-bit mask. */
        fun blurBytes(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
            if (radius < 1) return src.copyOf()
            val r = maxOf(1, radius / 2)
            var a = IntArray(w * h) { src[it].toInt() and 0xFF }
            var tmp = IntArray(w * h)
            repeat(3) {
                boxH(a, tmp, w, h, r); boxV(tmp, a, w, h, r)
            }
            return ByteArray(w * h) { a[it].coerceIn(0, 255).toByte() }
        }

        fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            for (y in 0 until h) {
                val row = y * w
                var sum = 0
                for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[row + x] = sum / div
                    sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
        }

        fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            for (x in 0 until w) {
                var sum = 0
                for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = sum / div
                    sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
                }
            }
        }
    }
}
