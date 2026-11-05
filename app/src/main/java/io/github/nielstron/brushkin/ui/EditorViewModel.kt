package io.github.nielstron.brushkin.ui

import android.app.Application
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.nielstron.brushkin.engine.BrushSettings
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.GradientShape
import io.github.nielstron.brushkin.engine.PaintTool
import io.github.nielstron.brushkin.engine.SelectMode
import androidx.lifecycle.viewModelScope
import io.github.nielstron.brushkin.engine.Ora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class ToolGroup(val label: String) { SELECT("Select"), PAINT("Paint"), TRANSFORM("Transform"), COLORS("Colors"), FILTER("Filters"), OTHER("Other") }

enum class Tool(val label: String, val group: ToolGroup, val paint: PaintTool? = null, /** Opens this filter (live preview panel). */ val filter: String? = null) {
    RECT_SELECT("Rectangle", ToolGroup.SELECT),
    ELLIPSE_SELECT("Ellipse", ToolGroup.SELECT),
    FREE_SELECT("Free", ToolGroup.SELECT),
    FUZZY_SELECT("Fuzzy", ToolGroup.SELECT),
    COLOR_SELECT("By Color", ToolGroup.SELECT),
    OBJECT_SELECT("Object", ToolGroup.SELECT),
    SCISSORS("Scissors", ToolGroup.SELECT),
    BRUSH("Paintbrush", ToolGroup.PAINT, PaintTool.BRUSH),
    PENCIL("Pencil", ToolGroup.PAINT, PaintTool.PENCIL),
    AIRBRUSH("Airbrush", ToolGroup.PAINT, PaintTool.AIRBRUSH),
    ERASER("Eraser", ToolGroup.PAINT, PaintTool.ERASER),
    SMUDGE("Smudge", ToolGroup.PAINT, PaintTool.SMUDGE),
    CLONE("Clone", ToolGroup.PAINT, PaintTool.CLONE),
    HEAL("Heal", ToolGroup.PAINT, PaintTool.HEAL),
    DODGE_BURN("Dodge / Burn", ToolGroup.PAINT, PaintTool.DODGE),
    BLUR_SHARPEN("Blur / Sharpen", ToolGroup.PAINT, PaintTool.BLUR),
    INK("Ink", ToolGroup.PAINT, PaintTool.BRUSH),
    MYPAINT("MyPaint Brush", ToolGroup.PAINT, PaintTool.BRUSH),
    PERSPECTIVE_CLONE("Persp. Clone", ToolGroup.PAINT, PaintTool.CLONE),
    REMOVE_OBJECT("Remove Object", ToolGroup.PAINT),
    BUCKET("Bucket Fill", ToolGroup.PAINT),
    GRADIENT("Gradient", ToolGroup.PAINT),
    // On-device models; picking one runs it (see isAction).
    DEPTH_BLUR("Depth Blur", ToolGroup.FILTER),
    UPSCALE("Upscale", ToolGroup.FILTER),
    DENOISE("Denoise", ToolGroup.FILTER),
    DEBLUR("Deblur", ToolGroup.FILTER),
    GAUSSIAN_BLUR("Gaussian Blur", ToolGroup.FILTER, filter = "Gaussian Blur"),
    UNSHARP_MASK("Unsharp Mask", ToolGroup.FILTER, filter = "Unsharp Mask"),
    NOISE_REDUCTION("Noise Reduct.", ToolGroup.FILTER, filter = "Noise Reduction"),
    MOTION_BLUR("Motion Blur", ToolGroup.FILTER, filter = "Motion Blur"),
    VIGNETTE("Vignette", ToolGroup.FILTER, filter = "Vignette"),
    DROP_SHADOW("Drop Shadow", ToolGroup.FILTER, filter = "Drop Shadow"),
    PIXELIZE("Pixelize", ToolGroup.FILTER, filter = "Pixelize"),
    RIPPLE("Ripple", ToolGroup.FILTER, filter = "Ripple"),
    WHIRL_PINCH("Whirl/Pinch", ToolGroup.FILTER, filter = "Whirl and Pinch"),
    LENS_DISTORTION("Lens Distortion", ToolGroup.FILTER, filter = "Lens Distortion"),
    RGB_NOISE("RGB Noise", ToolGroup.FILTER, filter = "RGB Noise"),
    // Color adjustments (each opens its live-preview panel).
    CURVES("Curves", ToolGroup.COLORS, filter = "Curves"),
    LEVELS("Levels", ToolGroup.COLORS, filter = "Levels"),
    BRIGHTNESS_CONTRAST("Bright./Contrast", ToolGroup.COLORS, filter = "Brightness-Contrast"),
    HUE_SATURATION("Hue/Saturation", ToolGroup.COLORS, filter = "Hue-Saturation"),
    COLOR_TO_ALPHA("Color to Alpha", ToolGroup.COLORS, filter = "Color to Alpha"),
    COLOR_BALANCE("Color Balance", ToolGroup.COLORS, filter = "Color Balance"),
    EXPOSURE("Exposure", ToolGroup.COLORS, filter = "Exposure"),
    SHADOWS_HIGHLIGHTS("Shadows/Highl.", ToolGroup.COLORS, filter = "Shadows-Highlights"),
    COLORIZE("Colorize", ToolGroup.COLORS, filter = "Colorize"),
    CHANNEL_MIXER("Channel Mixer", ToolGroup.COLORS, filter = "Channel Mixer"),
    THRESHOLD("Threshold", ToolGroup.COLORS, filter = "Threshold"),
    POSTERIZE("Posterize", ToolGroup.COLORS, filter = "Posterize"),
    /** Move and rotate: drag inside to move, outside or on the handle to rotate, then apply. */
    MOVE("Move/Rotate", ToolGroup.TRANSFORM),
    SCALE("Scale", ToolGroup.TRANSFORM),
    FLIP("Flip", ToolGroup.TRANSFORM),
    SHEAR("Shear", ToolGroup.TRANSFORM),
    PERSPECTIVE("Perspective", ToolGroup.TRANSFORM),
    TRANSFORM_3D("3D Transform", ToolGroup.TRANSFORM),
    HANDLE("Handle Transform", ToolGroup.TRANSFORM),
    WARP("Warp", ToolGroup.TRANSFORM),
    CAGE("Cage", ToolGroup.TRANSFORM),
    ALIGN("Align", ToolGroup.TRANSFORM),
    CROP("Crop", ToolGroup.TRANSFORM),
    PATHS("Paths", ToolGroup.OTHER),
    PICKER("Color Picker", ToolGroup.OTHER),
    TEXT("Text", ToolGroup.OTHER),
    MEME("Meme Text", ToolGroup.OTHER);

    val isSelect get() = group == ToolGroup.SELECT
    /** Taps pick an object (MobileSAM). */
    val picksObjects get() = this == OBJECT_SELECT || this == REMOVE_OBJECT
    /** One-shot commands listed with the tools: picking one runs it and keeps the current tool. */
    val isAction get() = this == DEPTH_BLUR || this == UPSCALE || this == DENOISE || this == DEBLUR || filter != null
    /** Tools that work on a lifted copy of the layer (applied or cancelled as a whole). */
    val liftsLayer get() = this == MOVE || this == SCALE || this == SHEAR || this == PERSPECTIVE ||
        this == TRANSFORM_3D || this == HANDLE || this == WARP || this == CAGE
    /** Transform tools with handles and Apply/Cancel (everything that lifts except Move). */
    val confirmTransform get() = liftsLayer && !directTransform
    /** One drag finishes the edit, so it's applied right away (undo takes it back); no Apply step. */
    val directTransform get() = this == MOVE || this == SCALE || this == SHEAR || this == TRANSFORM_3D
}

data class ExportedImage(val uri: android.net.Uri, val mime: String)

enum class Sheet { NONE, TOOLS, OPTIONS, COLOR, LAYERS, INSERT, HISTORY }

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Engine()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    var startupLoading by mutableStateOf(true)
    var startupStarted = false
    var importStatus by mutableStateOf<String?>(null)
    var exportStatus by mutableStateOf<String?>(null)
    var exportedImage by mutableStateOf<ExportedImage?>(null)

    var tool by mutableStateOf(Tool.BRUSH)
    var lastPaintTool by mutableStateOf(Tool.BRUSH)
    var sheet by mutableStateOf(Sheet.NONE)
    var showMenu by mutableStateOf(false)
    var dialog by mutableStateOf<String?>(null)

    var fg by mutableStateOf(0xFF1E5F74.toInt())
    var bg by mutableStateOf(Color.WHITE)
    val recentColors = mutableStateListOf(0xFF1E5F74.toInt(), 0xFFF2B66D.toInt(), 0xFFC46A5A.toInt(), 0xFF1A1A24.toInt())

    private val brushSettings = mutableStateOf(
        mapOf(
            Tool.BRUSH to BrushSettings(size = 24f, hardness = 0.3f),
            Tool.PENCIL to BrushSettings(size = 4f, hardness = 1f, pressureSize = false, pressureOpacity = false, smoothing = 0f),
            Tool.AIRBRUSH to BrushSettings(size = 80f, hardness = 0f, pressureSize = false),
            Tool.ERASER to BrushSettings(size = 40f, hardness = 0.6f, pressureOpacity = false),
            Tool.SMUDGE to BrushSettings(size = 40f, hardness = 0.2f, opacity = 0.8f, pressureSize = false),
            Tool.CLONE to BrushSettings(size = 60f, hardness = 0.5f, pressureSize = false, pressureOpacity = false),
            Tool.HEAL to BrushSettings(size = 60f, hardness = 0.3f, pressureSize = false, pressureOpacity = false),
            Tool.DODGE_BURN to BrushSettings(size = 90f, hardness = 0f, opacity = 0.6f, rate = 0.5f, pressureSize = false),
            Tool.BLUR_SHARPEN to BrushSettings(size = 70f, hardness = 0.2f, rate = 0.5f, pressureSize = false),
            // GIMP's Ink: a hard elliptical blob that thins with speed and follows the pen.
            Tool.INK to BrushSettings(size = 10f, hardness = 1f, shape = io.github.nielstron.brushkin.engine.TipShape.FLAT, aspect = 0.55f, angle = 30f, spacing = 0.04f,
                pressureOpacity = false, smoothing = 0.5f, velocitySize = -0.6f, tiltAngle = true),
            Tool.MYPAINT to io.github.nielstron.brushkin.engine.GimpFormats.readMyb(io.github.nielstron.brushkin.engine.ResourceStore.BUILTIN_MYPAINT[0].second, "Pencil").settings,
            Tool.PERSPECTIVE_CLONE to BrushSettings(size = 60f, hardness = 0.5f, pressureSize = false, pressureOpacity = false),
        )
    )

    // Resources (GIMP brushes, gradients, palettes, patterns, MyPaint brushes), shared by all images.
    val resources = io.github.nielstron.brushkin.engine.ResourceStore(File(app.filesDir, "resources")).also { io.github.nielstron.brushkin.engine.Library.store = it }
    var gradient by mutableStateOf(io.github.nielstron.brushkin.engine.GradientRes.FG_BG)
    var gradientReverse by mutableStateOf(false)
    var palette by mutableStateOf<io.github.nielstron.brushkin.engine.PaletteRes?>(null)
    var pattern by mutableStateOf<io.github.nielstron.brushkin.engine.PatternRes?>(null)
    var fillWithPattern by mutableStateOf(false)
    var myPaintBrush by mutableStateOf("Pencil")

    fun importResource(name: String, bytes: ByteArray) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { resources.import(name, bytes) } }
            messages.tryEmit(r.fold({ "Imported $it “${name.substringBeforeLast('.')}”" }, { it.message ?: "Couldn't import $name" }))
        }
    }

    /** Saves the current generated tip (shape, spikes, hardness…) as a GIMP .gbr brush. */
    fun saveGeneratedBrush() {
        val s = settings
        viewModelScope.launch(Dispatchers.IO) {
            val d = s.size.roundToInt().coerceIn(8, 512)
            val tip = io.github.nielstron.brushkin.engine.TipCache.get(d.toFloat(), s, false)
            val scaled = android.graphics.Bitmap.createScaledBitmap(tip, d, d, true)
            val mask = ByteArray(d * d).also { val buf = java.nio.ByteBuffer.allocate(scaled.rowBytes * d); scaled.copyPixelsToBuffer(buf); for (y in 0 until d) System.arraycopy(buf.array(), y * scaled.rowBytes, it, y * d, d) }
            val name = "Generated ${resources.brushes.value.size + 1}"
            val b = resources.saveBrush(name, mask, d, d)
            withContext(Dispatchers.Main) { settings = settings.copy(tipImage = b.name, spacing = s.spacing); messages.tryEmit("Saved “$name”") }
        }
    }

    /** GIMP's "Paste as new brush / pattern": the selection (or layer) becomes a resource. */
    fun selectionToResource(brush: Boolean) {
        viewModelScope.launch {
            val (bmp, _) = engine.copyRegion() ?: run { messages.tryEmit("Nothing to use"); return@launch }
            val name = (if (brush) "Brush " else "Pattern ") + (if (brush) resources.brushes.value.size + 1 else resources.patterns.value.size + 1)
            withContext(Dispatchers.IO) {
                if (brush) {
                    // Dark, opaque pixels paint (like GIMP brushes made from images).
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val mask = ByteArray(w * h) { i -> val c = px[i]; val l = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100; ((255 - l) * Color.alpha(c) / 255).toByte() }
                    val b = resources.saveBrush(name, mask, w, h)
                    withContext(Dispatchers.Main) { settings = settings.copy(tipImage = b.name, spacing = b.spacing) }
                } else {
                    val p = resources.savePattern(name, bmp)
                    withContext(Dispatchers.Main) { pattern = p; fillWithPattern = true }
                }
            }
            messages.tryEmit("Created $name")
        }
    }

    /** Settings for the current paint tool (Erase chip maps a brush to the eraser). */
    val strokeTool: Tool get() = if (eraseToggle && tool.paint != null && tool.paint?.isEffect != true && tool != Tool.SMUDGE) Tool.ERASER else tool

    /** The engine tool for the current stroke, with the Burn/Sharpen chips applied. */
    val paintTool: PaintTool? get() = when (val t = strokeTool) {
        Tool.DODGE_BURN -> if (altEffect) PaintTool.BURN else PaintTool.DODGE
        Tool.BLUR_SHARPEN -> if (altEffect) PaintTool.SHARPEN else PaintTool.BLUR
        else -> t.paint
    }

    /** Burn instead of Dodge, Sharpen instead of Blur (GIMP's Ctrl toggle). */
    var altEffect by mutableStateOf(false)
    var settingSource by mutableStateOf(false)
    var cloneSource by mutableStateOf<android.graphics.PointF?>(null)
    var cloneOffset: android.graphics.Point? = null

    // Perspective clone: the plane's corners (doc coordinates, clockwise from top-left), whether
    // they're being edited, and the source offset measured in plane coordinates.
    var perspPlane by mutableStateOf<FloatArray?>(null)
    var perspEditing by mutableStateOf(false)
    var perspOffset: android.graphics.PointF? = null

    /** Plane → unit square (×1000) homography. */
    fun planeMatrix(): android.graphics.Matrix? {
        val p = perspPlane ?: return null
        val m = android.graphics.Matrix()
        return if (m.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 1000f, 0f, 1000f), 0, 4)) m else null
    }

    /** Destination → source map for a perspective-clone stroke starting at [start]. */
    fun perspectiveMap(start: android.graphics.PointF): android.graphics.Matrix? {
        if (tool != Tool.PERSPECTIVE_CLONE) return null
        val h = planeMatrix() ?: return null
        val src = cloneSource ?: return null
        val off = perspOffset ?: run {
            val a = floatArrayOf(src.x, src.y, start.x, start.y); h.mapPoints(a)
            android.graphics.PointF(a[0] - a[2], a[1] - a[3]).also { perspOffset = it }
        }
        val inv = android.graphics.Matrix(); if (!h.invert(inv)) return null
        return android.graphics.Matrix(h).apply { postTranslate(off.x, off.y); postConcat(inv) }
    }

    var settings: BrushSettings
        get() = brushSettings.value[if (strokeTool.paint != null) strokeTool else lastPaintTool] ?: BrushSettings()
        set(v) {
            val t = if (strokeTool.paint != null) strokeTool else lastPaintTool
            brushSettings.value = brushSettings.value + (t to v)
        }

    // Modifier chips (GIMP's Shift/Ctrl/Alt).
    var lineMode by mutableStateOf(false)
    var pickMode by mutableStateOf(false)
    /** Pipette from the color sheet: the next touch on the image picks a color, then the sheet returns. */
    var pipette by mutableStateOf(false)
    var eraseToggle by mutableStateOf(false)
    var fromCenter by mutableStateOf(false)
    /** Width ÷ height that rectangle/ellipse selections and the crop keep; null = free. */
    var aspect by mutableStateOf<Float?>(null)
    /** Symmetry painting (null: off) around the image center. */
    var symmetryKind by mutableStateOf<io.github.nielstron.brushkin.engine.Symmetry.Kind?>(null)
    var symmetrySegments by mutableStateOf(6)
    fun symmetry() = symmetryKind?.let { k -> val i = engine.info.value; io.github.nielstron.brushkin.engine.Symmetry(k, i.width / 2f, i.height / 2f, symmetrySegments) }
    // View aids (GIMP's View menu): grid, rulers and snapping.
    var showGrid by mutableStateOf(false)
    var gridSize by mutableStateOf(64f)
    var showRulers by mutableStateOf(false)
    var snapGrid by mutableStateOf(false)
    var snapGuides by mutableStateOf(true)
    /** Rounded corners for the rectangle select tool (px). */
    var cornerRadius by mutableStateOf(0f)
    var keepRatio by mutableStateOf(true)
    /** Handle Transform: (source point in the lifted bitmap, current doc point). */
    val handles = ArrayList<Pair<android.graphics.PointF, android.graphics.PointF>>()
    var warpRadius by mutableStateOf(120f)
    var warpStrength by mutableStateOf(0.6f)
    var snapRotation by mutableStateOf(false)

    var selectMode by mutableStateOf(SelectMode.REPLACE)
    var feather by mutableStateOf(0f)
    var antialias by mutableStateOf(true)
    var threshold by mutableStateOf(15f)
    var sampleMerged by mutableStateOf(false)
    var fillWholeSelection by mutableStateOf(false)
    var gradientShape by mutableStateOf(GradientShape.LINEAR)
    var fgToTransparent by mutableStateOf(false)

    var cropRect by mutableStateOf<android.graphics.RectF?>(null)
    var textStyle by mutableStateOf(TEXT_STYLES[0].second)
    /** Where the Text tool was tapped (doc coordinates). */
    var textAt: android.graphics.PointF? = null

    /** On-canvas text being typed: the layer it edits (null = new) and its current spec. */
    class TextEdit(val layerId: Long?, val spec: io.github.nielstron.brushkin.engine.TextSpec)
    var textEdit by mutableStateOf<TextEdit?>(null)

    /** Text tool tap: edit the text layer under the finger, or start new text there. */
    fun textToolTap(x: Float, y: Float) {
        if (textEdit != null) { finishText(); return }
        val hit = engine.stack.all.lastOrNull { l -> l.visible && l.text?.let { textHit(it, x, y) } == true }
        if (hit != null) { editTextLayer(hit.id); return }
        val st = textStyle
        val preset = TEXT_STYLES.firstOrNull { it.second == st }?.first
        val size = 120f
        val spec = io.github.nielstron.brushkin.engine.TextSpec(
            "", size,
            when (preset) { "Title" -> "serif"; "hello" -> "cursive"; else -> "sans-serif" },
            bold = preset == "Title" || preset == "hello", color = fg,
        )
        // The tap marks the first line's baseline-ish top-left, like GIMP.
        val pad = size * 0.15f
        textEdit = TextEdit(null, spec.withMatrix(android.graphics.Matrix().apply { setTranslate(x - pad, y - pad) }))
    }

    /**
     * Meme tool tap: edit the text layer under the finger, else start meme text where tapped
     * (like the Text tool): white capitals in Anton with a black outline, lines centered.
     */
    fun memeTap(x: Float, y: Float) {
        if (textEdit != null) { finishText(); return }
        val hit = engine.stack.all.lastOrNull { l -> l.visible && l.text?.let { textHit(it, x, y) } == true }
        if (hit != null) { editTextLayer(hit.id); return }
        val i = engine.info.value
        val size = minOf(i.width / 9f, i.height / 7f).coerceAtLeast(24f)
        val pad = size * 0.15f
        val spec = io.github.nielstron.brushkin.engine.TextSpec(
            "", size, io.github.nielstron.brushkin.engine.TextSpec.MEME_FONT, color = android.graphics.Color.WHITE,
            outline = true, outlineColor = android.graphics.Color.BLACK, center = true,
        )
        textEdit = TextEdit(null, spec.withMatrix(android.graphics.Matrix().apply { setTranslate(x - pad, y - pad) }))
    }

    fun editTextLayer(id: Long) {
        val l = engine.stack.find(id) ?: return
        val spec = l.text ?: return
        engine.setActive(id)
        engine.hiddenLayerId = id
        textEdit = TextEdit(id, spec)
    }

    private fun textHit(t: io.github.nielstron.brushkin.engine.TextSpec, x: Float, y: Float): Boolean {
        if (t.path != null) return false
        val box = t.renderBox()
        val inv = android.graphics.Matrix(); t.matrix().invert(inv)
        val p = floatArrayOf(x, y); inv.mapPoints(p)
        return p[0] in 0f..box.width.toFloat() && p[1] in 0f..box.height.toFloat()
    }

    fun updateTextEdit(spec: io.github.nielstron.brushkin.engine.TextSpec) { textEdit = textEdit?.let { TextEdit(it.layerId, spec) } }

    /** Commit the on-canvas text as an editable text layer. */
    fun finishText(cancel: Boolean = false) {
        val te = textEdit ?: return
        textEdit = null
        engine.hiddenLayerId = -1
        if (cancel) return
        val spec = te.spec
        when {
            te.layerId != null && spec.text.isBlank() -> engine.deleteLayer(te.layerId)
            te.layerId != null -> engine.updateTextLayer(te.layerId, spec)
            spec.text.isNotBlank() -> engine.addTextLayer(spec)
        }
    }
    val path = PathEdit()
    /** Bumped when [path] changes so Compose and the canvas redraw. */
    var pathVersion by mutableStateOf(0)

    // Intelligent Scissors: anchors and the edge-following segment after each one (null while computing).
    val scissorsAnchors = ArrayList<android.graphics.PointF>()
    val scissorsSegs = ArrayList<FloatArray?>()
    var scissorsVersion by mutableStateOf(0)
    /** Foreground select: width of the band around the rough outline that gets refined (px). */

    fun scissorsTap(p: android.graphics.PointF, closeRadius: Float) {
        val first = scissorsAnchors.firstOrNull()
        if (first != null && scissorsAnchors.size >= 3 && kotlin.math.hypot(p.x - first.x, p.y - first.y) < closeRadius) { scissorsClose(); return }
        scissorsAnchors.add(p)
        if (scissorsAnchors.size >= 2) addScissorsSeg(scissorsAnchors[scissorsAnchors.size - 2], p)
        scissorsVersion++
    }

    private fun addScissorsSeg(a: android.graphics.PointF, b: android.graphics.PointF) {
        val idx = scissorsSegs.size
        scissorsSegs.add(null)
        val gen = scissorsGen
        viewModelScope.launch {
            val seg = engine.scissorsSegment(a.x, a.y, b.x, b.y)
            if (gen == scissorsGen && idx < scissorsSegs.size) { scissorsSegs[idx] = seg; scissorsVersion++ }
        }
    }
    private var scissorsGen = 0

    fun scissorsUndo() {
        if (scissorsAnchors.isEmpty()) return
        scissorsAnchors.removeAt(scissorsAnchors.size - 1)
        if (scissorsSegs.size > maxOf(0, scissorsAnchors.size - 1)) scissorsSegs.removeAt(scissorsSegs.size - 1)
        scissorsVersion++
    }

    fun scissorsClear() { scissorsAnchors.clear(); scissorsSegs.clear(); scissorsGen++; scissorsVersion++ }

    fun scissorsClose() {
        if (scissorsAnchors.size < 3) { messages.tryEmit("Add at least three points"); return }
        val anchors = scissorsAnchors.toList()
        val segs = scissorsSegs.toList()
        val mode = selectMode; val aa = antialias; val f = feather
        scissorsClear()
        viewModelScope.launch {
            val closing = engine.scissorsSegment(anchors.last().x, anchors.last().y, anchors[0].x, anchors[0].y)
            val path = android.graphics.Path()
            path.moveTo(anchors[0].x, anchors[0].y)
            for ((i, seg) in (segs + listOf(closing)).withIndex()) {
                val s = seg ?: engine.scissorsSegment(anchors[i].x, anchors[i].y, anchors[(i + 1) % anchors.size].x, anchors[(i + 1) % anchors.size].y)
                var k = 0
                while (k < s.size) { path.lineTo(s[k], s[k + 1]); k += 2 }
            }
            path.close()
            engine.select(path, mode, aa, f, "Intelligent Scissors")
        }
    }

    fun pathToSelection() {
        if (path.anchors.size < 3) { messages.tryEmit("Add at least three points"); return }
        val p = PathEdit().also { it.anchors.addAll(path.anchors); it.closed = true }.toPath()
        engine.select(p, selectMode, antialias, feather, "Path to Selection")
    }

    fun strokePath() {
        val pts = path.sample()
        if (pts.size < 2) { messages.tryEmit("Add at least two points"); return }
        val pt = (if (lastPaintTool.paint != null) lastPaintTool.paint else null) ?: io.github.nielstron.brushkin.engine.PaintTool.BRUSH
        val s = brushSettings.value[lastPaintTool] ?: BrushSettings()
        engine.strokeBegin(pt, s.copy(smoothing = 0f, pressureSize = false, pressureOpacity = false), fg, symmetry = symmetry())
        pts.chunked(256).forEach { engine.strokeAdd(it) }
        engine.strokeEnd()
    }

    init {
        engine.onMessage = { messages.tryEmit(it) }
        runCatching {
            io.github.nielstron.brushkin.engine.TextSpec.bundledFonts = mapOf(
                io.github.nielstron.brushkin.engine.TextSpec.MEME_FONT to android.graphics.Typeface.createFromAsset(app.assets, "fonts/Anton-Regular.ttf"))
        }
        io.github.nielstron.brushkin.engine.Effects.factory = { buildEffect(it) }
        viewModelScope.launch(Dispatchers.IO) { resources.load() }
        // Posted, not immediate: the clipboard property is declared further down.
        viewModelScope.launch(Dispatchers.Main) { clipboardManager.addPrimaryClipChangedListener { refreshClipboard() } }
        engine.newDocument(1600, 2400, Color.WHITE)
        // Autosave a few seconds after edits settle, so a crash or a killed process loses little.
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            engine.info.map { it.version }.distinctUntilChanged().drop(1).debounce(4000).collect { autosave() }
        }
    }

    // ------------------------------------------------------------ on-device models

    private val models = io.github.nielstron.brushkin.ml.ModelStore(app)
    private val objects = io.github.nielstron.brushkin.ml.ObjectSelectClient(models)
    private val images = io.github.nielstron.brushkin.ml.ImageModels(models)
    var mlBusy by mutableStateOf<String?>(null)
    private var mlJob: kotlinx.coroutines.Job? = null
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Cancels whatever long operation is running (model, download or engine work). */
    fun cancelWork() { mlJob?.cancel(); engine.cancelBusy() }

    fun cancelDownload() { downloadJob?.cancel(); downloadJob = null; modelProgress = null; modelRequest = null }

    /** A tool waiting for its model to be downloaded (after the user agreed). */
    class ModelRequest(val title: String, val text: String, val files: List<io.github.nielstron.brushkin.ml.ModelFile>, val then: () -> Unit)
    var modelRequest by mutableStateOf<ModelRequest?>(null)
    var modelProgress by mutableStateOf<Float?>(null)
    fun modelDownloadMb(r: ModelRequest) = ((models.missingBytes(r.files) + 999_999) / 1_000_000).toInt()

    /** Runs [then] right away when the models are there, else asks to download them first. */
    private fun withModels(title: String, text: String, files: List<io.github.nielstron.brushkin.ml.ModelFile>, then: () -> Unit) {
        if (mlBusy != null || modelProgress != null) return
        if (models.ready(files)) { then(); return }
        modelRequest = ModelRequest(title, text, files, then)
        dialog = "model-download"
    }

    /** Downloads the requested models, then runs what was waiting for them. */
    fun downloadModel() {
        val r = modelRequest ?: return
        if (modelProgress != null) return
        modelProgress = 0f
        downloadJob = viewModelScope.launch {
            try {
                models.download(r.files) { p -> viewModelScope.launch(Dispatchers.Main) { if (modelProgress != null) modelProgress = p } }
                modelProgress = null; modelRequest = null
                r.then()
            } catch (t: Throwable) {
                modelProgress = null
                if (t is kotlinx.coroutines.CancellationException) return@launch
                messages.tryEmit(t.message ?: "Couldn't download the model. Check your connection and try again.")
            }
        }
    }

    private fun mlError(what: String, t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        android.util.Log.w("Brushkin", "$what failed", t)
        messages.tryEmit(if (t is io.github.nielstron.brushkin.ml.ModelUnavailable) t.message ?: "$what isn't available" else "$what failed: ${t.message ?: t.javaClass.simpleName}")
    }

    // Object selection: taps on the object (and, to refine, on what isn't part of it).

    private val samText = "Selecting objects uses MobileSAM, an open model (Apache-2.0)."
    /** The taps of the object being selected (document pixels); empty = the next tap starts a new one. */
    val objPoints = mutableStateListOf<io.github.nielstron.brushkin.ml.ObjectSelect.Point>()
    /** Taps mark what isn't part of the object. */
    var objSubtract by mutableStateOf(false)
    private var objBox: android.graphics.RectF? = null
    private var objLogits: FloatArray? = null
    private var objSession = 0L
    private var objMode = SelectMode.REPLACE

    /** Starts over: the next tap selects a new object. */
    fun objectNew() { objPoints.clear(); objBox = null; objLogits = null; objSession = 0; objSubtract = false }

    /** A tap with the Object tool: a new object, or (while refining one) a point added to it or taken away. */
    fun objectTap(x: Float, y: Float) = withModels("Download object selection?", samText, objects.files) {
        if (objSession == 0L) { objectNew(); objSession = System.nanoTime(); objMode = if (tool == Tool.REMOVE_OBJECT) SelectMode.REPLACE else selectMode }
        objPoints.add(io.github.nielstron.brushkin.ml.ObjectSelect.Point(x, y, positive = !objSubtract || objPoints.isEmpty()))
        runObjectSelect()
    }

    /** Selects the main subject (a box around almost the whole image); taps then refine it. */
    fun selectForeground() = withModels("Download object selection?", samText, objects.files) {
        objectNew(); objSession = System.nanoTime(); objMode = if (tool == Tool.REMOVE_OBJECT) SelectMode.REPLACE else selectMode
        val i = engine.info.value
        objBox = android.graphics.RectF(i.width * 0.03f, i.height * 0.03f, i.width * 0.97f, i.height * 0.97f)
        runObjectSelect()
    }

    private fun runObjectSelect() {
        val pts = objPoints.toList(); val box = objBox; val prev = objLogits; val session = objSession; val mode = objMode
        val refining = prev != null
        mlBusy = when { refining -> "Refining"; box != null -> "Finding the foreground"; else -> "Finding the object" }
        mlJob = viewModelScope.launch {
            try {
                val full = engine.flattened(Color.WHITE)
                val small = withContext(Dispatchers.Default) {
                    // ≤1024 px (the model's input size), dimensions rounded to multiples of 16.
                    val k = minOf(1f, 1024f / maxOf(full.width, full.height))
                    fun r16(v: Float) = ((v / 16f).roundToInt() * 16).coerceAtLeast(16)
                    android.graphics.Bitmap.createScaledBitmap(full, r16(full.width * k), r16(full.height * k), true)
                }
                val kx = small.width / full.width.toFloat(); val ky = small.height / full.height.toFloat()
                val sp = pts.map { io.github.nielstron.brushkin.ml.ObjectSelect.Point(it.x * kx, it.y * ky, it.positive) }
                val sb = box?.let { android.graphics.RectF(it.left * kx, it.top * ky, it.right * kx, it.bottom * ky) }
                val r = objects.select(small, sp, sb, prev)
                if (session != objSession) return@launch // started over meanwhile
                val m = r.mask
                if (m == null) {
                    if (!refining) { messages.tryEmit(if (box != null) "No foreground subject found" else "No object found there. Try tapping the middle of it."); objectNew() }
                    else { objPoints.removeAt(objPoints.size - 1); messages.tryEmit("That would leave nothing selected") }
                } else {
                    objLogits = r.logits
                    engine.selectFromMask(m.bytes, m.width, m.height, mode, if (box != null) "Select Foreground" else "Object Select", session)
                }
            } catch (t: Throwable) {
                if (!refining) objectNew() else objPoints.removeLastOrNull()
                if (t !is kotlinx.coroutines.CancellationException) mlError("Object selection", t)
            } finally { mlBusy = null }
        }
    }

    // Image tools.

    /** Fills the selection from its surroundings (removes the selected object). */
    fun removeObject() {
        if (!engine.info.value.hasSelection) { messages.tryEmit("Select the object first, e.g. with the Object tool"); return }
        withModels("Download object removal?", "Removing objects uses MI-GAN, an open model (MIT).", listOf(io.github.nielstron.brushkin.ml.ImageModels.INPAINT)) {
            objectNew()
            engine.processActive("Remove Object", clipToSelection = false) { src, sel, _ -> images.inpaint(src, sel ?: ByteArray(src.width * src.height) { -1 }) }
            engine.selectNone()
        }
    }

    fun denoise() = withModels("Download denoising?", "Denoising uses NAFNet, an open model (MIT), trained on real camera noise.", listOf(io.github.nielstron.brushkin.ml.ImageModels.DENOISE)) {
        engine.processActive("Denoise") { src, _, p -> images.denoise(src, p) }
    }

    fun deblur() = withModels("Download deblurring?", "Deblurring uses NAFNet, an open model (MIT), trained on camera shake and motion blur.", listOf(io.github.nielstron.brushkin.ml.ImageModels.DEBLUR)) {
        engine.processActive("Deblur") { src, _, p -> images.deblur(src, p) }
    }

    /** Largest result of an upscale (memory: the image is held whole while it's processed). */
    private val maxUpscalePixels = 24_000_000L

    fun upscale(factor: Int) {
        val i = engine.info.value
        if (i.width.toLong() * i.height * factor * factor > maxUpscalePixels) {
            messages.tryEmit("Too large to enlarge ×$factor here (the result would be over 24 megapixels)"); return
        }
        withModels("Download upscaling?", "Upscaling uses PLKSR-Rep, an open model (MIT).", listOf(io.github.nielstron.brushkin.ml.ImageModels.UPSCALE)) {
            engine.upscaleImage(factor, "Upscale ×$factor") { src, p -> images.upscale(src, factor, p) }
        }
    }

    // Depth of field: a depth map of the active layer, then a live-previewed blur around a focus point.

    class DepthMap(val values: FloatArray, val side: Int)
    var depthMap by mutableStateOf<DepthMap?>(null)
    /** Focus point (document pixels) while the depth-of-field panel is open. */
    var dofFocus by mutableStateOf<android.graphics.PointF?>(null)
    var dofOpen by mutableStateOf(false)

    fun depthOfField() = withModels("Download depth estimation?", "Depth of field uses ZipDepth, an open model (MIT), to tell near from far.", listOf(io.github.nielstron.brushkin.ml.ImageModels.DEPTH)) {
        mlBusy = "Estimating depth"
        mlJob = viewModelScope.launch {
            try {
                val src = engine.flattened(Color.WHITE)
                val d = images.depth(src)
                depthMap = DepthMap(d, io.github.nielstron.brushkin.ml.ImageModels.DEPTH_SIDE)
                val i = engine.info.value
                // Start focused on the nearest thing in the middle of the image.
                dofFocus = android.graphics.PointF(i.width / 2f, i.height / 2f)
                dofOpen = true
                dialog = "depth-of-field"
            } catch (t: Throwable) { if (t !is kotlinx.coroutines.CancellationException) mlError("Depth estimation", t) } finally { mlBusy = null }
        }
    }

    // ------------------------------------------------------------ clipboard (shared with other apps)

    private val clipboardManager by lazy { getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java) }
    /** Whether the system clipboard holds an image we can paste. */
    var clipHasImage by mutableStateOf(false)
    private var lastCopy: Pair<android.net.Uri, android.graphics.Rect>? = null

    fun refreshClipboard() {
        clipHasImage = runCatching {
            val d = clipboardManager.primaryClipDescription ?: return@runCatching false
            (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") }
        }.getOrDefault(false)
    }

    /** Copy (or cut) the selection to the system clipboard as a PNG, so other apps can paste it too. */
    fun copy(cut: Boolean = false, merged: Boolean = false) {
        viewModelScope.launch {
            val (bmp, rect) = engine.copyRegion(merged) ?: run { messages.tryEmit("Nothing to copy"); return@launch }
            if (cut) engine.clearSelection("Cut")
            runCatching {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "share").apply { mkdirs() }
                    dir.listFiles { f -> f.name.startsWith("clip-") }?.forEach { it.delete() }
                    val f = File(dir, "clip-${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                }
                clipboardManager.setPrimaryClip(android.content.ClipData.newUri(app.contentResolver, "Brushkin image", uri))
                lastCopy = uri to rect
                clipHasImage = true
            }.onFailure { messages.tryEmit("Couldn't copy: ${it.message}") }
            // Android 13+ confirms clipboard copies itself.
            if (android.os.Build.VERSION.SDK_INT < 33) messages.tryEmit(if (cut) "Cut" else "Copied")
        }
    }

    /** Paste an image from the system clipboard as a new layer (ours go back where they came from). */
    fun paste() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
            val uri = clip?.let { c -> (0 until c.itemCount).firstNotNullOfOrNull { c.getItemAt(it).uri } }
            if (uri == null) { messages.tryEmit("The clipboard has no image"); return@launch }
            runCatching {
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(app.contentResolver, uri)) { d, info, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE; d.isMutableRequired = true
                        val k = maxOf(info.size.width, info.size.height) / 8192f
                        if (k > 1f) d.setTargetSize((info.size.width / k).toInt(), (info.size.height / k).toInt())
                    }.let { if (it.config != android.graphics.Bitmap.Config.ARGB_8888) it.copy(android.graphics.Bitmap.Config.ARGB_8888, true) else it }
                }
                val own = lastCopy?.takeIf { it.first == uri }?.second
                engine.placeFloating(bmp, "Pasted Layer", own?.let { android.graphics.Point(it.left, it.top) })
            }.onFailure { messages.tryEmit("Couldn't paste: ${it.message}") }
        }
    }

    fun restoreAtStartup() = viewModelScope.launch {
        try {
            if (autosaveFile.exists()) {
                val saved = withContext(Dispatchers.IO) { autosaveFile.inputStream().use { Ora.read(it) } }
                val name = getApplication<Application>().getSharedPreferences("brushkin", 0).getString("name", "Untitled") ?: "Untitled"
                engine.openStack(saved, name).join()
            } else {
                // Wait for the initial document queued by init before exposing the canvas.
                engine.post {}.join()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.emit("Couldn't restore image: ${e.message}")
        } finally {
            startupLoading = false
        }
    }

    private val saveLock = Mutex()
    private var savedVersion = -1L
    val autosaveFile get() = File(getApplication<Application>().filesDir, "autosave.ora")

    suspend fun autosave() = saveLock.withLock {
        if (startupLoading) return@withLock
        val v = engine.info.value.version
        if (v == savedVersion) return@withLock
        runCatching {
            val snap = engine.snapshot()
            withContext(Dispatchers.IO) {
                val dir = getApplication<Application>().filesDir
                val tmp = File(dir, "autosave.tmp")
                tmp.outputStream().use { Ora.write(snap, it) }
                tmp.renameTo(autosaveFile)
                getApplication<Application>().getSharedPreferences("brushkin", 0).edit().putString("name", engine.docName).apply()
            }
            savedVersion = v
        }.onFailure { android.util.Log.w("Brushkin", "autosave failed", it) }
    }

    // Favorite tools (shown in the toolbar strip and first in the tool list) and how often each tool
    // is picked (the tool list shows the most used ones per section first). Both persist.
    val recentCommands = RecentCommands(app.getSharedPreferences("brushkin-recent-commands", 0))

    private val toolPrefs = app.getSharedPreferences("brushkin-tools", 0)
    val favorites = mutableStateListOf<Tool>().apply {
        addAll(toolPrefs.getString("favorites", "")!!.split(',').mapNotNull { n -> Tool.entries.firstOrNull { it.name == n } })
    }
    private val toolUses = mutableStateMapOf<Tool, Int>().apply {
        for (e in toolPrefs.getString("uses", "")!!.split(',')) {
            val (n, c) = e.split(':').takeIf { it.size == 2 } ?: continue
            Tool.entries.firstOrNull { it.name == n }?.let { put(it, c.toIntOrNull() ?: 0) }
        }
    }
    fun toolUses(t: Tool) = toolUses[t] ?: 0

    fun toggleFavorite(t: Tool) {
        if (!favorites.remove(t)) favorites.add(t)
        toolPrefs.edit().putString("favorites", favorites.joinToString(",") { it.name }).apply()
    }

    /** Moves favorite [t] one place left (-1) or right (+1) in the toolbar. */
    fun moveFavorite(t: Tool, delta: Int) {
        val i = favorites.indexOf(t); val j = i + delta
        if (i < 0 || j !in favorites.indices) return
        favorites.add(j, favorites.removeAt(i))
        toolPrefs.edit().putString("favorites", favorites.joinToString(",") { it.name }).apply()
    }

    /** While set, a touch on the canvas picks the color under it for an open filter panel. */
    var filterPicking by mutableStateOf<((Int) -> Unit)?>(null)

    /** Picks [t] from the tool list: a tool becomes current, an action runs. */
    fun pickTool(t: Tool) {
        toolUses[t] = toolUses(t) + 1
        toolPrefs.edit().putString("uses", toolUses.entries.joinToString(",") { "${it.key.name}:${it.value}" }).apply()
        when (t) {
            Tool.DEPTH_BLUR -> depthOfField()
            Tool.UPSCALE -> dialog = "upscale"
            Tool.DENOISE -> denoise()
            Tool.DEBLUR -> deblur()
            else -> if (t.filter != null) dialog = "filter:${t.filter}" else selectTool(t)
        }
    }

    /** Transform tools act on the whole image instead of the active layer. */
    var transformWhole by mutableStateOf(false)
    /** Crop only the active layer (its boundary) instead of the image. */
    var cropLayer by mutableStateOf(false)

    /** Switches transforms between layer and whole image, re-lifting what's being transformed. */
    fun changeTransformTarget(whole: Boolean) {
        if (whole == transformWhole) return
        transformWhole = whole
        if (tool.liftsLayer && tool != Tool.WARP && tool != Tool.CAGE) {
            engine.cancelFloating(); handles.clear()
            if (tool.confirmTransform) engine.liftActive(whole)
        }
    }

    fun selectTool(t: Tool) {
        // Leaving a transform tool discards an unapplied transform (like GIMP).
        if (tool.liftsLayer && t != tool) engine.cancelFloating()
        if (t != tool) { altEffect = false; objectNew() }
        tool = t
        if (t.paint != null) lastPaintTool = t
        // Scale and Rotate show handles right away; Move lifts on touch.
        if (t.confirmTransform) {
            handles.clear()
            if (t == Tool.WARP || t == Tool.CAGE) engine.meshBegin(cage = t == Tool.CAGE) else engine.liftActive(transformWhole)
        }
        if (t != Tool.CROP) cropRect = null
        if (t != Tool.SCISSORS) scissorsClear()
        // Compile the object selection model in the background so the first tap is quick.
        if (t.picksObjects) viewModelScope.launch { objects.prewarm() }
        if (t != Tool.TEXT && t != Tool.MEME) finishText()
        settingSource = (t == Tool.CLONE || t == Tool.HEAL || t == Tool.PERSPECTIVE_CLONE) && cloneSource == null
        if (t == Tool.PERSPECTIVE_CLONE && perspPlane == null) {
            // Start with a plane inset from the image corners; the user drags it onto a surface.
            val i = engine.info.value; val w = i.width.toFloat(); val h = i.height.toFloat()
            perspPlane = floatArrayOf(w * 0.25f, h * 0.3f, w * 0.75f, h * 0.3f, w * 0.9f, h * 0.7f, w * 0.1f, h * 0.7f)
            perspEditing = true
        }
    }

    fun pickColor(c: Int) {
        fg = c
        pushRecent(c)
    }

    fun pushRecent(c: Int) {
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > 16) recentColors.removeAt(recentColors.size - 1)
    }

    fun swapColors() { val t = fg; fg = bg; bg = t }

    override fun onCleared() { engine.shutdown(); objects.close(); images.close() }
}
