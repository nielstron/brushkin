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

    fun fit() {
        val s = engine.stack
        if (width == 0 || s.width <= 1) return
        val pad = 24 * density
        val top = 80 * density; val bottom = 180 * density
        val avW = width - 2 * pad; val avH = height - top - bottom
        val sc = min(avW / s.width, avH / s.height)
        viewMatrix.reset()
        viewMatrix.postScale(sc, sc)
        viewMatrix.postTranslate((width - s.width * sc) / 2f, top + (avH - s.height * sc) / 2f)
        flipped = false
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
        Compositor.draw(canvas, s, visible, filter = sc < 2f, preview = engine.preview, floating = engine.floating, pixelSize = 1f / sc)
        canvas.restore()

        canvas.restore()

        // Selection outline in screen space so it stays equally visible at any zoom.
        s.selection?.let { sel -> drawAnts(canvas, sel) }

        drawOverlays(canvas)
        drawLoupe(canvas, s)
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
        val top = (rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0) + 76 * density
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
        drawPath(canvas)
        if (vm.tool == Tool.CLONE || vm.tool == Tool.HEAL) {
            val src = vm.cloneSource
            val o = vm.cloneOffset
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

    private fun drawFloatingHandles(canvas: Canvas, f: Floating) {
        val pts = f.corners(); viewMatrix.mapPoints(pts)
        val path = Path().apply { moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close() }
        canvas.drawPath(path, guideDark); canvas.drawPath(path, accent)
        val hr = 7 * density
        if (f.targetLayerId != null) when (vm.tool) {
            Tool.MOVE -> return
            Tool.ROTATE -> {
                val cx = (pts[0] + pts[4]) / 2; val cy = (pts[1] + pts[5]) / 2; val r = 10 * density
                for (p in listOf(guideDark, accent)) { canvas.drawLine(cx - r, cy, cx + r, cy, p); canvas.drawLine(cx, cy - r, cx, cy + r, p) }
                return
            }
            Tool.SCALE -> {
                for (i in 0 until 4) { canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, handleFill); canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], hr, accent) }
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

    private enum class Mode { NONE, STROKE, VIEW, SHAPE, PICK, FLOAT_MOVE, FLOAT_SCALE, FLOAT_ROTATE, CROP, IGNORE, PATH_POINT, PATH_HANDLE, PATH_NEW }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) null else PointF(e.getX(0), e.getY(0))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxPointers = 1; moved = false
                downTime = e.eventTime
                down = PointF(e.x, e.y); downDoc = toDoc(e.x, e.y)
                startSingle(e)
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
                if (mode == Mode.VIEW && e.pointerCount - 1 >= 2) {
                    // Re-anchor on remaining fingers.
                    gStartMatrix.set(viewMatrix)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (mode == Mode.VIEW) {
                    val quick = e.eventTime - downTime < 300
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

    private fun startSingle(e: MotionEvent) {
        val f = engine.floating
        val tool = vm.tool
        if (f != null) { startFloating(e, f); return }
        if (tool == Tool.MOVE) { engine.liftActive(); mode = Mode.FLOAT_MOVE; moving = true; return }
        if (tool == Tool.PATHS) { startPath(); return }
        if (tool == Tool.CROP) { mode = Mode.CROP; vm.cropRect = RectF(downDoc.x, downDoc.y, downDoc.x, downDoc.y); return }
        if (vm.pickMode || tool == Tool.PICKER) { mode = Mode.PICK; pick(e.x, e.y); return }
        val st = vm.strokeTool
        val cloneLike = tool == Tool.CLONE || tool == Tool.HEAL
        if (cloneLike && vm.settingSource) {
            mode = Mode.IGNORE
            vm.cloneSource = PointF(downDoc.x, downDoc.y); vm.cloneOffset = null; vm.settingSource = false
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
            engine.strokeBegin(pt, vm.settings, vm.fg, cloneOffsetFor(downDoc))
            engine.strokeAdd(listOf(StrokePoint(downDoc.x, downDoc.y, pressure(e))))
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
        val d = toDoc(e.x, e.y)
        when (mode) {
            Mode.STROKE -> {
                val pts = ArrayList<StrokePoint>(e.historySize + 1)
                for (i in 0 until e.historySize) {
                    val p = toDoc(e.getHistoricalX(i), e.getHistoricalY(i))
                    pts.add(StrokePoint(p.x, p.y, pressure(e, i)))
                }
                pts.add(StrokePoint(d.x, d.y, pressure(e)))
                strokePoints += pts.size
                engine.strokeAdd(pts)
                cursor = PointF(e.x, e.y)
            }
            Mode.PICK -> pick(e.x, e.y)
            Mode.CROP -> vm.cropRect = RectF(min(downDoc.x, d.x), min(downDoc.y, d.y), max(downDoc.x, d.x), max(downDoc.y, d.y)).also { constrain(it) }
            Mode.SHAPE -> when {
                lasso != null -> lasso!!.lineTo(d.x, d.y)
                vm.strokeTool.paint != null && vm.lineMode -> {
                    val from = lastLineEnd ?: downDoc
                    preview = Path().apply { moveTo(from.x, from.y); lineTo(d.x, d.y) }
                }
                vm.tool == Tool.GRADIENT -> preview = Path().apply { moveTo(downDoc.x, downDoc.y); lineTo(d.x, d.y) }
                vm.tool == Tool.RECT_SELECT || vm.tool == Tool.ELLIPSE_SELECT -> preview = shapePath(downDoc, d)
            }
            Mode.FLOAT_MOVE, Mode.FLOAT_SCALE, Mode.FLOAT_ROTATE -> dragFloating(e)
            Mode.PATH_POINT, Mode.PATH_HANDLE, Mode.PATH_NEW -> dragPath(d)
            else -> {}
        }
        invalidate()
    }

    private fun shapeRect(a: PointF, b: PointF): RectF {
        var w = b.x - a.x; var h = b.y - a.y
        if (vm.fixedRatio) { val m = max(abs(w), abs(h)); w = Math.copySign(m, w); h = Math.copySign(m, h) }
        return if (vm.fromCenter) RectF(a.x - abs(w), a.y - abs(h), a.x + abs(w), a.y + abs(h))
        else RectF(min(a.x, a.x + w), min(a.y, a.y + h), max(a.x, a.x + w), max(a.y, a.y + h))
    }

    private fun shapePath(a: PointF, b: PointF): Path {
        val r = shapeRect(a, b)
        return Path().apply { if (vm.tool == Tool.ELLIPSE_SELECT) addOval(r, Path.Direction.CW) else addRect(r, Path.Direction.CW) }
    }

    private fun constrain(r: RectF) {
        val s = engine.stack
        r.intersect(0f, 0f, s.width.toFloat(), s.height.toFloat())
    }

    private fun endSingle(e: MotionEvent) {
        val d = toDoc(e.x, e.y)
        val isTap = !moved
        when (mode) {
            Mode.STROKE -> { engine.strokeEnd(); lastLineEnd = d }
            Mode.SHAPE -> {
                val st = vm.strokeTool
                when {
                    st.paint != null && vm.lineMode -> {
                        val from = lastLineEnd ?: downDoc
                        engine.strokeBegin(vm.paintTool ?: st.paint, vm.settings.copy(smoothing = 0f), vm.fg, cloneOffsetFor(from))
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
                    vm.tool == Tool.OBJECT_SELECT -> if (isTap) vm.objectSelect(d.x, d.y)
                    vm.tool == Tool.FUZZY_SELECT || vm.tool == Tool.COLOR_SELECT -> if (isTap)
                        engine.fuzzySelect(d.x.toInt(), d.y.toInt(), vm.threshold.toInt(), vm.selectMode, vm.tool == Tool.COLOR_SELECT, vm.sampleMerged)
                    vm.tool == Tool.BUCKET -> if (isTap) {
                        engine.bucketFill(d.x.toInt(), d.y.toInt(), vm.fg, vm.threshold.toInt(), vm.fillWholeSelection, vm.sampleMerged, vm.settings.opacity, vm.settings.mode)
                    }
                    vm.tool == Tool.GRADIENT -> if (!isTap) {
                        val c1 = if (vm.fgToTransparent) vm.fg and 0x00FFFFFF else vm.bg
                        engine.gradient(downDoc.x, downDoc.y, d.x, d.y, vm.fg, c1, vm.gradientShape, vm.settings.opacity, vm.settings.mode)
                    }
                    vm.tool == Tool.TEXT -> if (isTap) vm.dialog = "text"
                }
                preview = null; lasso = null
            }
            Mode.PICK -> vm.pushRecent(vm.fg)
            Mode.FLOAT_MOVE -> if (moving || vm.tool == Tool.MOVE) { moving = false; engine.commitMove(d.x - downDoc.x, d.y - downDoc.y) }
            else -> {}
        }
    }

    /** Aligned clone: the offset is fixed by the first stroke after choosing a source. */
    private fun cloneOffsetFor(start: PointF): android.graphics.Point? {
        if (vm.tool != Tool.CLONE && vm.tool != Tool.HEAL) return null
        val src = vm.cloneSource ?: return null
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

    private val accentFill = Paint().apply { color = 0xFFFFB547.toInt(); isAntiAlias = true }

    private fun onLongPress() {
        if (mode != Mode.STROKE || moved) return
        engine.strokeCancel()
        if (vm.tool == Tool.CLONE || vm.tool == Tool.HEAL) {
            // Long-press = GIMP's Ctrl-click: set the clone source.
            mode = Mode.IGNORE
            vm.cloneSource = PointF(downDoc.x, downDoc.y); vm.cloneOffset = null
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
            // Dedicated tools: Rotate turns around the center from anywhere; Move only moves.
            Tool.ROTATE -> { mode = Mode.FLOAT_ROTATE; fPivotDoc = centerDoc; return }
            Tool.MOVE -> { mode = Mode.FLOAT_MOVE; return }
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

    private fun dragFloating(e: MotionEvent) {
        val f = engine.floating ?: return
        val d = toDoc(e.x, e.y)
        if (moving) { f.matrix.set(f.initial); f.matrix.postTranslate(d.x - downDoc.x, d.y - downDoc.y); return }
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
