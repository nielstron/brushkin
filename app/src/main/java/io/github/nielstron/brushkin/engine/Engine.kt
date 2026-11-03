package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

data class LayerInfo(
    val id: Long, val name: String, val visible: Boolean, val opacity: Float, val mode: LayerMode,
    val lockAlpha: Boolean, val thumb: Bitmap?,
    val hasMask: Boolean = false, val maskEnabled: Boolean = true, val editMask: Boolean = false, val maskThumb: Bitmap? = null,
)

data class DocInfo(
    val width: Int = 0,
    val height: Int = 0,
    val layers: List<LayerInfo> = emptyList(),
    val activeId: Long = -1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoNames: List<String> = emptyList(),
    val redoNames: List<String> = emptyList(),
    val hasSelection: Boolean = false,
    val floating: Boolean = false,
    val name: String = "Untitled",
    /** Bumped on every document change; used to decide when to autosave. */
    val version: Long = 0,
)

enum class GradientShape { LINEAR, RADIAL }

/**
 * Owns the document and performs every mutation on a single dedicated thread, so the UI thread
 * only ever posts requests and draws immutable snapshots. Heavy pixel work (filters, flood fill)
 * fans out to all cores from that thread.
 */
private const val PREVIEW_MAX = 2048f

class Engine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "brushkin-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile var stack = Stack(1, 1, emptyList(), -1); private set
    @Volatile var preview: Preview? = null; private set
    @Volatile var floating: Floating? = null; private set
    var docName = "Untitled"; private set

    private val history = History()
    @Volatile private var version = 0L
    private val _info = MutableStateFlow(DocInfo())
    val info: StateFlow<DocInfo> = _info
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /** Called (from any thread) when the visible image changed. */
    @Volatile var onInvalidate: (() -> Unit)? = null
    /** Error/info messages for the UI. */
    @Volatile var onMessage: ((String) -> Unit)? = null

    private fun invalidate() = onInvalidate?.invoke()

    fun post(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("Brushkin", "engine op failed", t)
            onMessage?.invoke("Operation failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun <T> busy(label: String, block: suspend () -> T): T {
        _busy.value = label
        try { return block() } finally { _busy.value = null }
    }

    // ---------------------------------------------------------------- document

    fun newDocument(w: Int, h: Int, background: Int?, name: String = "Untitled") = post {
        val bg = TiledBitmap(w, h)
        if (background != null) {
            for (i in bg.tiles.indices) bg.tiles[i] = TiledBitmap.newTile().apply { eraseColor(background) }
        }
        val layer = Layer(name = if (background != null) "Background" else "Layer", pixels = bg)
        replaceDocument(Stack(w, h, listOf(layer), layer.id), name)
    }

    fun openImage(bmp: Bitmap, name: String) = post {
        val px = TiledBitmap.fromBitmap(bmp)
        val layer = Layer(name = name, pixels = px)
        replaceDocument(Stack(bmp.width, bmp.height, listOf(layer), layer.id), name)
    }

    fun openStack(s: Stack, name: String) = post { replaceDocument(s, name) }

    private fun replaceDocument(s: Stack, name: String) {
        history.clear()
        floating = null; preview = null
        stack = s
        docName = name
        thumbs.clear()
        publish()
        invalidate()
    }

    // ---------------------------------------------------------------- state publishing

    private val thumbs = HashMap<Long, Pair<Int, Bitmap>>()
    private val maskThumbs = HashMap<Long, Pair<Int, Bitmap>>()
    private var thumbJob: Job? = null

    private fun publish(thumbsNow: Boolean = false) {
        val s = stack
        _info.value = DocInfo(
            width = s.width, height = s.height,
            layers = s.layers.asReversed().map { l ->
                LayerInfo(
                    l.id, l.name, l.visible, l.opacity, l.mode, l.lockAlpha, thumbs[l.id]?.second,
                    hasMask = l.mask != null, maskEnabled = l.maskEnabled, editMask = l.editMask && l.mask != null, maskThumb = maskThumbs[l.id]?.second,
                )
            },
            activeId = s.active?.id ?: -1,
            canUndo = history.canUndo, canRedo = history.canRedo,
            undoNames = history.undoNames, redoNames = history.redoNames,
            hasSelection = s.selection != null,
            floating = floating != null,
            name = docName,
            version = version,
        )
        // Thumbnails are cheap but not free; batch them after edits settle.
        thumbJob?.cancel()
        thumbJob = scope.launch {
            if (!thumbsNow) delay(120)
            if (updateThumbs()) publish(thumbsNow = true)
        }
    }

    private fun signature(t: TiledBitmap): Int {
        var h = 17
        for (b in t.tiles) h = h * 31 + System.identityHashCode(b)
        return h
    }

    private fun updateThumbs(): Boolean {
        val s = stack
        var changed = false
        val maxDim = 96f
        val scale = minOf(maxDim / s.width, maxDim / s.height)
        fun render(t: TiledBitmap): Bitmap {
            val bmp = Bitmap.createBitmap((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            if (t.fill != 0) bmp.eraseColor(t.fill)
            val c = Canvas(bmp)
            // Overlap tiles by one thumbnail pixel so downscaled tile edges don't leave seams.
            val pad = 1f / scale
            val p = Paint().apply { isFilterBitmap = true }
            c.scale(scale, scale)
            for (i in t.tiles.indices) {
                val b = t.tiles[i] ?: continue
                val x = t.tileX(i).toFloat(); val y = t.tileY(i).toFloat()
                c.drawBitmap(b, null, RectF(x, y, x + TILE + pad, y + TILE + pad), p)
            }
            return bmp
        }
        for (l in s.layers) {
            val sig = signature(l.pixels)
            if (thumbs[l.id]?.first != sig) { thumbs[l.id] = sig to render(l.pixels); changed = true }
            val m = l.mask
            if (m == null) { if (maskThumbs.remove(l.id) != null) changed = true; continue }
            val ms = signature(m)
            if (maskThumbs[l.id]?.first != ms) { maskThumbs[l.id] = ms to render(m); changed = true }
        }
        val ids = s.layers.map { it.id }.toSet()
        thumbs.keys.retainAll(ids); maskThumbs.keys.retainAll(ids)
        return changed
    }

    private fun record(name: String, before: Stack, diffs: List<TileDiff> = emptyList(), extraBytes: Long = 0) {
        version++
        history.push(HistoryEntry(name, before, stack, diffs, extraBytes))
        publish()
        invalidate()
    }

    fun undo() = post {
        if (floating != null) return@post
        val e = history.undo() ?: return@post
        version++
        stack = e.before
        publish(); invalidate()
    }

    fun redo() = post {
        if (floating != null) return@post
        val e = history.redo() ?: return@post
        version++
        stack = e.after
        publish(); invalidate()
    }

    /** Undo/redo until [steps] entries remain in the undo list. */
    fun jumpHistory(undoCount: Int) = post {
        version++
        while (history.undoNames.size > undoCount) { history.undo()?.let { stack = it.before } ?: break }
        while (history.undoNames.size < undoCount) { history.redo()?.let { stack = it.after } ?: break }
        publish(); invalidate()
    }

    // ---------------------------------------------------------------- painting

    private var stroke: StrokeOp? = null
    private var strokeTarget: TiledBitmap? = null
    private var strokeBefore: Stack? = null
    private var strokeTool: PaintTool = PaintTool.BRUSH

    fun strokeBegin(tool: PaintTool, settings: BrushSettings, color: Int, cloneOffset: android.graphics.Point? = null) = post {
        val s = stack
        val layer = s.active ?: return@post
        if (floating != null) return@post
        if (!layer.visible) { onMessage?.invoke("The active layer is hidden"); return@post }
        if (layer.lockPixels) { onMessage?.invoke("The active layer's pixels are locked"); return@post }
        if ((tool == PaintTool.CLONE || tool == PaintTool.HEAL) && cloneOffset == null) {
            onMessage?.invoke("Set a source first: tap “Set source”, then tap the spot to copy from"); return@post
        }
        val target = layer.drawable
        val onMask = target !== layer.pixels
        // On a mask, colors become gray and the eraser paints black (hides).
        val t = if (onMask && tool == PaintTool.ERASER) PaintTool.BRUSH else tool
        val c = if (onMask) (if (tool == PaintTool.ERASER) MASK_BLACK else maskGray(color)) else color
        stroke = StrokeOp(target, t, settings, c, layer.lockAlpha && !onMask, s.selection, cloneOffset)
        strokeTarget = target; strokeBefore = s; strokeTool = tool
    }

    fun strokeAdd(points: List<StrokePoint>) = post {
        val st = stroke ?: return@post
        st.add(points)
        invalidate()
    }

    fun strokeEnd() = post {
        val st = stroke ?: return@post
        stroke = null
        st.catchUp()
        val changes = st.finish()
        if (changes.isNotEmpty()) record(toolName(strokeTool), strokeBefore!!, listOf(TileDiff(strokeTarget!!, changes)))
        else invalidate()
    }

    fun strokeCancel() = post {
        stroke?.cancel(); stroke = null
        invalidate()
    }

    private fun toolName(t: PaintTool) = when (t) {
        PaintTool.BRUSH -> "Paintbrush"; PaintTool.PENCIL -> "Pencil"; PaintTool.AIRBRUSH -> "Airbrush"
        PaintTool.ERASER -> "Eraser"; PaintTool.SMUDGE -> "Smudge"; PaintTool.CLONE -> "Clone"
        PaintTool.HEAL -> "Heal"; PaintTool.DODGE -> "Dodge"; PaintTool.BURN -> "Burn"
        PaintTool.BLUR -> "Blur"; PaintTool.SHARPEN -> "Sharpen"
    }

    /** Fills tiles of the active layer through [draw], respecting the selection. */
    private fun paintRegion(name: String, region: Rect?, opacity: Float, mode: LayerMode, eraser: Boolean = false, draw: (Canvas) -> Unit) {
        val s = stack
        val layer = s.active ?: return
        if (layer.lockPixels) { onMessage?.invoke("The active layer's pixels are locked"); return }
        val sel = s.selection
        val r = Rect(0, 0, s.width, s.height)
        if (region != null && !r.intersect(region)) return
        if (sel != null && !r.intersect(sel.bounds)) return
        val target = layer.drawable
        val onMask = target !== layer.pixels
        val session = TileEditSession(target)
        val scratch = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val sc = Canvas(scratch)
        val comp = Paint().apply {
            alpha = (opacity * 255).roundToInt()
            when {
                // Masks: everything becomes gray; clearing paints black.
                onMask -> colorFilter = if (eraser) android.graphics.ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                    else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                eraser -> xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                layer.lockAlpha -> xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                mode.blend != null -> blendMode = mode.blend
            }
        }
        val c = Canvas()
        target.forTilesIn(r) { i ->
            val tx = target.tileX(i).toFloat(); val ty = target.tileY(i).toFloat()
            scratch.eraseColor(0)
            sc.save(); sc.translate(-tx, -ty); draw(sc); sc.restore()
            if (sel != null) MaskOps.apply(sc, sel.mask, -tx, -ty)
            c.setBitmap(session.writable(i))
            c.drawBitmap(scratch, 0f, 0f, comp)
        }
        c.setBitmap(null)
        val changes = session.finish()
        if (changes.isNotEmpty()) record(name, s, listOf(TileDiff(target, changes)))
    }

    fun fillSelection(color: Int, name: String = "Fill") = post {
        paintRegion(name, null, 1f, LayerMode.NORMAL) { it.drawColor(color) }
    }

    fun clearSelection() = post {
        paintRegion("Clear", null, 1f, LayerMode.NORMAL, eraser = true) { it.drawColor(Color.BLACK) }
    }

    fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, shape: GradientShape, opacity: Float, mode: LayerMode) = post {
        val shader = when (shape) {
            GradientShape.LINEAR -> LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP)
            GradientShape.RADIAL -> RadialGradient(x0, y0, hypot(x1 - x0, y1 - y0).coerceAtLeast(1f), c0, c1, Shader.TileMode.CLAMP)
        }
        val p = Paint().apply { this.shader = shader; isDither = true }
        paintRegion("Gradient", null, opacity, mode) { it.drawPaint(p) }
    }

    fun bucketFill(x: Int, y: Int, color: Int, threshold: Int, wholeSelection: Boolean, sampleMerged: Boolean, opacity: Float, mode: LayerMode) = post {
        val s = stack
        if (wholeSelection) { paintRegion("Bucket Fill", null, opacity, mode) { it.drawColor(color) }; return@post }
        busy("Filling") {
            val src = if (sampleMerged) Compositor.flatten(s).let { b -> IntArray(s.width * s.height).also { b.getPixels(it, 0, s.width, 0, 0, s.width, s.height) } }
            else s.active?.drawable?.toPixels() ?: return@busy
            val mask = floodFill(src, s.width, s.height, x, y, threshold)
            val mb = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
            Selection.writeMaskBytes(mb, mask, s.width, s.height)
            val bounds = Selection.fromMask(mb)?.bounds ?: return@busy
            val p = Paint().apply { this.color = color }
            paintRegion("Bucket Fill", bounds, opacity, mode) { c ->
                c.drawColor(color)
                MaskOps.apply(c, mb, 0f, 0f)
            }
            p.reset()
        }
    }

    /** Samples the color at a doc position; [merged] samples the visible image. */
    fun pickColor(x: Int, y: Int, merged: Boolean, cb: (Int) -> Unit) = post {
        val s = stack
        if (x !in 0 until s.width || y !in 0 until s.height) return@post
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-x.toFloat(), -y.toFloat())
        if (merged) Compositor.draw(c, s, RectF(x.toFloat(), y.toFloat(), x + 1f, y + 1f), filter = false)
        else s.active?.let { Compositor.drawTiles(c, it.pixels, null, null) }
        val px = bmp.getPixel(0, 0)
        if (Color.alpha(px) > 0) withContext(Dispatchers.Main) { cb(px or 0xFF000000.toInt()) }
    }

    // ---------------------------------------------------------------- selection

    fun select(path: Path, mode: SelectMode, antialias: Boolean, feather: Float, name: String) = post {
        val s = stack
        val sel = Selection.combine(s.selection, path, s.width, s.height, mode, antialias, feather)
        stack = s.copy(selection = sel)
        record(name, s)
    }

    fun fuzzySelect(x: Int, y: Int, threshold: Int, mode: SelectMode, byColor: Boolean, sampleMerged: Boolean) = post {
        val s = stack
        busy("Selecting") {
            val src = if (sampleMerged) Compositor.flatten(s).let { b -> IntArray(s.width * s.height).also { b.getPixels(it, 0, s.width, 0, 0, s.width, s.height) } }
            else s.active?.pixels?.toPixels() ?: return@busy
            val mask = floodFill(src, s.width, s.height, x, y, threshold, contiguous = !byColor)
            val mb = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
            Selection.writeMaskBytes(mb, mask, s.width, s.height)
            stack = s.copy(selection = Selection.combineMask(s.selection, mb, mode))
            record(if (byColor) "Select by Color" else "Fuzzy Select", s)
        }
    }

    fun selectAll() = post { val s = stack; stack = s.copy(selection = Selection.all(s.width, s.height)); record("Select All", s) }
    fun selectNone() = post { val s = stack; if (s.selection == null) return@post; stack = s.copy(selection = null); record("Select None", s) }

    fun selectInvert() = post {
        val s = stack
        val b = s.selection?.bytes() ?: ByteArray(s.width * s.height)
        for (i in b.indices) b[i] = (255 - (b[i].toInt() and 0xFF)).toByte()
        stack = s.copy(selection = Selection.fromBytes(b, s.width, s.height)); record("Invert Selection", s)
    }

    /** Feather (blur), grow (>0) or shrink (<0) the selection. */
    fun selectModify(name: String, radius: Int, kind: Int) = post {
        val s = stack
        val sel = s.selection ?: return@post
        busy(name) {
            val blurred = Selection.blurBytes(sel.bytes(), s.width, s.height, radius * 2)
            if (kind != 0) for (i in blurred.indices) {
                val v = blurred[i].toInt() and 0xFF
                blurred[i] = (if (kind > 0) (if (v > 8) 255 else 0) else (if (v > 247) 255 else 0)).toByte()
            }
            stack = s.copy(selection = Selection.fromBytes(blurred, s.width, s.height)); record(name, s)
        }
    }

    fun alphaToSelection() = post {
        val s = stack
        val l = s.active ?: return@post
        val px = l.pixels.toPixels()
        val b = ByteArray(px.size) { (px[it] ushr 24).toByte() }
        stack = s.copy(selection = Selection.fromBytes(b, s.width, s.height)); record("Alpha to Selection", s)
    }

    // ---------------------------------------------------------------- layers

    fun setActive(id: Long) = post { stack = stack.copy(activeId = id); publish() }

    fun addLayer(name: String = "Layer", fill: Int? = null) = post {
        val s = stack
        val t = TiledBitmap(s.width, s.height)
        if (fill != null) for (i in t.tiles.indices) t.tiles[i] = TiledBitmap.newTile().apply { eraseColor(fill) }
        val l = Layer(name = uniqueName(name), pixels = t)
        val idx = s.indexOf(s.activeId).let { if (it < 0) s.layers.size else it + 1 }
        stack = s.copy(layers = s.layers.toMutableList().apply { add(idx, l) }, activeId = l.id)
        record("New Layer", s)
    }

    private fun uniqueName(base: String): String {
        val names = stack.layers.map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base #$i" in names) i++
        return "$base #$i"
    }

    fun duplicateLayer() = post {
        val s = stack
        val l = s.active ?: return@post
        val copy = l.copy(id = newId(), name = uniqueName("${l.name} copy"), pixels = l.pixels.copy(), mask = l.mask?.copy())
        val idx = s.indexOf(l.id) + 1
        stack = s.copy(layers = s.layers.toMutableList().apply { add(idx, copy) }, activeId = copy.id)
        record("Duplicate Layer", s)
    }

    fun deleteLayer(id: Long) = post {
        val s = stack
        if (s.layers.size <= 1) { onMessage?.invoke("An image needs at least one layer"); return@post }
        val idx = s.indexOf(id)
        if (idx < 0) return@post
        val layers = s.layers.filter { it.id != id }
        val newActive = if (s.activeId == id) layers[(idx - 1).coerceAtLeast(0)].id else s.activeId
        stack = s.copy(layers = layers, activeId = newActive)
        record("Delete Layer", s)
    }

    fun moveLayer(id: Long, delta: Int) = post {
        val s = stack
        val idx = s.indexOf(id)
        val to = (idx + delta).coerceIn(0, s.layers.size - 1)
        if (idx < 0 || to == idx) return@post
        stack = s.copy(layers = s.layers.toMutableList().apply { add(to, removeAt(idx)) })
        record(if (delta > 0) "Raise Layer" else "Lower Layer", s)
    }

    private var propsBefore: Stack? = null

    /** Change layer properties. With [commit] = false (e.g. while dragging a slider) no history entry is created yet. */
    fun setLayer(id: Long, name: String, commit: Boolean = true, change: (Layer) -> Layer) = post {
        val s = stack
        val l = s.layers.firstOrNull { it.id == id } ?: return@post
        if (propsBefore == null) propsBefore = s
        stack = s.replace(change(l))
        if (commit) {
            val before = propsBefore!!
            propsBefore = null
            record(name, before)
        } else { publish(); invalidate() }
    }

    fun mergeDown(id: Long) = post {
        val s = stack
        val idx = s.indexOf(id)
        if (idx <= 0) return@post
        val top = s.layers[idx]; val bottom = s.layers[idx - 1]
        busy("Merging") {
            val sub = Stack(s.width, s.height, listOf(bottom.copy(opacity = 1f, mode = LayerMode.NORMAL, visible = true), top), top.id)
            val bmp = Compositor.flatten(sub)
            val merged = bottom.copy(id = newId(), pixels = TiledBitmap.fromBitmap(bmp), mask = null, editMask = false)
            stack = s.copy(layers = s.layers.toMutableList().apply { removeAt(idx); set(idx - 1, merged) }, activeId = merged.id)
            record("Merge Down", s, extraBytes = s.width.toLong() * s.height * 4)
        }
    }

    fun flatten() = post {
        val s = stack
        busy("Flattening") {
            val bmp = Compositor.flatten(s, Color.WHITE)
            val l = Layer(name = "Background", pixels = TiledBitmap.fromBitmap(bmp))
            stack = s.copy(layers = listOf(l), activeId = l.id)
            record("Flatten Image", s, extraBytes = s.width.toLong() * s.height * 4)
        }
    }

    // ---------------------------------------------------------------- whole-image transforms

    private fun transformAll(name: String, newW: Int, newH: Int, m: Matrix, cropSelection: Boolean = true) {
        val s = stack
        val p = Paint().apply { isFilterBitmap = true }
        fun xf(t: TiledBitmap): TiledBitmap {
            val dst = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            if (t.fill != 0) dst.eraseColor(t.fill)
            Canvas(dst).drawBitmap(t.toBitmap(), m, p)
            return TiledBitmap.fromBitmap(dst, fill = t.fill)
        }
        val layers = s.layers.map { l -> l.copy(pixels = xf(l.pixels), mask = l.mask?.let { xf(it) }) }
        val sel = if (!cropSelection) null else s.selection?.let { sel ->
            val dst = Bitmap.createBitmap(newW, newH, Bitmap.Config.ALPHA_8)
            Canvas(dst).drawBitmap(sel.mask, m, p)
            Selection.fromMask(dst)
        }
        stack = Stack(newW, newH, layers, s.activeId, sel)
        thumbs.clear()
        record(name, s, extraBytes = newW.toLong() * newH * 4 * layers.size)
    }

    fun flipImage(horizontal: Boolean) = post {
        val s = stack
        busy("Flipping") {
            val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, s.width / 2f, 0f) else setScale(1f, -1f, 0f, s.height / 2f) }
            transformAll(if (horizontal) "Flip Horizontally" else "Flip Vertically", s.width, s.height, m)
        }
    }

    fun rotateImage(degrees: Int) = post {
        val s = stack
        busy("Rotating") {
            val swap = degrees % 180 != 0
            val nw = if (swap) s.height else s.width; val nh = if (swap) s.width else s.height
            val m = Matrix().apply { postRotate(degrees.toFloat(), s.width / 2f, s.height / 2f); postTranslate((nw - s.width) / 2f, (nh - s.height) / 2f) }
            transformAll("Rotate $degrees°", nw, nh, m)
        }
    }

    fun scaleImage(nw: Int, nh: Int) = post {
        val s = stack
        busy("Scaling") {
            transformAll("Scale Image", nw, nh, Matrix().apply { setScale(nw / s.width.toFloat(), nh / s.height.toFloat()) })
        }
    }

    fun canvasSize(nw: Int, nh: Int, dx: Int, dy: Int) = post {
        busy("Resizing canvas") { transformAll("Canvas Size", nw, nh, Matrix().apply { setTranslate(dx.toFloat(), dy.toFloat()) }) }
    }

    fun cropToSelection() = post {
        val s = stack
        val b = s.selection?.bounds ?: run { onMessage?.invoke("Make a selection first"); return@post }
        busy("Cropping") {
            transformAll("Crop to Selection", b.width(), b.height(), Matrix().apply { setTranslate(-b.left.toFloat(), -b.top.toFloat()) }, cropSelection = false)
        }
    }

    fun crop(r: Rect) = post {
        val s = stack
        val b = Rect(r); if (!b.intersect(0, 0, s.width, s.height) || b.isEmpty) return@post
        busy("Cropping") {
            transformAll("Crop Image", b.width(), b.height(), Matrix().apply { setTranslate(-b.left.toFloat(), -b.top.toFloat()) }, cropSelection = false)
        }
    }

    fun flipLayer(horizontal: Boolean) = post {
        val s = stack
        val l = s.active ?: return@post
        val t = l.drawable
        val dst = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        if (t.fill != 0) dst.eraseColor(t.fill)
        val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, s.width / 2f, 0f) else setScale(1f, -1f, 0f, s.height / 2f) }
        Canvas(dst).drawBitmap(t.toBitmap(), m, null)
        replaceTiles(s, t, TiledBitmap.fromBitmap(dst, fill = t.fill), "Flip Layer")
    }

    private fun replaceLayerPixels(before: Stack, l: Layer, px: TiledBitmap, name: String) = replaceTiles(before, l.pixels, px, name)

    /** Swap in all tiles of [px] for [target], as one undoable step. */
    private fun replaceTiles(before: Stack, target: TiledBitmap, px: TiledBitmap, name: String) {
        val changes = HashMap<Int, Pair<Bitmap?, Bitmap?>>()
        for (i in px.tiles.indices) {
            val a = target.tiles[i]; val b = px.tiles[i]
            if (a !== b) changes[i] = a to b
            target.tiles[i] = b
        }
        record(name, before, listOf(TileDiff(target, changes)))
    }

    // ---------------------------------------------------------------- masks

    enum class MaskInit(val label: String) { WHITE("White (full opacity)"), BLACK("Black (full transparency)"), SELECTION("Selection"), ALPHA("Layer's alpha channel"), GRAYSCALE("Grayscale copy of layer") }

    fun addMask(init: MaskInit) = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.mask != null) { onMessage?.invoke("This layer already has a mask"); return@post }
        val mask = when (init) {
            MaskInit.WHITE -> newMask(s.width, s.height)
            MaskInit.BLACK -> TiledBitmap(s.width, s.height, MASK_WHITE).also { t -> for (i in t.tiles.indices) t.tiles[i] = TiledBitmap.newTile().apply { eraseColor(MASK_BLACK) } }
            MaskInit.SELECTION -> {
                val sel = s.selection ?: run { onMessage?.invoke("There is no selection"); return@post }
                val b = sel.bytes()
                TiledBitmap.fromPixels(IntArray(b.size) { val v = b[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, s.width, s.height, MASK_WHITE)
            }
            MaskInit.ALPHA, MaskInit.GRAYSCALE -> {
                val px = l.pixels.toPixels()
                TiledBitmap.fromPixels(IntArray(px.size) {
                    val v = if (init == MaskInit.ALPHA) px[it] ushr 24 else luma(px[it])
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }, s.width, s.height, MASK_WHITE)
            }
        }
        stack = s.replace(l.copy(mask = mask, maskEnabled = true, editMask = true))
        record("Add Layer Mask", s)
    }

    fun applyMask() = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.mask == null) return@post
        busy("Applying mask") {
            val sub = Stack(s.width, s.height, listOf(l.copy(opacity = 1f, mode = LayerMode.NORMAL, visible = true, maskEnabled = true)), l.id)
            val bmp = Compositor.flatten(sub)
            stack = s.replace(l.copy(pixels = TiledBitmap.fromBitmap(bmp), mask = null, editMask = false))
            record("Apply Layer Mask", s, extraBytes = s.width.toLong() * s.height * 4)
        }
    }

    fun deleteMask() = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.mask == null) return@post
        stack = s.replace(l.copy(mask = null, editMask = false))
        record("Delete Layer Mask", s)
    }

    /** Choose whether tools act on the layer or its mask. Not an undo step, like in GIMP. */
    fun setEditMask(id: Long, edit: Boolean) = post {
        val s = stack
        val l = s.layers.firstOrNull { it.id == id } ?: return@post
        stack = s.copy(layers = s.layers.map { if (it.id == id) it.copy(editMask = edit && it.mask != null) else it }, activeId = id)
        publish()
        if (l.editMask != edit) onMessage?.invoke(if (edit) "Editing mask: paint black to hide, white to reveal" else "Editing layer pixels")
    }

    fun maskToSelection() = post {
        val s = stack
        val m = s.active?.mask ?: return@post
        val px = m.toPixels()
        stack = s.copy(selection = Selection.fromBytes(ByteArray(px.size) { luma(px[it]).toByte() }, s.width, s.height))
        record("Mask to Selection", s)
    }

    // ---------------------------------------------------------------- filters

    /** Downscaled copy of the filtered drawable used for fast, memory-light previews. */
    private class Proxy(val px: IntArray, val w: Int, val h: Int, val k: Float, val mask: ByteArray?)
    private var proxy: Proxy? = null
    private var filterLayer: Long = -1
    private var filterOnMask = false
    private val previewRequests = kotlinx.coroutines.flow.MutableStateFlow<Filter?>(null)
    private var previewJob: Job? = null

    /** Prepare a preview proxy (≤ 2048 px) of the active drawable and start the preview worker. */
    fun filterBegin(): Job {
        // Reset synchronously so a preview requested right after this call isn't lost.
        previewRequests.value = null
        return filterBeginInternal()
    }

    private fun filterBeginInternal() = post {
        val s = stack
        val l = s.active ?: return@post
        filterOnMask = l.drawable !== l.pixels
        filterLayer = l.id
        val k = minOf(1f, PREVIEW_MAX / maxOf(s.width, s.height).toFloat())
        val pw = (s.width * k).roundToInt().coerceAtLeast(1); val ph = (s.height * k).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.scale(pw / s.width.toFloat(), ph / s.height.toFloat())
        if (l.drawable.fill != 0) small.eraseColor(l.drawable.fill)
        Compositor.drawTiles(c, l.drawable, null, Paint().apply { isFilterBitmap = true }, pad = 1f / k)
        val px = IntArray(pw * ph).also { small.getPixels(it, 0, pw, 0, 0, pw, ph) }
        val mask = s.selection?.let { sel ->
            val m = Bitmap.createScaledBitmap(sel.mask, pw, ph, true)
            Selection.maskBytes(m, pw, ph)
        }
        proxy = Proxy(px, pw, ph, k, mask)
        previewJob?.cancel()
        // One preview at a time, always for the latest request: slider drags never pile up work.
        previewJob = CoroutineScope(Dispatchers.Default).launch {
            previewRequests.collect { f ->
                if (f == null) return@collect
                val p = proxy ?: return@collect
                val sf = f.scaled(p.k)
                val out = maskBlend(p.px, sf.apply(p.px, p.w, p.h), p.mask, p.w, p.h)
                val tiles = TiledBitmap.fromPixels(out, p.w, p.h, if (filterOnMask) MASK_WHITE else 0)
                withContext(dispatcher) {
                    if (proxy === p && previewRequests.value === f) {
                        preview = Preview(filterLayer, filterOnMask, tiles, 1f / p.k)
                        invalidate()
                    }
                }
            }
        }
    }

    fun filterPreview(f: Filter) { previewRequests.value = f }

    fun filterApply(f: Filter) = post {
        previewJob?.cancel()
        proxy = null
        val s = stack
        val l = s.layers.firstOrNull { it.id == filterLayer } ?: return@post
        val target = if (filterOnMask && l.mask != null) l.mask else l.pixels
        busy(f.name) {
            val out = withContext(Dispatchers.Default) {
                val src = target.toPixels()
                maskBlend(src, f.apply(src, s.width, s.height), s.selection?.bytes(), s.width, s.height)
            }
            preview = null
            replaceTiles(s, target, TiledBitmap.fromPixels(out, s.width, s.height, target.fill), f.name)
        }
    }

    fun filterCancel() = post {
        previewJob?.cancel()
        proxy = null; preview = null
        invalidate()
    }

    // ---------------------------------------------------------------- floating (insert / paste / transform)

    fun placeFloating(bmp: Bitmap, name: String) = post {
        val s = stack
        commitFloatingNow()
        val m = Matrix()
        // Fit into 70% of the canvas, centered.
        val scale = minOf(1f, s.width * 0.7f / bmp.width, s.height * 0.7f / bmp.height)
        m.setScale(scale, scale)
        m.postTranslate((s.width - bmp.width * scale) / 2f, (s.height - bmp.height * scale) / 2f)
        floating = Floating(bmp, m, null, name)
        publish(); invalidate()
    }

    /** Transform tool: lift the selection (or the whole active layer) into a floating bitmap. */
    fun liftActive() = post {
        if (floating != null) return@post
        val s = stack
        val l = s.active ?: return@post
        val full = l.pixels.toBitmap()
        val sel = s.selection
        if (sel == null) {
            floating = Floating(full, Matrix(), l.id, l.name, remainder = null)
        } else {
            val b = sel.bounds
            val lifted = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
            val c = Canvas(lifted)
            c.drawBitmap(full, -b.left.toFloat(), -b.top.toFloat(), null)
            MaskOps.apply(c, sel.mask, -b.left.toFloat(), -b.top.toFloat())
            MaskOps.apply(Canvas(full), sel.mask, 0f, 0f, PorterDuff.Mode.DST_OUT)
            floating = Floating(lifted, Matrix().apply { setTranslate(b.left.toFloat(), b.top.toFloat()) }, l.id, l.name, remainder = TiledBitmap.fromBitmap(full))
        }
        publish(); invalidate()
    }

    fun commitFloating(matrix: Matrix) = post {
        floating?.matrix?.set(matrix)
        commitFloatingNow()
    }

    private suspend fun commitFloatingNow() {
        val f = floating ?: return
        val s = stack
        busy("Applying") {
            val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val target = f.targetLayerId?.let { id -> s.layers.firstOrNull { it.id == id } }
            if (target != null) {
                f.remainder?.let { Compositor.drawTiles(c, it, null, null) }
                c.drawBitmap(f.bitmap, f.matrix, Paint().apply { isFilterBitmap = true })
                floating = null
                val moved = f.selectionMoved(s)
                stack = s.copy(selection = moved)
                replaceLayerPixels(s, target, TiledBitmap.fromBitmap(out), "Transform")
            } else {
                c.drawBitmap(f.bitmap, f.matrix, Paint().apply { isFilterBitmap = true; isAntiAlias = true })
                floating = null
                val l = Layer(name = uniqueName(f.name), pixels = TiledBitmap.fromBitmap(out))
                val idx = s.indexOf(s.activeId).let { if (it < 0) s.layers.size else it + 1 }
                stack = s.copy(layers = s.layers.toMutableList().apply { add(idx, l) }, activeId = l.id)
                record("Insert ${f.name}", s, extraBytes = 0)
            }
        }
    }

    private fun Floating.selectionMoved(s: Stack): Selection? {
        val sel = s.selection ?: return null
        if (remainder == null) return sel
        val m = Matrix(matrix); m.preTranslate(-sel.bounds.left.toFloat(), -sel.bounds.top.toFloat())
        val dst = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
        Canvas(dst).drawBitmap(sel.mask, m, Paint().apply { isFilterBitmap = true })
        return Selection.fromMask(dst)
    }

    fun cancelFloating() = post {
        floating = null
        publish(); invalidate()
    }

    // ---------------------------------------------------------------- clipboard

    private var clipboard: Bitmap? = null
    private var clipOffset = Rect()

    fun copy(merged: Boolean = false, cut: Boolean = false) = post {
        val s = stack
        val l = s.active ?: return@post
        val b = s.selection?.bounds ?: Rect(0, 0, s.width, s.height)
        val bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-b.left.toFloat(), -b.top.toFloat())
        if (merged) Compositor.draw(c, s) else Compositor.drawTiles(c, l.pixels, null, null)
        s.selection?.let { MaskOps.apply(c, it.mask, 0f, 0f) }
        clipboard = bmp; clipOffset = Rect(b)
        if (cut) paintRegion("Cut", null, 1f, LayerMode.NORMAL, eraser = true) { it.drawColor(Color.BLACK) }
        else onMessage?.invoke("Copied")
    }

    fun paste() = post {
        val clip = clipboard ?: run { onMessage?.invoke("Clipboard is empty"); return@post }
        commitFloatingNow()
        floating = Floating(clip, Matrix().apply { setTranslate(clipOffset.left.toFloat(), clipOffset.top.toFloat()) }, null, "Pasted Layer")
        publish(); invalidate()
    }

    // ---------------------------------------------------------------- export

    suspend fun flattened(background: Int? = null): Bitmap = withContext(dispatcher) { Compositor.flatten(stack, background) }

    suspend fun snapshot(): Stack = withContext(dispatcher) {
        // Tiles are copy-on-write, so a shallow copy of each layer's tile array is a consistent snapshot.
        val s = stack
        s.copy(layers = s.layers.map { it.copy(pixels = it.pixels.copy()) })
    }

    fun shutdown() { scope.coroutineContext[Job]?.cancel(); executor.shutdown() }
}
