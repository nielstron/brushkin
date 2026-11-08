package io.github.nielstron.brushkin.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Floating
import io.github.nielstron.brushkin.engine.StrokePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The painting surface. Renders the engine's current snapshot through the hardware canvas (each
 * tile is a texture that is only re-uploaded when it changes) and turns touch/stylus input into
 * tool actions. Never blocks: all document work is posted to the engine thread.
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, private val vm: EditorViewModel) : View(context) {
    private val engine = vm.engine
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** doc → view */
    val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var fittedFor = 0L
    var flipped = false; private set
    var onViewChanged: (() -> Unit)? = null
    /** Unobscured editor area, measured by the Compose controls in view coordinates. */
    var contentBounds: RectF? = null
        set(value) {
            if (field == value) return
            field = value
            fit()
        }
    var adSpaceDp = 0
        set(value) {
            if (field == value) return
            field = value
            fit()
        }

    private val checker: Paint = Paint().apply {
        val s = (8 * density).toInt().coerceAtLeast(4)
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF3A3A40.toInt())
        val p = Paint().apply { color = 0xFF2E2E34.toInt() }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val shadow = Paint().apply { color = 0x66000000; }
    private val antsBlack = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 0f }
    private val antsWhite = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 0f }
    private val guide = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1.5f * density; isAntiAlias = true }
    private val guideDark = Paint().apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeWidth = 3f * density; isAntiAlias = true }
    private val handleFill = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val marker = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val markerRing = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density; isAntiAlias = true }
    private val accent = Paint().apply { color = 0xFFFFB547.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density; isAntiAlias = true }
    private val dim = Paint().apply { color = 0x99000000.toInt() }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        engine.onInvalidate = { postInvalidateOnAnimation() }
        isFocusable = true
    }

    // ------------------------------------------------------------------ view transform

    val scale: Float get() { val v = FloatArray(9); viewMatrix.getValues(v); return hypot(v[0], v[3]) }
    val viewRotation: Float get() { val v = FloatArray(9); viewMatrix.getValues(v); return Math.toDegrees(atan2(v[3].toDouble(), v[0].toDouble())).toFloat() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) fit()
    }

    fun fit() {
        val s = engine.stack
        if (width == 0 || s.width <= 1) return
        val bounds = contentBounds ?: return // The editor supplies its bounds after layout.
        val avW = bounds.width(); val avH = bounds.height()
        if (avW <= 0 || avH <= 0) return
        val sc = min(avW / s.width, avH / s.height)
        viewMatrix.reset()
        viewMatrix.postScale(sc, sc)
        viewMatrix.postTranslate(bounds.left + (avW - s.width * sc) / 2f, bounds.top + (avH - s.height * sc) / 2f)
        flipped = false
        changed()
    }

    /** Where the view was on a document, for coming back to it. */
    class SavedView(val matrix: Matrix, val flipped: Boolean, val width: Int, val height: Int)

    fun saveView() = SavedView(Matrix(viewMatrix), flipped, width, height)

    /** Returns to [saved] (fits the image when there's none or the screen has changed since). */
    fun restoreView(saved: SavedView?) {
        val s = engine.stack
        fittedFor = (s.width.toLong() shl 32) or s.height.toLong()
        if (saved == null || saved.width != width || saved.height != height) { fit(); return }
        viewMatrix.set(saved.matrix)
        flipped = saved.flipped
        changed()
    }

    fun zoomTo(z: Float) {
        val cx = width / 2f; val cy = height / 2f
        val f = z / scale
        viewMatrix.postScale(f, f, cx, cy); changed()
    }

    fun resetRotation() {
        val r = viewRotation
        viewMatrix.postRotate(-r, width / 2f, height / 2f); changed()
    }

    /** Moves the view by ([dx], [dy]) screen pixels. */
    fun panBy(dx: Float, dy: Float) { viewMatrix.postTranslate(dx, dy); changed() }

    fun flipView() {
        viewMatrix.postScale(-1f, 1f, width / 2f, height / 2f); flipped = !flipped; changed()
    }

    private fun changed() { viewMatrix.invert(inverse); invalidate(); onViewChanged?.invoke() }

    private fun toDoc(x: Float, y: Float): PointF {
        val p = floatArrayOf(x, y); inverse.mapPoints(p); return PointF(p[0], p[1])
    }

    private fun toView(x: Float, y: Float): PointF {
        val p = floatArrayOf(x, y); viewMatrix.mapPoints(p); return PointF(p[0], p[1])
    }

    // ------------------------------------------------------------------ drawing

    private val docRect = RectF()
    private val visible = RectF()
    private var cursor: PointF? = null

    override fun onDraw(canvas: Canvas) {
        val s = engine.stack
        val key = (s.width.toLong() shl 32) or s.height.toLong()
        if (key != fittedFor && width > 0 && s.width > 1) { fittedFor = key; fit() }
        val g = gl
        if (g != null) {
            // The GL view underneath renders the document in linear light; this view only adds overlays.
            loupeFrame = null
            drawAids(canvas, s)
            s.selection?.let { sel -> drawAnts(canvas, sel) }
            drawOverlays(canvas)
            drawLoupe(canvas, s)
            g.renderer.frame = Frame(Matrix(viewMatrix), scale < 2f, loupeFrame)
            g.view.requestRender()
            return
        }
        canvas.drawColor(0xFF111113.toInt())
        docRect.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)

        canvas.save()
        canvas.concat(viewMatrix)
        val sc = scale
        // Drop shadow + transparency checkerboard.
        canvas.drawRect(docRect.left + 4 / sc, docRect.top + 6 / sc, docRect.right + 4 / sc, docRect.bottom + 6 / sc, shadow)
        checker.shader.setLocalMatrix(Matrix().apply { setScale(1 / sc, 1 / sc) })
        canvas.drawRect(docRect, checker)
        canvas.save()
        canvas.clipRect(docRect)
        Compositor.draw(canvas, s, visible, filter = sc < 2f, preview = engine.preview, floating = engine.floating, pixelSize = 1f / sc, hiddenId = engine.hiddenLayerId)
        canvas.restore()

        canvas.restore()

        drawQuickMask(canvas, s)
        drawAids(canvas, s)
        // Selection outline in screen space so it stays equally visible at any zoom.
        s.selection?.let { sel -> drawAnts(canvas, sel) }

        drawOverlays(canvas)
        drawLoupe(canvas, s)
    }

    // ------------------------------------------------------------------ grid, guides, rulers, snapping

    private val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0f; color = 0x55808080 }
    private val guidePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density; color = 0xFF29B6F6.toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f) }
    private val guideActive = Paint(guidePaint).apply { color = 0xFFFFB547.toInt(); pathEffect = null }
    private val rulerBg = Paint().apply { color = 0xF01C1C21.toInt() }
    private val rulerTick = Paint().apply { color = 0xFF9A9AA6.toInt(); strokeWidth = 1f }
    private val rulerText = Paint().apply { color = 0xFF9A9AA6.toInt(); textSize = 9f * resources.displayMetrics.scaledDensity; isAntiAlias = true }
    /** Guide being dragged: index in the stack (-1: new from a ruler) and its current position. */
    private var guideDrag: Pair<Int, io.github.nielstron.brushkin.engine.Guide>? = null

    private val rulerSize get() = 18 * density
    private val rulerTop get() = (androidx.core.view.ViewCompat.getRootWindowInsets(this)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0) + (76 + adSpaceDp) * density

    private fun inRuler(x: Float, y: Float) = vm.showRulers && ((y >= rulerTop && y < rulerTop + rulerSize) || (x < rulerSize && y >= rulerTop))

    /** Touches on a ruler pull out a new guide; with the Move tool, existing guides can be dragged. */
    private fun startGuide(e: MotionEvent): Boolean {
        val s = engine.stack
        if (vm.showRulers && e.y >= rulerTop && e.y < rulerTop + rulerSize) { guideDrag = -1 to io.github.nielstron.brushkin.engine.Guide(false, toDoc(e.x, e.y).y.roundToInt()); mode = Mode.GUIDE; return true }
        if (vm.showRulers && e.x < rulerSize && e.y >= rulerTop) { guideDrag = -1 to io.github.nielstron.brushkin.engine.Guide(true, toDoc(e.x, e.y).x.roundToInt()); mode = Mode.GUIDE; return true }
        if (vm.tool == Tool.MOVE && s.guides.isNotEmpty()) {
            val d = toDoc(e.x, e.y); val th = 14 * density / scale
            val i = s.guides.indexOfFirst { abs((if (it.vertical) d.x else d.y) - it.pos) < th }
            if (i >= 0) { guideDrag = i to s.guides[i]; mode = Mode.GUIDE; return true }
        }
        return false
    }

    /** Tools whose points snap to the grid and guides. */
    private val snaps get() = vm.tool == Tool.RECT_SELECT || vm.tool == Tool.ELLIPSE_SELECT || vm.tool == Tool.CROP || vm.tool == Tool.GRADIENT ||
        vm.tool == Tool.PATHS || vm.tool == Tool.SCISSORS || vm.tool == Tool.TEXT || vm.tool == Tool.FREE_SELECT || (vm.strokeTool.paint != null && vm.lineMode)

    private fun docPoint(x: Float, y: Float): PointF { val d = toDoc(x, y); return if (snaps) snap(d) else d }

    private fun snapTargets(vertical: Boolean, v: Float, best: Float): Pair<Float, Float>? {
        var out: Float? = null; var b = best
        if (vm.snapGuides) for (g in engine.stack.guides) if (g.vertical == vertical) { val dd = abs(v - g.pos); if (dd < b) { b = dd; out = g.pos.toFloat() } }
        if (vm.snapGrid && vm.gridSize >= 1f) { val gv = Math.round(v / vm.gridSize) * vm.gridSize; val dd = abs(v - gv); if (dd < b) { b = dd; out = gv } }
        return out?.let { it to b }
    }

    private fun snap(p: PointF): PointF {
        val th = 10 * density / scale
        return PointF(snapTargets(true, p.x, th)?.first ?: p.x, snapTargets(false, p.y, th)?.first ?: p.y)
    }

    /** Move-tool offset with the layer's edges or center snapped to guides / grid. */
    private fun moveDelta(f: io.github.nielstron.brushkin.engine.Floating, d: PointF): PointF {
        var dx = d.x - downDoc.x; var dy = d.y - downDoc.y
        if (!vm.snapGuides && !vm.snapGrid) return PointF(dx, dy)
        val r = RectF(0f, 0f, f.bitmap.width.toFloat(), f.bitmap.height.toFloat()); f.initial.mapRect(r); r.offset(dx, dy)
        val th = 10 * density / scale
        var bx: Pair<Float, Float>? = null
        for (x in floatArrayOf(r.left, r.right, r.centerX())) snapTargets(true, x, bx?.second ?: th)?.let { (t, dist) -> bx = (t - x) to dist }
        var by: Pair<Float, Float>? = null
        for (y in floatArrayOf(r.top, r.bottom, r.centerY())) snapTargets(false, y, by?.second ?: th)?.let { (t, dist) -> by = (t - y) to dist }
        bx?.let { dx += it.first }; by?.let { dy += it.first }
        return PointF(dx, dy)
    }

    private fun drawAids(canvas: Canvas, s: io.github.nielstron.brushkin.engine.Stack) {
        val sc = scale
        if (vm.showGrid && vm.gridSize * sc >= 6f) {
            canvas.save(); canvas.concat(viewMatrix)
            canvas.clipRect(0f, 0f, s.width.toFloat(), s.height.toFloat())
            val vis = RectF(0f, 0f, width.toFloat(), height.toFloat()); inverse.mapRect(vis)
            val gs = vm.gridSize
            var x = maxOf(0f, (vis.left / gs).toInt() * gs)
            while (x <= minOf(s.width.toFloat(), vis.right)) { canvas.drawLine(x, 0f, x, s.height.toFloat(), gridPaint); x += gs }
            var y = maxOf(0f, (vis.top / gs).toInt() * gs)
            while (y <= minOf(s.height.toFloat(), vis.bottom)) { canvas.drawLine(0f, y, s.width.toFloat(), y, gridPaint); y += gs }
            canvas.restore()
        }
        val big = 4f * maxOf(s.width, s.height)
        fun guideLine(g: io.github.nielstron.brushkin.engine.Guide, p: Paint) {
            val pts = if (g.vertical) floatArrayOf(g.pos.toFloat(), -big, g.pos.toFloat(), big) else floatArrayOf(-big, g.pos.toFloat(), big, g.pos.toFloat())
            viewMatrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], p)
        }
        val drag = guideDrag
        canvas.save()
        // Guides span the canvas area, not the toolbars above it.
        canvas.clipRect(if (vm.showRulers) rulerSize else 0f, if (vm.showRulers) rulerTop + rulerSize else rulerTop, width.toFloat(), height.toFloat())
        for ((i, g) in s.guides.withIndex()) if (drag?.first != i) guideLine(g, guidePaint)
        drag?.let { guideLine(it.second, guideActive) }
        canvas.restore()
        if (vm.showRulers) drawRulers(canvas)
        else if (exclusionFor != -1f) { exclusionFor = -1f; systemGestureExclusionRects = emptyList() }
    }

    private var exclusionFor = -1f

    private fun drawRulers(canvas: Canvas) {
        val top = rulerTop; val rs = rulerSize
        // The left ruler sits in the back-gesture zone: claim part of it (Android grants up to
        // 200dp per edge) so guides can be pulled out there.
        if (exclusionFor != top + height) {
            exclusionFor = top + height
            val h = 200 * density; val cy = (top + height) / 2f
            systemGestureExclusionRects = listOf(android.graphics.Rect(0, (cy - h / 2).toInt(), (rs * 2).toInt(), (cy + h / 2).toInt()))
        }
        canvas.drawRect(0f, top, width.toFloat(), top + rs, rulerBg)
        canvas.drawRect(0f, top, rs, height.toFloat(), rulerBg)
        // Tick spacing: the 1-2-5 step that is at least ~56 px on screen.
        var step = 1f
        val steps = floatArrayOf(1f, 2f, 5f)
        outer@ for (k in 0..6) for (m in steps) { step = m * 10f.pow(k); if (step * scale >= 56 * density) break@outer }
        val sub = step / 5f
        val docAtTop = toDoc(0f, top + rs)
        // Horizontal ruler (x).
        val x0 = toDoc(rs, top).x; val x1 = toDoc(width.toFloat(), top).x
        var v = Math.floor((minOf(x0, x1) / sub).toDouble()).toFloat() * sub
        while (v <= maxOf(x0, x1)) {
            val sx = toView(v, docAtTop.y).x
            if (sx >= rs) {
                val major = abs(v / step - Math.round(v / step)) < 1e-3f
                canvas.drawLine(sx, top + rs, sx, top + rs - (if (major) rs * 0.7f else rs * 0.25f), rulerTick)
                if (major) canvas.drawText(v.roundToInt().toString(), sx + 2 * density, top + rulerText.textSize, rulerText)
            }
            v += sub
        }
        // Vertical ruler (y).
        val y0 = toDoc(rs, top + rs).y; val y1 = toDoc(rs, height.toFloat()).y
        v = Math.floor((minOf(y0, y1) / sub).toDouble()).toFloat() * sub
        while (v <= maxOf(y0, y1)) {
            val sy = toView(docAtTop.x, v).y
            if (sy >= top + rs) {
                val major = abs(v / step - Math.round(v / step)) < 1e-3f
                canvas.drawLine(rs, sy, rs - (if (major) rs * 0.7f else rs * 0.25f), sy, rulerTick)
                if (major) { canvas.save(); canvas.rotate(-90f, 2 * density + rulerText.textSize * 0.8f, sy - 2 * density); canvas.drawText(v.roundToInt().toString(), 2 * density + rulerText.textSize * 0.8f, sy - 2 * density, rulerText); canvas.restore() }
            }
            v += sub
        }
        // Where the finger is, marked on both rulers.
        lastTouch?.let { t -> canvas.drawLine(t.x, top, t.x, top + rs, guideActive); canvas.drawLine(0f, t.y, rs, t.y, guideActive) }
    }

    private fun drawSymmetry(canvas: Canvas, sym: io.github.nielstron.brushkin.engine.Symmetry) {
        val big = 4f * maxOf(engine.stack.width, engine.stack.height)
        val axes = when (sym.kind) {
            io.github.nielstron.brushkin.engine.Symmetry.Kind.MIRROR_H -> listOf(90f)
            io.github.nielstron.brushkin.engine.Symmetry.Kind.MIRROR_V -> listOf(0f)
            io.github.nielstron.brushkin.engine.Symmetry.Kind.MIRROR_BOTH -> listOf(0f, 90f)
            io.github.nielstron.brushkin.engine.Symmetry.Kind.MANDALA -> List(sym.segments) { 360f * it / sym.segments - 90f }
        }
        canvas.save()
        val docR = RectF(0f, 0f, engine.stack.width.toFloat(), engine.stack.height.toFloat())
        canvas.clipPath(Path().apply { addRect(docR, Path.Direction.CW); transform(viewMatrix) })
        for (a in axes) {
            val rad = Math.toRadians(a.toDouble())
            val full = sym.kind != io.github.nielstron.brushkin.engine.Symmetry.Kind.MANDALA
            val pts = floatArrayOf(if (full) sym.cx - big * kotlin.math.cos(rad).toFloat() else sym.cx, if (full) sym.cy - big * kotlin.math.sin(rad).toFloat() else sym.cy,
                sym.cx + big * kotlin.math.cos(rad).toFloat(), sym.cy + big * kotlin.math.sin(rad).toFloat())
            viewMatrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], guideDark); canvas.drawLine(pts[0], pts[1], pts[2], pts[3], guide)
        }
        canvas.restore()
    }

    /** The perspective-clone plane with a 4×4 grid, so its perspective is visible. */
    private fun drawPlane(canvas: Canvas) {
        val p = vm.perspPlane ?: return
        val h = vm.planeMatrix() ?: return
        val inv = Matrix(); if (!h.invert(inv)) return
        val path = Path()
        for (k in 0..4) {
            val t = k * 250f
            val a = floatArrayOf(t, 0f, t, 1000f, 0f, t, 1000f, t); inv.mapPoints(a); viewMatrix.mapPoints(a)
            path.moveTo(a[0], a[1]); path.lineTo(a[2], a[3]); path.moveTo(a[4], a[5]); path.lineTo(a[6], a[7])
        }
        canvas.drawPath(path, guideDark); canvas.drawPath(path, if (vm.perspEditing) accent else guide)
        if (vm.perspEditing) for (k in 0 until 4) {
            val v = toView(p[k * 2], p[k * 2 + 1])
            canvas.drawCircle(v.x, v.y, 10 * density, handleFill); canvas.drawCircle(v.x, v.y, 10 * density, accent)
        }
    }

    // ------------------------------------------------------------------ scissors

    private fun drawScissors(canvas: Canvas) {
        val anchors = vm.scissorsAnchors
        if (anchors.isEmpty()) return
        val p = Path()
        for ((i, a) in anchors.withIndex()) {
            if (i == 0) { p.moveTo(a.x, a.y); continue }
            val seg = vm.scissorsSegs.getOrNull(i - 1)
            if (seg == null) p.lineTo(a.x, a.y) else { var k = 0; while (k < seg.size) { p.lineTo(seg[k], seg[k + 1]); k += 2 } }
        }
        p.transform(viewMatrix)
        canvas.drawPath(p, guideDark); canvas.drawPath(p, guide)
        for ((i, a) in anchors.withIndex()) {
            val v = toView(a.x, a.y)
            canvas.drawCircle(v.x, v.y, (if (i == 0) 9 else 6) * density, guideDark)
            canvas.drawCircle(v.x, v.y, (if (i == 0) 9 else 6) * density, guide)
        }
    }

    // ------------------------------------------------------------------ quick mask

    /** Gray mask tile → red tint, half opaque where unselected (like GIMP's quick mask). */
    private val quickMaskPaint = Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            -0.5f, 0f, 0f, 0f, 127.5f,
        ))
    }
    private val quickMaskFill = Paint()

    private fun drawQuickMask(canvas: Canvas, s: io.github.nielstron.brushkin.engine.Stack) {
        val qm = s.quickMask ?: return
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(0f, 0f, s.width.toFloat(), s.height.toFloat())
        quickMaskFill.color = Color.argb(127 - Color.red(qm.fill) / 2, 255, 0, 0)
        val t = io.github.nielstron.brushkin.engine.TILE.toFloat()
        for (i in qm.tiles.indices) {
            val x = qm.tileX(i).toFloat(); val y = qm.tileY(i).toFloat()
            val tile = qm.tiles[i]
            if (tile == null) canvas.drawRect(x, y, x + t, y + t, quickMaskFill)
            else canvas.drawBitmap(tile, x, y, quickMaskPaint)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ loupe

    private val loupeBorder = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E2E35.toInt(); strokeWidth = 2 * density; isAntiAlias = true }
    private val loupeBg = Paint().apply { color = 0xFF111113.toInt() }
    private val hairWhite = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 0f }
    private val hairBlack = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 0f }
    private val loupeVisible = RectF()
    private var loupeFrame: Pair<RectF, Matrix>? = null

    /** GL renderer underneath (null: fall back to drawing the document here). */
    class Gl(val view: android.opengl.GLSurfaceView, val renderer: GlRenderer)
    var gl: Gl? = null

    /** Magnified view of the area under the finger, shown in the top corner away from it. */
    private fun drawLoupe(canvas: Canvas, s: io.github.nielstron.brushkin.engine.Stack) {
        val active = mode == Mode.STROKE || mode == Mode.SHAPE || mode == Mode.PICK || mode == Mode.CROP ||
            mode == Mode.PATH_POINT || mode == Mode.PATH_HANDLE || mode == Mode.PATH_NEW ||
            mode == Mode.FLOAT_MOVE || mode == Mode.FLOAT_SCALE || mode == Mode.FLOAT_ROTATE
        val touch = lastTouch ?: return
        if (!active) return
        val size = 132 * density
        val margin = 12 * density
        val top = (androidx.core.view.ViewCompat.getRootWindowInsets(this)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0) + 128 * density
        val left = if (touch.x < width / 2f) width - size - margin else margin
        val box = RectF(left, top, left + size, top + size)
        // Move out of the way if the finger is right under the loupe.
        if (box.contains(touch.x, touch.y)) box.offset(0f, size + margin)
        val k = (3f).coerceAtMost(48f / scale.coerceAtLeast(1e-3f)).coerceAtLeast(1f)
        val clip = Path().apply { addRoundRect(box, 16 * density, 16 * density, Path.Direction.CW) }

        val total = scale * k
        if (gl != null) {
            loupeFrame = box to Matrix(viewMatrix).apply { postTranslate(-touch.x, -touch.y); postScale(k, k); postTranslate(box.centerX(), box.centerY()) }
            canvas.save(); canvas.clipPath(clip)
            canvas.translate(box.centerX(), box.centerY()); canvas.scale(k, k); canvas.translate(-touch.x, -touch.y); canvas.concat(viewMatrix)
            s.selection?.let { canvas.drawPath(it.outline, hairBlack) }
            preview?.let { canvas.drawPath(it, hairBlack) }
            vm.cropRect?.let { canvas.drawRect(it, hairWhite) }
            canvas.restore()
        } else {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(box, loupeBg)
        canvas.translate(box.centerX(), box.centerY())
        canvas.scale(k, k)
        canvas.translate(-touch.x, -touch.y)
        canvas.concat(viewMatrix)
        // Doc-space region visible in the loupe.
        val half = size / 2f / total * 1.5f
        val d = toDoc(touch.x, touch.y)
        loupeVisible.set(d.x - half, d.y - half, d.x + half, d.y + half)
        checker.shader.setLocalMatrix(Matrix().apply { setScale(1 / total, 1 / total) })
        canvas.drawRect(docRect, checker)
        canvas.save()
        canvas.clipRect(docRect)
        Compositor.draw(canvas, s, loupeVisible, filter = total < 2f, preview = engine.preview, floating = engine.floating, pixelSize = 1f / total)
        canvas.restore()
        s.selection?.let { canvas.drawPath(it.outline, hairBlack); canvas.drawPath(it.outline, hairWhite) }
        preview?.let { canvas.drawPath(it, hairBlack) }
        vm.cropRect?.let { canvas.drawRect(it, hairWhite) }
        canvas.restore()
        checker.shader.setLocalMatrix(Matrix().apply { setScale(1 / scale, 1 / scale) })
        }

        // Crosshair and brush outline at the center.
        val cx = box.centerX(); val cy = box.centerY()
        canvas.save(); canvas.clipPath(clip)
        val r = if (vm.strokeTool.paint != null && mode == Mode.STROKE) vm.settings.size / 2f * total else 0f
        if (r > 2) { canvas.drawCircle(cx, cy, r, guideDark); canvas.drawCircle(cx, cy, r, guide) }
        val cl = 8 * density
        for (p in listOf(guideDark, guide)) { canvas.drawLine(cx - cl, cy, cx + cl, cy, p); canvas.drawLine(cx, cy - cl, cx, cy + cl, p) }
        if (mode == Mode.PICK) {
            val sw = Paint().apply { color = vm.fg }
            canvas.drawRect(box.left, box.bottom - 22 * density, box.right, box.bottom, sw)
        }
        canvas.restore()
        canvas.drawPath(clip, loupeBorder)
    }

    // Selection outline: a black/white checker shader in screen space. Unlike a dash effect it
    // alternates along outlines made of many tiny segments, in every direction.
    private val antsStripe: BitmapShader = run {
        val n = (5 * density).toInt().coerceAtLeast(3)
        val b = Bitmap.createBitmap(n * 2, n * 2, Bitmap.Config.ARGB_8888)
        for (y in 0 until n * 2) for (x in 0 until n * 2) b.setPixel(x, y, if ((x / n + y / n) % 2 == 0) Color.WHITE else Color.BLACK)
        BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val antsPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; shader = antsStripe; strokeCap = Paint.Cap.SQUARE }
    private var antsPath = Path()
    private var antsFor: Any? = null
    private val antsMatrix = Matrix()

    private fun drawAnts(canvas: Canvas, sel: io.github.nielstron.brushkin.engine.Selection) {
        // Re-map the outline only when the selection or the view changed.
        if (antsFor !== sel || antsMatrix != viewMatrix) {
            antsPath = Path(sel.outline).apply { transform(viewMatrix) }
            antsFor = sel; antsMatrix.set(viewMatrix)
        }
        // Static (not animated): a checker stroke reads clearly on any background without flicker.
        canvas.drawPath(antsPath, antsPaint)
    }

    private fun drawOverlays(canvas: Canvas) {
        // Shape previews are kept in doc coordinates and mapped here.
        preview?.let { p ->
            val vp = Path(p); vp.transform(viewMatrix)
            canvas.drawPath(vp, guideDark); canvas.drawPath(vp, guide)
        }
        vm.cropRect?.let { r ->
            val vr = Path().apply { addRect(r, Path.Direction.CW); transform(viewMatrix) }
            val outside = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW); op(vr, Path.Op.DIFFERENCE) }
            canvas.drawPath(outside, dim)
            canvas.drawPath(vr, accent)
        }
        engine.floating?.let { drawFloatingHandles(canvas, it) }
        if (engine.floating != null || !vm.tool.directTransform) updateGestureExclusion()
        // Move/Rotate while idle: the outline of what moves, and the rotation handle.
        if (vm.tool.directTransform && engine.floating == null) moveQuad()?.let { pts ->
            val path = Path().apply { moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close() }
            canvas.drawPath(path, guideDark); canvas.drawPath(path, accent)
            if (vm.tool == Tool.MOVE) {
                val h = rotateHandle(pts); val tx = (pts[0] + pts[2]) / 2; val ty = (pts[1] + pts[3]) / 2
                canvas.drawLine(tx, ty, h.x, h.y, guideDark); canvas.drawLine(tx, ty, h.x, h.y, accent)
                canvas.drawCircle(h.x, h.y, 9 * density, handleFill); canvas.drawCircle(h.x, h.y, 9 * density, accent)
            }
            if (vm.tool == Tool.SCALE) for (i in 0 until 4) { canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], 7 * density, handleFill); canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], 7 * density, accent) }
        }
        updateGestureExclusion()
        drawPath(canvas)
        if (vm.tool == Tool.SCISSORS) drawScissors(canvas)
        if (vm.tool.picksObjects) for (p in vm.objPoints) {
            // Object taps: green on the object, red for what isn't part of it.
            val v = toView(p.x, p.y)
            marker.color = if (p.positive) 0xFF3DBE5C.toInt() else 0xFFE5484D.toInt()
            canvas.drawCircle(v.x, v.y, 7f * density, marker)
            canvas.drawCircle(v.x, v.y, 7f * density, markerRing)
        }
        if (vm.dofOpen) vm.dofFocus?.let { f ->
            val v = toView(f.x, f.y); val r = 16f * density
            canvas.drawCircle(v.x, v.y, r, guideDark); canvas.drawCircle(v.x, v.y, r, accent)
            canvas.drawLine(v.x - r * 1.6f, v.y, v.x - r * 0.5f, v.y, accent); canvas.drawLine(v.x + r * 0.5f, v.y, v.x + r * 1.6f, v.y, accent)
            canvas.drawLine(v.x, v.y - r * 1.6f, v.x, v.y - r * 0.5f, accent); canvas.drawLine(v.x, v.y + r * 0.5f, v.x, v.y + r * 1.6f, accent)
        }
        // The active layer's boundary when it's smaller than the image (GIMP's yellow dashes).
        engine.info.value.let { i -> i.layers.firstOrNull { it.id == i.activeId }?.bounds }?.let { b ->
            val p = Path().apply { addRect(RectF(b), Path.Direction.CW); transform(viewMatrix) }
            canvas.drawPath(p, layerBoundary)
        }
        vm.symmetry()?.takeIf { vm.strokeTool.paint != null }?.let { drawSymmetry(canvas, it) }
        if (vm.tool == Tool.PERSPECTIVE_CLONE) drawPlane(canvas)
        if (vm.tool == Tool.CLONE || vm.tool == Tool.HEAL || vm.tool == Tool.PERSPECTIVE_CLONE) {
            val src = vm.cloneSource
            val o = vm.cloneOffset.takeIf { vm.tool != Tool.PERSPECTIVE_CLONE }
            val c = cursor
            // While painting, the crosshair follows the sampled spot.
            val p = if (o != null && c != null && mode == Mode.STROKE) { val d = toDoc(c.x, c.y); toView(d.x + o.x, d.y + o.y) } else src?.let { toView(it.x, it.y) }
            if (p != null) {
                val r = 10 * density
                for (paint in listOf(guideDark, guide)) {
                    canvas.drawLine(p.x - r, p.y, p.x + r, p.y, paint); canvas.drawLine(p.x, p.y - r, p.x, p.y + r, paint)
                    canvas.drawCircle(p.x, p.y, r * 0.6f, paint)
                }
            }
        }
        val c = cursor
        if (c != null && vm.strokeTool.paint != null && !vm.lineMode) {
            val r = vm.settings.size / 2f * scale
            if (r > 3) {
                canvas.drawCircle(c.x, c.y, r, guideDark)
                canvas.drawCircle(c.x, c.y, r, guide)
            }
        }
    }

    /** Whether (x, y) lies inside the convex quad [pts] (screen corners in order). */
    private fun insideQuad(pts: FloatArray, x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[(i + 1) % 4 * 2]; val by = pts[(i + 1) % 4 * 2 + 1]
            val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return true
    }

    /**
     * Transform handles near the screen's left or right edge would trigger the system Back gesture:
     * keep small areas around them for the app (Android allows a limited amount per edge).
     */
    private var exclusionKey = 0
    private fun updateGestureExclusion() {
        val pts = when {
            engine.floating != null -> engine.floating!!.corners().also { viewMatrix.mapPoints(it) }
            vm.tool.directTransform -> moveQuad()
            else -> null
        }
        val rects = ArrayList<Rect>()
        if (pts != null) {
            val r = (24 * density).toInt(); val edge = 48 * density
            val handles = (0 until 4).map { PointF(pts[it * 2], pts[it * 2 + 1]) } + rotateHandle(pts)
            for (h in handles) if (h.x < edge || h.x > width - edge) rects.add(Rect(h.x.toInt() - r, h.y.toInt() - r, h.x.toInt() + r, h.y.toInt() + r))
        }
        val key = rects.hashCode()
        if (key != exclusionKey) { exclusionKey = key; systemGestureExclusionRects = rects }
    }

    private fun drawFloatingHandles(canvas: Canvas, f: Floating) {
        val pts = f.corners(); viewMatrix.mapPoints(pts)
        val path = Path().apply { moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close() }
        canvas.drawPath(path, guideDark); canvas.drawPath(path, accent)
        val hr = 7 * density
        if (f.targetLayerId != null) when (vm.tool) {
            Tool.MOVE -> {
                val cx = (pts[0] + pts[4]) / 2; val cy = (pts[1] + pts[5]) / 2; val r = 10 * density
                for (p in listOf(guideDark, accent)) { canvas.drawLine(cx - r, cy, cx + r, cy, p); canvas.drawLine(cx, cy - r, cx, cy + r, p) }
                // Rotation handle above the top edge.
                val h = rotateHandle(pts); val tx = (pts[0] + pts[2]) / 2; val ty = (pts[1] + pts[3]) / 2
                canvas.drawLine(tx, ty, h.x, h.y, guideDark); canvas.drawLine(tx, ty, h.x, h.y, accent)
                canvas.drawCircle(h.x, h.y, hr * 1.3f, handleFill); canvas.drawCircle(h.x, h.y, hr * 1.3f, accent)
                return
            }
            Tool.SCALE, Tool.PERSPECTIVE -> {
                for (i in 0 until 4) { canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, handleFill); canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, accent) }
                return
            }
            Tool.SHEAR, Tool.TRANSFORM_3D -> return
            Tool.HANDLE -> {
                for (hd in vm.handles) { val p = toView(hd.second.x, hd.second.y); canvas.drawCircle(p.x, p.y, hr * 1.2f, accentFill); canvas.drawCircle(p.x, p.y, hr * 1.2f, guideDark) }
                return
            }
            Tool.WARP -> {
                val c = cursor ?: return
                val r = vm.warpRadius * scale
                canvas.drawCircle(c.x, c.y, r, guideDark); canvas.drawCircle(c.x, c.y, r, guide)
                return
            }
            Tool.CAGE -> {
                val cp = engine.cageNow
                if (cp.isNotEmpty()) {
                    val path = Path()
                    cp.forEachIndexed { i, p -> val v = toView(p.x, p.y); if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y) }
                    if (cp.size > 2) path.close()
                    canvas.drawPath(path, guideDark); canvas.drawPath(path, accent)
                    for (p in cp) { val v = toView(p.x, p.y); canvas.drawCircle(v.x, v.y, hr, handleFill); canvas.drawCircle(v.x, v.y, hr, accent) }
                }
                return
            }
            else -> {}
        }
        for (i in 0 until 4) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, handleFill)
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, accent)
        }
        val rh = rotateHandle(pts)
        canvas.drawLine((pts[0] + pts[2]) / 2, (pts[1] + pts[3]) / 2, rh.x, rh.y, accent)
        canvas.drawCircle(rh.x, rh.y, hr, handleFill)
        canvas.drawCircle(rh.x, rh.y, hr, accent)
    }

    private fun rotateHandle(pts: FloatArray): PointF {
        val mx = (pts[0] + pts[2]) / 2; val my = (pts[1] + pts[3]) / 2
        val cx = (pts[0] + pts[4]) / 2; val cy = (pts[1] + pts[5]) / 2
        val dx = mx - cx; val dy = my - cy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val d = 36 * density
        return PointF(mx + dx / len * d, my + dy / len * d)
    }

    // ------------------------------------------------------------------ input

    private enum class Mode { NONE, STROKE, VIEW, PAN, SHAPE, PICK, FLOAT_MOVE, FLOAT_SCALE, FLOAT_ROTATE, CROP, IGNORE, PATH_POINT, PATH_HANDLE, PATH_NEW,
        FLOAT_SHEAR, FLOAT_CORNER, FLOAT_3D, FLOAT_HANDLE, WARP_PUSH, CAGE_DRAG, GUIDE, PLANE_CORNER, FOCUS }

    private var mode = Mode.NONE
    private var downTime = 0L
    private var down = PointF()
    private var downDoc = PointF()
    private var strokePoints = 0
    private var preview: Path? = null
    private var lasso: Path? = null
    private var maxPointers = 0
    private var moved = false
    private var lastLineEnd: PointF? = null
    private val longPress = Runnable { onLongPress() }

    // Multi-touch view gesture state.
    private var gStartMatrix = Matrix()
    private var gStart = Gesture(0f, 0f, 1f, 0f)

    private data class Gesture(val cx: Float, val cy: Float, val dist: Float, val angle: Float)

    private fun gesture(e: MotionEvent): Gesture {
        val n = min(e.pointerCount, 2)
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
        cx /= n; cy /= n
        if (n < 2) return Gesture(cx, cy, 1f, 0f)
        val dx = e.getX(1) - e.getX(0); val dy = e.getY(1) - e.getY(0)
        return Gesture(cx, cy, max(1f, hypot(dx, dy)), Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            cursor = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(e.x, e.y)
            invalidate()
            return true
        }
        return super.onHoverEvent(e)
    }

    private var lastTouch: PointF? = null
    private var effectGesture = false
    private var navigationOnly = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) null else PointF(e.getX(0), e.getY(0))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                effectGesture = vm.dialog?.startsWith("filter:") == true
                navigationOnly = effectGesture && vm.filterPicking == null
                maxPointers = 1; moved = false
                downTime = e.eventTime
                down = PointF(e.x, e.y); downDoc = docPoint(e.x, e.y)
                if (navigationOnly || !startGuide(e)) startSingle(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                // A second finger turns whatever started into a view gesture. Strokes that just
                // started are cancelled so pinching never leaves a dot behind.
                if (mode == Mode.STROKE) {
                    if (e.eventTime - downTime < 250 || strokePoints < 8) engine.strokeCancel() else engine.strokeEnd()
                }
                preview = null; lasso = null; cursor = null
                if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS || mode != Mode.STROKE) {
                    mode = Mode.VIEW
                    gStartMatrix.set(viewMatrix); gStart = gesture(e)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - down.x, e.y - down.y) > slop) { moved = true; removeCallbacks(longPress) }
                when (mode) {
                    Mode.PAN -> {
                        viewMatrix.set(gStartMatrix)
                        viewMatrix.postTranslate(e.x - down.x, e.y - down.y)
                        changed()
                    }
                    Mode.VIEW -> if (e.pointerCount >= 2) {
                        val g = gesture(e)
                        viewMatrix.set(gStartMatrix)
                        viewMatrix.postTranslate(g.cx - gStart.cx, g.cy - gStart.cy)
                        val f = g.dist / gStart.dist
                        val newScale = (scaleOf(gStartMatrix) * f).coerceIn(0.02f, 64f)
                        val ff = newScale / scaleOf(gStartMatrix)
                        viewMatrix.postScale(ff, ff, g.cx, g.cy)
                        var da = g.angle - gStart.angle
                        // Snap rotation near 0° so it's easy to get back to straight.
                        val total = (rotationOf(gStartMatrix) + da)
                        val snapped = Math.round(total / 90f) * 90f
                        if (abs(total - snapped) < 6) da += snapped - total
                        viewMatrix.postRotate(da, g.cx, g.cy)
                        changed()
                    }
                    else -> moveSingle(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.VIEW && navigationOnly && e.pointerCount == 2) {
                    // Continue panning with the remaining finger after a pinch.
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    down = PointF(e.getX(remaining), e.getY(remaining))
                    gStartMatrix.set(viewMatrix)
                    mode = Mode.PAN
                } else if (mode == Mode.VIEW && e.pointerCount - 1 >= 2) {
                    // Re-anchor on remaining fingers.
                    gStartMatrix.set(viewMatrix)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (mode == Mode.VIEW) {
                    val quick = !effectGesture && e.eventTime - downTime < 300
                    if (quick && maxPointers == 2 && !moved) engine.undo()
                    if (quick && maxPointers == 3 && !moved) engine.redo()
                } else endSingle(e)
                mode = Mode.NONE
                if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) cursor = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == Mode.STROKE) engine.strokeCancel()
                mode = Mode.NONE; preview = null; lasso = null; cursor = null
                invalidate()
            }
        }
        return true
    }

    private fun scaleOf(m: Matrix): Float { val v = FloatArray(9); m.getValues(v); return hypot(v[0], v[3]) }
    private fun rotationOf(m: Matrix): Float { val v = FloatArray(9); m.getValues(v); return Math.toDegrees(atan2(v[3].toDouble(), v[0].toDouble())).toFloat() }

    private fun pressure(e: MotionEvent, i: Int = -1): Float {
        val isStylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (!isStylus) return 1f
        return if (i < 0) e.pressure else e.getHistoricalPressure(i)
    }

    private fun axis(e: MotionEvent, a: Int, i: Int): Float =
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) 0f else if (i < 0) e.getAxisValue(a) else e.getHistoricalAxisValue(a, i)

    /** Stylus direction in doc space (the view may be rotated). */
    private fun orientation(e: MotionEvent, i: Int): Float {
        // Android: 0 = pointing up, clockwise positive; convert to an angle from +x.
        val o = axis(e, MotionEvent.AXIS_ORIENTATION, i)
        return o - Math.PI.toFloat() / 2 - Math.toRadians(viewRotation.toDouble()).toFloat()
    }

    private fun startSingle(e: MotionEvent) {
        if (navigationOnly) {
            mode = Mode.PAN
            gStartMatrix.set(viewMatrix)
            return
        }
        if (vm.pipette) { mode = Mode.PICK; pick(e.x, e.y); return }
        // A filter panel is picking a color from the image.
        vm.filterPicking?.let { cb ->
            mode = Mode.IGNORE
            engine.pickColor(downDoc.x.toInt(), downDoc.y.toInt(), merged = true) { c -> post { cb(c) } }
            return
        }
        // Depth of field: touching the image moves the focus point.
        if (vm.dofOpen) { mode = Mode.FOCUS; vm.dofFocus = PointF(downDoc.x, downDoc.y); invalidate(); return }
        val f = engine.floating
        val tool = vm.tool
        if (f != null) { startFloating(e, f); return }
        // Direct transforms: each drag applies when the finger lifts (undo takes it back), redrawn
        // from the original pixels so a series of drags doesn't blur. Move/Rotate: inside moves,
        // outside or the handle rotates. Scale: corners scale, inside moves.
        if (tool.directTransform) {
            val q = moveQuad()
            q?.let { fPivotDoc = toDoc((it[0] + it[4]) / 2, (it[1] + it[5]) / 2) }
            rotating = false; moving = false; direct = true
            mode = when (tool) {
                Tool.MOVE -> {
                    rotating = q != null && (rotateHandle(q).let { h -> hypot(e.x - h.x, e.y - h.y) < 36 * density } || !insideQuad(q, e.x, e.y))
                    moving = !rotating
                    if (rotating) Mode.FLOAT_ROTATE else Mode.FLOAT_MOVE
                }
                Tool.SCALE -> {
                    val i = if (q == null) -1 else (0 until 4).firstOrNull { hypot(e.x - q[it * 2], e.y - q[it * 2 + 1]) < 32 * density } ?: -1
                    if (i >= 0) {
                        val opp = (i + 2) % 4
                        fPivotDoc = toDoc(q!![opp * 2], q[opp * 2 + 1]); fAnchorDoc = toDoc(q[i * 2], q[i * 2 + 1])
                        Mode.FLOAT_SCALE
                    } else { moving = true; Mode.FLOAT_MOVE }
                }
                Tool.SHEAR -> Mode.FLOAT_SHEAR
                else -> Mode.FLOAT_3D
            }
            engine.liftActive(vm.transformWhole, continueSession = true)
            return
        }
        if (tool == Tool.PATHS) { startPath(); return }
        if (tool == Tool.CROP) { mode = Mode.CROP; vm.cropRect = RectF(downDoc.x, downDoc.y, downDoc.x, downDoc.y); return }
        if (vm.pickMode || tool == Tool.PICKER) { mode = Mode.PICK; pick(e.x, e.y); return }
        val st = vm.strokeTool
        val cloneLike = tool == Tool.CLONE || tool == Tool.HEAL || tool == Tool.PERSPECTIVE_CLONE
        if (tool == Tool.PERSPECTIVE_CLONE && vm.perspEditing) {
            // Drag the plane's corners onto the surface to clone along.
            val p = vm.perspPlane
            if (p != null) {
                var best = -1; var bd = 32 * density
                for (k in 0 until 4) { val v = toView(p[k * 2], p[k * 2 + 1]); val dd = hypot(v.x - e.x, v.y - e.y); if (dd < bd) { bd = dd; best = k } }
                if (best >= 0) { cornerIndex = best; mode = Mode.PLANE_CORNER; return }
            }
            mode = Mode.IGNORE; return
        }
        if (cloneLike && vm.settingSource) {
            mode = Mode.IGNORE
            vm.cloneSource = PointF(downDoc.x, downDoc.y); vm.cloneOffset = null; vm.perspOffset = null; vm.settingSource = false
            invalidate(); return
        }
        val pt = vm.paintTool
        if (st.paint != null && pt != null) {
            if (vm.lineMode) {
                mode = Mode.SHAPE
                val from = lastLineEnd ?: downDoc
                preview = Path().apply { moveTo(from.x, from.y); lineTo(downDoc.x, downDoc.y) }
                return
            }
            mode = Mode.STROKE
            strokePoints = 1
            engine.strokeBegin(pt, vm.settings, vm.fg, cloneOffsetFor(downDoc), vm.symmetry(), vm.perspectiveMap(downDoc))
            engine.strokeAdd(listOf(StrokePoint(downDoc.x, downDoc.y, pressure(e), axis(e, MotionEvent.AXIS_TILT, -1), orientation(e, -1), e.eventTime)))
            cursor = PointF(e.x, e.y)
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) postDelayed(longPress, 450)
            invalidate()
            return
        }
        when (tool) {
            Tool.RECT_SELECT, Tool.ELLIPSE_SELECT, Tool.GRADIENT -> { mode = Mode.SHAPE; preview = Path() }
            Tool.FREE_SELECT -> { mode = Mode.SHAPE; lasso = Path().apply { moveTo(downDoc.x, downDoc.y) }; preview = lasso }
            else -> mode = Mode.SHAPE // taps
        }
    }

    private fun moveSingle(e: MotionEvent) {
        val d = docPoint(e.x, e.y)
        when (mode) {
            Mode.PLANE_CORNER -> vm.perspPlane = vm.perspPlane?.copyOf()?.also { it[cornerIndex * 2] = d.x; it[cornerIndex * 2 + 1] = d.y }
            Mode.GUIDE -> guideDrag = guideDrag?.let { (i, g) -> i to g.copy(pos = (if (g.vertical) d.x else d.y).roundToInt()) }
            Mode.WARP_PUSH -> { cursor = PointF(e.x, e.y); dragFloating(e) }
            Mode.STROKE -> {
                val pts = ArrayList<StrokePoint>(e.historySize + 1)
                for (i in 0 until e.historySize) {
                    val p = toDoc(e.getHistoricalX(i), e.getHistoricalY(i))
                    pts.add(StrokePoint(p.x, p.y, pressure(e, i), axis(e, MotionEvent.AXIS_TILT, i), orientation(e, i), e.getHistoricalEventTime(i)))
                }
                pts.add(StrokePoint(d.x, d.y, pressure(e), axis(e, MotionEvent.AXIS_TILT, -1), orientation(e, -1), e.eventTime))
                strokePoints += pts.size
                engine.strokeAdd(pts)
                cursor = PointF(e.x, e.y)
            }
            Mode.PICK -> pick(e.x, e.y)
            Mode.FOCUS -> { vm.dofFocus = PointF(d.x, d.y); invalidate() }
            Mode.CROP -> {
                var w = d.x - downDoc.x; var h = d.y - downDoc.y
                vm.aspect?.let { r -> val m = max(abs(w), abs(h) * r); w = Math.copySign(m, w); h = Math.copySign(m / r, h) }
                vm.cropRect = RectF(min(downDoc.x, downDoc.x + w), min(downDoc.y, downDoc.y + h), max(downDoc.x, downDoc.x + w), max(downDoc.y, downDoc.y + h)).also { if (vm.aspect == null) constrain(it) }
            }
            Mode.SHAPE -> when {
                lasso != null -> lasso!!.lineTo(d.x, d.y)
                vm.strokeTool.paint != null && vm.lineMode -> {
                    val from = lastLineEnd ?: downDoc
                    preview = Path().apply { moveTo(from.x, from.y); lineTo(d.x, d.y) }
                }
                vm.tool == Tool.GRADIENT -> preview = Path().apply { moveTo(downDoc.x, downDoc.y); lineTo(d.x, d.y) }
                vm.tool == Tool.RECT_SELECT || vm.tool == Tool.ELLIPSE_SELECT -> preview = shapePath(downDoc, d)
            }
            Mode.FLOAT_MOVE, Mode.FLOAT_SCALE, Mode.FLOAT_ROTATE, Mode.FLOAT_SHEAR, Mode.FLOAT_CORNER, Mode.FLOAT_3D,
            Mode.FLOAT_HANDLE, Mode.WARP_PUSH, Mode.CAGE_DRAG -> dragFloating(e)
            Mode.PATH_POINT, Mode.PATH_HANDLE, Mode.PATH_NEW -> dragPath(d)
            else -> {}
        }
        invalidate()
    }

    private fun shapeRect(a: PointF, b: PointF): RectF {
        var w = b.x - a.x; var h = b.y - a.y
        vm.aspect?.let { r -> val m = max(abs(w), abs(h) * r); w = Math.copySign(m, w); h = Math.copySign(m / r, h) }
        return if (vm.fromCenter) RectF(a.x - abs(w), a.y - abs(h), a.x + abs(w), a.y + abs(h))
        else RectF(min(a.x, a.x + w), min(a.y, a.y + h), max(a.x, a.x + w), max(a.y, a.y + h))
    }

    private fun shapePath(a: PointF, b: PointF): Path {
        val r = shapeRect(a, b)
        val rad = minOf(vm.cornerRadius, r.width() / 2, r.height() / 2)
        return Path().apply {
            when {
                vm.tool == Tool.ELLIPSE_SELECT -> addOval(r, Path.Direction.CW)
                vm.tool == Tool.RECT_SELECT && rad > 0.5f -> addRoundRect(r, rad, rad, Path.Direction.CW)
                else -> addRect(r, Path.Direction.CW)
            }
        }
    }

    private fun constrain(r: RectF) {
        val s = engine.stack
        r.intersect(0f, 0f, s.width.toFloat(), s.height.toFloat())
    }

    private fun endSingle(e: MotionEvent) {
        val d = docPoint(e.x, e.y)
        val isTap = !moved
        when (mode) {
            Mode.GUIDE -> {
                guideDrag?.let { (i, g) ->
                    val s = engine.stack
                    // Dropped back on a ruler (or outside the image): remove.
                    val gone = inRuler(e.x, e.y) || g.pos < 0 || g.pos > (if (g.vertical) s.width else s.height)
                    when {
                        i < 0 && !gone -> engine.addGuide(g)
                        i >= 0 -> engine.moveGuide(i, if (gone) null else g)
                    }
                }
                guideDrag = null
            }
            Mode.STROKE -> { engine.strokeEnd(); lastLineEnd = d }
            Mode.SHAPE -> {
                val st = vm.strokeTool
                when {
                    st.paint != null && vm.lineMode -> {
                        val from = lastLineEnd ?: downDoc
                        engine.strokeBegin(vm.paintTool ?: st.paint, vm.settings.copy(smoothing = 0f), vm.fg, cloneOffsetFor(from), vm.symmetry(), vm.perspectiveMap(from))
                        engine.strokeAdd(listOf(StrokePoint(from.x, from.y, 1f), StrokePoint(d.x, d.y, 1f)))
                        engine.strokeEnd()
                        lastLineEnd = d
                    }
                    vm.tool == Tool.RECT_SELECT || vm.tool == Tool.ELLIPSE_SELECT ->
                        if (isTap) engine.selectNone()
                        else engine.select(shapePath(downDoc, d), vm.selectMode, vm.antialias, vm.feather, if (vm.tool == Tool.ELLIPSE_SELECT) "Ellipse Select" else "Rectangle Select")
                    vm.tool == Tool.FREE_SELECT -> {
                        val l = lasso
                        if (l != null && !isTap) { l.close(); engine.select(Path(l), vm.selectMode, vm.antialias, vm.feather, "Free Select") }
                        else if (isTap) engine.selectNone()
                    }
                    vm.tool.picksObjects -> if (isTap) vm.objectTap(d.x, d.y)
                    vm.tool == Tool.SCISSORS -> if (isTap) { vm.scissorsTap(PointF(d.x, d.y), 24 * density / scale); invalidate() }
                    vm.tool == Tool.FUZZY_SELECT || vm.tool == Tool.COLOR_SELECT -> if (isTap)
                        engine.fuzzySelect(d.x.toInt(), d.y.toInt(), vm.threshold.toInt(), vm.selectMode, vm.tool == Tool.COLOR_SELECT, vm.sampleMerged)
                    vm.tool == Tool.BUCKET -> if (isTap) {
                        engine.bucketFill(d.x.toInt(), d.y.toInt(), vm.fg, vm.threshold.toInt(), vm.fillWholeSelection, vm.sampleMerged, vm.settings.opacity, vm.settings.mode,
                            vm.pattern?.bitmap?.takeIf { vm.fillWithPattern })
                    }
                    vm.tool == Tool.GRADIENT -> if (!isTap) {
                        val (cols, pos) = vm.gradient.stops(vm.fg, vm.bg, vm.gradientReverse)
                        engine.gradient(downDoc.x, downDoc.y, d.x, d.y, cols, pos, vm.gradientShape, vm.settings.opacity, vm.settings.mode)
                    }
                    vm.tool == Tool.TEXT -> if (isTap) vm.textToolTap(d.x, d.y)
                    vm.tool == Tool.MEME -> if (isTap) vm.memeTap(d.x, d.y)
                }
                preview = null; lasso = null
            }
            Mode.PICK -> {
                // Sample where the finger lifted; the result arrives from the engine thread.
                val back = vm.pipette
                vm.pipette = false
                engine.pickColor(d.x.toInt(), d.y.toInt(), merged = true) { c ->
                    vm.fg = c; vm.pushRecent(c)
                    // The pipette returns to the color sheet, showing the picked color.
                    if (back) vm.sheet = io.github.nielstron.brushkin.ui.Sheet.COLOR
                }
                if (back) postDelayed({ if (vm.sheet == io.github.nielstron.brushkin.ui.Sheet.NONE) vm.sheet = io.github.nielstron.brushkin.ui.Sheet.COLOR }, 300)
            }
            Mode.FLOAT_MOVE -> if (moving) {
                moving = false
                val m = engine.floating?.let { moveDelta(it, toDoc(e.x, e.y)) } ?: PointF(d.x - downDoc.x, d.y - downDoc.y)
                engine.commitMove(m.x, m.y)
            }
            Mode.FLOAT_SCALE, Mode.FLOAT_SHEAR, Mode.FLOAT_3D -> if (direct) {
                direct = false
                val f = engine.floating
                if (f == null || f.matrix == f.initial) engine.cancelFloating() else engine.commitFloating(Matrix(f.matrix), vm.tool.label)
            }
            Mode.FLOAT_ROTATE -> if (rotating) {
                rotating = false
                val deg = rotationDeg(d)
                val f = engine.floating
                if (f == null || kotlin.math.abs(deg) < 0.05f) engine.cancelFloating()
                else engine.commitFloating(Matrix(f.initial).apply { postRotate(deg, fPivotDoc.x, fPivotDoc.y) }, "Rotate Layer")
            }
            else -> {}
        }
    }

    /** Aligned clone: the offset is fixed by the first stroke after choosing a source. */
    private fun cloneOffsetFor(start: PointF): android.graphics.Point? {
        if (vm.tool != Tool.CLONE && vm.tool != Tool.HEAL && vm.tool != Tool.PERSPECTIVE_CLONE) return null
        val src = vm.cloneSource ?: return null
        if (vm.tool == Tool.PERSPECTIVE_CLONE) return android.graphics.Point(0, 0)
        val o = vm.cloneOffset ?: android.graphics.Point((src.x - start.x).toInt(), (src.y - start.y).toInt()).also { vm.cloneOffset = it }
        return o
    }

    // ------------------------------------------------------------------ paths tool

    private var pathIndex = -1
    private var pathHandleOut = true

    private fun startPath() {
        val pe = vm.path
        val hit = 22 * density
        fun near(p: PointF) = run { val v = toView(p.x, p.y); hypot(v.x - down.x, v.y - down.y) < hit }
        // Handles of the last-touched anchor take priority, then anchors.
        pe.anchors.forEachIndexed { i, a ->
            if (a.hout != a.p && near(a.hout)) { pathIndex = i; pathHandleOut = true; mode = Mode.PATH_HANDLE; return }
            if (a.hin != a.p && near(a.hin)) { pathIndex = i; pathHandleOut = false; mode = Mode.PATH_HANDLE; return }
        }
        val idx = pe.anchors.indexOfFirst { near(it.p) }
        if (idx == 0 && pe.anchors.size > 2 && !pe.closed) { pe.closed = true; mode = Mode.IGNORE; bumpPath(); return }
        if (idx >= 0) { pathIndex = idx; mode = Mode.PATH_POINT; return }
        if (pe.closed) { mode = Mode.IGNORE; return }
        pe.anchors.add(Anchor(PointF(downDoc.x, downDoc.y)))
        pathIndex = pe.anchors.lastIndex; mode = Mode.PATH_NEW
        bumpPath()
    }

    private fun dragPath(d: PointF) {
        val pe = vm.path
        val a = pe.anchors.getOrNull(pathIndex) ?: return
        when (mode) {
            Mode.PATH_POINT -> {
                val dx = d.x - a.p.x; val dy = d.y - a.p.y
                pe.anchors[pathIndex] = Anchor(PointF(d.x, d.y), PointF(a.hin.x + dx, a.hin.y + dy), PointF(a.hout.x + dx, a.hout.y + dy))
            }
            Mode.PATH_NEW -> // Dragging right after placing a point pulls out symmetric handles (smooth point).
                pe.anchors[pathIndex] = Anchor(a.p, PointF(2 * a.p.x - d.x, 2 * a.p.y - d.y), PointF(d.x, d.y))
            Mode.PATH_HANDLE -> pe.anchors[pathIndex] = if (pathHandleOut) a.copy(hout = PointF(d.x, d.y)) else a.copy(hin = PointF(d.x, d.y))
            else -> {}
        }
        bumpPath()
    }

    private fun bumpPath() { vm.pathVersion++; invalidate() }

    private val pathPaint = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFB547.toInt(); strokeWidth = 2f * density; isAntiAlias = true }
    private val pathHandle = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1f * density; isAntiAlias = true }

    private fun drawPath(canvas: Canvas) {
        if (vm.tool != Tool.PATHS || vm.path.anchors.isEmpty()) return
        val vp = vm.path.toPath().apply { transform(viewMatrix) }
        canvas.drawPath(vp, guideDark); canvas.drawPath(vp, pathPaint)
        val r = 6 * density
        vm.path.anchors.forEachIndexed { i, a ->
            val p = toView(a.p.x, a.p.y)
            for ((h, show) in listOf(a.hin to (a.hin != a.p), a.hout to (a.hout != a.p))) if (show) {
                val hv = toView(h.x, h.y)
                canvas.drawLine(p.x, p.y, hv.x, hv.y, guideDark)
                canvas.drawLine(p.x, p.y, hv.x, hv.y, pathHandle)
                canvas.drawCircle(hv.x, hv.y, r * 0.7f, handleFill)
                canvas.drawCircle(hv.x, hv.y, r * 0.7f, guideDark)
            }
            canvas.drawCircle(p.x, p.y, r, if (i == 0) accentFill else handleFill)
            canvas.drawCircle(p.x, p.y, r, guideDark)
        }
    }

    private val layerBoundary = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density; color = 0xFFFFD54F.toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) }
    private val accentFill = Paint().apply { color = 0xFFFFB547.toInt(); isAntiAlias = true }

    private fun onLongPress() {
        if (mode != Mode.STROKE || moved) return
        engine.strokeCancel()
        if (vm.tool == Tool.CLONE || vm.tool == Tool.HEAL || vm.tool == Tool.PERSPECTIVE_CLONE) {
            // Long-press = GIMP's Ctrl-click: set the clone source.
            mode = Mode.IGNORE
            vm.cloneSource = PointF(downDoc.x, downDoc.y); vm.cloneOffset = null; vm.perspOffset = null
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate(); return
        }
        mode = Mode.PICK
        pick(down.x, down.y)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun pick(x: Float, y: Float) {
        val d = toDoc(x, y)
        engine.pickColor(d.x.toInt(), d.y.toInt(), merged = true) { vm.fg = it }
    }

    // ------------------------------------------------------------------ floating transform

    private var fStart = Matrix()
    private var fAnchorDoc = PointF()
    private var fPivotDoc = PointF()

    private fun startFloating(e: MotionEvent, f: Floating) {
        val pts = f.corners(); viewMatrix.mapPoints(pts)
        val hit = 24 * density
        fStart.set(f.matrix)
        val centerDoc = toDoc((pts[0] + pts[4]) / 2, (pts[1] + pts[5]) / 2)
        if (f.targetLayerId != null) when (vm.tool) {
            Tool.SHEAR -> { mode = Mode.FLOAT_SHEAR; fPivotDoc = centerDoc; return }
            Tool.TRANSFORM_3D -> { mode = Mode.FLOAT_3D; fPivotDoc = centerDoc; return }
            Tool.PERSPECTIVE -> {
                for (i in 0 until 4) if (hypot(e.x - pts[i * 2], e.y - pts[i * 2 + 1]) < hit) {
                    mode = Mode.FLOAT_CORNER; cornerIndex = i
                    cornersDoc = f.corners(); return
                }
                mode = Mode.IGNORE; return
            }
            Tool.HANDLE -> {
                val near = vm.handles.indexOfFirst { val p = toView(it.second.x, it.second.y); hypot(e.x - p.x, e.y - p.y) < hit }
                if (near >= 0) { mode = Mode.FLOAT_HANDLE; cornerIndex = near; return }
                if (vm.handles.size >= 4) { mode = Mode.IGNORE; return }
                val inv = Matrix(); f.matrix.invert(inv)
                val src = floatArrayOf(downDoc.x, downDoc.y); inv.mapPoints(src)
                vm.handles.add(PointF(src[0], src[1]) to PointF(downDoc.x, downDoc.y))
                handleBase = Matrix(f.matrix)
                mode = Mode.FLOAT_HANDLE; cornerIndex = vm.handles.lastIndex; invalidate(); return
            }
            Tool.WARP -> { mode = Mode.WARP_PUSH; lastWarp = PointF(downDoc.x, downDoc.y); return }
            Tool.CAGE -> {
                if (engine.cageNow.isEmpty()) cageEditing = false
                val near = engine.cageNow.indexOfFirst { val p = toView(it.x, it.y); hypot(e.x - p.x, e.y - p.y) < hit }
                if (near >= 0 && engine.cageNow.size >= 3) { mode = Mode.CAGE_DRAG; cornerIndex = near; cageEditing = true; return }
                if (!cageEditing) engine.cageAdd(downDoc.x, downDoc.y)
                mode = Mode.IGNORE; return
            }
            // Rotate: drag inside to move, outside (or on the handle) to rotate; both apply as one step.
            Tool.MOVE -> {
                val h = rotateHandle(pts)
                val onHandle = hypot(e.x - h.x, e.y - h.y) < hit * 1.5f
                mode = if (!onHandle && insideQuad(pts, e.x, e.y)) Mode.FLOAT_MOVE else Mode.FLOAT_ROTATE
                fPivotDoc = centerDoc; return
            }
            Tool.SCALE -> {
                for (i in 0 until 4) if (hypot(e.x - pts[i * 2], e.y - pts[i * 2 + 1]) < hit) {
                    mode = Mode.FLOAT_SCALE
                    val opp = (i + 2) % 4
                    fPivotDoc = toDoc(pts[opp * 2], pts[opp * 2 + 1]); fAnchorDoc = toDoc(pts[i * 2], pts[i * 2 + 1])
                    return
                }
                mode = Mode.FLOAT_MOVE; return
            }
            else -> {}
        }
        val rh = rotateHandle(pts)
        val centerView = PointF((pts[0] + pts[4]) / 2, (pts[1] + pts[5]) / 2)
        val cDoc = toDoc(centerView.x, centerView.y)
        if (hypot(e.x - rh.x, e.y - rh.y) < hit) {
            mode = Mode.FLOAT_ROTATE; fPivotDoc = cDoc; return
        }
        for (i in 0 until 4) {
            if (hypot(e.x - pts[i * 2], e.y - pts[i * 2 + 1]) < hit) {
                mode = Mode.FLOAT_SCALE
                val opp = (i + 2) % 4
                fPivotDoc = toDoc(pts[opp * 2], pts[opp * 2 + 1])
                fAnchorDoc = toDoc(pts[i * 2], pts[i * 2 + 1])
                return
            }
        }
        mode = Mode.FLOAT_MOVE
    }

    /** Move tool drag in progress (the layer is lifted on touch and applied on release). */
    private var moving = false
    /** Move/Rotate tool: this drag rotates (applied on release). */
    private var rotating = false
    /** The current drag belongs to a direct transform (applied on release). */
    private var direct = false

    private fun rotationDeg(d: PointF): Float {
        val a0 = atan2(downDoc.y - fPivotDoc.y, downDoc.x - fPivotDoc.x)
        val a1 = atan2(d.y - fPivotDoc.y, d.x - fPivotDoc.x)
        var deg = Math.toDegrees((a1 - a0).toDouble()).toFloat()
        if (vm.snapRotation) deg = Math.round(deg / 15f) * 15f
        return deg
    }

    // What the Move/Rotate tool acts on while idle: the selection, the layer's content or the image.
    private var moveBoundsKey = Long.MIN_VALUE
    private var moveBounds: RectF? = null
    private fun moveQuad(): FloatArray? {
        val s = engine.stack
        val key = (engine.info.value.version * 31 + s.activeId) * 2 + (if (vm.transformWhole) 1 else 0)
        if (key != moveBoundsKey) {
            moveBoundsKey = key
            moveBounds = when {
                vm.transformWhole -> RectF(0f, 0f, s.width.toFloat(), s.height.toFloat())
                s.selection != null -> RectF(s.selection.bounds)
                else -> s.active?.takeIf { !it.isGroup }?.let { io.github.nielstron.brushkin.engine.LayerExtent.bounds(it) }?.let { RectF(it) }
            }
        }
        // In a series of direct transforms: the transformed frame of the original pixels.
        engine.transformFrame()?.let { fr -> return fr.also { viewMatrix.mapPoints(it) } }
        val r = moveBounds ?: return null
        return floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom).also { viewMatrix.mapPoints(it) }
    }
    private var cornerIndex = 0
    private var cornersDoc = FloatArray(8)
    private var handleBase = Matrix()
    private var lastWarp = PointF()
    /** Cage: once a point has been dragged, taps no longer add points. */
    private var cageEditing = false
    private val camera = android.graphics.Camera()

    private fun dragFloating(e: MotionEvent) {
        val f = engine.floating ?: return
        val d = toDoc(e.x, e.y)
        if (direct) fStart.set(f.initial)
        if (moving) { val m = moveDelta(f, d); f.matrix.set(f.initial); f.matrix.postTranslate(m.x, m.y); return }
        if (rotating) { f.matrix.set(f.initial); f.matrix.postRotate(rotationDeg(d), fPivotDoc.x, fPivotDoc.y); return }
        when (mode) {
            Mode.WARP_PUSH -> {
                engine.meshPush(lastWarp.x, lastWarp.y, d.x - lastWarp.x, d.y - lastWarp.y, vm.warpRadius, vm.warpStrength)
                lastWarp = PointF(d.x, d.y); return
            }
            Mode.CAGE_DRAG -> { engine.cageMove(cornerIndex, d.x, d.y); return }
            Mode.FLOAT_CORNER -> {
                val dst = cornersDoc.copyOf(); dst[cornerIndex * 2] = d.x; dst[cornerIndex * 2 + 1] = d.y
                val w = f.bitmap.width.toFloat(); val h = f.bitmap.height.toFloat()
                f.matrix.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, dst, 0, 4); return
            }
            Mode.FLOAT_HANDLE -> {
                val hs = vm.handles
                hs[cornerIndex] = hs[cornerIndex].first to PointF(d.x, d.y)
                // GIMP Handle Transform: 1 handle moves, 2 add scale/rotate, 3 affine, 4 perspective.
                val src = hs.flatMap { listOf(it.first.x, it.first.y) }.toFloatArray()
                val dst = hs.flatMap { listOf(it.second.x, it.second.y) }.toFloatArray()
                if (hs.size == 1) { f.matrix.set(handleBase); val p = floatArrayOf(src[0], src[1]); handleBase.mapPoints(p); f.matrix.postTranslate(dst[0] - p[0], dst[1] - p[1]) }
                else f.matrix.setPolyToPoly(src, 0, dst, 0, hs.size)
                return
            }
            else -> {}
        }
        f.matrix.set(fStart)
        when (mode) {
            Mode.FLOAT_MOVE -> f.matrix.postTranslate(d.x - downDoc.x, d.y - downDoc.y)
            Mode.FLOAT_SCALE -> {
                if (vm.tool == Tool.SCALE && !vm.keepRatio) {
                    // Free scale: each axis follows the finger independently.
                    fun k(a: Float, p: Float, x: Float) = if (kotlin.math.abs(a - p) < 1f) 1f else ((x - p) / (a - p)).coerceIn(-100f, 100f).let { if (kotlin.math.abs(it) < 0.01f) 0.01f else it }
                    f.matrix.postScale(k(fAnchorDoc.x, fPivotDoc.x, d.x), k(fAnchorDoc.y, fPivotDoc.y, d.y), fPivotDoc.x, fPivotDoc.y)
                } else {
                    val s0 = hypot(fAnchorDoc.x - fPivotDoc.x, fAnchorDoc.y - fPivotDoc.y).coerceAtLeast(1f)
                    val s1 = hypot(d.x - fPivotDoc.x, d.y - fPivotDoc.y)
                    val k = (s1 / s0).coerceAtLeast(0.01f)
                    f.matrix.postScale(k, k, fPivotDoc.x, fPivotDoc.y)
                }
            }
            Mode.FLOAT_SHEAR -> {
                val dx = d.x - downDoc.x; val dy = d.y - downDoc.y
                val sz = f.corners().let { c -> hypot(c[6] - c[0], c[7] - c[1]) to hypot(c[2] - c[0], c[3] - c[1]) }
                if (abs(dx) >= abs(dy)) f.matrix.postSkew(dx / sz.first.coerceAtLeast(1f), 0f, fPivotDoc.x, fPivotDoc.y)
                else f.matrix.postSkew(0f, dy / sz.second.coerceAtLeast(1f), fPivotDoc.x, fPivotDoc.y)
            }
            Mode.FLOAT_3D -> {
                val dx = e.x - down.x; val dy = e.y - down.y
                camera.save()
                // Depth proportional to the image so the perspective looks the same at any size.
                camera.setLocation(0f, 0f, -maxOf(f.bitmap.width, f.bitmap.height) / 72f * 1.2f)
                camera.rotateY(dx * 0.25f); camera.rotateX(-dy * 0.25f)
                val cm = Matrix(); camera.getMatrix(cm); camera.restore()
                cm.preTranslate(-fPivotDoc.x, -fPivotDoc.y); cm.postTranslate(fPivotDoc.x, fPivotDoc.y)
                f.matrix.postConcat(cm)
            }
            Mode.FLOAT_ROTATE -> {
                val a0 = atan2(downDoc.y - fPivotDoc.y, downDoc.x - fPivotDoc.x)
                val a1 = atan2(d.y - fPivotDoc.y, d.x - fPivotDoc.x)
                var deg = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                if (vm.snapRotation) deg = Math.round(deg / 15f) * 15f
                f.matrix.postRotate(deg, fPivotDoc.x, fPivotDoc.y)
            }
            else -> {}
        }
    }
}
