package io.github.nielstron.brushkin.ui

import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nielstron.brushkin.engine.BRUSH_PRESETS
import io.github.nielstron.brushkin.engine.DocInfo
import io.github.nielstron.brushkin.engine.GradientShape
import io.github.nielstron.brushkin.engine.LayerInfo
import io.github.nielstron.brushkin.engine.LayerMode
import io.github.nielstron.brushkin.engine.TipShape
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets(vm: EditorViewModel, info: DocInfo, files: FileActions) {
    if (vm.sheet == Sheet.NONE) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = vm.sheet != Sheet.OPTIONS)
    ModalBottomSheet(
        onDismissRequest = { vm.sheet = Sheet.NONE },
        sheetState = state,
        containerColor = Ui.panel,
        dragHandle = { SheetHandle() },
        scrimColor = Color.Black.copy(alpha = 0.35f),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            when (vm.sheet) {
                Sheet.TOOLS -> ToolsSheet(vm)
                Sheet.OPTIONS -> OptionsSheet(vm)
                Sheet.COLOR -> ColorSheet(vm)
                Sheet.LAYERS -> LayersSheet(vm, info)
                Sheet.INSERT -> InsertSheet(vm, files)
                Sheet.HISTORY -> HistorySheet(vm, info)
                Sheet.NONE -> {}
            }
        }
    }
}

// ---------------------------------------------------------------------------- tools

/** Tools per section shown before "Show all": two rows. */
private const val TOOLS_SHOWN = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsSheet(vm: EditorViewModel) {
    val expanded = remember { mutableStateListOf<ToolGroup>() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
        Text("Tools", color = Ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp, 0.dp, 4.dp, 4.dp))
        Text(
            if (vm.favorites.isEmpty()) "Long-press a tool to add it to the toolbar" else "Long-press a tool to add it to or remove it from the toolbar",
            color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (vm.favorites.isNotEmpty()) {
            SectionLabel("Favorites", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))
            ToolGrid(vm, vm.favorites.toList())
        }
        for (g in ToolGroup.entries) {
            val all = Tool.entries.filter { it.group == g }
            val open = g in expanded || all.size <= TOOLS_SHOWN
            // The most used tools (and the current one), kept in their usual order.
            val shown = if (open) all else {
                val top = all.sortedByDescending { vm.toolUses(it) }.take(TOOLS_SHOWN).toMutableSet()
                if (vm.tool in all && vm.tool !in top) { top.remove(top.minBy { vm.toolUses(it) }); top.add(vm.tool) }
                all.filter { it in top }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(g.label, Modifier.weight(1f))
                if (all.size > TOOLS_SHOWN) Text(
                    if (g in expanded) "Show fewer" else "Show all ${all.size}", color = Ui.accent, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (!expanded.remove(g)) expanded.add(g) }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            ToolGrid(vm, shown)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolGrid(vm: EditorViewModel, tools: List<Tool>) {
    FlowRow(maxItemsInEachRow = 4, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in tools) {
            val active = vm.tool == t
            Box(
                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Ui.accent else Ui.panel2)
                    .combinedClickable(onClick = { vm.sheet = Sheet.NONE; vm.pickTool(t) }, onLongClick = { vm.toggleFavorite(t) }),
            ) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ToolIcons.of(t), null, tint = if (active) Ui.onAccent else Ui.text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(t.label, color = if (active) Ui.onAccent else Ui.text, fontSize = 11.sp, maxLines = 1)
                }
                if (t in vm.favorites) Icon(
                    androidx.compose.material.icons.Icons.Rounded.Star, "In the toolbar",
                    tint = if (active) Ui.onAccent else Ui.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp),
                )
            }
        }
        repeat((4 - tools.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

/** Fixed width : height for rectangle and ellipse selections and the crop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AspectPicker(vm: EditorViewModel) {
    SectionLabel("Aspect ratio", Modifier.padding(top = 12.dp, bottom = 6.dp))
    val presets = listOf(null to "Free", 1 to 1, 4 to 3, 3 to 4, 3 to 2, 2 to 3, 16 to 9, 9 to 16)
    var w by remember { mutableStateOf("") }; var h by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((a, b) in presets) {
            val r = if (a == null) null else a / (b as Int).toFloat()
            val label = if (a == null) "Free" else "$a:$b"
            Chip(label, vm.aspect == r && (r != null || w.isEmpty())) { vm.aspect = r; w = ""; h = "" }
        }
    }
    // Any other ratio, e.g. a print size.
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Field(value: String, hint: String, onChange: (String) -> Unit) = Box(
            Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Ui.panel2).border(1.dp, Ui.border, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(hint, color = Ui.muted, fontSize = 14.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Ui.text, fontSize = 14.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ui.accent))
        }
        fun apply() { val a = w.toFloatOrNull(); val b = h.toFloatOrNull(); if (a != null && b != null && a > 0 && b > 0) vm.aspect = a / b }
        Text("Custom", color = Ui.text, fontSize = 14.sp)
        Field(w, "Width") { w = it.filter { c -> c.isDigit() || c == '.' }.take(6); apply() }
        Text(":", color = Ui.muted)
        Field(h, "Height") { h = it.filter { c -> c.isDigit() || c == '.' }.take(6); apply() }
    }
}

// ---------------------------------------------------------------------------- options

@Composable
private fun OptionsSheet(vm: EditorViewModel) {
    val t = vm.tool
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(if (t.paint != null) vm.strokeTool.label else t.label, color = Ui.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val paintLike = t.paint != null || t == Tool.BUCKET || t == Tool.GRADIENT
        if (t.paint != null) {
            BrushPreview(vm)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in BRUSH_PRESETS) {
                    val s = vm.settings
                    val sel = s.tipImage == null && s.shape == p.shape && s.hardness == p.hardness && s.aspect == p.aspect
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Ui.panel2)
                                .border(2.dp, if (sel) Ui.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { vm.settings = vm.settings.copy(shape = p.shape, hardness = p.hardness, aspect = p.aspect, angle = p.angle, spacing = p.spacing, tipImage = null) },
                            contentAlignment = Alignment.Center,
                        ) { TipGlyph(p.shape, p.hardness, p.aspect, p.angle) }
                        Text(p.name, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            ImageBrushes(vm)
            if (t == Tool.MYPAINT) MyPaintBrushes(vm)
            Spacer(Modifier.height(8.dp))
            val s = vm.settings
            LabeledSlider("Size", sizeToPos(s.size), 0f..1f, "${s.size.roundToInt()} px", { vm.settings = vm.settings.copy(size = posToSize(it)) })
            LabeledSlider("Opacity", s.opacity, 0.01f..1f, "${(s.opacity * 100).roundToInt()}%", { vm.settings = vm.settings.copy(opacity = it) })
            if (t != Tool.PENCIL) LabeledSlider("Hardness", s.hardness, 0f..1f, "%.2f".format(s.hardness), { vm.settings = vm.settings.copy(hardness = it) })
            LabeledSlider("Flow", s.flow, 0.01f..1f, "${(s.flow * 100).roundToInt()}%", { vm.settings = vm.settings.copy(flow = it) })
            LabeledSlider("Spacing", s.spacing, 0.01f..1f, "${(s.spacing * 100).roundToInt()}", { vm.settings = vm.settings.copy(spacing = it) })
            if (s.tipImage == null && s.shape != TipShape.SPLATTER) {
                // GIMP's brush editor: a generated shape with spikes, aspect and angle.
                SectionLabel("Brush shape", Modifier.padding(top = 10.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, n) in listOf("Circle", "Square", "Diamond").withIndex()) Chip(n, s.genShape == i) { vm.settings = vm.settings.copy(genShape = i) }
                    Chip("Save as brush", false) { vm.saveGeneratedBrush() }
                }
                LabeledSlider("Spikes", s.spikes.toFloat(), 2f..20f, "${s.spikes}", { vm.settings = vm.settings.copy(spikes = it.roundToInt()) })
                LabeledSlider("Angle", s.angle, -90f..90f, "${s.angle.roundToInt()}°", { vm.settings = vm.settings.copy(angle = it) })
                LabeledSlider("Aspect", s.aspect, 0.05f..1f, "%.2f".format(s.aspect), { vm.settings = vm.settings.copy(aspect = it) })
            } else if (s.shape == TipShape.FLAT) {
                LabeledSlider("Angle", s.angle, -90f..90f, "${s.angle.roundToInt()}°", { vm.settings = vm.settings.copy(angle = it) })
            }
            LabeledSlider("Smoothing", s.smoothing, 0f..0.95f, "${(s.smoothing * 100).roundToInt()}", { vm.settings = vm.settings.copy(smoothing = it) })
            val rateLabel = when (t) { Tool.SMUDGE -> "Rate"; Tool.DODGE_BURN -> "Exposure"; Tool.BLUR_SHARPEN -> "Strength"; else -> null }
            if (rateLabel != null) LabeledSlider(rateLabel, s.rate, 0f..1f, "${(s.rate * 100).roundToInt()}", { vm.settings = vm.settings.copy(rate = it) })
            Toggle("Pressure → size", s.pressureSize) { vm.settings = vm.settings.copy(pressureSize = it) }
            Toggle("Pressure → opacity", s.pressureOpacity) { vm.settings = vm.settings.copy(pressureOpacity = it) }
            SectionLabel("Dynamics")
            LabeledSlider("Speed → size", s.velocitySize, -1f..1f, signed(s.velocitySize), { vm.settings = vm.settings.copy(velocitySize = it) })
            LabeledSlider("Speed → opacity", s.velocityOpacity, -1f..1f, signed(s.velocityOpacity), { vm.settings = vm.settings.copy(velocityOpacity = it) })
            LabeledSlider("Random size", s.randomSize, 0f..1f, "${(s.randomSize * 100).roundToInt()}%", { vm.settings = vm.settings.copy(randomSize = it) })
            LabeledSlider("Random opacity", s.randomOpacity, 0f..1f, "${(s.randomOpacity * 100).roundToInt()}%", { vm.settings = vm.settings.copy(randomOpacity = it) })
            LabeledSlider("Random angle", s.randomAngle, 0f..1f, "±${(s.randomAngle * 180).roundToInt()}°", { vm.settings = vm.settings.copy(randomAngle = it) })
            LabeledSlider("Stylus tilt → size", s.tiltSize, 0f..1f, "${(s.tiltSize * 100).roundToInt()}%", { vm.settings = vm.settings.copy(tiltSize = it) })
            Toggle("Tip follows stylus direction", s.tiltAngle) { vm.settings = vm.settings.copy(tiltAngle = it) }
            if (t != Tool.SMUDGE) {
                SectionLabel("Symmetry")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Off", vm.symmetryKind == null) { vm.symmetryKind = null }
                    for (k in io.github.nielstron.brushkin.engine.Symmetry.Kind.entries) Chip(k.label, vm.symmetryKind == k) { vm.symmetryKind = k }
                }
                if (vm.symmetryKind == io.github.nielstron.brushkin.engine.Symmetry.Kind.MANDALA)
                    LabeledSlider("Segments", vm.symmetrySegments.toFloat(), 2f..24f, "${vm.symmetrySegments}", { vm.symmetrySegments = it.roundToInt() })
            }
        }
        if (paintLike && t != Tool.ERASER && t.paint?.isEffect != true && t != Tool.SMUDGE && !vm.eraseToggle) {
            if (t.paint == null) LabeledSlider("Opacity", vm.settings.opacity, 0.01f..1f, "${(vm.settings.opacity * 100).roundToInt()}%", { vm.settings = vm.settings.copy(opacity = it) })
            ModePicker(vm.settings.mode) { vm.settings = vm.settings.copy(mode = it) }
        }
        if (t.isSelect) {
            LabeledSlider("Feather", vm.feather, 0f..100f, "${vm.feather.roundToInt()} px", { vm.feather = it })
            Toggle("Antialiasing", vm.antialias) { vm.antialias = it }
            if (t == Tool.RECT_SELECT) LabeledSlider("Rounded corners", vm.cornerRadius, 0f..300f, "${vm.cornerRadius.roundToInt()} px", { vm.cornerRadius = it })
        }
        if (t == Tool.RECT_SELECT || t == Tool.ELLIPSE_SELECT) Toggle("Expand from center", vm.fromCenter) { vm.fromCenter = it }
        if (t == Tool.RECT_SELECT || t == Tool.ELLIPSE_SELECT || t == Tool.CROP) AspectPicker(vm)
        if (t == Tool.FUZZY_SELECT || t == Tool.COLOR_SELECT || t == Tool.BUCKET) {
            LabeledSlider("Threshold", vm.threshold, 0f..255f, "${vm.threshold.roundToInt()}", { vm.threshold = it })
            Toggle("Sample merged", vm.sampleMerged) { vm.sampleMerged = it }
        }
        if (t == Tool.BUCKET) Toggle("Fill whole selection", vm.fillWholeSelection) { vm.fillWholeSelection = it }
        if (t == Tool.GRADIENT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Chip("Linear", vm.gradientShape == GradientShape.LINEAR) { vm.gradientShape = GradientShape.LINEAR }
                Chip("Radial", vm.gradientShape == GradientShape.RADIAL) { vm.gradientShape = GradientShape.RADIAL }
            }
            GradientPicker(vm)
        }
        if (t == Tool.BUCKET) PatternPicker(vm)
        if (t == Tool.WARP) {
            LabeledSlider("Radius", vm.warpRadius, 10f..600f, "${vm.warpRadius.roundToInt()} px", { vm.warpRadius = it })
            LabeledSlider("Strength", vm.warpStrength, 0.05f..1f, "${(vm.warpStrength * 100).roundToInt()}%", { vm.warpStrength = it })
        }
        if (t == Tool.ROTATE) Toggle("Snap rotation to 15°", vm.snapRotation) { vm.snapRotation = it }
        if (t == Tool.SCALE) Toggle("Keep aspect ratio", vm.keepRatio) { vm.keepRatio = it }
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ui.text.copy(alpha = 0.85f), modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ui.accent, checkedThumbColor = Ui.onAccent))
    }
}

@Composable
fun ModePicker(mode: LayerMode, group: Boolean = false, onPick: (LayerMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().height(48.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text("Mode", color = Ui.text.copy(alpha = 0.85f), modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(mode.label, color = Ui.text, fontSize = 14.sp)
        }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 400.dp)) {
            for (m in LayerMode.entries.filter { group || it != LayerMode.PASS_THROUGH }) DropdownMenuItem(text = { Text(m.label) }, onClick = { onPick(m); open = false })
        }
    }
}

@Composable
private fun TipGlyph(shape: TipShape, hardness: Float, aspect: Float, angle: Float) {
    Canvas(Modifier.size(32.dp)) {
        val r = size.minDimension / 2
        when (shape) {
            TipShape.SPLATTER -> listOf(0.3f to 0.3f, 0.7f to 0.4f, 0.45f to 0.75f, 0.2f to 0.6f).forEach { (x, y) -> drawCircle(Ui.text, r * 0.14f, Offset(size.width * x, size.height * y)) }
            TipShape.CHALK -> for (i in 0 until 40) { val a = i * 2.4f; val d = r * (i % 7) / 7f; drawCircle(Ui.text, 1.2f, center + Offset(kotlin.math.cos(a) * d, kotlin.math.sin(a) * d)) }
            else -> {
                val inner = hardness.coerceIn(0.01f, 0.99f)
                val brush = Brush.radialGradient(0f to Ui.text, inner to Ui.text, 1f to Ui.text.copy(alpha = 0f), center = center, radius = r)
                rotateAndScale(angle, aspect) { drawCircle(brush, r) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateAndScale(angle: Float, aspect: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.transform.rotate(angle, center)
    drawContext.transform.scale(1f, aspect, center)
    block()
    drawContext.canvas.restore()
}

@Composable
private fun BrushPreview(vm: EditorViewModel) {
    val s = vm.settings
    val color = if (vm.strokeTool == Tool.ERASER) Color(0xFF888888) else Color(vm.fg)
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF4EFE6))) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(16f, size.height * 0.68f)
            cubicTo(size.width * 0.25f, size.height * 0.1f, size.width * 0.45f, size.height * 0.95f, size.width * 0.6f, size.height * 0.4f)
            cubicTo(size.width * 0.75f, 0f, size.width * 0.85f, size.height * 0.6f, size.width - 16f, size.height * 0.5f)
        }
        val w = (s.size * 0.5f).coerceIn(2f, size.height * 0.8f)
        drawPath(p, color.copy(alpha = s.opacity), style = Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------- color

private val PALETTE = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF2B3A67, 0xFF3D3550, 0xFFC46A5A, 0xFFF2B66D, 0xFFFBD38D, 0xFF9FD3D6,
    0xFF2E5E6E, 0xFF1A1A24, 0xFFE9E1D3, 0xFF1E5F74, 0xFFE5484D, 0xFF46A758, 0xFF3E63DD, 0xFF8E4EC6,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSheet(vm: EditorViewModel) {
    val hsv = remember(vm.fg) { FloatArray(3).also { android.graphics.Color.colorToHSV(vm.fg, it) } }
    var hue by remember(vm.fg) { mutableStateOf(hsv[0]) }
    var sat by remember(vm.fg) { mutableStateOf(hsv[1]) }
    var value by remember(vm.fg) { mutableStateOf(hsv[2]) }
    fun commit(h: Float = hue, s: Float = sat, v: Float = value) { vm.fg = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) }

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp, 52.dp)) {
                Box(Modifier.align(Alignment.BottomEnd).size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(vm.bg)).border(3.dp, Ui.panel, RoundedCornerShape(10.dp)))
                Box(Modifier.align(Alignment.TopStart).size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(vm.fg)).border(2.dp, Ui.accent, RoundedCornerShape(10.dp)))
            }
            var hex by remember(vm.fg) { mutableStateOf("%06X".format(vm.fg and 0xFFFFFF)) }
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Ui.panel2).border(1.dp, Ui.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = Ui.muted)
                BasicTextField(
                    hex, { v ->
                        hex = v.filter { it.isLetterOrDigit() }.take(6).uppercase()
                        if (hex.length == 6) hex.toLongOrNull(16)?.let { vm.fg = (0xFF000000 or it).toInt() }
                    },
                    textStyle = TextStyle(color = Ui.text, fontFamily = Ui.mono, fontSize = 15.sp), singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ui.accent), modifier = Modifier.padding(start = 6.dp),
                )
            }
            IconButton(onClick = { vm.pipette = true; vm.sheet = Sheet.NONE }) { Icon(Icons.Rounded.Colorize, "Pick a color from the image", tint = Ui.text) }
            IconButton(onClick = { vm.swapColors() }) { Icon(Icons.Rounded.SwapHoriz, "Swap foreground and background", tint = Ui.text) }
        }
        Spacer(Modifier.height(14.dp))
        // Saturation/value square.
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp))
                .pointerInput(hue) {
                    fun set(o: Offset) { sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); commit() }
                    detectDragGestures(onDragStart = { set(it) }) { c, _ -> set(c.position) }
                }
                .pointerInput(hue) { detectTapGestures { o -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); commit() } },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(sat * size.width, (1 - value) * size.height)
            drawCircle(Color.White, 11.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.5f), 12.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(14.dp))
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    fun set(o: Offset) { hue = (o.x / size.width).coerceIn(0f, 1f) * 360f; commit(h = hue) }
                    detectDragGestures(onDragStart = { set(it) }) { c, _ -> set(c.position) }
                },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            drawCircle(Color.White, size.height / 2, Offset(hue / 360f * size.width, size.height / 2), style = Stroke(3.dp.toPx()))
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("Palette")
        Spacer(Modifier.height(8.dp))
        PaletteSection(vm)
        Spacer(Modifier.height(16.dp))
        SectionLabel("Recent")
        Spacer(Modifier.height(8.dp))
        Swatches(vm.recentColors.toList()) { vm.fg = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Int>, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in colors) Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(c)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).clickable { onPick(c) }
        )
    }
}

// ---------------------------------------------------------------------------- layers

@Composable
private fun LayersSheet(vm: EditorViewModel, info: DocInfo) {
    val e = vm.engine
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", color = Ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text("${info.width} × ${info.height}", color = Ui.muted, fontFamily = Ui.mono, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(info.layers, key = { it.id }) { l -> LayerRow(vm, l, l.id == info.activeId, info) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(Ui.accent).clickable { e.addLayer() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { Text("+  New layer", color = Ui.onAccent, fontWeight = FontWeight.SemiBold) }
            SquareAction(Icons.Rounded.CreateNewFolder, "New layer group") { e.addGroup() }
            SquareAction(Icons.Rounded.ContentCopy, "Duplicate layer") { e.duplicateLayer() }
            SquareAction(Icons.Rounded.VerticalAlignBottom, "Merge down") { e.mergeDown(info.activeId) }
            SquareAction(Icons.Rounded.Delete, "Delete layer") { e.deleteLayer(info.activeId) }
        }
        ChannelsSection(vm, info)
    }
}

/** Saved channels (stored selections): show/hide as an overlay, load into the selection, delete. */
@Composable
private fun ChannelsSection(vm: EditorViewModel, info: DocInfo) {
    val e = vm.engine
    if (info.channels.isEmpty() && !info.hasSelection) return
    SectionLabel("Channels", Modifier.padding(top = 4.dp, bottom = 6.dp))
    for ((id, name, visible) in info.channels) Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ui.panel2).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { e.updateChannel(id, if (visible) "Hide Channel" else "Show Channel") { it.copy(visible = !it.visible) } }) {
            Icon(if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (visible) "Hide channel" else "Show channel", tint = if (visible) Ui.text else Ui.muted)
        }
        Text(name, color = Ui.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Chip("Select", false) { e.channelToSelection(id, vm.selectMode) }
        IconButton(onClick = { e.updateChannel(id, "Delete Channel") { null } }) { Icon(Icons.Rounded.Delete, "Delete channel", tint = Ui.muted) }
    }
    if (info.hasSelection) Box(Modifier.padding(top = 8.dp)) { Chip("Save selection to channel", false) { e.saveSelectionToChannel() } }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SquareAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Ui.text)
    }
}

@Composable
private fun LayerRow(vm: EditorViewModel, l: LayerInfo, active: Boolean, info: DocInfo) {
    val e = vm.engine
    var opacity by remember(l.id, l.opacity) { mutableStateOf(l.opacity) }
    var renaming by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) Color(0xFF2A2822) else Color.Transparent)
            .border(1.dp, if (active) Color(0xFF4A4232) else Color.Transparent, RoundedCornerShape(14.dp))
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).clickable { e.setActive(l.id) }.padding(start = (l.depth * 18).dp), verticalAlignment = Alignment.CenterVertically) {
            if (l.isGroup) IconButton(onClick = { e.toggleExpanded(l.id) }, modifier = Modifier.size(32.dp, 48.dp)) {
                Icon(if (l.expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, if (l.expanded) "Collapse group" else "Expand group", tint = Ui.muted)
            }
            IconButton(onClick = { e.setLayer(l.id, if (l.visible) "Hide Layer" else "Show Layer") { it.copy(visible = !it.visible) } }) {
                Icon(if (l.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (l.visible) "Hide ${l.name}" else "Show ${l.name}", tint = if (l.visible) Ui.text else Ui.muted)
            }
            // Tap the layer or mask thumbnail to choose what tools edit (like GIMP).
            val editingPixels = active && !l.editMask
            Box(
                Modifier.size(52.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A40))
                    .border(2.dp, if (l.hasMask && editingPixels) Ui.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Edit layer pixels") { e.setEditMask(l.id, false) },
                contentAlignment = Alignment.Center,
            ) {
                l.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
            }
            if (l.hasMask) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(34.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                        .border(2.dp, if (active && l.editMask) Ui.accent else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Edit layer mask") { e.setEditMask(l.id, true) },
                    contentAlignment = Alignment.Center,
                ) {
                    l.maskThumb?.let { Image(it.asImageBitmap(), "Layer mask", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), alpha = if (l.maskEnabled) 1f else 0.35f) }
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(l.name, color = if (l.visible) Ui.text else Ui.muted, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                val sub = buildList {
                    if (l.isGroup) add("Group"); if (l.isText) add("Text"); add(l.mode.label); if (l.opacity < 1f) add("${(l.opacity * 100).roundToInt()}%"); if (l.lockAlpha) add("alpha locked")
                    if (l.effects.isNotEmpty()) add("${l.effects.size} effect" + if (l.effects.size > 1) "s" else "")
                    if (l.lockPosition) add("position locked"); if (l.linked) add("linked"); if (l.bounds != null) add("${l.bounds.width()}×${l.bounds.height()}")
                    if (active && l.editMask) add("editing mask") else if (l.hasMask && !l.maskEnabled) add("mask off")
                }.joinToString(" · ")
                Text(sub, color = Ui.muted, fontSize = 12.sp, maxLines = 1)
            }
            if (l.lockAlpha) Icon(Icons.Rounded.Lock, null, tint = Ui.muted, modifier = Modifier.size(16.dp).padding(end = 4.dp))
        }
        if (active) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
            ModePicker(l.mode, group = l.isGroup) { m -> e.setLayer(l.id, "Layer Mode") { it.copy(mode = m) } }
            LabeledSlider("Opacity", opacity, 0f..1f, "${(opacity * 100).roundToInt()}", {
                opacity = it; e.setLayer(l.id, "Layer Opacity", commit = false) { x -> x.copy(opacity = it) }
            }, onDone = { e.setLayer(l.id, "Layer Opacity", commit = true) { x -> x.copy(opacity = opacity) } })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Lock alpha", l.lockAlpha, if (l.lockAlpha) Icons.Rounded.Lock else Icons.Rounded.LockOpen) { e.setLayer(l.id, "Lock Alpha") { it.copy(lockAlpha = !it.lockAlpha) } }
                Chip("Lock position", l.lockPosition) { e.setLayer(l.id, "Lock Position") { it.copy(lockPosition = !it.lockPosition) } }
                Chip("Link", l.linked) { e.setLayer(l.id, "Link Layer") { it.copy(linked = !it.linked) } }
                IconButton(onClick = { e.moveLayer(l.id, +1) }) { Icon(Icons.Rounded.ArrowUpward, "Raise layer", tint = Ui.text) }
                IconButton(onClick = { e.moveLayer(l.id, -1) }) { Icon(Icons.Rounded.ArrowDownward, "Lower layer", tint = Ui.text) }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename layer", tint = Ui.text) }
            }
            if (l.effects.isNotEmpty()) {
                // Non-destructive effects: tap to hide/show, ✕ to remove, or merge them into the pixels.
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Effects", color = Ui.muted, fontSize = 12.sp)
                    for ((i, fx) in l.effects.withIndex()) {
                        Chip(fx.name, fx.visible) { e.setEffects(l.id, if (fx.visible) "Hide Effect" else "Show Effect") { list -> list.mapIndexed { k, x -> if (k == i) x.copy(visible = !x.visible) else x } } }
                        Text("✕", color = Ui.muted, modifier = Modifier.clickable { e.setEffects(l.id, "Remove Effect") { list -> list.filterIndexed { k, _ -> k != i } } }.padding(4.dp))
                    }
                    Chip("Merge", false) { e.mergeEffects(l.id) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (l.isText) Chip("Edit text", false, ToolIcons.text) { vm.sheet = Sheet.NONE; vm.dialog = "text-edit:${l.id}" }
                if (l.depth > 0) Chip("Move out of group", false) { e.moveOutOfGroup(l.id) }
                Chip("Into group above", false) { e.moveIntoGroupAbove(l.id) }
                if (!l.isGroup) Chip("Put in new group", false) { e.groupActive() }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!l.hasMask) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Chip("Add mask", false) { open = true }
                        DropdownMenu(open, { open = false }) {
                            for (m in io.github.nielstron.brushkin.engine.Engine.MaskInit.entries) {
                                if (m == io.github.nielstron.brushkin.engine.Engine.MaskInit.SELECTION && !info.hasSelection) continue
                                DropdownMenuItem(text = { Text(m.label) }, onClick = { open = false; e.addMask(m) })
                            }
                        }
                    }
                } else {
                    Chip(if (l.maskEnabled) "Disable mask" else "Enable mask", !l.maskEnabled) { e.setLayer(l.id, "Toggle Mask") { it.copy(maskEnabled = !it.maskEnabled) } }
                    Chip("Apply mask", false) { e.applyMask() }
                    Chip("Delete mask", false) { e.deleteMask() }
                    Chip("Mask to selection", false) { e.maskToSelection() }
                }
            }
        }
    }
    if (renaming) TextPrompt("Rename layer", l.name, onDismiss = { renaming = false }) { n -> renaming = false; e.setLayer(l.id, "Rename Layer") { it.copy(name = n) } }
}

// ---------------------------------------------------------------------------- history

@Composable
private fun HistorySheet(vm: EditorViewModel, info: DocInfo) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text("History", color = Ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val rows = listOf("Original") + info.undoNames + info.redoNames
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(rows.size) { i ->
                val current = i == info.undoNames.size
                val future = i > info.undoNames.size
                Text(
                    rows[i], color = if (future) Ui.muted else Ui.text, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (current) Ui.accentSoft else Color.Transparent)
                        .clickable { vm.engine.jumpHistory(i) }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- insert

private val EMOJI = listOf(
    "😀", "😂", "😍", "😎", "🥳", "😭", "🤔", "👍", "🔥", "✨", "🌅", "⛵", "🐟", "🌊", "🎨", "❤️",
    "⭐", "🌸", "🍀", "🐱", "🐶", "🦊", "🌙", "☀️", "⚡", "💧", "🎉", "💬", "👀", "🙌", "🍕", "☕",
)

enum class Sticker(val label: String) { STAR("Star"), HEART("Heart"), BUBBLE("Speech bubble"), SPARKLE("Sparkle"), ARROW("Arrow") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsertSheet(vm: EditorViewModel, files: FileActions) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        Text("Insert", color = Ui.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        SectionLabel("Image as layer")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InsertTile(Icons.Rounded.Image, "Gallery", Modifier.weight(1f)) { vm.sheet = Sheet.NONE; files.pickImageLayer() }
            InsertTile(Icons.Rounded.FolderOpen, "Files", Modifier.weight(1f)) { vm.sheet = Sheet.NONE; files.pickImageLayerFile() }
            InsertTile(Icons.Rounded.ContentPaste, "Paste", Modifier.weight(1f)) { vm.sheet = Sheet.NONE; vm.paste() }
            InsertTile(Icons.Rounded.CreateNewFolder, "Empty layer", Modifier.weight(1f)) { vm.sheet = Sheet.NONE; vm.engine.addLayer() }
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("Text layer")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, style) in TEXT_STYLES) {
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel2)
                        .clickable { vm.sheet = Sheet.NONE; vm.textStyle = style; vm.selectTool(Tool.TEXT); vm.messages.tryEmit("Tap where the text should go") },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = Ui.text, style = style.preview) }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("Stickers")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Sticker.entries) {
                val bmp = remember(s) { renderSticker(s, 96, vm.fg) }
                Box(
                    Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel2)
                        .clickable { vm.sheet = Sheet.NONE; vm.engine.placeFloating(renderSticker(s, 600, vm.fg), s.label) },
                    contentAlignment = Alignment.Center,
                ) { Image(bmp.asImageBitmap(), s.label, Modifier.size(36.dp)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Emoji", Modifier.weight(1f))
            Text("All emoji…", color = Ui.accent, fontSize = 13.sp, modifier = Modifier.clickable { vm.sheet = Sheet.NONE; vm.dialog = "emoji" }.padding(8.dp))
        }
        FlowRow(maxItemsInEachRow = 8, horizontalArrangement = Arrangement.SpaceBetween) {
            for (em in EMOJI) Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.sheet = Sheet.NONE; vm.engine.placeFloating(renderText(em, 400f, Typeface.DEFAULT, android.graphics.Color.BLACK), "Emoji $em") },
                contentAlignment = Alignment.Center,
            ) { Text(em, fontSize = 24.sp) }
        }
    }
}

@Composable
private fun InsertTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel2).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Ui.text)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Ui.text, fontSize = 11.sp)
    }
}

/** Renders text (including color emoji) into a tightly cropped bitmap. */
fun renderText(text: String, sizePx: Float, typeface: Typeface, color: Int, outline: Boolean = false): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = sizePx; this.color = color }
    val lines = text.split('\n')
    val fm = p.fontMetrics
    val lineH = fm.descent - fm.ascent
    val pad = sizePx * 0.15f
    val w = (lines.maxOf { p.measureText(it) } + pad * 2).toInt().coerceAtLeast(1)
    val h = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    lines.forEachIndexed { i, line ->
        val y = pad - fm.ascent + i * lineH
        if (outline) {
            val o = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = sizePx * 0.08f; this.color = android.graphics.Color.WHITE; strokeJoin = Paint.Join.ROUND }
            c.drawText(line, pad, y, o)
        }
        c.drawText(line, pad, y, p)
    }
    return bmp
}

fun renderSticker(s: Sticker, size: Int, accent: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.scale(size / 24f, size / 24f)
    val (d, fill, stroke) = when (s) {
        Sticker.STAR -> Triple("M12 2l3 6.5 7 .8-5.2 4.8 1.5 7L12 17.6 5.7 21.1l1.5-7L2 9.3l7-.8z", 0xFFFBD38D.toInt(), android.graphics.Color.WHITE)
        Sticker.HEART -> Triple("M12 21s-8-5-8-11a4.5 4.5 0 0 1 8-3 4.5 4.5 0 0 1 8 3c0 6-8 11-8 11z", 0xFFF07AA8.toInt(), android.graphics.Color.WHITE)
        Sticker.BUBBLE -> Triple("M4 5h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9l-5 4v-4H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", 0xFFF4EFE6.toInt(), 0xFF1A1A24.toInt())
        Sticker.SPARKLE -> Triple("M12 2c1 6 4 9 10 10-6 1-9 4-10 10-1-6-4-9-10-10 6-1 9-4 10-10z", 0xFF9FD3D6.toInt(), android.graphics.Color.WHITE)
        Sticker.ARROW -> Triple("M3 10h11V5l7 7-7 7v-5H3z", accent, android.graphics.Color.WHITE)
    }
    val path: Path = androidx.core.graphics.PathParser.createPathFromPathData(d)
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND })
    return bmp
}

private fun signed(v: Float) = (if (v > 0.005f) "+" else "") + "${(v * 100).roundToInt()}%"

// ---------------------------------------------------------------------------- resources

@Composable
private fun ImageBrushes(vm: EditorViewModel) {
    val brushes by vm.resources.brushes.collectAsState()
    Spacer(Modifier.height(10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (b in brushes) {
            val sel = vm.settings.tipImage == b.name
            val thumb = remember(b) { b.frames.first().extractAlpha() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Ui.panel2)
                        .border(2.dp, if (sel) Ui.accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { vm.settings = vm.settings.copy(tipImage = b.name, spacing = b.spacing.coerceIn(0.02f, 1f)) },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.foundation.Image(thumb.asImageBitmap(), b.name, Modifier.size(40.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Ui.text)) }
                Text(b.name.take(10), color = Ui.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
            }
        }
        Chip("From selection", false) { vm.selectionToResource(brush = true) }
        Chip("Import…", false) { vm.dialog = "import" }
    }
}

@Composable
private fun MyPaintBrushes(vm: EditorViewModel) {
    val list by vm.resources.mypaint.collectAsState()
    SectionLabel("MyPaint brushes", Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in list) Chip(b.name, vm.myPaintBrush == b.name) { vm.myPaintBrush = b.name; vm.settings = b.settings }
        Chip("Import .myb…", false) { vm.dialog = "import" }
    }
}

@Composable
private fun GradientPicker(vm: EditorViewModel) {
    val list by vm.resources.gradients.collectAsState()
    SectionLabel("Gradient", Modifier.padding(top = 10.dp, bottom = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in list) {
            val (cols, pos) = g.stops(vm.fg, vm.bg, vm.gradientReverse)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (vm.gradient === g) Ui.panel2 else Color.Transparent)
                    .border(1.dp, if (vm.gradient === g) Ui.accent else Ui.border, RoundedCornerShape(10.dp)).clickable { vm.gradient = g }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.width(110.dp).height(22.dp).clip(RoundedCornerShape(6.dp))) {
                    val n = 32
                    for (k in 0 until n) {
                        val x = (k + 0.5f) / n
                        drawRect(Color(sampleStops(cols, pos, x)), topLeft = Offset(size.width * k / n, 0f), size = androidx.compose.ui.geometry.Size(size.width / n + 1, size.height))
                    }
                }
                Text(g.name, color = Ui.text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
            }
        }
    }
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Reverse", vm.gradientReverse) { vm.gradientReverse = !vm.gradientReverse }
        Chip("Edit…", false) { vm.dialog = "gradient-edit" }
        Chip("Import…", false) { vm.dialog = "import" }
    }
}

/** Color of a multi-stop gradient at [x] (0…1). */
fun sampleStops(cols: IntArray, pos: FloatArray, x: Float): Int {
    if (cols.isEmpty()) return 0
    if (x <= pos.first()) return cols.first()
    for (i in 1 until pos.size) if (x <= pos[i]) {
        val t = if (pos[i] - pos[i - 1] < 1e-6f) 1f else (x - pos[i - 1]) / (pos[i] - pos[i - 1])
        return io.github.nielstron.brushkin.engine.GradientRes.lerpColor(cols[i - 1], cols[i], t)
    }
    return cols.last()
}

@Composable
private fun PatternPicker(vm: EditorViewModel) {
    val list by vm.resources.patterns.collectAsState()
    Toggle("Fill with pattern", vm.fillWithPattern) { vm.fillWithPattern = it; if (it && vm.pattern == null) vm.pattern = list.firstOrNull() }
    if (vm.fillWithPattern) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (p in list) {
            val img = remember(p) { p.bitmap.asImageBitmap() }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (vm.pattern === p) Ui.accent else Ui.border, RoundedCornerShape(10.dp)).clickable { vm.pattern = p }
                    .drawBehind {
                        // Tile the pattern across the swatch.
                        var y = 0f
                        while (y < size.height) { var x = 0f; while (x < size.width) { drawImage(img, Offset(x, y)); x += img.width.coerceAtLeast(1) }; y += img.height.coerceAtLeast(1) }
                    },
            )
        }
        Chip("From selection", false) { vm.selectionToResource(brush = false) }
        Chip("Edit…", false) { vm.dialog = "pattern-edit" }
        Chip("Import…", false) { vm.dialog = "import" }
    }
    if (vm.fillWithPattern && list.isEmpty()) Chip("Create a pattern…", false) { vm.dialog = "pattern-edit" }
}

/** Palette swatches; user palettes can be edited (+ adds the FG color, long-press removes). */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PaletteSection(vm: EditorViewModel) {
    val palettes by vm.resources.palettes.collectAsState()
    val current = vm.palette
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Default", current == null) { vm.palette = null }
        for (p in palettes) Chip(p.name, current === p) { vm.palette = p }
        Chip("New", false) {
            vm.palette = vm.resources.savePalette(io.github.nielstron.brushkin.engine.PaletteRes("Palette ${palettes.size + 1}", listOf(vm.fg)))
        }
        Chip("Import…", false) { vm.dialog = "import" }
    }
    val colors = current?.colors ?: PALETTE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((i, c) in colors.withIndex()) Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(c)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .combinedClickable(onClick = { vm.pickColor(c) }, onLongClick = {
                    if (current != null) vm.palette = vm.resources.savePalette(io.github.nielstron.brushkin.engine.PaletteRes(current.name, current.colors.filterIndexed { k, _ -> k != i }), current)
                })
        )
        if (current != null) Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ui.border, RoundedCornerShape(10.dp))
                .clickable { vm.palette = vm.resources.savePalette(io.github.nielstron.brushkin.engine.PaletteRes(current.name, current.colors + vm.fg), current) },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Ui.text, fontSize = 18.sp) }
    }
    if (current != null) Text("Tap + to add the current color · long-press a swatch to remove it", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
}
