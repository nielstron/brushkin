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
    val depth: Int = 0, val isGroup: Boolean = false, val expanded: Boolean = true, val isText: Boolean = false,
    val lockPosition: Boolean = false, val linked: Boolean = false, val bounds: Rect? = null,
    val effects: List<LayerEffect> = emptyList(),
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
    val channels: List<Triple<Long, String, Boolean>> = emptyList(),
    val floating: Boolean = false,
    val quickMask: Boolean = false,
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
/** Filter previews run on a proxy no larger than this (about screen resolution). */
private const val PREVIEW_MAX = 1280f

class Engine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "brushkin-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile var stack = Stack(1, 1, emptyList(), -1); private set
    @Volatile var preview: Preview? = null; private set
    @Volatile var floating: Floating? = null; private set
    /** A layer not drawn on screen while it's being edited in place (on-canvas text). */
    @Volatile var hiddenLayerId: Long = -1
        set(v) { field = v; invalidate() }
    var docName = "Untitled"; private set

    private val history = History()
    @Volatile private var version = 0L
    private val _info = MutableStateFlow(DocInfo())
    val info: StateFlow<DocInfo> = _info
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy
    private val _progress = MutableStateFlow<Float?>(null)
    /** Progress (0…1) of the current busy operation when it's known. */
    val progress: StateFlow<Float?> = _progress
    private val _cancellable = MutableStateFlow(false)
    /** The current busy operation can be cancelled ([cancelBusy]) without leaving anything half done. */
    val cancellable: StateFlow<Boolean> = _cancellable
    private var busyJob: Job? = null

    /** Stops a cancellable busy operation; the document stays as it was. */
    fun cancelBusy() { if (_cancellable.value) busyJob?.cancel() }

    /** Called (from any thread) when the visible image changed. */
    @Volatile var onInvalidate: (() -> Unit)? = null
    /** Error/info messages for the UI. */
    @Volatile var onMessage: ((String) -> Unit)? = null

    private fun invalidate() = onInvalidate?.invoke()

    init {
        // Layers with non-destructive effects show their filtered pixels, computed off this thread.
        Compositor.displayPixels = { l -> Effects.pixelsAsync(l, { job -> scope.launch(Dispatchers.Default) { job() } }, { invalidate() }) }
    }

    fun post(block: suspend () -> Unit): Job = scope.launch {
        try { block() } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("Brushkin", "engine op failed", t)
            onMessage?.invoke("Operation failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun <T> busy(label: String, cancellable: Boolean = false, block: suspend () -> T): T {
        _busy.value = label; _progress.value = null; _cancellable.value = cancellable
        busyJob = kotlin.coroutines.coroutineContext[Job]
        try { return block() } finally { _busy.value = null; _progress.value = null; _cancellable.value = false; busyJob = null }
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
            layers = buildList {
                // Top-most first, children indented below their (expanded) group.
                fun add(list: List<Layer>, depth: Int) {
                    for (l in list.asReversed()) {
                        add(LayerInfo(
                            l.id, l.name, l.visible, l.opacity, l.mode, l.lockAlpha, thumbs[l.id]?.second,
                            hasMask = l.mask != null, maskEnabled = l.maskEnabled, editMask = l.editMask && l.mask != null, maskThumb = maskThumbs[l.id]?.second,
                            depth = depth, isGroup = l.isGroup, expanded = l.expanded, isText = l.text != null,
                            lockPosition = l.lockPosition, linked = l.linked, bounds = l.bounds, effects = l.effects,
                        ))
                        if (l.isGroup && l.expanded) add(l.children!!, depth + 1)
                    }
                }
                add(s.layers, 0)
            },
            activeId = s.active?.id ?: -1,
            canUndo = history.canUndo, canRedo = history.canRedo,
            undoNames = history.undoNames, redoNames = history.redoNames,
            hasSelection = s.selection != null,
            channels = s.channels.map { Triple(it.id, it.name, it.visible) },
            floating = floating != null,
            quickMask = s.quickMask != null,
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
        fun sigOf(l: Layer): Int = if (l.isGroup) l.children!!.fold(l.children.size) { a, c -> a * 31 + sigOf(c) + (if (c.visible) 1 else 0) + c.mode.ordinal } else signature(l.pixels)
        for (l in s.all) {
            val sig = sigOf(l)
            if (thumbs[l.id]?.first != sig) {
                thumbs[l.id] = sig to if (l.isGroup) {
                    // Group thumbnail: its children composited.
                    val sub = Compositor.flatten(Stack(s.width, s.height, l.children!!, -1))
                    Bitmap.createScaledBitmap(sub, (s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1), true)
                } else render(l.pixels)
                changed = true
            }
            val m = l.mask
            if (m == null) { if (maskThumbs.remove(l.id) != null) changed = true; continue }
            val ms = signature(m)
            if (maskThumbs[l.id]?.first != ms) { maskThumbs[l.id] = ms to render(m); changed = true }
        }
        val ids = s.all.map { it.id }.toSet()
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

    fun strokeBegin(tool: PaintTool, settings: BrushSettings, color: Int, cloneOffset: android.graphics.Point? = null, symmetry: Symmetry? = null, perspective: Matrix? = null) = post {
        val s = stack
        val layer = s.active ?: return@post
        if (floating != null) return@post
        if (layer.isGroup) { onMessage?.invoke("Layer groups can't be painted on. Pick a layer."); return@post }
        if (!layer.visible) { onMessage?.invoke("The active layer is hidden"); return@post }
        if (layer.lockPixels) { onMessage?.invoke("The active layer's pixels are locked"); return@post }
        if ((tool == PaintTool.CLONE || tool == PaintTool.HEAL) && cloneOffset == null) {
            onMessage?.invoke("Set a source first: tap “Set source”, then tap the spot to copy from"); return@post
        }
        s.quickMask?.let { qm ->
            // Quick mask: paint white to select, black (or the eraser) to deselect.
            val t = if (tool == PaintTool.ERASER) PaintTool.BRUSH else tool
            stroke = StrokeOp(qm, t, settings, if (tool == PaintTool.ERASER) MASK_BLACK else maskGray(color), false, null, cloneOffset, symmetry)
            strokeTarget = qm; strokeBefore = s; strokeTool = tool
            return@post
        }
        val target = layer.drawable
        val onMask = target !== layer.pixels
        // On a mask, colors become gray and the eraser paints black (hides).
        val t = if (onMask && tool == PaintTool.ERASER) PaintTool.BRUSH else tool
        val c = if (onMask) (if (tool == PaintTool.ERASER) MASK_BLACK else maskGray(color)) else color
        stroke = StrokeOp(target, t, settings, c, layer.lockAlpha && !onMask, clipSelection(s, layer), cloneOffset, symmetry, perspective)
        strokeTarget = target; strokeBefore = s; strokeTool = tool
        if (layer.text != null && !onMask) stack = dropText(s)
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
        val qm = s.quickMask
        if (qm == null && layer.isGroup) { onMessage?.invoke("Layer groups can't be painted on. Pick a layer."); return }
        if (qm == null && layer.lockPixels) { onMessage?.invoke("The active layer's pixels are locked"); return }
        val sel = if (qm == null) clipSelection(s, layer) else s.selection
        val r = Rect(0, 0, s.width, s.height)
        if (region != null && !r.intersect(region)) return
        if (sel != null && !r.intersect(sel.bounds)) return
        val target = qm ?: layer.drawable
        val onMask = target !== layer.pixels
        if (layer.text != null && !onMask) stack = dropText(stack)
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
            LinearTile.composite(session.writable(i), session.original(i), target.fill) { lc -> lc.drawBitmap(scratch, 0f, 0f, comp) }
            target.touched(i)
        }
        c.setBitmap(null)
        val changes = session.finish()
        if (changes.isNotEmpty()) record(name, s, listOf(TileDiff(target, changes)))
    }

    fun fillSelection(color: Int, name: String = "Fill") = post {
        paintRegion(name, null, 1f, LayerMode.NORMAL) { it.drawColor(color) }
    }

    fun clearSelection(name: String = "Clear") = post {
        paintRegion(name, null, 1f, LayerMode.NORMAL, eraser = true) { it.drawColor(Color.BLACK) }
    }

    /** The selected pixels of the active layer (or the visible image) and where they came from. */
    suspend fun copyRegion(merged: Boolean = false): Pair<Bitmap, Rect>? = withContext(dispatcher) {
        val s = stack
        val l = s.active ?: return@withContext null
        val b = s.selection?.bounds ?: (if (merged) Rect(0, 0, s.width, s.height) else l.pixels.contentBounds() ?: return@withContext null)
        val bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-b.left.toFloat(), -b.top.toFloat())
        if (merged) c.drawBitmap(Compositor.flatten(s, region = b), b.left.toFloat(), b.top.toFloat(), null) else Compositor.drawTiles(c, l.pixels, null, null)
        s.selection?.let { MaskOps.apply(c, it.mask, 0f, 0f) }
        clipboard = bmp; clipOffset = Rect(b)
        bmp to Rect(b)
    }

    fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, positions: FloatArray, shape: GradientShape, opacity: Float, mode: LayerMode) = post {
        val shader = when (shape) {
            GradientShape.LINEAR -> LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
            GradientShape.RADIAL -> RadialGradient(x0, y0, hypot(x1 - x0, y1 - y0).coerceAtLeast(1f), colors, positions, Shader.TileMode.CLAMP)
        }
        val p = Paint().apply { this.shader = shader; isDither = true }
        paintRegion("Gradient", null, opacity, mode) { it.drawPaint(p) }
    }

    fun bucketFill(x: Int, y: Int, color: Int, threshold: Int, wholeSelection: Boolean, sampleMerged: Boolean, opacity: Float, mode: LayerMode, pattern: Bitmap? = null) = post {
        val s = stack
        // Pattern fills tile the pattern from the image origin, like GIMP.
        val pp = pattern?.let { Paint().apply { shader = android.graphics.BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) } }
        val fill = { c: Canvas -> if (pp != null) c.drawPaint(pp) else c.drawColor(color) }
        if (wholeSelection) { paintRegion("Bucket Fill", null, opacity, mode) { fill(it) }; return@post }
        busy("Filling") {
            val src = if (sampleMerged) Compositor.flatten(s).let { b -> IntArray(s.width * s.height).also { b.getPixels(it, 0, s.width, 0, 0, s.width, s.height) } }
            else s.active?.drawable?.toPixels() ?: return@busy
            val mask = floodFill(src, s.width, s.height, x, y, threshold)
            val mb = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
            Selection.writeMaskBytes(mb, mask, s.width, s.height)
            val bounds = Selection.fromMask(mb)?.bounds ?: return@busy
            val p = Paint().apply { this.color = color }
            paintRegion("Bucket Fill", bounds, opacity, mode) { c ->
                fill(c)
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
        val px = if (merged) Compositor.flatten(s, region = Rect(x, y, x + 1, y + 1)).getPixel(0, 0)
        else { s.active?.let { Compositor.drawTiles(c, it.pixels, null, null) }; bmp.getPixel(0, 0) }
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

    private var maskSession: Pair<Long, Long>? = null

    /**
     * Combine a mask computed elsewhere (e.g. object segmentation, any resolution) with the selection.
     * Successive calls with the same nonzero [session] (refining one object) replace each other's
     * result, as one undo step, as long as nothing else changed the document in between.
     */
    fun selectFromMask(bytes: ByteArray, mw: Int, mh: Int, mode: SelectMode, name: String, session: Long = 0) = post {
        var s = stack
        val ms = maskSession
        if (session != 0L && ms != null && ms.first == session && ms.second == version) history.dropLast()?.let { s = it.before }
        val small = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        Selection.writeMaskBytes(small, bytes, mw, mh)
        val full = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
        Canvas(full).drawBitmap(small, null, Rect(0, 0, s.width, s.height), Paint().apply { isFilterBitmap = true })
        stack = s.copy(selection = Selection.combineMask(s.selection, full, mode))
        record(name, s)
        maskSession = if (session != 0L) session to version else null
    }

    private var mergedCache: Pair<Long, IntArray>? = null

    /** The visible image as sRGB pixels, cached per document version (scissors, foreground select). */
    private fun mergedPixels(s: Stack): IntArray {
        mergedCache?.let { (v, px) -> if (v == version && px.size == s.width * s.height) return px }
        val b = Compositor.flatten(s)
        val px = IntArray(s.width * s.height).also { b.getPixels(it, 0, s.width, 0, 0, s.width, s.height) }
        mergedCache = version to px
        return px
    }

    /** Intelligent Scissors: the edge-following path between two points (x,y pairs). */
    suspend fun scissorsSegment(ax: Float, ay: Float, bx: Float, by: Float): FloatArray = withContext(dispatcher) {
        val s = stack
        Scissors.liveWire(mergedPixels(s), s.width, s.height, ax.toInt(), ay.toInt(), bx.toInt(), by.toInt())
    }

    private fun transformMask(mk: TiledBitmap, m: Matrix, w: Int, h: Int): TiledBitmap {
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (mk.fill != 0) dst.eraseColor(mk.fill)
        Canvas(dst).drawBitmap(mk.toBitmap(), m, Paint().apply { isFilterBitmap = true })
        return TiledBitmap.fromBitmap(dst, fill = mk.fill)
    }

    // ---------------------------------------------------------------- non-destructive effects

    /** Ends a filter preview by adding the filter as a layer effect instead of changing pixels. */
    fun filterAsEffect(e: LayerEffect) = post {
        previewJob?.cancel(); proxy = null; preview = null
        val s = stack
        val l = s.find(filterLayer) ?: return@post
        if (filterOnMask) { onMessage?.invoke("Effects apply to layer pixels, not masks"); invalidate(); return@post }
        stack = s.replace(l.copy(effects = l.effects + e))
        record("Add ${e.name} Effect", s)
    }

    /** Adds [e] as an effect to every layer (not groups), as one undo step. */
    fun addEffectToAll(e: LayerEffect) = post {
        previewJob?.cancel(); proxy = null; preview = null
        val s = stack
        var next = s
        for (l in s.all) if (!l.isGroup) next = next.replace(l.copy(effects = l.effects + e))
        stack = next
        record("Add ${e.name} Effect (All Layers)", s)
    }

    /** Applies [f] to the pixels of every unlocked layer (within the selection), as one undo step. */
    fun filterApplyAll(f: Filter) = post {
        previewJob?.cancel(); proxy = null
        val s = stack
        busy(f.name) {
            val diffs = ArrayList<TileDiff>()
            var next = s
            for (l in s.all) {
                if (l.isGroup || l.lockPixels) continue
                val out = withContext(Dispatchers.Default) { applyFilterBanded(l.pixels, f, clipSelection(s, l)?.mask) }
                val changes = HashMap<Int, Pair<Bitmap?, Bitmap?>>()
                for (i in out.tiles.indices) { val a = l.pixels.tiles[i]; val b = out.tiles[i]; if (a !== b) changes[i] = a to b; l.pixels.tiles[i] = b }
                diffs.add(TileDiff(l.pixels, changes))
                // Filtered text is pixels now.
                if (l.text != null) next = next.replace(next.find(l.id)!!.copy(text = null))
            }
            preview = null
            stack = next
            record("${f.name} (All Layers)", s, diffs)
        }
    }

    fun setEffects(layerId: Long, name: String, f: (List<LayerEffect>) -> List<LayerEffect>) = post {
        val s = stack
        val l = s.find(layerId) ?: return@post
        stack = s.replace(l.copy(effects = f(l.effects)))
        record(name, s)
    }

    /** Bakes the effects into the pixels (GIMP's "Merge Filters"). */
    fun mergeEffects(layerId: Long) = post {
        val s = stack
        val l = s.find(layerId) ?: return@post
        if (l.effects.isEmpty()) return@post
        busy("Merging effects") {
            val out = withContext(Dispatchers.Default) { Effects.pixelsSync(l) }
            stack = s.replace(l.copy(effects = emptyList()))
            replaceLayerPixels(s, l, out.copy(), "Merge Effects")
        }
    }

    // ---------------------------------------------------------------- layer boundary

    /** The selection, intersected with the layer's boundary when it's smaller than the canvas. */
    private fun clipSelection(s: Stack, l: Layer): Selection? {
        val b = l.bounds ?: return s.selection
        val p = Path().apply { addRect(RectF(b), Path.Direction.CW) }
        return Selection.combine(s.selection, p, s.width, s.height, if (s.selection == null) SelectMode.REPLACE else SelectMode.INTERSECT, false, 0f)
    }

    /** Layer ▸ Layer Boundary Size: clip the layer to [r] (image coordinates). */
    fun setLayerBoundary(r: Rect) = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.isGroup) return@post
        val canvas = Rect(0, 0, s.width, s.height)
        val b = Rect(r).takeIf { !it.isEmpty } ?: return@post
        val full = b.contains(canvas)
        val outside = l.outside.filter { (k, _) -> Rect.intersects(b, Rect(LayerExtent.cx(k) * TILE, LayerExtent.cy(k) * TILE, LayerExtent.cx(k) * TILE + TILE, LayerExtent.cy(k) * TILE + TILE)) }
        stack = s.replace(l.copy(bounds = if (full) null else b, outside = outside))
        if (full) record("Layer Boundary Size", s) else replaceLayerPixels(s, l, LayerExtent.clipTo(l.pixels, b), "Layer Boundary Size")
    }

    /** Layer ▸ Layer to Image Size: the layer becomes exactly canvas-sized (off-canvas content is dropped). */
    fun layerToImageSize() = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.bounds == null && l.outside.isEmpty()) return@post
        stack = s.replace(l.copy(bounds = null, outside = emptyMap()))
        record("Layer to Image Size", s)
    }

    /** Layer ▸ Crop to Content: the boundary hugs the layer's pixels. */
    fun cropLayerToContent() {
        val l = stack.active ?: return
        val b = LayerExtent.bounds(l) ?: return
        setLayerBoundary(b)
    }

    fun setLayerFlags(id: Long, lockPosition: Boolean? = null, linked: Boolean? = null) = post {
        val s = stack
        val l = s.find(id) ?: return@post
        stack = s.replace(l.copy(lockPosition = lockPosition ?: l.lockPosition, linked = linked ?: l.linked))
        publish()
    }

    // ---------------------------------------------------------------- saved channels

    /** Select ▸ Save to Channel. */
    fun saveSelectionToChannel() = post {
        val s = stack
        val sel = s.selection ?: run { onMessage?.invoke("Make a selection first"); return@post }
        val b = sel.bytes()
        val px = IntArray(b.size) { val v = b[it].toInt() and 0xFF; (0xFF shl 24) or (v * 0x010101) }
        val names = s.channels.map { it.name }.toSet()
        var n = 1; while ("Selection mask copy${if (n > 1) " #$n" else ""}" in names) n++
        val ch = SavedChannel(name = "Selection mask copy${if (n > 1) " #$n" else ""}", mask = TiledBitmap.fromPixels(px, s.width, s.height, MASK_BLACK))
        stack = s.copy(channels = listOf(ch) + s.channels)
        record("Save to Channel", s)
    }

    /** Channel to selection, combined with the current one like any selection tool. */
    fun channelToSelection(id: Long, mode: SelectMode) = post {
        val s = stack
        val ch = s.channels.firstOrNull { it.id == id } ?: return@post
        val px = ch.mask.toPixels()
        val mb = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ALPHA_8)
        Selection.writeMaskBytes(mb, ByteArray(px.size) { (px[it] and 0xFF).toByte() }, s.width, s.height)
        stack = s.copy(selection = Selection.combineMask(s.selection, mb, mode))
        record("Channel to Selection", s)
    }

    fun updateChannel(id: Long, name: String, f: (SavedChannel) -> SavedChannel?) = post {
        val s = stack
        stack = s.copy(channels = s.channels.mapNotNull { if (it.id == id) f(it) else it })
        record(name, s)
    }

    // ---------------------------------------------------------------- guides

    fun addGuide(g: Guide) = post { val s = stack; stack = s.copy(guides = s.guides + g); record("Add Guide", s) }
    fun moveGuide(index: Int, g: Guide?) = post {
        val s = stack
        if (index !in s.guides.indices) return@post
        stack = s.copy(guides = s.guides.toMutableList().apply { if (g == null) removeAt(index) else set(index, g) })
        record(if (g == null) "Remove Guide" else "Move Guide", s)
    }
    fun clearGuides() = post { val s = stack; if (s.guides.isEmpty()) return@post; stack = s.copy(guides = emptyList()); record("Remove All Guides", s) }

    /** Toggle quick mask: the selection becomes a paintable channel (shown as a red overlay) and back. */
    fun quickMaskToggle() = post {
        val s = stack
        val qm = s.quickMask
        if (qm == null) {
            val sel = s.selection
            val m = if (sel == null) TiledBitmap(s.width, s.height, MASK_BLACK)
            else sel.bytes().let { b -> TiledBitmap.fromPixels(IntArray(b.size) { val v = b[it].toInt() and 0xFF; (0xFF shl 24) or (v * 0x010101) }, s.width, s.height, MASK_BLACK) }
            stack = s.copy(quickMask = m, selection = null)
            onMessage?.invoke("Quick mask: paint white to select, black to deselect")
        } else {
            val px = qm.toPixels()
            stack = s.copy(quickMask = null, selection = Selection.fromBytes(ByteArray(px.size) { (px[it] and 0xFF).toByte() }, s.width, s.height))
        }
        record("Quick Mask", s)
    }

    /** Select a band of [radius] px around the selection edge (Select ▸ Border). */
    fun selectBorder(radius: Int) = post {
        val s = stack
        val sel = s.selection ?: return@post
        busy("Border") {
            val b = Selection.blurBytes(sel.bytes(), s.width, s.height, radius * 2)
            // Grown minus shrunk: inside the blur's transition zone.
            for (i in b.indices) {
                val v = b[i].toInt() and 0xFF
                b[i] = (if (v in 9..246) 255 else 0).toByte()
            }
            stack = s.copy(selection = Selection.fromBytes(b, s.width, s.height)); record("Border", s)
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
        stack = s.insertAbove(s.activeId, l).copy(activeId = l.id)
        record("New Layer", s)
    }

    private fun uniqueName(base: String): String {
        val names = stack.all.map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base #$i" in names) i++
        return "$base #$i"
    }

    fun duplicateLayer() = post {
        val s = stack
        val l = s.active ?: return@post
        fun deep(x: Layer): Layer = x.copy(id = newId(), pixels = x.pixels.copy(), mask = x.mask?.copy(), children = x.children?.map { deep(it) })
        val copy = deep(l).copy(name = uniqueName("${l.name} copy"))
        stack = s.insertAbove(l.id, copy).copy(activeId = copy.id)
        record("Duplicate Layer", s)
    }

    fun deleteLayer(id: Long) = post {
        val s = stack
        val removed = s.remove(id)
        val leaves = removed.all.filter { !it.isGroup }
        if (leaves.isEmpty()) { onMessage?.invoke("An image needs at least one layer"); return@post }
        val newActive = if (removed.find(s.activeId) != null) s.activeId else leaves.last().id
        stack = removed.copy(activeId = newActive)
        record("Delete Layer", s)
    }

    fun moveLayer(id: Long, delta: Int) = post {
        val s = stack
        val (parent, list) = s.siblingsOf(id) ?: return@post
        val idx = list.indexOfFirst { it.id == id }
        val to = (idx + delta).coerceIn(0, list.size - 1)
        if (to == idx) return@post
        stack = s.withSiblings(parent, list.toMutableList().apply { add(to, removeAt(idx)) })
        record(if (delta > 0) "Raise Layer" else "Lower Layer", s)
    }

    private var propsBefore: Stack? = null

    /** Change layer properties. With [commit] = false (e.g. while dragging a slider) no history entry is created yet. */
    fun setLayer(id: Long, name: String, commit: Boolean = true, change: (Layer) -> Layer) = post {
        val s = stack
        val l = s.find(id) ?: return@post
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
        val (parent, list) = s.siblingsOf(id) ?: return@post
        val idx = list.indexOfFirst { it.id == id }
        if (idx <= 0) { onMessage?.invoke("There is no layer below to merge into"); return@post }
        val top = list[idx]; val bottom = list[idx - 1]
        busy("Merging") {
            val sub = Stack(s.width, s.height, listOf(bottom.copy(opacity = 1f, mode = LayerMode.NORMAL, visible = true), top), top.id)
            val bmp = Compositor.flatten(sub)
            val merged = Layer(name = bottom.name, pixels = TiledBitmap.fromBitmap(bmp), opacity = bottom.opacity, mode = bottom.mode, visible = bottom.visible)
            stack = s.withSiblings(parent, list.toMutableList().apply { removeAt(idx); set(idx - 1, merged) }).copy(activeId = merged.id)
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

    /**
     * Runs [op] (e.g. a neural network, off this thread) on the active layer's pixels as a
     * canvas-sized bitmap with the selection mask (0…255 per pixel, null = everything) and a progress
     * callback, and puts its result back as one undo step: within the selection when
     * [clipToSelection], else as returned.
     */
    fun processActive(name: String, clipToSelection: Boolean = true, op: suspend (Bitmap, ByteArray?, (Float) -> Unit) -> Bitmap) = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.isGroup) { onMessage?.invoke("This works on layers, not groups. Pick a layer."); return@post }
        if (l.drawable !== l.pixels) { onMessage?.invoke("This works on the layer, not its mask"); return@post }
        if (l.lockPixels) { onMessage?.invoke("The active layer's pixels are locked"); return@post }
        val w = s.width; val h = s.height
        busy(name, cancellable = true) {
            val src = l.pixels.toBitmap()
            val sel = clipSelection(s, l)?.let { Selection.maskBytes(it.mask, w, h) }
            val out = withContext(Dispatchers.Default) {
                val res = op(src, sel) { p -> _progress.value = p }
                val px = IntArray(w * h).also { res.getPixels(it, 0, w, 0, 0, w, h) }
                if (clipToSelection && sel != null) maskBlend(IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }, px, sel, w, h) else px
            }
            if (l.text != null) stack = dropText(stack)
            replaceTiles(s, l.pixels, TiledBitmap.fromPixels(out, w, h, l.pixels.fill), name)
        }
    }

    /**
     * Enlarges the image [factor] times. Plain pixel layers go through [upscaler] (a neural
     * network); masks, selection, text and layers with content off the canvas are scaled smoothly.
     */
    fun upscaleImage(factor: Int, name: String, upscaler: suspend (Bitmap, (Float) -> Unit) -> Bitmap) = post {
        val s = stack
        val nw = s.width * factor; val nh = s.height * factor
        val leaves = s.all.filter { !it.isGroup && it.text == null && it.outside.isEmpty() && it.bounds == null && !it.pixels.isEmpty() }
        busy(name, cancellable = true) {
            val replaced = HashMap<Long, TiledBitmap>()
            leaves.forEachIndexed { i, l ->
                val big = withContext(Dispatchers.Default) {
                    upscaler(l.pixels.toBitmap()) { p -> _progress.value = (i + p) / leaves.size }
                }
                replaced[l.id] = TiledBitmap.fromBitmap(big)
                big.recycle()
            }
            if (stack !== s) { onMessage?.invoke("The image changed while upscaling; try again"); return@busy }
            transformAll(name, nw, nh, Matrix().apply { setScale(factor.toFloat(), factor.toFloat()) }, replace = replaced)
        }
    }

    private fun transformAll(name: String, newW: Int, newH: Int, m: Matrix, cropSelection: Boolean = true, replace: Map<Long, TiledBitmap> = emptyMap()) {
        val s = stack
        val p = Paint().apply { isFilterBitmap = true }
        fun xf(t: TiledBitmap): TiledBitmap {
            val dst = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            if (t.fill != 0) dst.eraseColor(t.fill)
            Canvas(dst).drawBitmap(t.toBitmap(), m, p)
            return TiledBitmap.fromBitmap(dst, fill = t.fill)
        }
        val layers = Stack.mapTree(s.layers) { l ->
            if (l.isGroup) l.copy(pixels = TiledBitmap(newW, newH), mask = l.mask?.let { xf(it) })
            else if (l.id in replace) l.copy(pixels = replace.getValue(l.id), mask = l.mask?.let { xf(it) })
            else {
                // Content off the canvas comes along (a canvas resize can bring it back into view).
                val (px, outside) = LayerExtent.transform(l, m, newW, newH)
                val bounds = l.bounds?.let { b -> val r = RectF(b); m.mapRect(r); Rect().also { r.round(it) } }?.takeIf { !it.contains(Rect(0, 0, newW, newH)) }
                l.copy(pixels = bounds?.let { LayerExtent.clipTo(px, it) } ?: px, outside = outside, bounds = bounds, mask = l.mask?.let { xf(it) })
            }
        }
        val sel = if (!cropSelection) null else s.selection?.let { sel ->
            val dst = Bitmap.createBitmap(newW, newH, Bitmap.Config.ALPHA_8)
            Canvas(dst).drawBitmap(sel.mask, m, p)
            Selection.fromMask(dst)
        }
        // Guides follow the image (a quarter turn makes vertical guides horizontal).
        val guides = s.guides.mapNotNull { g ->
            val pts = if (g.vertical) floatArrayOf(g.pos.toFloat(), 0f, g.pos.toFloat(), 1f) else floatArrayOf(0f, g.pos.toFloat(), 1f, g.pos.toFloat())
            m.mapPoints(pts)
            val vertical = kotlin.math.abs(pts[0] - pts[2]) < 0.01f
            val pos = (if (vertical) pts[0] else pts[1]).roundToInt()
            if (pos < 0 || pos > (if (vertical) newW else newH)) null else Guide(vertical, pos)
        }
        val channels = s.channels.map { c -> c.copy(mask = transformMask(c.mask, m, newW, newH)) }
        stack = Stack(newW, newH, layers, s.activeId, sel, guides = guides, channels = channels)
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

    // ---------------------------------------------------------------- groups

    /** New empty group above the active layer. */
    fun addGroup() = post {
        val s = stack
        val g = Layer(name = uniqueName("Layer Group"), pixels = TiledBitmap(s.width, s.height), children = emptyList())
        stack = s.insertAbove(s.activeId, g).copy(activeId = g.id)
        record("New Layer Group", s)
    }

    /** Wrap the active layer in a new group. */
    fun groupActive() = post {
        val s = stack
        val l = s.active ?: return@post
        val g = Layer(name = uniqueName("Layer Group"), pixels = TiledBitmap(s.width, s.height), children = listOf(l))
        val (parent, list) = s.siblingsOf(l.id) ?: return@post
        stack = s.withSiblings(parent, list.map { if (it.id == l.id) g else it })
        record("Group Layer", s)
    }

    /** Move a layer into the group directly above it (as its bottom-most child). */
    fun moveIntoGroupAbove(id: Long) = post {
        val s = stack
        val (parent, list) = s.siblingsOf(id) ?: return@post
        val idx = list.indexOfFirst { it.id == id }
        val target = list.getOrNull(idx + 1)?.takeIf { it.isGroup } ?: run { onMessage?.invoke("There is no group directly above"); return@post }
        val l = list[idx]
        val newList = list.toMutableList().apply { removeAt(idx) }.map { if (it.id == target.id) it.copy(children = listOf(l) + it.children!!, expanded = true) else it }
        stack = s.withSiblings(parent, newList)
        record("Move into Group", s)
    }

    /** Move a layer out of its group, placing it right above the group. */
    fun moveOutOfGroup(id: Long) = post {
        val s = stack
        val (parent, _) = s.siblingsOf(id) ?: return@post
        if (parent == null) return@post
        val l = s.find(id) ?: return@post
        stack = s.remove(id).insertAbove(parent.id, l)
        record("Move out of Group", s)
    }

    fun toggleExpanded(id: Long) = post {
        val s = stack
        val l = s.find(id) ?: return@post
        stack = s.replace(l.copy(expanded = !l.expanded))
        publish()
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
        val l = s.find(id) ?: return@post
        stack = s.replace(l.copy(editMask = edit && l.mask != null)).copy(activeId = id)
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
    private class Proxy(val px: IntArray, val w: Int, val h: Int, val k: Float, val mask: ByteArray?, val others: List<Pair<Long, IntArray>> = emptyList())
    private var proxy: Proxy? = null
    private var filterLayer: Long = -1
    private var filterOnMask = false
    private val previewRequests = kotlinx.coroutines.flow.MutableStateFlow<Filter?>(null)
    private var previewJob: Job? = null

    /**
     * Prepare a preview proxy (≤ 2048 px) of the active drawable and start the preview worker;
     * with [allLayers] the other layers' pixels are previewed too.
     */
    fun filterBegin(allLayers: Boolean = false): Job {
        // Reset synchronously so a preview requested right after this call isn't lost.
        previewRequests.value = null
        return filterBeginInternal(allLayers)
    }

    private fun filterBeginInternal(allLayers: Boolean) = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.isGroup) { onMessage?.invoke("Filters apply to layers, not groups. Pick a layer."); return@post }
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
        fun proxyOf(t: TiledBitmap): IntArray {
            val b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val cc = Canvas(b); cc.scale(pw / s.width.toFloat(), ph / s.height.toFloat())
            Compositor.drawTiles(cc, t, null, Paint().apply { isFilterBitmap = true }, pad = 1f / k)
            return IntArray(pw * ph).also { b.getPixels(it, 0, pw, 0, 0, pw, ph) }
        }
        val others = if (!allLayers) emptyList() else s.all.filter { !it.isGroup && it.id != l.id && !it.lockPixels }.map { it.id to proxyOf(it.pixels) }
        proxy = Proxy(px, pw, ph, k, mask, others)
        previewJob?.cancel()
        // One preview at a time, always for the latest request: slider drags never pile up work.
        previewJob = CoroutineScope(Dispatchers.Default).launch {
            previewRequests.collect { f ->
                if (f == null) return@collect
                val p = proxy ?: return@collect
                val sf = f.scaled(p.k)
                val out = maskBlend(p.px, sf.apply(p.px, p.w, p.h), p.mask, p.w, p.h)
                val tiles = TiledBitmap.fromPixels(out, p.w, p.h, if (filterOnMask) MASK_WHITE else 0)
                val others = p.others.associate { (id, opx) -> id to TiledBitmap.fromPixels(maskBlend(opx, sf.apply(opx, p.w, p.h), p.mask, p.w, p.h), p.w, p.h) }
                // Show every finished preview: requests are handled in order, so it's never older than
                // what's on screen. (Waiting for the latest request meant nothing updated while a
                // slider was being dragged.)
                withContext(dispatcher) {
                    if (proxy === p) {
                        preview = Preview(filterLayer, filterOnMask, tiles, 1f / p.k, others)
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
        val l = s.find(filterLayer) ?: return@post
        val target = if (filterOnMask && l.mask != null) l.mask else l.pixels
        busy(f.name) {
            // Process in horizontal bands (overlapping by the filter's reach) so even very large
            // images never need more than a few band-sized arrays on the Java heap.
            val out = withContext(Dispatchers.Default) { applyFilterBanded(target, f, clipSelection(s, l)?.mask) }
            preview = null
            if (!filterOnMask && l.text != null) stack = dropText(stack)
            replaceTiles(s, target, out, f.name)
        }
    }

    fun filterCancel() = post {
        previewJob?.cancel()
        proxy = null; preview = null
        invalidate()
    }

    // ---------------------------------------------------------------- floating (insert / paste / transform)

    /** Place [bmp] for positioning: at [at] (doc px) unscaled, or fitted and centered. */
    fun placeFloating(bmp: Bitmap, name: String, at: android.graphics.Point? = null, text: TextSpec? = null, matrix: Matrix? = null) = post {
        val s = stack
        commitFloatingNow()
        val m = Matrix()
        if (matrix != null) m.set(matrix)
        else if (at != null) m.setTranslate(at.x.toFloat(), at.y.toFloat()) else {
            // Fit into 70% of the canvas, centered.
            val scale = minOf(1f, s.width * 0.7f / bmp.width, s.height * 0.7f / bmp.height)
            m.setScale(scale, scale)
            m.postTranslate((s.width - bmp.width * scale) / 2f, (s.height - bmp.height * scale) / 2f)
        }
        floating = Floating(bmp, m, null, name, text = text)
        publish(); invalidate()
    }

    // ---------------------------------------------------------------- text layers

    /** Text on a path goes straight in as a layer (nothing to position). */
    fun addTextLayer(spec: TextSpec) = post {
        val s = stack
        val l = Layer(name = uniqueName(textName(spec)), pixels = spec.renderLayer(s.width, s.height), text = spec)
        stack = s.insertAbove(s.activeId, l).copy(activeId = l.id)
        record("Add Text Layer", s)
    }

    /** Change an existing text layer's text, font or color; it is re-rendered in place. */
    fun updateTextLayer(id: Long, spec: TextSpec) = post {
        val s = stack
        val l = s.find(id) ?: return@post
        busy("Rendering text") {
            stack = s.replace(l.copy(text = spec, name = textName(spec), pixels = spec.renderLayer(s.width, s.height)))
            record("Edit Text", s, extraBytes = s.width.toLong() * s.height * 4)
        }
    }

    private fun textName(spec: TextSpec) = spec.text.lineSequence().first().take(24).ifBlank { "Text" }

    /** Painting or filtering a text layer makes it a normal layer (GIMP asks; we just do it). */
    private fun dropText(s: Stack): Stack {
        val l = s.active ?: return s
        if (l.text == null) return s
        onMessage?.invoke("“${l.name}” is now a pixel layer")
        return s.replace(l.copy(text = null))
    }

    /** Transform tool: lift the selection (or the whole active layer) into a floating bitmap. */
    /**
     * Direct transforms (each drag applied at once): the pixels as first lifted and the transform so
     * far, so a series of moves, rotations and scales resamples the original once per drag instead of
     * blurring a little more each time. Valid while nothing else changed the document.
     */
    private class TransformSession(
        val layerId: Long, val bitmap: Bitmap, val remainder: TiledBitmap?, val selection: Selection?,
        val matrix: Matrix, val name: String, var version: Long,
    )
    private var session: TransformSession? = null

    /** The current direct-transform frame of the active layer: corners of its original pixels, in doc coordinates. */
    fun transformFrame(): FloatArray? {
        val ss = session?.takeIf { it.version == version && it.layerId == stack.activeId && floating == null } ?: return null
        val w = ss.bitmap.width.toFloat(); val h = ss.bitmap.height.toFloat()
        return floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h).also { ss.matrix.mapPoints(it) }
    }

    /**
     * Lifts the active layer (or the selected part of it) for transforming; [wholeImage]: the whole
     * image instead. With [continueSession], a direct transform continues from the original pixels.
     */
    fun liftActive(wholeImage: Boolean = false, continueSession: Boolean = false) = post {
        if (floating != null) return@post
        if (wholeImage) { session = null; liftImage(); return@post }
        val ss = session
        if (continueSession && ss != null && ss.version == version && ss.layerId == stack.activeId) {
            floating = Floating(ss.bitmap, Matrix(ss.matrix), ss.layerId, ss.name, remainder = ss.remainder)
            publish(); invalidate(); return@post
        }
        val sel = stack.selection
        liftNow()
        val f = floating
        val l = stack.active
        session = if (continueSession && f != null && f.targetLayerId != null && l?.text == null) TransformSession(f.targetLayerId, f.bitmap, f.remainder, sel, Matrix(f.matrix), f.name, -1) else null
    }

    private fun liftImage() {
        if (floating != null) return
        val s = stack
        floating = Floating(Compositor.flatten(s), Matrix(), null, "Image", wholeImage = true)
        publish(); invalidate()
    }

    private fun liftNow() {
        if (floating != null) return
        val s = stack
        val l = s.active ?: return
        if (l.isGroup) { onMessage?.invoke("Transform a layer, not a group"); return }
        if (l.lockPosition) { onMessage?.invoke("“${l.name}” has its position locked"); return }
        val sel = s.selection
        if (sel == null) {
            // Lift just the layer's content, so the handles hug what's actually on it.
            if (l.lockPosition) { onMessage?.invoke("“${l.name}” has its position locked"); return }
            val b = LayerExtent.bounds(l) ?: run { onMessage?.invoke("“${l.name}” is empty"); return }
            val lifted = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
            val c = Canvas(lifted); c.translate(-b.left.toFloat(), -b.top.toFloat())
            LayerExtent.draw(c, l)
            floating = Floating(lifted, Matrix().apply { setTranslate(b.left.toFloat(), b.top.toFloat()) }, l.id, l.name, remainder = null)
        } else {
            val full = l.pixels.toBitmap()
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

    // ---------------------------------------------------------------- mesh deformation (Warp, Cage)

    /** The lifted pixels deformed by moving the vertices of a grid laid over them. */
    private class Mesh(val src: Bitmap, val gw: Int, val gh: Int, val rest: FloatArray, val verts: FloatArray, val target: Long?, val name: String, val remainder: TiledBitmap?)
    private var mesh: Mesh? = null
    /** Cage points (doc coordinates): where they were placed, and where they are now. */
    @Volatile var cageRest: List<android.graphics.PointF> = emptyList(); private set
    @Volatile var cageNow: List<android.graphics.PointF> = emptyList(); private set
    private var cageWeights: Array<FloatArray?>? = null
    private val meshSeq = java.util.concurrent.atomic.AtomicInteger()

    fun meshBegin(cage: Boolean) = post {
        floating = null
        liftNow()
        val f = floating ?: return@post
        val v = FloatArray(9); f.matrix.getValues(v)
        val ox = v[2]; val oy = v[5]
        val w = f.bitmap.width; val h = f.bitmap.height
        val gw = (w / 24).coerceIn(4, if (cage) 48 else 32); val gh = (h / 24).coerceIn(4, if (cage) 48 else 32)
        val rest = FloatArray((gw + 1) * (gh + 1) * 2)
        for (j in 0..gh) for (i in 0..gw) { val k = (j * (gw + 1) + i) * 2; rest[k] = ox + w * i / gw.toFloat(); rest[k + 1] = oy + h * j / gh.toFloat() }
        mesh = Mesh(f.bitmap, gw, gh, rest, rest.copyOf(), f.targetLayerId, f.name, f.remainder)
        cageRest = emptyList(); cageNow = emptyList(); cageWeights = null
    }

    private fun meshRender() {
        val m = mesh ?: return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in m.verts.indices step 2) { minX = minOf(minX, m.verts[k]); maxX = maxOf(maxX, m.verts[k]); minY = minOf(minY, m.verts[k + 1]); maxY = maxOf(maxY, m.verts[k + 1]) }
        val ow = (maxX - minX).toInt().coerceIn(1, 16384); val oh = (maxY - minY).toInt().coerceIn(1, 16384)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.translate(-minX, -minY)
        c.drawBitmapMesh(m.src, m.gw, m.gh, m.verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        floating = Floating(out, Matrix().apply { setTranslate(minX, minY) }, m.target, m.name, remainder = m.remainder)
        invalidate()
    }

    /** Re-render only for the newest request, so fast finger movement never queues up work. */
    private fun meshRenderLatest(seq: Int) { if (seq == meshSeq.get()) meshRender() }

    /** Warp: push vertices near (x, y) by (dx, dy), fading out over [radius]. */
    fun meshPush(x: Float, y: Float, dx: Float, dy: Float, radius: Float, strength: Float) {
        val seq = meshSeq.incrementAndGet()
        post {
            val m = mesh ?: return@post
            for (k in m.verts.indices step 2) {
                val d = kotlin.math.hypot(m.verts[k] - x, m.verts[k + 1] - y)
                if (d >= radius) continue
                val t = 1 - d / radius
                val wgt = t * t * strength
                m.verts[k] += dx * wgt; m.verts[k + 1] += dy * wgt
            }
            meshRenderLatest(seq)
        }
    }

    fun cageAdd(x: Float, y: Float) = post {
        if (mesh == null) return@post
        cageRest = cageRest + android.graphics.PointF(x, y); cageNow = cageNow + android.graphics.PointF(x, y)
        cageWeights = null
        invalidate()
    }

    /** Cage: move point [i]; everything inside the cage follows (mean value coordinates). */
    fun cageMove(i: Int, x: Float, y: Float) {
        val seq = meshSeq.incrementAndGet()
        post {
            val m = mesh ?: return@post
            if (i !in cageNow.indices || cageRest.size < 3) return@post
            cageNow = cageNow.toMutableList().also { it[i] = android.graphics.PointF(x, y) }
            val wts = cageWeights ?: Array(m.rest.size / 2) { k -> meanValue(cageRest, m.rest[k * 2], m.rest[k * 2 + 1]) }.also { cageWeights = it }
            for (k in wts.indices) {
                val wv = wts[k] ?: continue
                var nx = 0f; var ny = 0f
                for (c in wv.indices) { nx += wv[c] * cageNow[c].x; ny += wv[c] * cageNow[c].y }
                m.verts[k * 2] = nx; m.verts[k * 2 + 1] = ny
            }
            meshRenderLatest(seq)
        }
    }

    /** Mean value coordinates of (x, y) in [poly], or null when outside (outside doesn't move). */
    private fun meanValue(poly: List<android.graphics.PointF>, x: Float, y: Float): FloatArray? {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        if (!inside) return null
        val n = poly.size
        val w = FloatArray(n)
        val dx = FloatArray(n) { poly[it].x - x }; val dy = FloatArray(n) { poly[it].y - y }
        val r = FloatArray(n) { kotlin.math.hypot(dx[it], dy[it]).coerceAtLeast(1e-4f) }
        fun tanHalf(i: Int): Float {
            val k = (i + 1) % n
            val cross = dx[i] * dy[k] - dy[i] * dx[k]; val dot = dx[i] * dx[k] + dy[i] * dy[k]
            val ang = kotlin.math.atan2(cross, dot)
            return kotlin.math.tan(ang / 2)
        }
        var sum = 0f
        for (i in 0 until n) { w[i] = (tanHalf((i - 1 + n) % n) + tanHalf(i)) / r[i]; sum += w[i] }
        if (kotlin.math.abs(sum) < 1e-8f) return null
        for (i in 0 until n) w[i] /= sum
        return w
    }

    // ---------------------------------------------------------------- align

    /** Align the active layer's content to the canvas: h/v = 0 start, 1 center, 2 end, -1 keep. */
    fun alignLayer(h: Int, v: Int) = post {
        val s = stack
        val l = s.active ?: return@post
        if (l.isGroup) return@post
        val b = l.pixels.contentBounds() ?: return@post
        val dx = when (h) { 0 -> -b.left; 1 -> (s.width - b.width()) / 2 - b.left; 2 -> s.width - b.right; else -> 0 }
        val dy = when (v) { 0 -> -b.top; 1 -> (s.height - b.height()) / 2 - b.top; 2 -> s.height - b.bottom; else -> 0 }
        if (dx == 0 && dy == 0) return@post
        val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.translate(dx.toFloat(), dy.toFloat())
        Compositor.drawTiles(c, l.pixels, null, null)
        replaceLayerPixels(s, l, TiledBitmap.fromBitmap(out), "Align Layer")
    }

    fun commitFloating(matrix: Matrix, name: String = "Transform") = post {
        floating?.matrix?.set(matrix)
        commitFloatingNow(name)
    }

    /** Move tool: apply [dx], [dy] to the lifted layer and commit — one undo step. */
    fun commitMove(dx: Float, dy: Float) = post {
        val f = floating ?: return@post
        f.matrix.set(f.initial); f.matrix.postTranslate(dx, dy)
        if (dx == 0f && dy == 0f) { floating = null; publish(); invalidate(); return@post }
        commitFloatingNow("Move Layer")
    }

    private suspend fun commitFloatingNow(name: String = "Transform") {
        val f = floating ?: return
        mesh = null; cageRest = emptyList(); cageNow = emptyList()
        val s = stack
        if (f.wholeImage) {
            // Every layer, mask, channel and the selection get the same transform. A pure move keeps
            // the canvas; other transforms resize it to fit the result (like GIMP's "Image" mode).
            val m = Matrix(f.matrix)
            busy("Applying") {
                floating = null
                if (name == "Move Layer") transformAll("Move Image", s.width, s.height, m)
                else {
                    val r = RectF(0f, 0f, s.width.toFloat(), s.height.toFloat()); m.mapRect(r)
                    val b = Rect(); r.roundOut(b)
                    if (b.width() < 1 || b.height() < 1 || b.width().toLong() * b.height() > 100_000_000L) { onMessage?.invoke("The result would be too large"); publish(); invalidate(); return@busy }
                    m.postTranslate(-b.left.toFloat(), -b.top.toFloat())
                    transformAll("$name Image", b.width(), b.height(), m)
                }
            }
            return
        }
        busy("Applying") {
            val out = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val target = f.targetLayerId?.let { id -> s.find(id) }
            if (target != null && target.text != null && f.remainder == null) {
                // Transforming a text layer keeps it editable: compose the transform into its spec.
                val m = Matrix(f.initial); val inv = Matrix(); m.invert(inv)
                val spec = target.text.withMatrix(Matrix(target.text.matrix()).apply { postConcat(inv); postConcat(f.matrix) })
                val newSpec = if (spec.path != null) target.text else spec
                floating = null
                stack = s.replace(target.copy(text = newSpec, pixels = newSpec.renderLayer(s.width, s.height)))
                record(name, s, extraBytes = s.width.toLong() * s.height * 4)
                return@busy
            }
            if (target != null) {
                val fp = Paint().apply { isFilterBitmap = true }
                f.remainder?.let { Compositor.drawTiles(c, it, null, null) }
                c.drawBitmap(f.bitmap, f.matrix, fp)
                floating = null
                val ss = session?.takeIf { it.bitmap === f.bitmap }
                // In a direct-transform series the selection, too, is moved from where it started.
                val moved = if (ss != null) f.selectionMoved(s.copy(selection = ss.selection)) else f.selectionMoved(s)
                // What lands beyond the canvas is kept (layers can be larger than the image).
                val keepOld = f.remainder != null
                val area = RectF(0f, 0f, f.bitmap.width.toFloat(), f.bitmap.height.toFloat()).also { f.matrix.mapRect(it) }.let { val r = Rect(); it.roundOut(r); r }
                val fresh = LayerExtent.outsideTiles(area, s.width, s.height) { cc ->
                    if (keepOld) for ((k, t) in target.outside) cc.drawBitmap(t, LayerExtent.cx(k) * TILE.toFloat(), LayerExtent.cy(k) * TILE.toFloat(), null)
                    cc.drawBitmap(f.bitmap, f.matrix, fp)
                }
                val outside = (if (keepOld) target.outside else emptyMap()) + fresh
                // A layer boundary moves with the layer.
                val delta = Matrix().also { f.initial.invert(it); it.postConcat(f.matrix) }
                val bounds = target.bounds?.takeIf { !keepOld }?.let { b -> RectF(b).also { delta.mapRect(it) }.let { r -> Rect().also { r.round(it) } } } ?: target.bounds
                var next = s.copy(selection = moved).replace(target.copy(outside = outside, bounds = bounds))
                // Linked layers follow a whole-layer move or transform.
                if (target.linked && !keepOld) for (o in next.all) if (o.linked && o.id != target.id && !o.isGroup && !o.lockPosition) {
                    val (px, out2) = LayerExtent.transform(o, delta, s.width, s.height)
                    next = next.replace(o.copy(pixels = px, outside = out2, mask = o.mask?.let { transformMask(it, delta, s.width, s.height) }))
                }
                stack = next
                replaceLayerPixels(s, target, TiledBitmap.fromBitmap(out), name)
                if (ss != null) { ss.matrix.set(f.matrix); ss.version = version }
            } else {
                c.drawBitmap(f.bitmap, f.matrix, Paint().apply { isFilterBitmap = true; isAntiAlias = true })
                floating = null
                val spec = f.text?.withMatrix(f.matrix)
                val l = Layer(name = uniqueName(spec?.let { textName(it) } ?: f.name), pixels = if (spec != null) spec.renderLayer(s.width, s.height) else TiledBitmap.fromBitmap(out), text = spec)
                stack = s.insertAbove(s.activeId, l).copy(activeId = l.id)
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
        mesh = null; cageRest = emptyList(); cageNow = emptyList()
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
        if (merged) c.drawBitmap(Compositor.flatten(s), 0f, 0f, null) else Compositor.drawTiles(c, l.pixels, null, null)
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
        s.copy(layers = Stack.mapTree(s.layers) { it.copy(pixels = it.pixels.copy(), mask = it.mask?.copy()) })
    }

    fun shutdown() { scope.coroutineContext[Job]?.cancel(); executor.shutdown() }
}
