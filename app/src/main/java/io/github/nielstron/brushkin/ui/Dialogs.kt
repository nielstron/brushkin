package io.github.nielstron.brushkin.ui

import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import io.github.nielstron.brushkin.engine.TextSpec
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nielstron.brushkin.engine.Channel
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.engine.ColorBalanceFilter
import io.github.nielstron.brushkin.engine.Curve
import io.github.nielstron.brushkin.engine.DocInfo
import io.github.nielstron.brushkin.engine.Filter
import io.github.nielstron.brushkin.engine.GaussianBlurFilter
import io.github.nielstron.brushkin.engine.HueSaturationFilter
import io.github.nielstron.brushkin.engine.IDENTITY_CURVE
import io.github.nielstron.brushkin.engine.LutFilter
import io.github.nielstron.brushkin.engine.NoiseFilter
import io.github.nielstron.brushkin.engine.PixelizeFilter
import io.github.nielstron.brushkin.engine.ThresholdFilter
import io.github.nielstron.brushkin.engine.UnsharpMaskFilter
import io.github.nielstron.brushkin.engine.curveLut
import kotlin.math.abs
import kotlin.math.roundToInt

interface FileActions {
    fun pickImageLayer()
    fun pickImageLayerFile()
    fun openImage()
    fun openProject()
    fun saveProject()
    suspend fun exportPreview(options: io.github.nielstron.brushkin.export.ImageExportOptions): android.graphics.Bitmap
    fun export(options: io.github.nielstron.brushkin.export.ImageExportOptions)
    suspend fun stickerPacks(): List<io.github.nielstron.brushkin.export.StickerPack>
    suspend fun addSticker(packId: String?, name: String, emoji: String): io.github.nielstron.brushkin.export.StickerPack
    suspend fun removeSticker(packId: String, file: String): io.github.nielstron.brushkin.export.StickerPack
    fun importWhatsApp(packId: String)
    fun exportTelegram(emoji: String)
    fun openExport(uri: android.net.Uri, mime: String)
    fun share()
    fun importResources()
}

data class TextStyleSpec(val typeface: Typeface, val preview: TextStyle, val outline: Boolean = false)

val TEXT_STYLES = listOf(
    "Aa" to TextStyleSpec(Typeface.create("sans-serif", Typeface.NORMAL), TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)),
    "Title" to TextStyleSpec(Typeface.create("serif", Typeface.BOLD), TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)),
    "hello" to TextStyleSpec(Typeface.create("cursive", Typeface.BOLD), TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Cursive, color = Color(0xFFFBD38D))),
)

@Composable
fun TextPrompt(title: String, initial: String, keyboard: KeyboardType = KeyboardType.Text, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard)) },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Ui.panel,
    )
}

@Composable
fun Dialogs(vm: EditorViewModel, info: DocInfo, files: FileActions) {
    val d = vm.dialog ?: return
    val close = { vm.dialog = null }
    val e = vm.engine
    when {
        d == "stroke-selection" || d == "fill-selection-outline" -> SelectionOutlineDialog(vm, d == "stroke-selection", close)
        d == "save" -> SaveMenuDialog(close) { vm.dialog = it }
        d == "export-image" -> ImageExportDialog(info, files, close) { options -> close(); files.export(options) }
        d == "save-project" -> androidx.compose.runtime.LaunchedEffect(Unit) { close(); files.saveProject() }
        d == "export-sticker" -> StickerExportDialog(info, files, close)
        d == "new" -> NewImageDialog(vm, close)
        d == "emoji" -> EmojiDialog(vm, close)
        d == "text" -> { close(); vm.selectTool(Tool.TEXT); vm.messages.tryEmit("Tap where the text should go") }
        d.startsWith("text-edit:") -> { close(); vm.selectTool(Tool.TEXT); vm.editTextLayer(d.removePrefix("text-edit:").toLong()) }
        d.startsWith("rename-tab:") -> {
            val tab = vm.tabs.items.firstOrNull { it.id == d.removePrefix("rename-tab:").toLong() }
            if (tab == null) close()
            else TextPrompt("Rename image", tabTitle(tab.name), onDismiss = close) { name -> close(); vm.tabs.renameTitle(tab, name) }
        }
        d.startsWith("rename-layer:") -> {
            val id = d.removePrefix("rename-layer:").toLong()
            val layer = info.layers.first { it.id == id }
            TextPrompt("Rename layer", layer.name, onDismiss = close) { name -> close(); e.setLayer(id, "Rename Layer") { it.copy(name = name) } }
        }
        d == "scale" -> SizeDialog("Scale image", info.width, info.height, true, close) { w, h -> e.scaleImage(w, h) }
        d == "layer-bounds" -> {
            val al = info.layers.firstOrNull { it.id == info.activeId }
            val cur = al?.bounds ?: android.graphics.Rect(0, 0, info.width, info.height)
            SizeDialog("Layer boundary size", cur.width(), cur.height(), false, close) { w, h -> e.setLayerBoundary(android.graphics.Rect(cur.left, cur.top, cur.left + w, cur.top + h)) }
        }
        d == "canvas" -> SizeDialog("Canvas size", info.width, info.height, false, close) { w, h -> e.canvasSize(w, h, (w - info.width) / 2, (h - info.height) / 2) }
        d == "feather" || d == "grow" || d == "shrink" -> TextPrompt("${d.replaceFirstChar { it.uppercase() }} selection by (px)", "10", KeyboardType.Number, close) { v ->
            close()
            val r = v.toIntOrNull()?.coerceIn(1, 500) ?: return@TextPrompt
            e.selectModify(d.replaceFirstChar { it.uppercase() } + " Selection", r, when (d) { "grow" -> 1; "shrink" -> -1; else -> 0 })
        }
        d == "import" -> { close(); files.importResources() }
        d == "gradient-edit" -> GradientEditor(vm, close)
        d == "pattern-edit" -> PatternEditor(vm, close)
        d == "model-download" -> ModelDownloadDialog(vm, close)
        d == "depth-of-field" -> DepthOfFieldPanel(vm, close)
        d == "upscale" -> AlertDialog(
            onDismissRequest = close, containerColor = Ui.panel,
            title = { Text("Upscale image", color = Ui.text) },
            text = { Text("Enlarges every layer with an on-device model that adds detail (PLKSR-Rep). Now ${info.width} × ${info.height} px.", color = Ui.text, fontSize = 14.sp) },
            confirmButton = {
                Row {
                    TextButton(onClick = { close(); vm.upscale(2) }) { Text("×2 (${info.width * 2} × ${info.height * 2})") }
                    TextButton(onClick = { close(); vm.upscale(4) }) { Text("×4") }
                }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        d == "grid" -> TextPrompt("Grid spacing (px)", vm.gridSize.toInt().toString(), KeyboardType.Number, close) { v ->
            close(); v.toIntOrNull()?.coerceIn(2, 4096)?.let { vm.gridSize = it.toFloat(); vm.showGrid = true }
        }
        d == "border" -> TextPrompt("Border selection width (px)", "10", KeyboardType.Number, close) { v ->
            close(); v.toIntOrNull()?.coerceIn(1, 500)?.let { e.selectBorder(it) }
        }
        d.startsWith("filter:") -> FilterPanel(vm, d.removePrefix("filter:"), close)
    }
}

/** Every system emoji, via AndroidX's emoji picker; the chosen one is placed as a layer. */
@Composable
private fun EmojiDialog(vm: EditorViewModel, close: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.panel).padding(8.dp)) {
            Text("Emoji", color = Ui.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(query) {
                results = if (query.isBlank()) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EmojiSearch.search(ctx, query) }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EmojiSearch.index(ctx) } }
            fun place(emoji: String) {
                close()
                vm.engine.placeFloating(renderText(emoji, 400f, Typeface.DEFAULT, android.graphics.Color.BLACK), "Emoji $emoji")
            }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search emoji (e.g. boat, heart, laugh)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            )
            if (query.isNotBlank()) {
                if (results.isEmpty()) Text("No emoji found", color = Ui.muted, modifier = Modifier.padding(16.dp).height(428.dp))
                else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Fixed(8), Modifier.fillMaxWidth().height(460.dp),
                ) {
                    items(results.size) { i ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { place(results[i]) }, contentAlignment = Alignment.Center) {
                            Text(results[i], fontSize = 26.sp)
                        }
                    }
                }
            } else androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                        emojiGridColumns = 8
                        setOnEmojiPickedListener { item ->
                            close()
                            vm.engine.placeFloating(renderText(item.emoji, 400f, Typeface.DEFAULT, android.graphics.Color.BLACK), "Emoji ${item.emoji}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(460.dp),
            )
        }
    }
}

@Composable
private fun NewImageDialog(vm: EditorViewModel, close: () -> Unit) {
    var w by remember { mutableStateOf("1600") }
    var h by remember { mutableStateOf("2400") }
    var transparent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("New image") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, size) in listOf("Phone" to (1080 to 2400), "Square" to (2048 to 2048), "A4 150dpi" to (1240 to 1754), "HD" to (1920 to 1080), "4K" to (3840 to 2160))) {
                        Chip(label, w == "${size.first}" && h == "${size.second}") { w = "${size.first}"; h = "${size.second}" }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit) }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Toggle("Transparent background", transparent) { transparent = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull()?.coerceIn(1, 8192); val hh = h.toIntOrNull()?.coerceIn(1, 8192)
                if (ww != null && hh != null) { vm.tabs.newTab(ww, hh, if (transparent) null else vm.bg); close() }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun SizeDialog(title: String, w0: Int, h0: Int, keepRatioDefault: Boolean, close: () -> Unit, ok: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf("$w0") }
    var h by remember { mutableStateOf("$h0") }
    var keep by remember { mutableStateOf(keepRatioDefault) }
    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text(title) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { v -> w = v.filter(Char::isDigit); if (keep) w.toIntOrNull()?.let { h = "${(it * h0 / w0.toFloat()).roundToInt()}" } }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(h, { v -> h = v.filter(Char::isDigit); if (keep) h.toIntOrNull()?.let { w = "${(it * w0 / h0.toFloat()).roundToInt()}" } }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Toggle("Keep aspect ratio", keep) { keep = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull()?.coerceIn(1, 8192); val hh = h.toIntOrNull()?.coerceIn(1, 8192)
                if (ww != null && hh != null) { ok(ww, hh); close() }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

val FONTS = listOf("Sans" to "sans-serif", "Serif" to "serif", "Mono" to "monospace", "Script" to "cursive", "Casual" to "casual", "Condensed" to "sans-serif-condensed", "Black" to "sans-serif-black", "Meme" to io.github.nielstron.brushkin.engine.TextSpec.MEME_FONT)

// ---------------------------------------------------------------------------- filters

/** A filter parameter; [default] is what the panel opens with and Reset goes back to. */
internal data class Param(val label: String, val range: ClosedFloatingPointRange<Float>, val default: Float, val choices: List<Pair<Float, String>> = emptyList(), val integral: Boolean = false, val allowedRange: ClosedFloatingPointRange<Float> = range, val gamma: Float = 1f, val fmt: (Float) -> String = { "%.2f".format(it) })

internal class FilterSpec(val params: List<Param>, val build: (List<Float>) -> Filter)

/** The filters GEGL has no operation for; every other filter's controls are generated from GEGL. */
private fun specs(fg: Int, bg: Int): Map<String, FilterSpec> = geglSpecs + mapOf(
    "Colorize" to FilterSpec(listOf(Param("Hue", 0f..360f, 200f) { "${it.roundToInt()}°" }, Param("Saturation", 0f..1f, 0.5f), Param("Lightness", -1f..1f, 0f))) {
        ColorizeFilter(it[0], it[1], it[2])
    },
    "Color Balance" to FilterSpec(listOf(Param("Cyan ↔ Red", -1f..1f, 0f), Param("Magenta ↔ Green", -1f..1f, 0f), Param("Yellow ↔ Blue", -1f..1f, 0f))) { ColorBalanceFilter(it[0], it[1], it[2]) },
    // Kept for "Color to Alpha" effects in saved documents; the panel picks its own color.
    "Color to Alpha" to FilterSpec(emptyList()) { ColorToAlphaFilter(bg) },
)

/** Effects saved before these filters moved to GEGL hold Brushkin's own parameters, by position. */
private fun filterSavedBeforeGegl(name: String, p: List<Float>): Filter? = when (name) {
    "Drop Shadow" -> DropShadowFilter(p[0], p[1], p[2], p[3], android.graphics.Color.BLACK)
    "Vignette" -> VignetteFilter(p[0], p[1], p[2], android.graphics.Color.BLACK)
    "Motion Blur" -> MotionBlurFilter(p[0], p[1])
    "Shadows-Highlights" -> ShadowsHighlightsFilter(p[0], p[1], p[2])
    "Exposure" -> ExposureFilter(p[0], p[1])
    "Channel Mixer" -> ChannelMixerFilter(p.toFloatArray(), false)
    "Noise Reduction" -> NoiseReductionFilter(p[0].roundToInt())
    "Ripple" -> RippleFilter(p[0], p[1], p[2] > 0.5f)
    "Whirl and Pinch" -> WhirlPinchFilter(p[0], p[1], p[2])
    "Lens Distortion" -> LensDistortionFilter(p[0], p[1], p[2])
    "Brightness-Contrast" -> LutFilter.brightnessContrast(p[0], p[1])
    "Levels" -> LutFilter.levels(p[0], p[1], p[2], p[3], p[4])
    "Hue-Saturation" -> HueSaturationFilter(p[0], p[1], p[2])
    "Threshold" -> ThresholdFilter(p[0].roundToInt(), p[1].roundToInt())
    "Posterize" -> LutFilter.posterize(p[0].roundToInt())
    "Gaussian Blur" -> GaussianBlurFilter(p[0])
    "Unsharp Mask" -> UnsharpMaskFilter(p[0], p[1])
    "Pixelize" -> PixelizeFilter(p[0].roundToInt())
    "RGB Noise" -> NoiseFilter(p[0])
    else -> null
}

/** Bottom panel that live-previews a filter on the canvas above it. */
@Composable
private fun FilterPanel(vm: EditorViewModel, name: String, close: () -> Unit) {
    val e = vm.engine
    val spec = specs(vm.fg, vm.bg)[name]
    val values = remember { mutableStateMapOf<Int, Float>() }
    var curves by remember { mutableStateOf(mapOf<Channel, Curve>()) }
    val toAlpha = name == "Color to Alpha"
    var color by remember { mutableStateOf(android.graphics.Color.WHITE) }
    fun build(): Filter = when {
        toAlpha -> io.github.nielstron.brushkin.engine.ColorToAlphaFilter(color)
        spec != null -> spec.build(spec.params.indices.map { values[it] ?: spec.params[it].default })
        else -> LutFilter.curves(curves)
    }
    // Bake into pixels by default. Live layer effects are an explicit, per-filter choice.
    val unchanged = { spec == null && curves.isEmpty() }
    var finished by remember { mutableStateOf(false) }
    var allLayers by remember { mutableStateOf(false) }
    var liveEffect by remember { mutableStateOf(false) }
    val effectPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("brushkin-effects", 0)
    // The preview shows exactly what closing applies: this layer, or with "All layers" every layer.
    val afterEffects = allLayers || e.info.value.layers.firstOrNull { it.id == e.info.value.activeId }?.editMask != true
    val canBeEffect = spec != null && !toAlpha && !e.info.value.hasSelection && afterEffects
    val asEffect = liveEffect && canBeEffect
    LaunchedEffect(allLayers, afterEffects) { e.filterBegin(allLayers, afterEffects) }
    LaunchedEffect(values.toMap(), curves, color, allLayers, asEffect) { e.filterPreview(build()) }
    fun finish() {
        if (finished) return; finished = true
        val effect = spec?.let { io.github.nielstron.brushkin.engine.LayerEffect(name, it.params.indices.map { i -> values[i] ?: it.params[i].default }, vm.fg, vm.bg) }
        when {
            unchanged() -> e.filterCancel()
            allLayers && asEffect && effect != null -> { e.filterCancel(); e.addEffectToAll(effect) }
            allLayers -> e.filterApplyAll(build(), afterEffects = true)
            asEffect -> {
                e.filterCancel()
                e.filterAsEffect(io.github.nielstron.brushkin.engine.LayerEffect(name, spec.params.indices.map { values[it] ?: spec.params[it].default }, vm.fg, vm.bg))
            }
            else -> e.filterApply(build(), afterEffects = afterEffects)
        }
    }
    fun reset() { values.clear(); curves = emptyMap(); color = android.graphics.Color.WHITE; vm.filterPicking = null }
    val finishLatest by androidx.compose.runtime.rememberUpdatedState { finish() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.filterPicking = null; finishLatest() } }
    BackHandler { finish(); close() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Ui.panel).navigationBarsPadding().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Long names break after their hyphen ("Brightness-/Contrast"), not mid-word.
                Text(name.replace("-", "-\u200B"), color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { reset() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Refresh, "Reset", tint = Ui.text) }
                IconButton(onClick = { finished = true; e.filterCancel(); close() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Cancel (no change)", tint = Ui.text) }
                IconButton(onClick = { finish(); close() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Done (keeps the result)", tint = Ui.accent) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All layers", allLayers) { allLayers = !allLayers }
                if (canBeEffect) EffectChip(liveEffect, effectPrefs) { liveEffect = it }
            }
            Spacer(Modifier.height(8.dp))
            if (toAlpha) {
                // The color to make transparent: presets or one picked from the image.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(color)).border(2.dp, Ui.border, RoundedCornerShape(10.dp)))
                    val picking = vm.filterPicking != null
                    Chip("Pick from image", picking, androidx.compose.material.icons.Icons.Rounded.Colorize) { vm.filterPicking = if (picking) null else { c -> color = c } }
                    Chip("White", !picking && color == android.graphics.Color.WHITE) { vm.filterPicking = null; color = android.graphics.Color.WHITE }
                    Chip("Black", !picking && color == android.graphics.Color.BLACK) { vm.filterPicking = null; color = android.graphics.Color.BLACK }
                    Chip("Background color", !picking && color == vm.bg) { vm.filterPicking = null; color = vm.bg }
                }
                if (vm.filterPicking != null) Text("Touch the image to pick the color to remove", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            } else if (spec != null) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    spec.params.forEachIndexed { i, p ->
                        val v = values[i] ?: p.default
                        when {
                            p.choices.isNotEmpty() -> {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }) { Text("${p.label}: ${p.choices.firstOrNull { it.first == v }?.second ?: v}") }
                                    androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
                                        p.choices.forEach { (number, label) ->
                                            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { values[i] = number; expanded = false })
                                        }
                                    }
                                }
                            }
                            else -> FilterParameterRow(p, v) { values[i] = it }
                        }
                    }
                }
            } else CurvesEditor(curves) { curves = it }
        }
    }
}

@Composable
private fun CurvesEditor(curves: Map<Channel, Curve>, onChange: (Map<Channel, Curve>) -> Unit) {
    var channel by remember { mutableStateOf(Channel.VALUE) }
    val pts = curves[channel] ?: IDENTITY_CURVE
    val chColor = when (channel) { Channel.RED -> Color(0xFFF08A8A); Channel.GREEN -> Color(0xFF7DD68F); Channel.BLUE -> Color(0xFF8AB4F8); else -> Ui.text }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (c in Channel.entries) Chip(c.label, c == channel) { channel = c }
        }
        Spacer(Modifier.height(10.dp))
        var dragIdx by remember { mutableStateOf(-1) }
        Canvas(
            Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF141417)).border(1.dp, Ui.border, RoundedCornerShape(14.dp))
                .pointerInput(channel, pts) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun norm(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (1f - o.y / size.height).coerceIn(0f, 1f)
                        val (nx, ny) = norm(down.position)
                        var list = pts.toMutableList()
                        dragIdx = list.indexOfFirst { abs(it.first - nx) * size.width < 60 && abs(it.second - ny) * size.height < 60 }
                        if (dragIdx < 0) { list.add(nx to ny); list.sortBy { it.first }; dragIdx = list.indexOf(nx to ny) }
                        onChange(curves + (channel to list.toList()))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() != Offset.Zero) {
                                var (x, y) = norm(ch.position)
                                // Endpoints keep their x; inner points stay between neighbours.
                                if (dragIdx == 0) x = list[0].first
                                else if (dragIdx == list.size - 1) x = list.last().first
                                else x = x.coerceIn(list[dragIdx - 1].first + 0.01f, list[dragIdx + 1].first - 0.01f)
                                // Dragging an inner point far off the graph deletes it.
                                val outside = ch.position.y < -40 || ch.position.y > size.height + 40
                                list = list.toMutableList()
                                if (outside && dragIdx in 1 until list.size - 1) { list.removeAt(dragIdx); onChange(curves + (channel to list.toList())); break }
                                list[dragIdx] = x to y
                                onChange(curves + (channel to list.toList()))
                            }
                            ch.consume()
                        }
                        dragIdx = -1
                    }
                }
        ) {
            val grid = Color(0xFF26262C)
            for (i in 1..3) {
                drawLine(grid, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height))
                drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
            }
            drawLine(Color(0xFF4A4A52), Offset(0f, size.height), Offset(size.width, 0f))
            val lut = curveLut(pts)
            val path = Path()
            for (i in 0..255) {
                val x = i / 255f * size.width; val y = (1 - lut[i] / 255f) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, chColor, style = Stroke(2.5.dp.toPx()))
            pts.forEachIndexed { i, p ->
                val o = Offset(p.first * size.width, (1 - p.second) * size.height)
                drawCircle(if (i == dragIdx) Ui.accent else Color(0xFF1B1B1F), 9.dp.toPx(), o)
                drawCircle(Color.White, 9.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
        }
        Text("Tap to add a point · drag a point off the graph to remove it", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = { onChange(curves - channel) }) { Text("Reset ${channel.label}", color = Ui.accent) }
    }
}

/**
 * Gradient editor: a list of color stops (tap a stop's swatch to give it the current color).
 * Saved as a GIMP .ggr with linear segments between the stops.
 */
@Composable
fun GradientEditor(vm: EditorViewModel, close: () -> Unit) {
    val g0 = vm.gradient
    val stops = androidx.compose.runtime.remember {
        // Start from the current gradient: each segment's left color, plus the final right color.
        val list = g0.segments.map { it.left to io.github.nielstron.brushkin.engine.GradientRes.resolve(it.c0, it.k0, vm.fg, vm.bg) }.toMutableList()
        g0.segments.lastOrNull()?.let { list.add(it.right to io.github.nielstron.brushkin.engine.GradientRes.resolve(it.c1, it.k1, vm.fg, vm.bg)) }
        androidx.compose.runtime.mutableStateListOf(*list.toTypedArray())
    }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (g0.file != null) g0.name else "Custom ${vm.resources.gradients.value.size - 1}") }
    fun sorted() = stops.sortedBy { it.first }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Ui.panel,
        title = { Text("Edit gradient", color = Ui.text) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                val s = sorted()
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    val n = 48
                    for (k in 0 until n) drawRect(
                        Color(sampleStops(s.map { it.second }.toIntArray(), s.map { it.first }.toFloatArray(), (k + 0.5f) / n)),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * k / n, 0f), size = androidx.compose.ui.geometry.Size(size.width / n + 1, size.height),
                    )
                }
                androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                for ((i, st) in stops.withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(st.second)).border(1.dp, Ui.border, RoundedCornerShape(8.dp)).clickable { stops[i] = st.first to vm.fg })
                    androidx.compose.material3.Slider(st.first, { stops[i] = it to st.second }, Modifier.weight(1f).padding(horizontal = 8.dp))
                    if (stops.size > 2) Text("✕", color = Ui.muted, modifier = Modifier.clickable { stops.removeAt(i) }.padding(8.dp))
                }
                Text("Tap a swatch to set it to the current color.", color = Ui.muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Add stop", false) { stops.add(0.5f to vm.fg) }
                    Chip("Transparent stop", false) { stops.add(0.5f to (vm.fg and 0x00FFFFFF)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = sorted()
                val segs = s.zipWithNext { a, b -> io.github.nielstron.brushkin.engine.GradientRes.Segment(a.first, (a.first + b.first) / 2, b.first, a.second, b.second) }
                if (segs.isNotEmpty()) {
                    val g = io.github.nielstron.brushkin.engine.GradientRes(name.ifBlank { "Custom" }, segs)
                    vm.gradient = vm.resources.saveGradient(g, g0.takeIf { it.file != null })
                }
                close()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** Rebuilds a layer effect's filter (non-destructive filters store only name and parameters). */
fun buildEffect(e: io.github.nielstron.brushkin.engine.LayerEffect): Filter? =
    (if (e.paramsVersion < 2) filterSavedBeforeGegl(e.name, e.params) else null) ?: specs(e.fg, e.bg)[e.name]?.let { sp ->
        sp.build(sp.params.indices.map { e.params.getOrNull(it) ?: sp.params[it].default })
    }

/**
 * Pattern editor: generate two-color patterns from the current colors, and rename, scale, offset,
 * make seamless or delete the selected pattern. Patterns are saved as GIMP .pat files.
 */
@Composable
fun PatternEditor(vm: EditorViewModel, close: () -> Unit) {
    val list by vm.resources.patterns.collectAsState()
    val ops = io.github.nielstron.brushkin.engine.PatternOps
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cur by remember { mutableStateOf(vm.pattern ?: list.firstOrNull()) }
    var name by remember(cur) { mutableStateOf(cur?.name ?: "") }
    var kind by remember { mutableStateOf(0) }
    var cell by remember { mutableStateOf(16f) }
    fun update(f: (android.graphics.Bitmap) -> android.graphics.Bitmap) {
        val p = cur ?: return
        scope.launch {
            val np = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.resources.updatePattern(p, name.ifBlank { p.name }, f(p.bitmap)) }
            cur = np; vm.pattern = np
        }
    }
    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("Patterns", color = Ui.text) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in list) {
                        val img = remember(p) { p.bitmap.asImageBitmap() }
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (cur === p) Ui.accent else Ui.border, RoundedCornerShape(10.dp)).clickable { cur = p }
                            .drawBehind { var y = 0f; while (y < size.height) { var x = 0f; while (x < size.width) { drawImage(img, androidx.compose.ui.geometry.Offset(x, y)); x += img.width.coerceAtLeast(1) }; y += img.height.coerceAtLeast(1) } })
                    }
                }
                cur?.let { p ->
                    Text("${p.bitmap.width} × ${p.bitmap.height}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Rename", false) { update { it } }
                        Chip("½ size", false) { update { ops.scale(it, 0.5f) } }
                        Chip("2× size", false) { update { ops.scale(it, 2f) } }
                        Chip("Offset ½", false) { update { ops.offsetHalf(it) } }
                        Chip("Make seamless", false) { update { ops.seamless(it) } }
                        Chip("Delete", false) { vm.resources.delete(p.file); if (vm.pattern === p) vm.pattern = null; cur = null }
                    }
                }
                SectionLabel("New from colors", Modifier.padding(top = 14.dp, bottom = 6.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, n) in listOf("Checker", "Stripes", "Dots", "Diagonal").withIndex()) Chip(n, kind == i) { kind = i }
                }
                LabeledSlider("Cell", cell, 2f..128f, "${cell.roundToInt()} px", { cell = it })
                Chip("Create", false) {
                    scope.launch {
                        val bmp = ops.generate(kind, cell.roundToInt(), vm.fg, vm.bg)
                        val n = listOf("Checker", "Stripes", "Dots", "Diagonal")[kind] + " ${cell.roundToInt()}"
                        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.resources.savePattern(n, bmp) }
                        cur = p; vm.pattern = p; vm.fillWithPattern = true
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
    )
}

/** Asks before fetching the object selection model (FOSS build), then shows the download. */
@Composable
private fun ModelDownloadDialog(vm: EditorViewModel, close: () -> Unit) {
    val r = vm.modelRequest ?: run { close(); return }
    val p = vm.modelProgress
    var started by remember { mutableStateOf(false) }
    val mb = remember(r) { vm.modelDownloadMb(r) }
    // Close once the download has finished (the tool then runs by itself).
    LaunchedEffect(p, started) { if (started && p == null) close() }
    AlertDialog(
        onDismissRequest = { if (p == null) { vm.modelRequest = null; close() } }, containerColor = Ui.panel,
        title = { Text(r.title, color = Ui.text) },
        text = {
            Column {
                Text(
                    "${r.text} It isn't part of the app: it's downloaded once ($mb MB) from the project's GitHub page, " +
                        "runs on this device and then works offline. Clearing the app's cache removes it.",
                    color = Ui.text, fontSize = 14.sp,
                )
                if (p != null) {
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Ui.accent, trackColor = Ui.panel2, drawStopIndicator = {})
                }
            }
        },
        confirmButton = { if (p == null) TextButton(onClick = { started = true; vm.downloadModel() }) { Text("Download") } },
        dismissButton = {
            if (p == null) TextButton(onClick = { vm.modelRequest = null; close() }) { Text("Not now") }
            else TextButton(onClick = { vm.cancelDownload(); close() }) { Text("Cancel") }
        },
    )
}

/** Depth of field: touch the image to set the focus; the blur previews live on the canvas. */
@Composable
private fun DepthOfFieldPanel(vm: EditorViewModel, close: () -> Unit) {
    val e = vm.engine
    val map = vm.depthMap ?: run { close(); return }
    val info = e.info.collectAsState().value
    val longSide = maxOf(info.width, info.height).toFloat()
    var strength by remember { mutableStateOf(0.5f) }
    var range by remember { mutableStateOf(0.15f) }
    fun build(): io.github.nielstron.brushkin.engine.Filter {
        val f = vm.dofFocus ?: android.graphics.PointF(info.width / 2f, info.height / 2f)
        // Strongest blur: up to 4% of the image's long side.
        return io.github.nielstron.brushkin.engine.DepthBlurFilter(map.values, map.side, map.side, f.x / info.width, f.y / info.height, strength * longSide * 0.04f, range)
    }
    // No Apply step: the previewed blur is kept when the panel closes (undo takes it back);
    // with no blur, closing changes nothing.
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return; finished = true
        if (strength < 0.005f) e.filterCancel() else e.filterApply(build())
        vm.dofOpen = false; vm.depthMap = null; vm.dofFocus = null
    }
    fun reset() { strength = 0.5f; range = 0.15f; vm.dofFocus = android.graphics.PointF(info.width / 2f, info.height / 2f) }
    LaunchedEffect(Unit) { e.filterBegin() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { finish() } }
    LaunchedEffect(vm.dofFocus, strength, range) { e.filterPreview(build()) }
    BackHandler { finish(); close() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Ui.panel).navigationBarsPadding().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Depth of field", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { reset() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Refresh, "Reset", tint = Ui.text) }
                IconButton(onClick = { finished = true; e.filterCancel(); vm.dofOpen = false; vm.depthMap = null; vm.dofFocus = null; close() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Cancel (no change)", tint = Ui.text) }
                IconButton(onClick = { finish(); close() }) { Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Done (keeps the result)", tint = Ui.accent) }
            }
            Text("Touch the image to choose what stays sharp", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            LabeledSlider("Blur", strength, 0f..1f, "${(strength * 100).roundToInt()}%", { strength = it })
            LabeledSlider("Sharp depth range", range, 0f..1f, "${(range * 100).roundToInt()}%", { range = it })
        }
    }
}


@Composable
private fun SelectionOutlineDialog(vm: EditorViewModel, stroke: Boolean, close: () -> Unit) {
    var source by remember { mutableStateOf("Foreground") }
    var width by remember { mutableStateOf("4") }
    var antialias by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = close, title = { Text(if (stroke) "Stroke selection" else "Fill selection outline") },
        text = { Column {
            Row { for (option in listOf("Foreground","Background","Pattern")) Chip(option,source == option) { source = option } }
            if (stroke) OutlinedTextField(width,{width = it},label = { Text("Width (px)") },keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (stroke) Toggle("Antialiasing",antialias) { antialias = it }
            if (source == "Pattern" && vm.pattern == null) Text("Choose a pattern in Patterns first.")
        } },
        confirmButton = { TextButton(enabled = (!stroke || (width.toFloatOrNull()?.let { it > 0 && it.isFinite() } == true)) && (source != "Pattern" || vm.pattern != null), onClick = {
            vm.engine.selectionOutline(if (source == "Background") vm.bg else vm.fg,if (stroke) width.toFloat() else null,vm.pattern?.bitmap?.takeIf { source == "Pattern" },antialias)
            close()
        }) { Text("Apply") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
