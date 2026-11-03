package io.github.nielstron.brushkin.ui

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
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
    fun export(format: String)
    fun share()
}

data class TextStyleSpec(val typeface: Typeface, val preview: TextStyle, val outline: Boolean = false)

val TEXT_STYLES = listOf(
    "Aa" to TextStyleSpec(Typeface.create("sans-serif", Typeface.NORMAL), TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)),
    "Title" to TextStyleSpec(Typeface.create("serif", Typeface.BOLD), TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)),
    "hello" to TextStyleSpec(Typeface.create("cursive", Typeface.BOLD), TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Cursive, color = Color(0xFFFBD38D))),
    "POP" to TextStyleSpec(Typeface.create("sans-serif-black", Typeface.NORMAL), TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Black), outline = true),
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
        d == "export" -> AlertDialog(
            onDismissRequest = close, containerColor = Ui.panel,
            title = { Text("Export") },
            text = {
                Column {
                    for ((label, fmt) in listOf("PNG (lossless)" to "png", "JPEG" to "jpg", "WebP" to "webp")) {
                        Text(label, color = Ui.text, modifier = Modifier.fillMaxWidth().clickable { close(); files.export(fmt) }.padding(vertical = 14.dp))
                    }
                    Text("Share…", color = Ui.text, modifier = Modifier.fillMaxWidth().clickable { close(); files.share() }.padding(vertical = 14.dp))
                    Text("Save project with layers (.ora)…", color = Ui.text, modifier = Modifier.fillMaxWidth().clickable { close(); files.saveProject() }.padding(vertical = 14.dp))
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Close") } },
        )
        d == "new" -> NewImageDialog(vm, close)
        d == "text" -> TextDialog(vm, close)
        d == "scale" -> SizeDialog("Scale image", info.width, info.height, true, close) { w, h -> e.scaleImage(w, h) }
        d == "canvas" -> SizeDialog("Canvas size", info.width, info.height, false, close) { w, h -> e.canvasSize(w, h, (w - info.width) / 2, (h - info.height) / 2) }
        d == "feather" || d == "grow" || d == "shrink" -> TextPrompt("${d.replaceFirstChar { it.uppercase() }} selection by (px)", "10", KeyboardType.Number, close) { v ->
            close()
            val r = v.toIntOrNull()?.coerceIn(1, 500) ?: return@TextPrompt
            e.selectModify(d.replaceFirstChar { it.uppercase() } + " Selection", r, when (d) { "grow" -> 1; "shrink" -> -1; else -> 0 })
        }
        d.startsWith("filter:") -> FilterPanel(vm, d.removePrefix("filter:"), close)
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
                if (ww != null && hh != null) { vm.engine.newDocument(ww, hh, if (transparent) null else vm.bg); close() }
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

@Composable
private fun TextDialog(vm: EditorViewModel, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(160f) }
    var style by remember { mutableStateOf(vm.textStyle) }
    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("Add text") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Type something") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, s) in TEXT_STYLES) Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Ui.panel2)
                            .border(2.dp, if (style == s) Ui.accent else Color.Transparent, RoundedCornerShape(12.dp)).clickable { style = s },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = Ui.text, style = s.preview.copy(fontSize = 15.sp)) }
                }
                LabeledSlider("Size", size, 24f..600f, "${size.roundToInt()} px", { size = it })
                Text("Uses the foreground color.", color = Ui.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) {
                    vm.engine.placeFloating(renderText(text, size, style.typeface, vm.fg, style.outline), "Text: ${text.lines().first().take(20)}")
                    // Text is placed at its real size, not fitted.
                }
                close()
            }) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------- filters

private data class Param(val label: String, val range: ClosedFloatingPointRange<Float>, val default: Float, val fmt: (Float) -> String = { "%.2f".format(it) })

private class FilterSpec(val params: List<Param>, val build: (List<Float>) -> Filter)

private val SPECS: Map<String, FilterSpec> = mapOf(
    "Brightness-Contrast" to FilterSpec(listOf(Param("Brightness", -1f..1f, 0f), Param("Contrast", -1f..1f, 0f))) { LutFilter.brightnessContrast(it[0], it[1]) },
    "Levels" to FilterSpec(
        listOf(
            Param("Input black", 0f..254f, 0f) { "${it.roundToInt()}" }, Param("Input white", 1f..255f, 255f) { "${it.roundToInt()}" },
            Param("Gamma", 0.1f..3f, 1f), Param("Output black", 0f..255f, 0f) { "${it.roundToInt()}" }, Param("Output white", 0f..255f, 255f) { "${it.roundToInt()}" },
        )
    ) { LutFilter.levels(it[0], it[1], it[2], it[3], it[4]) },
    "Hue-Saturation" to FilterSpec(
        listOf(Param("Hue", -180f..180f, 0f) { "${it.roundToInt()}°" }, Param("Saturation", -1f..1f, 0f), Param("Lightness", -1f..1f, 0f))
    ) { HueSaturationFilter(it[0], it[1], it[2]) },
    "Color Balance" to FilterSpec(listOf(Param("Cyan ↔ Red", -1f..1f, 0f), Param("Magenta ↔ Green", -1f..1f, 0f), Param("Yellow ↔ Blue", -1f..1f, 0f))) { ColorBalanceFilter(it[0], it[1], it[2]) },
    "Threshold" to FilterSpec(listOf(Param("Low", 0f..255f, 127f) { "${it.roundToInt()}" }, Param("High", 0f..255f, 255f) { "${it.roundToInt()}" })) { ThresholdFilter(it[0].roundToInt(), it[1].roundToInt()) },
    "Posterize" to FilterSpec(listOf(Param("Levels", 2f..32f, 4f) { "${it.roundToInt()}" })) { LutFilter.posterize(it[0].roundToInt()) },
    "Gaussian Blur" to FilterSpec(listOf(Param("Radius", 0f..200f, 8f) { "${it.roundToInt()} px" })) { GaussianBlurFilter(it[0]) },
    "Unsharp Mask" to FilterSpec(listOf(Param("Radius", 1f..60f, 3f) { "${it.roundToInt()} px" }, Param("Amount", 0f..5f, 0.5f))) { UnsharpMaskFilter(it[0], it[1]) },
    "Pixelize" to FilterSpec(listOf(Param("Block size", 2f..128f, 16f) { "${it.roundToInt()} px" })) { PixelizeFilter(it[0].roundToInt()) },
    "RGB Noise" to FilterSpec(listOf(Param("Amount", 0f..1f, 0.2f))) { NoiseFilter(it[0]) },
)

/** Bottom panel that live-previews a filter on the canvas above it. */
@Composable
private fun FilterPanel(vm: EditorViewModel, name: String, close: () -> Unit) {
    val e = vm.engine
    val spec = SPECS[name]
    val values = remember { mutableStateMapOf<Int, Float>() }
    var curves by remember { mutableStateOf(mapOf<Channel, Curve>()) }
    fun build(): Filter = spec?.build(spec.params.indices.map { values[it] ?: spec.params[it].default }) ?: LutFilter.curves(curves)
    LaunchedEffect(Unit) { e.filterBegin() }
    LaunchedEffect(values.toMap(), curves) { e.filterPreview(build()) }
    val cancel = { e.filterCancel(); close() }
    BackHandler { cancel() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Ui.panel).navigationBarsPadding().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = cancel) { Text("Cancel", color = Ui.text) }
                Text(name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Ui.accent).clickable { e.filterApply(build()); close() }.padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text("Apply", color = Ui.onAccent, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(8.dp))
            if (spec != null) {
                spec.params.forEachIndexed { i, p ->
                    val v = values[i] ?: p.default
                    LabeledSlider(p.label, v, p.range, p.fmt(v), { values[i] = it })
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
