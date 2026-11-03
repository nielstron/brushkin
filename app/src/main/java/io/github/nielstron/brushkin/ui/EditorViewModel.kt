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

enum class ToolGroup(val label: String) { SELECT("Select"), PAINT("Paint"), TRANSFORM("Transform"), OTHER("Other") }

enum class Tool(val label: String, val group: ToolGroup, val paint: PaintTool? = null) {
    RECT_SELECT("Rectangle", ToolGroup.SELECT),
    ELLIPSE_SELECT("Ellipse", ToolGroup.SELECT),
    FREE_SELECT("Free", ToolGroup.SELECT),
    FUZZY_SELECT("Fuzzy", ToolGroup.SELECT),
    COLOR_SELECT("By Color", ToolGroup.SELECT),
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
    TRANSFORM("Transform", ToolGroup.TRANSFORM),
    CROP("Crop", ToolGroup.TRANSFORM),
    PICKER("Color Picker", ToolGroup.OTHER),
    TEXT("Text", ToolGroup.OTHER);

    val isSelect get() = group == ToolGroup.SELECT
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

    init {
        engine.onMessage = { messages.tryEmit(it) }
        engine.newDocument(1600, 2400, Color.WHITE)
        // Autosave a few seconds after edits settle, so a crash or a killed process loses little.
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            engine.info.map { it.version }.distinctUntilChanged().drop(1).debounce(4000).collect { autosave() }
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
        if (tool == Tool.TRANSFORM && t != Tool.TRANSFORM) engine.cancelFloating()
        if (t != tool) altEffect = false
        tool = t
        if (t.paint != null) lastPaintTool = t
        if (t == Tool.TRANSFORM) engine.liftActive()
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

    override fun onCleared() { engine.shutdown() }
}
