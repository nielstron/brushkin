package io.github.nielstron.brushkin.ui

import android.app.Application
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

enum class ToolGroup(val label: String) { SELECT("Select"), PAINT("Paint"), TRANSFORM("Transform"), OTHER("Other") }

enum class Tool(val label: String, val group: ToolGroup, val paint: PaintTool? = null) {
    RECT_SELECT("Rectangle", ToolGroup.SELECT),
    ELLIPSE_SELECT("Ellipse", ToolGroup.SELECT),
    FREE_SELECT("Free", ToolGroup.SELECT),
    FUZZY_SELECT("Fuzzy", ToolGroup.SELECT),
    COLOR_SELECT("By Color", ToolGroup.SELECT),
    OBJECT_SELECT("Object", ToolGroup.SELECT),
    BRUSH("Paintbrush", ToolGroup.PAINT, PaintTool.BRUSH),
    PENCIL("Pencil", ToolGroup.PAINT, PaintTool.PENCIL),
    AIRBRUSH("Airbrush", ToolGroup.PAINT, PaintTool.AIRBRUSH),
    ERASER("Eraser", ToolGroup.PAINT, PaintTool.ERASER),
    SMUDGE("Smudge", ToolGroup.PAINT, PaintTool.SMUDGE),
    CLONE("Clone", ToolGroup.PAINT, PaintTool.CLONE),
    HEAL("Heal", ToolGroup.PAINT, PaintTool.HEAL),
    DODGE_BURN("Dodge / Burn", ToolGroup.PAINT, PaintTool.DODGE),
    BLUR_SHARPEN("Blur / Sharpen", ToolGroup.PAINT, PaintTool.BLUR),
    BUCKET("Bucket Fill", ToolGroup.PAINT),
    GRADIENT("Gradient", ToolGroup.PAINT),
    MOVE("Move", ToolGroup.TRANSFORM),
    SCALE("Scale", ToolGroup.TRANSFORM),
    ROTATE("Rotate", ToolGroup.TRANSFORM),
    FLIP("Flip", ToolGroup.TRANSFORM),
    CROP("Crop", ToolGroup.TRANSFORM),
    PATHS("Paths", ToolGroup.OTHER),
    PICKER("Color Picker", ToolGroup.OTHER),
    TEXT("Text", ToolGroup.OTHER);

    val isSelect get() = group == ToolGroup.SELECT
    /** Tools that work on a lifted copy of the layer (applied or cancelled as a whole). */
    val liftsLayer get() = this == MOVE || this == SCALE || this == ROTATE
}

enum class Sheet { NONE, TOOLS, OPTIONS, COLOR, LAYERS, INSERT, HISTORY }

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Engine()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

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
        )
    )

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

    var settings: BrushSettings
        get() = brushSettings.value[if (strokeTool.paint != null) strokeTool else lastPaintTool] ?: BrushSettings()
        set(v) {
            val t = if (strokeTool.paint != null) strokeTool else lastPaintTool
            brushSettings.value = brushSettings.value + (t to v)
        }

    // Modifier chips (GIMP's Shift/Ctrl/Alt).
    var lineMode by mutableStateOf(false)
    var pickMode by mutableStateOf(false)
    var eraseToggle by mutableStateOf(false)
    var fromCenter by mutableStateOf(false)
    var fixedRatio by mutableStateOf(false)
    var keepRatio by mutableStateOf(true)
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
    val path = PathEdit()
    /** Bumped when [path] changes so Compose and the canvas redraw. */
    var pathVersion by mutableStateOf(0)

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
        engine.strokeBegin(pt, s.copy(smoothing = 0f, pressureSize = false, pressureOpacity = false), fg)
        pts.chunked(256).forEach { engine.strokeAdd(it) }
        engine.strokeEnd()
    }

    init {
        engine.onMessage = { messages.tryEmit(it) }
        // Posted, not immediate: the clipboard property is declared further down.
        viewModelScope.launch(Dispatchers.Main) { clipboardManager.addPrimaryClipChangedListener { refreshClipboard() } }
        engine.newDocument(1600, 2400, Color.WHITE)
        // Autosave a few seconds after edits settle, so a crash or a killed process loses little.
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            engine.info.map { it.version }.distinctUntilChanged().drop(1).debounce(4000).collect { autosave() }
        }
    }

    private val objects = io.github.nielstron.brushkin.ml.ObjectSelectClient(app)
    var mlBusy by mutableStateOf<String?>(null)

    /** Select the object under a doc point, or the whole foreground when x < 0. Runs off the UI thread. */
    fun objectSelect(x: Float, y: Float) {
        if (mlBusy != null) return
        mlBusy = if (x < 0) "Finding the foreground" else "Finding the object"
        val mode = selectMode
        viewModelScope.launch {
            try {
                val full = engine.flattened(Color.WHITE)
                val small = withContext(Dispatchers.Default) {
                    // ≤1280 px, and dimensions rounded to multiples of 16: ML Kit's GPU path reads
                    // past the end of rows with odd sizes on some drivers.
                    val k = minOf(1f, 1024f / maxOf(full.width, full.height))
                    fun r16(v: Float) = ((v / 16f).roundToInt() * 16).coerceAtLeast(16)
                    android.graphics.Bitmap.createScaledBitmap(full, r16(full.width * k), r16(full.height * k), true)
                }
                val sx = if (x < 0) -1 else (x * small.width / full.width).toInt()
                val sy = if (y < 0) -1 else (y * small.height / full.height).toInt()
                val m = objects.select(small, sx, sy)
                if (m == null) messages.tryEmit(if (x < 0) "No foreground subject found" else "No object found there. Try tapping the middle of it.")
                else engine.selectFromMask(m.bytes, m.width, m.height, mode, if (x < 0) "Select Foreground" else "Object Select")
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                messages.tryEmit(
                    when {
                        "download" in msg.lowercase() -> "Object selection is still being set up by Google Play services. Try again in a moment."
                        t is io.github.nielstron.brushkin.ml.ObjectSelectUnavailable -> "Object selection isn't available on this device"
                        else -> "Object selection failed: ${msg.ifBlank { t.javaClass.simpleName }}"
                    }
                )
            } finally { mlBusy = null }
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

    private val saveLock = Mutex()
    private var savedVersion = -1L
    val autosaveFile get() = File(getApplication<Application>().filesDir, "autosave.ora")

    suspend fun autosave() = saveLock.withLock {
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

    fun selectTool(t: Tool) {
        // Leaving a transform tool discards an unapplied transform (like GIMP).
        if (tool.liftsLayer && t != tool) engine.cancelFloating()
        if (t != tool) altEffect = false
        tool = t
        if (t.paint != null) lastPaintTool = t
        // Scale and Rotate show handles right away; Move lifts on touch.
        if (t == Tool.SCALE || t == Tool.ROTATE) engine.liftActive()
        if (t != Tool.CROP) cropRect = null
        settingSource = (t == Tool.CLONE || t == Tool.HEAL) && cloneSource == null
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

    override fun onCleared() { engine.shutdown(); objects.close() }
}
