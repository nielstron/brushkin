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

enum class PaintTool {
    BRUSH, PENCIL, AIRBRUSH, ERASER, SMUDGE, CLONE, HEAL, DODGE, BURN, BLUR, SHARPEN;

    /** Tools whose stroke reveals a precomputed effect of the original pixels instead of a color. */
    val isEffect get() = this in setOf(CLONE, HEAL, DODGE, BURN, BLUR, SHARPEN)
}

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
    /** Smudge: paint carried per dab. Dodge/Burn: exposure. Blur/Sharpen: strength. */
    val rate: Float = 0.6f,
    // Paint dynamics (GIMP's dynamics editor): how speed, stylus tilt and randomness change dabs.
    /** −1…1: fast strokes get thinner (−) or thicker (+). */
    val velocitySize: Float = 0f,
    /** −1…1: fast strokes get lighter (−) or stronger (+). */
    val velocityOpacity: Float = 0f,
    /** 0…1: random size jitter per dab. */
    val randomSize: Float = 0f,
    val randomOpacity: Float = 0f,
    /** 0…1: random tip rotation (up to ±180°). */
    val randomAngle: Float = 0f,
    /** 0…1: a tilted stylus paints wider (pencil shading). */
    val tiltSize: Float = 0f,
    /** The tip follows the stylus' direction. */
    val tiltAngle: Boolean = false,
    /** An imported/created image brush (by name) instead of the generated tip. */
    val tipImage: String? = null,
)

/** [tilt] radians from vertical, [orient] radians (stylus direction), [time] ms. */
data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val tilt: Float = 0f, val orient: Float = 0f, val time: Long = 0L)

/** Symmetry painting: every dab is repeated mirrored and/or rotated around ([cx], [cy]). */
data class Symmetry(val kind: Kind, val cx: Float, val cy: Float, val segments: Int = 6) {
    enum class Kind(val label: String) { MIRROR_H("Mirror ↔"), MIRROR_V("Mirror ↕"), MIRROR_BOTH("Both"), MANDALA("Mandala") }

    /** Copies of point ([x], [y]) as (x, y, extra rotation°, mirrored), including the original. */
    fun copies(x: Float, y: Float): List<FloatArray> {
        val dx = x - cx; val dy = y - cy
        return when (kind) {
            Kind.MIRROR_H -> listOf(floatArrayOf(x, y, 0f, 0f), floatArrayOf(cx - dx, y, 0f, 1f))
            Kind.MIRROR_V -> listOf(floatArrayOf(x, y, 0f, 0f), floatArrayOf(x, cy - dy, 0f, 1f))
            Kind.MIRROR_BOTH -> listOf(floatArrayOf(x, y, 0f, 0f), floatArrayOf(cx - dx, y, 0f, 1f), floatArrayOf(x, cy - dy, 0f, 1f), floatArrayOf(cx - dx, cy - dy, 180f, 0f))
            Kind.MANDALA -> List(segments.coerceIn(2, 64)) { k ->
                val a = (2 * Math.PI * k / segments.coerceIn(2, 64)); val c = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
                floatArrayOf(cx + dx * c - dy * sn, cy + dx * sn + dy * c, Math.toDegrees(a).toFloat(), 0f)
            }
        }
    }
}

/** Rasterized brush tips are cached by their quantized parameters. */
object TipCache {
    private data class Key(val d: Int, val hard: Int, val shape: TipShape, val aspect: Int, val angle: Int, val pencil: Boolean, val image: String? = null, val frame: Int = 0)
    private val cache = object : LinkedHashMap<Key, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?) = size > 24
    }

    fun get(diameter: Float, s: BrushSettings, pencil: Boolean, frame: Int = 0): Bitmap {
        // Generate at least 32px so small dabs stay smooth when scaled down.
        val d = max(if (pencil) 1 else 32, min(1024, diameter.roundToInt()))
        val img = s.tipImage?.let { Library.store?.brushByName?.get(it) }
        val key = Key(d, (s.hardness * 20).roundToInt(), s.shape, (s.aspect * 20).roundToInt(), s.angle.roundToInt(), pencil, img?.name, frame)
        return synchronized(cache) { cache.getOrPut(key) { if (img != null) renderImage(d, img.frames[frame % img.frames.size], s) else render(d, s, pencil) } }
    }

    /** Image brushes: the mask scaled to fit a d×d square (aspect kept), rotated by the angle. */
    private fun renderImage(d: Int, mask: Bitmap, s: BrushSettings): Bitmap {
        val out = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
        val k = d / max(mask.width, mask.height).toFloat()
        val c = Canvas(out)
        c.rotate(s.angle, d / 2f, d / 2f)
        c.translate((d - mask.width * k) / 2f, (d - mask.height * k) / 2f); c.scale(k, k)
        c.drawBitmap(mask, 0f, 0f, Paint().apply { isFilterBitmap = true })
        return out
    }

    fun frames(s: BrushSettings) = s.tipImage?.let { Library.store?.brushByName?.get(it)?.frames?.size } ?: 1

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
    /** Clone/Heal: source position minus destination position. */
    private val cloneOffset: android.graphics.Point? = null,
    private val symmetry: Symmetry? = null,
    /** Perspective clone: maps a destination point to its source (a homography). */
    private val perspective: android.graphics.Matrix? = null,
) {
    val session = TileEditSession(target)
    private val buf = HashMap<Int, Bitmap>()
    private val pencil = tool == PaintTool.PENCIL
    private val effective = when (tool) {
        PaintTool.AIRBRUSH -> s.copy(flow = s.flow * 0.12f, spacing = min(s.spacing, 0.05f))
        PaintTool.PENCIL -> s.copy(hardness = 1f)
        else -> s
    }
    private var tip = TipCache.get(s.size, effective, pencil)
    /** Animated brushes (.gih) pick a random cell per dab. */
    private val frameCount = TipCache.frames(s)
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
                dab(p.copy(x = l.x + (p.x - l.x) * t, y = l.y + (p.y - l.y) * t, pressure = l.pressure + (p.pressure - l.pressure) * t), changed)
            }
            last = p
        }
        if (tool != PaintTool.SMUDGE) for (i in dirty) recomposite(i)
        bounds.union(changed)
        return changed
    }

    private var lastRaw: StrokePoint? = null
    private var noSmooth = false

    /** Smoothing lags behind the finger; finish the stroke exactly where it lifted. */
    fun catchUp() {
        val raw = lastRaw ?: return
        noSmooth = true
        add(listOf(raw))
        noSmooth = false
    }

    /** Smoothed stroke speed in doc px per ms (0 when the input has no timestamps). */
    private var speed = 0f

    private fun smooth(p: StrokePoint): StrokePoint {
        lastRaw?.let { l -> if (p.time > l.time && l.time > 0) speed = speed * 0.7f + 0.3f * (hypot(p.x - l.x, p.y - l.y) / (p.time - l.time)) }
        lastRaw = p
        if (noSmooth) { smoothed = p; return p }
        val prev = smoothed
        val k = s.smoothing.coerceIn(0f, 0.95f)
        val out = if (prev == null || k == 0f) p else p.copy(
            x = prev.x + (p.x - prev.x) * (1 - k), y = prev.y + (p.y - prev.y) * (1 - k), pressure = prev.pressure + (p.pressure - prev.pressure) * (1 - k)
        )
        smoothed = out
        return out
    }

    private fun sizeFactor(p: Float) = if (s.pressureSize) 0.15f + 0.85f * p.coerceIn(0f, 1f) else 1f

    private fun dab(p: StrokePoint, changed: Rect) {
        // Dynamics: speed (0…1 at ~2 px/ms), stylus tilt (0…1 at 60°) and per-dab randomness.
        val v = (speed / 2f).coerceIn(0f, 1f)
        val tiltN = (p.tilt / 1.05f).coerceIn(0f, 1f)
        var f = sizeFactor(p.pressure) * (1 + s.velocitySize * v) * (1 + s.tiltSize * tiltN * 2f)
        if (s.randomSize > 0f) f *= 1 - s.randomSize * rnd.nextFloat()
        var alpha = effective.flow * (if (s.pressureOpacity) p.pressure.coerceIn(0.05f, 1f) else 1f) * (1 + s.velocityOpacity * v)
        if (s.randomOpacity > 0f) alpha *= 1 - s.randomOpacity * rnd.nextFloat()
        alpha = alpha.coerceIn(0f, 1f)
        var angle = 0f
        if (s.tiltAngle && p.tilt > 0.05f) angle += Math.toDegrees(p.orient.toDouble()).toFloat() - s.angle
        if (s.randomAngle > 0f) angle += (rnd.nextFloat() - 0.5f) * 360f * s.randomAngle
        val r = max(0.5f, s.size * max(0.05f, f) / 2f)
        val jx = if (s.shape == TipShape.SPLATTER) (rnd.nextFloat() - 0.5f) * r else 0f
        val jy = if (s.shape == TipShape.SPLATTER) (rnd.nextFloat() - 0.5f) * r else 0f
        val sym = symmetry
        if (sym == null || tool == PaintTool.SMUDGE) { stamp(p.x + jx, p.y + jy, r, alpha, angle, false, changed); return }
        for (c in sym.copies(p.x + jx, p.y + jy)) stamp(c[0], c[1], r, alpha, angle + c[2], c[3] != 0f, changed)
    }

    private fun stamp(cx: Float, cy: Float, r: Float, alpha: Float, angle: Float, mirrored: Boolean, changed: Rect) {
        val dst = if (pencil) RectF(
            (cx - r).roundToInt().toFloat(), (cy - r).roundToInt().toFloat(),
            (cx - r).roundToInt() + max(1, (2 * r).roundToInt()).toFloat(), (cy - r).roundToInt() + max(1, (2 * r).roundToInt()).toFloat()
        ) else RectF(cx - r, cy - r, cx + r, cy + r)
        val ir = Rect()
        // A rotated tip needs the rect's circumcircle.
        val turned = !pencil && (angle != 0f || mirrored)
        if (turned) RectF(cx - r * 1.42f, cy - r * 1.42f, cx + r * 1.42f, cy + r * 1.42f).roundOut(ir) else dst.roundOut(ir)
        if (!ir.intersect(0, 0, target.width, target.height)) return
        changed.union(ir)
        if (tool == PaintTool.SMUDGE) { smudgeDab(dst, ir, alpha); return }
        dabPaint.alpha = (alpha * 255).roundToInt().coerceIn(1, 255)
        if (frameCount > 1) tip = TipCache.get(s.size, effective, pencil, rnd.nextInt(frameCount))
        target.forTilesIn(ir) { i ->
            val b = buf.getOrPut(i) { Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8) }
            canvas.setBitmap(b)
            canvas.save()
            canvas.translate(-target.tileX(i).toFloat(), -target.tileY(i).toFloat())
            if (turned) { canvas.rotate(angle, cx, cy); if (mirrored) canvas.scale(-1f, 1f, cx, cy) }
            canvas.drawBitmap(tip, tipRect, dst, dabPaint)
            canvas.restore()
            dirty.add(i)
        }
        canvas.setBitmap(null)
    }

    // ------------------------------------------------------------ effect tools

    private val effectTiles = HashMap<Int, Bitmap>()
    private val effectScratch by lazy { Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888) }
    private val effectPaint by lazy {
        Paint().apply {
            alpha = (s.opacity * 255).roundToInt()
            if (lockAlpha) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
    }

    /** Original (pre-stroke) pixels of an arbitrary doc region; outside the image is transparent. */
    private fun region(x: Int, y: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        if (target.fill != 0) out.fill(target.fill)
        val r = Rect(x, y, x + w, y + h)
        target.forTilesIn(r) { i ->
            val t = session.original(i) ?: return@forTilesIn
            val tx = target.tileX(i); val ty = target.tileY(i)
            val l = max(x, tx); val tp = max(y, ty)
            val rr = min(x + w, tx + TILE); val b = min(y + h, ty + TILE)
            if (l >= rr || tp >= b) return@forTilesIn
            t.getPixels(out, (tp - y) * w + (l - x), w, l - tx, tp - ty, rr - l, b - tp)
        }
        return out
    }

    private fun crop(px: IntArray, w: Int, pad: Int): IntArray {
        val out = IntArray(TILE * TILE)
        for (y in 0 until TILE) System.arraycopy(px, (y + pad) * w + pad, out, y * TILE, TILE)
        return out
    }

    private fun effectTile(i: Int): Bitmap = effectTiles.getOrPut(i) {
        val tx = target.tileX(i); val ty = target.tileY(i)
        val px: IntArray = when (tool) {
            PaintTool.CLONE -> perspective?.let { perspectiveTile(it, tx, ty) } ?: run { val o = cloneOffset!!; region(tx + o.x, ty + o.y, TILE, TILE) }
            PaintTool.HEAL -> {
                // Frequency-separation heal: source detail on top of destination's low frequencies.
                val o = cloneOffset!!
                val pad = 12; val w = TILE + 2 * pad
                val src = region(tx + o.x - pad, ty + o.y - pad, w, w)
                val dst = region(tx - pad, ty - pad, w, w)
                val bs = blurArgb(src, w, w, pad); val bd = blurArgb(dst, w, w, pad)
                val out = IntArray(w * w)
                for (k in out.indices) {
                    val c = src[k]
                    fun ch(sh: Int) = (((c shr sh) and 0xFF) + ((bd[k] shr sh) and 0xFF) - ((bs[k] shr sh) and 0xFF)).coerceIn(0, 255)
                    out[k] = (maxOf(c ushr 24, dst[k] ushr 24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
                crop(out, w, pad)
            }
            PaintTool.DODGE, PaintTool.BURN -> {
                val e = 1f + s.rate.coerceIn(0f, 1f) * 1.5f
                val g = if (tool == PaintTool.DODGE) 1f / e else e
                val lut = IntArray(256) { (255 * Math.pow(it / 255.0, g.toDouble())).roundToInt().coerceIn(0, 255) }
                region(tx, ty, TILE, TILE).also { a ->
                    for (k in a.indices) { val c = a[k]; a[k] = (c and 0xFF000000.toInt()) or (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF] }
                }
            }
            PaintTool.BLUR -> {
                val pad = max(1, (s.rate * 12).roundToInt()); val w = TILE + 2 * pad
                crop(blurArgb(region(tx - pad, ty - pad, w, w), w, w, pad), w, pad)
            }
            PaintTool.SHARPEN -> {
                val pad = 3; val w = TILE + 2 * pad
                crop(UnsharpMaskFilter(2f, s.rate * 2.5f).apply(region(tx - pad, ty - pad, w, w), w, w), w, pad)
            }
            else -> IntArray(TILE * TILE)
        }
        Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, TILE, 0, 0, TILE, TILE) }
    }

    /** The pre-stroke layer, for perspective clone's arbitrary source lookups. */
    private val original by lazy { Bitmap.createBitmap(region(0, 0, target.width, target.height), target.width, target.height, Bitmap.Config.ARGB_8888) }

    private fun perspectiveTile(m: android.graphics.Matrix, tx: Int, ty: Int): IntArray {
        // Destination p shows source m(p): draw the original through m⁻¹.
        val inv = android.graphics.Matrix(); if (!m.invert(inv)) return IntArray(TILE * TILE)
        val out = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-tx.toFloat(), -ty.toFloat()); c.concat(inv)
        c.drawBitmap(original, 0f, 0f, Paint().apply { isFilterBitmap = true })
        return IntArray(TILE * TILE).also { out.getPixels(it, 0, TILE, 0, 0, TILE, TILE) }
    }

    private fun recompositeEffect(i: Int) {
        val tile = session.writable(i)
        val orig = session.original(i)
        val sc = effectScratch
        val c2 = Canvas(sc)
        sc.eraseColor(0)
        c2.drawBitmap(effectTile(i), 0f, 0f, null)
        MaskOps.apply(c2, buf[i]!!, 0f, 0f)
        if (selection != null) MaskOps.apply(c2, selection.mask, -target.tileX(i).toFloat(), -target.tileY(i).toFloat())
        LinearTile.composite(tile, orig, target.fill) { c -> c.drawBitmap(sc, 0f, 0f, effectPaint) }
        target.touched(i)
    }

    private fun recomposite(i: Int) {
        if (tool.isEffect) { recompositeEffect(i); return }
        val tile = session.writable(i)
        val orig = session.original(i)
        var src = buf[i]!!
        if (selection != null) {
            val sc = scratch!!
            val c2 = Canvas(sc)
            sc.eraseColor(0)
            c2.drawBitmap(src, 0f, 0f, null)
            MaskOps.apply(c2, selection.mask, -target.tileX(i).toFloat(), -target.tileY(i).toFloat())
            src = sc
        }
        val stroke = src
        LinearTile.composite(tile, orig, target.fill) { c -> c.drawBitmap(stroke, 0f, 0f, compPaint) }
        target.touched(i)
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
        Canvas(mixed).drawBitmap(tmp, 0f, 0f, Paint().apply { this.alpha = ((1f - s.rate) * 255).roundToInt() })
        smudgeBuf = mixed
        // Shape by the tip and apply.
        val stamp = mixed.copy(Bitmap.Config.ARGB_8888, true)
        val sc = Canvas(stamp)
        MaskOps.apply(sc, tip, RectF(dst.left - ir.left, dst.top - ir.top, dst.right - ir.left, dst.bottom - ir.top))
        if (selection != null) MaskOps.apply(sc, selection.mask, -ir.left.toFloat(), -ir.top.toFloat())
        val p = Paint().apply { this.alpha = (alpha * s.opacity * 255).roundToInt() }
        if (lockAlpha) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        target.forTilesIn(ir) { i ->
            val t = session.writable(i)
            canvas.setBitmap(t)
            canvas.drawBitmap(stamp, (ir.left - target.tileX(i)).toFloat(), (ir.top - target.tileY(i)).toFloat(), p)
            target.touched(i)
        }
        canvas.setBitmap(null)
    }

    fun finish(): Map<Int, Pair<Bitmap?, Bitmap?>> = session.finish()
    fun cancel() = session.cancel()

    companion object {
        fun isTap(points: List<StrokePoint>) = points.size < 2 || points.all { abs(it.x - points[0].x) < 1 && abs(it.y - points[0].y) < 1 }
    }
}

/**
 * Blends paint onto a tile in linear light (GIMP 2.10's default): the original tile and the new
 * paint are combined in a half-float linear buffer, then written back as 8-bit sRGB. Starting
 * from the untouched original each time means no precision is lost over a stroke.
 * Masks (non-zero fill) hold coverage values, not colors, so they are blended as-is.
 */
object LinearTile {
    private val lin = ThreadLocal.withInitial {
        Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGBA_F16, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB))
    }
    private val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val tileCanvas = ThreadLocal.withInitial { Canvas() }

    fun composite(tile: Bitmap, orig: Bitmap?, fill: Int, draw: (Canvas) -> Unit) {
        // Unbinding the canvas afterwards (setBitmap(null)) is what bumps the bitmap's generation
        // ID; the GPU renderer relies on it to re-upload changed tiles.
        val c = tileCanvas.get()!!
        c.setBitmap(tile)
        try {
            if (fill != 0) {
                tile.eraseColor(fill)
                if (orig != null) c.drawBitmap(orig, 0f, 0f, null)
                draw(c)
                return
            }
            val l = lin.get()!!
            l.eraseColor(0)
            val lc = Canvas(l)
            if (orig != null) lc.drawBitmap(orig, 0f, 0f, null)
            draw(lc)
            c.drawBitmap(l, 0f, 0f, copy)
        } finally { c.setBitmap(null) }
    }
}
