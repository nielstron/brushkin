package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class TipShape { ROUND, CHALK, FLAT, SPLATTER }

enum class PaintTool { BRUSH, PENCIL, AIRBRUSH, ERASER, SMUDGE }

data class BrushPreset(val name: String, val shape: TipShape, val hardness: Float, val aspect: Float = 1f, val angle: Float = 0f, val spacing: Float = 0.1f)

val BRUSH_PRESETS = listOf(
    BrushPreset("Soft round", TipShape.ROUND, 0.3f),
    BrushPreset("Hard round", TipShape.ROUND, 0.95f),
    BrushPreset("Chalk", TipShape.CHALK, 0.6f, spacing = 0.15f),
    BrushPreset("Flat", TipShape.FLAT, 0.8f, aspect = 0.3f, angle = 45f, spacing = 0.05f),
    BrushPreset("Ink", TipShape.FLAT, 1f, aspect = 0.5f, angle = 35f, spacing = 0.04f),
    BrushPreset("Splatter", TipShape.SPLATTER, 1f, spacing = 0.5f),
)

data class BrushSettings(
    val size: Float = 24f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val hardness: Float = 0.3f,
    val spacing: Float = 0.1f,
    val shape: TipShape = TipShape.ROUND,
    val aspect: Float = 1f,
    val angle: Float = 0f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = true,
    val smoothing: Float = 0.3f,
    val mode: LayerMode = LayerMode.NORMAL,
    /** Smudge: how much of the carried paint is kept per dab. */
    val smudgeRate: Float = 0.6f,
)

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

/** Rasterized brush tips are cached by their quantized parameters. */
object TipCache {
    private data class Key(val d: Int, val hard: Int, val shape: TipShape, val aspect: Int, val angle: Int, val pencil: Boolean)
    private val cache = object : LinkedHashMap<Key, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?) = size > 24
    }

    fun get(diameter: Float, s: BrushSettings, pencil: Boolean): Bitmap {
        // Generate at least 32px so small dabs stay smooth when scaled down.
        val d = max(if (pencil) 1 else 32, min(1024, diameter.roundToInt()))
        val key = Key(d, (s.hardness * 20).roundToInt(), s.shape, (s.aspect * 20).roundToInt(), s.angle.roundToInt(), pencil)
        return synchronized(cache) { cache.getOrPut(key) { render(d, s, pencil) } }
    }

    private fun render(d: Int, s: BrushSettings, pencil: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
        val px = ByteArray(bmp.rowBytes * d)
        val r = d / 2f
        val hard = s.hardness.coerceIn(0f, 0.999f)
        val ca = cos(Math.toRadians(s.angle.toDouble())).toFloat()
        val sa = sin(Math.toRadians(s.angle.toDouble())).toFloat()
        val aspect = s.aspect.coerceIn(0.05f, 1f)
        val rnd = Random(d * 31 + s.shape.ordinal)
        if (s.shape == TipShape.SPLATTER) {
            val c = Canvas(bmp)
            val p = Paint().apply { isAntiAlias = !pencil; color = 0xFF000000.toInt() }
            repeat(14) {
                val a = rnd.nextFloat() * 6.283f
                val rr = r * 0.85f * kotlin.math.sqrt(rnd.nextFloat())
                c.drawCircle(r + cos(a) * rr, r + sin(a) * rr, max(0.5f, r * (0.05f + rnd.nextFloat() * 0.12f)), p)
            }
            return bmp
        }
        for (y in 0 until d) for (x in 0 until d) {
            val dx = x + 0.5f - r; val dy = y + 0.5f - r
            // Rotate into tip space and stretch the minor axis.
            val u = dx * ca + dy * sa
            val v = (-dx * sa + dy * ca) / aspect
            val dist = hypot(u, v) / r
            var a = when {
                pencil -> if (dist <= 1f) 1f else 0f
                dist >= 1f -> 0f
                dist <= hard -> 1f
                else -> {
                    val t = (dist - hard) / (1f - hard)
                    // Smooth gaussian-like falloff.
                    exp(-t * t * 4f) * (1f - t)
                }
            }
            if (s.shape == TipShape.CHALK && a > 0f) a *= if (rnd.nextFloat() < 0.35f) 0f else (0.5f + rnd.nextFloat() * 0.5f)
            px[y * bmp.rowBytes + x] = (a * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px))
        return bmp
    }
}

/**
 * One brush stroke in progress on a layer. Dabs accumulate in a per-stroke alpha buffer so that
 * stroke opacity behaves like GIMP's (overlapping dabs within one stroke never exceed the
 * opacity), and the layer tiles are recomposited from their original contents + the buffer.
 */
class StrokeOp(
    private val target: TiledBitmap,
    private val tool: PaintTool,
    private val s: BrushSettings,
    private val color: Int,
    private val lockAlpha: Boolean,
    private val selection: Selection?,
) {
    val session = TileEditSession(target)
    private val buf = HashMap<Int, Bitmap>()
    private val pencil = tool == PaintTool.PENCIL
    private val effective = when (tool) {
        PaintTool.AIRBRUSH -> s.copy(flow = s.flow * 0.12f, spacing = min(s.spacing, 0.05f))
        PaintTool.PENCIL -> s.copy(hardness = 1f)
        else -> s
    }
    private val tip = TipCache.get(s.size, effective, pencil)
    private val tipRect = Rect(0, 0, tip.width, tip.height)
    private val dabPaint = Paint().apply { isFilterBitmap = !pencil; isAntiAlias = !pencil }
    private val canvas = Canvas()
    private val compPaint = Paint().apply {
        this.color = this@StrokeOp.color
        alpha = (s.opacity * 255).roundToInt()
        when {
            tool == PaintTool.ERASER -> xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            lockAlpha -> xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            s.mode.blend != null -> blendMode = s.mode.blend
        }
    }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val scratch: Bitmap? = if (selection != null) Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8) else null

    private var last: StrokePoint? = null
    private var smoothed: StrokePoint? = null
    private var residual = 0f
    private val rnd = Random(System.nanoTime())

    // Smudge state.
    private var smudgeBuf: Bitmap? = null
    private var smudgeTmp: Bitmap? = null

    private val dirty = HashSet<Int>()
    val bounds = Rect()

    /** Adds input points; returns the doc-space rect that changed. */
    fun add(points: List<StrokePoint>): Rect {
        dirty.clear()
        val changed = Rect()
        for (raw in points) {
            val p = smooth(raw)
            val l = last
            if (l == null) {
                dab(p, changed)
                last = p
                continue
            }
            val dist = hypot(p.x - l.x, p.y - l.y)
            var travelled = 0f
            while (true) {
                val pr = l.pressure + (p.pressure - l.pressure) * (travelled / max(dist, 1e-3f))
                val step = max(0.5f, effective.spacing * s.size * sizeFactor(pr))
                val need = step - residual
                if (travelled + need > dist) { residual += dist - travelled; break }
                travelled += need
                residual = 0f
                val t = travelled / max(dist, 1e-3f)
                dab(StrokePoint(l.x + (p.x - l.x) * t, l.y + (p.y - l.y) * t, l.pressure + (p.pressure - l.pressure) * t), changed)
            }
            last = p
        }
        if (tool != PaintTool.SMUDGE) for (i in dirty) recomposite(i)
        bounds.union(changed)
        return changed
    }

    private fun smooth(p: StrokePoint): StrokePoint {
        val prev = smoothed
        val k = s.smoothing.coerceIn(0f, 0.95f)
        val out = if (prev == null || k == 0f) p else StrokePoint(
            prev.x + (p.x - prev.x) * (1 - k), prev.y + (p.y - prev.y) * (1 - k), prev.pressure + (p.pressure - prev.pressure) * (1 - k)
        )
        smoothed = out
        return out
    }

    private fun sizeFactor(p: Float) = if (s.pressureSize) 0.15f + 0.85f * p.coerceIn(0f, 1f) else 1f

    private fun dab(p: StrokePoint, changed: Rect) {
        val r = max(0.5f, s.size * sizeFactor(p.pressure) / 2f)
        val jx = if (s.shape == TipShape.SPLATTER) (rnd.nextFloat() - 0.5f) * r else 0f
        val jy = if (s.shape == TipShape.SPLATTER) (rnd.nextFloat() - 0.5f) * r else 0f
        val cx = p.x + jx; val cy = p.y + jy
        val dst = if (pencil) RectF(
            (cx - r).roundToInt().toFloat(), (cy - r).roundToInt().toFloat(),
            (cx - r).roundToInt() + max(1, (2 * r).roundToInt()).toFloat(), (cy - r).roundToInt() + max(1, (2 * r).roundToInt()).toFloat()
        ) else RectF(cx - r, cy - r, cx + r, cy + r)
        val ir = Rect(); dst.roundOut(ir)
        if (!ir.intersect(0, 0, target.width, target.height)) return
        changed.union(ir)
        val alpha = effective.flow * (if (s.pressureOpacity) p.pressure.coerceIn(0.05f, 1f) else 1f)
        if (tool == PaintTool.SMUDGE) { smudgeDab(dst, ir, alpha); return }
        dabPaint.alpha = (alpha * 255).roundToInt().coerceIn(1, 255)
        target.forTilesIn(ir) { i ->
            val b = buf.getOrPut(i) { Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8) }
            canvas.setBitmap(b)
            canvas.save()
            canvas.translate(-target.tileX(i).toFloat(), -target.tileY(i).toFloat())
            canvas.drawBitmap(tip, tipRect, dst, dabPaint)
            canvas.restore()
            dirty.add(i)
        }
        canvas.setBitmap(null)
    }

    private fun recomposite(i: Int) {
        val tile = session.writable(i)
        val orig = session.original(i)
        canvas.setBitmap(tile)
        tile.eraseColor(0)
        if (orig != null) canvas.drawBitmap(orig, 0f, 0f, null)
        var src = buf[i]!!
        if (selection != null) {
            val sc = scratch!!
            val c2 = Canvas(sc)
            sc.eraseColor(0)
            c2.drawBitmap(src, 0f, 0f, null)
            c2.drawBitmap(selection.mask, -target.tileX(i).toFloat(), -target.tileY(i).toFloat(), maskPaint)
            src = sc
        }
        canvas.drawBitmap(src, 0f, 0f, compPaint)
        canvas.setBitmap(null)
    }

    private fun smudgeDab(dst: RectF, ir: Rect, alpha: Float) {
        val d = max(1, ir.width().coerceAtLeast(ir.height()))
        val size = max(d, 2)
        if (smudgeBuf == null || smudgeBuf!!.width != size) {
            smudgeBuf = null
            smudgeTmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
        val tmp = smudgeTmp!!
        // Sample what's under the dab.
        tmp.eraseColor(0)
        val c = Canvas(tmp)
        target.forTilesIn(ir) { i ->
            val t = target.tiles[i] ?: return@forTilesIn
            c.drawBitmap(t, (target.tileX(i) - ir.left).toFloat(), (target.tileY(i) - ir.top).toFloat(), null)
        }
        val sb = smudgeBuf
        if (sb == null) {
            smudgeBuf = tmp.copy(Bitmap.Config.ARGB_8888, true)
            return
        }
        // Mix carried paint with what's under the brush.
        val mixed = sb.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(mixed).drawBitmap(tmp, 0f, 0f, Paint().apply { this.alpha = ((1f - s.smudgeRate) * 255).roundToInt() })
        smudgeBuf = mixed
        // Shape by the tip and apply.
        val stamp = mixed.copy(Bitmap.Config.ARGB_8888, true)
        val sc = Canvas(stamp)
        sc.drawBitmap(tip, tipRect, RectF(dst.left - ir.left, dst.top - ir.top, dst.right - ir.left, dst.bottom - ir.top), maskPaint)
        if (selection != null) sc.drawBitmap(selection.mask, -ir.left.toFloat(), -ir.top.toFloat(), maskPaint)
        val p = Paint().apply { this.alpha = (alpha * s.opacity * 255).roundToInt() }
        if (lockAlpha) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        target.forTilesIn(ir) { i ->
            val t = session.writable(i)
            canvas.setBitmap(t)
            canvas.drawBitmap(stamp, (ir.left - target.tileX(i)).toFloat(), (ir.top - target.tileY(i)).toFloat(), p)
        }
        canvas.setBitmap(null)
    }

    fun finish(): Map<Int, Pair<Bitmap?, Bitmap?>> = session.finish()
    fun cancel() = session.cancel()

    companion object {
        fun isTap(points: List<StrokePoint>) = points.size < 2 || points.all { abs(it.x - points[0].x) < 1 && abs(it.y - points[0].y) < 1 }
    }
}
