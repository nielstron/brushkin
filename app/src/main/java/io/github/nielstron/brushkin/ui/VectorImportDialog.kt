package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nielstron.brushkin.export.VectorImport
import kotlin.math.roundToInt

private const val MAX_DIM = 8192
private const val MAX_PIXELS = 40_000_000L

/**
 * Asks at which size to draw an SVG or PDF (and, for a PDF, which pages), like GIMP's import
 * dialogs. Width and height keep the drawing's proportions.
 */
@Composable
fun VectorImportDialog(p: VectorImport.Pending, canvasW: Int, canvasH: Int, close: () -> Unit, ok: (VectorImport.Options) -> Unit) {
    val s = p.source
    val pdf = s.kind == VectorImport.Kind.PDF
    // Inserted drawings start out filling 70% of the canvas (as other inserted images do).
    val fitCanvas = minOf(canvasW * 0.7f / s.width, canvasH * 0.7f / s.height)
    val initial = remember {
        val d = VectorImport.defaults(s, p.insert)
        if (p.insert) d.copy(width = maxOf(1, (s.width * fitCanvas).roundToInt()), height = maxOf(1, (s.height * fitCanvas).roundToInt())) else d
    }
    var w by remember { mutableStateOf("${initial.width}") }
    var h by remember { mutableStateOf("${initial.height}") }
    var from by remember { mutableStateOf("${initial.pages.first + 1}") }
    var to by remember { mutableStateOf("${initial.pages.last + 1}") }
    var background by remember { mutableStateOf(initial.background) }

    fun setScale(k: Float) { w = "${maxOf(1, (s.width * k).roundToInt())}"; h = "${maxOf(1, (s.height * k).roundToInt())}" }
    val scale = w.toIntOrNull()?.let { it / s.width }
    val presets = buildList {
        if (pdf) for (dpi in listOf(72, 150, 300)) add("$dpi dpi" to dpi / 72f)
        else { add("Original" to 1f); add("2×" to 2f); add("4×" to 4f) }
        if (p.insert) add("Fit canvas" to fitCanvas)
    }
    val first = (from.toIntOrNull() ?: 1).coerceIn(1, s.pageCount)
    val last = if (p.insert || !pdf) first else (to.toIntOrNull() ?: first).coerceIn(first, minOf(s.pageCount, first + VectorImport.MAX_PDF_PAGES - 1))
    val pages = last - first + 1
    val tooLarge = scale != null && (maxOf(s.width, s.height) * scale > MAX_DIM || s.width * scale * s.height * scale * pages > MAX_PIXELS)

    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("${if (p.insert) "Insert" else "Open"} ${if (pdf) "PDF" else "SVG"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val info = if (pdf) {
                    val mm = { pt: Float -> (pt * 25.4f / 72f).roundToInt() }
                    "${s.pageCount} page${if (s.pageCount == 1) "" else "s"} · ${mm(s.width)} × ${mm(s.height)} mm"
                } else "Drawing size ${s.width.roundToInt()} × ${s.height.roundToInt()} px"
                Text(info, color = Ui.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, k) in presets) Chip(label, w == "${maxOf(1, (s.width * k).roundToInt())}") { setScale(k) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { v -> w = v.filter(Char::isDigit).take(6); w.toIntOrNull()?.let { h = "${maxOf(1, (it * s.height / s.width).roundToInt())}" } },
                        label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(h, { v -> h = v.filter(Char::isDigit).take(6); h.toIntOrNull()?.let { w = "${maxOf(1, (it * s.width / s.height).roundToInt())}" } },
                        label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (pdf && scale != null) Text("${(scale * 72f).roundToInt()} dpi", color = Ui.muted, fontSize = 13.sp)
                if (pdf && s.pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, label = { Text(if (p.insert) "Page" else "From page") }, singleLine = true,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (!p.insert) OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, label = { Text("To page") }, singleLine = true,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (!p.insert) Text("Each page becomes a layer (up to ${VectorImport.MAX_PDF_PAGES})", color = Ui.muted, fontSize = 13.sp)
                }
                Toggle("White background", background) { background = it }
                if (tooLarge) Text("Too large: it will be drawn smaller to fit in memory", color = Ui.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull(); val hh = h.toIntOrNull()
                if (ww != null && hh != null && ww > 0 && hh > 0) ok(VectorImport.Options(ww, hh, (first - 1)..(last - 1), background))
            }) { Text(if (p.insert) "Insert" else "Open") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
