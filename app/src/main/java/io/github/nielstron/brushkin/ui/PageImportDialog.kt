package io.github.nielstron.brushkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nielstron.brushkin.export.PageImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_DIM = 8192
private const val MAX_PIXELS = 40_000_000L

private enum class SizeUnit(val label: String, val perInch: Float?, val digits: Int) { PX("px", null, 0), MM("mm", 25.4f, 1), IN("in", 1f, 3) }

private fun fmt(v: Float, digits: Int): String =
    if (digits == 0) "${v.roundToInt()}" else "%.${digits}f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

private fun number(s: String) = s.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }

/** "1-3, 5" for pages {0, 1, 2, 4}. */
private fun rangeText(pages: Set<Int>): String {
    val sorted = pages.sorted(); val parts = ArrayList<String>(); var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        parts += if (j > i) "${sorted[i] + 1}-${sorted[j] + 1}" else "${sorted[i] + 1}"
        i = j + 1
    }
    return parts.joinToString(", ")
}

/** Pages typed as "1-3, 5" (1-based); null while it doesn't parse. */
private fun parseRange(text: String, count: Int): Set<Int>? {
    val out = LinkedHashSet<Int>()
    for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-').map { it.trim() }
        val a = bounds[0].toIntOrNull() ?: return null
        val b = if (bounds.size == 2) bounds[1].ifEmpty { "$count" }.toIntOrNull() ?: return null else if (bounds.size == 1) a else return null
        if (a < 1 || b < a) return null
        for (p in a..minOf(b, count)) out += p - 1
    }
    return out.ifEmpty { null }
}

/**
 * The choices GIMP's import dialogs offer: for SVG the size (with unit) and resolution, for PDF
 * the pages, size and resolution, how to open the pages, reverse order and annotations, for
 * multi-page TIFF the pages and how to open them. SVG and PDF can get a background color.
 */
@Composable
fun PageImportDialog(vm: EditorViewModel, p: PageImport.Pending, canvasW: Int, canvasH: Int, close: () -> Unit, ok: (PageImport.Options) -> Unit) {
    val s = p.source
    val kind = s.kind
    val sized = kind != PageImport.Kind.TIFF
    val initial = remember { PageImport.defaults(s, p.insert) }
    // Pixels, physical size and resolution go together: the drawing's own size is physical
    // (SVG pixels are 1/96 inch, PDF points 1/72), so more pixels per inch means more pixels.
    fun natural(res: Float): Pair<Float, Float> =
        if (kind == PageImport.Kind.TIFF) s.width to s.height
        else s.width * s.inchesPerUnit * res to s.height * s.inchesPerUnit * res

    // Inserted drawings start out filling 70% of the canvas (as other inserted images do).
    val fit = minOf(canvasW * 0.7f / s.width, canvasH * 0.7f / s.height)
    // Kept unrounded (only shown rounded), so the defaults are exactly ratio 1 at their own size.
    var res by remember { mutableStateOf(when {
        p.insert && kind == PageImport.Kind.PDF -> (fit * 72f).coerceIn(1f, 9600f)
        kind == PageImport.Kind.SVG && !p.insert -> (initial.width / (s.width * s.inchesPerUnit)).coerceIn(1f, 9600f)
        else -> initial.resolution.toFloat()
    }) }
    var xr by remember { mutableStateOf((if (p.insert && kind == PageImport.Kind.SVG) s.width * fit else initial.width.toFloat()) / natural(res).first) }
    // One ratio for both sides (the default width and height are rounded separately).
    var yr by remember { mutableStateOf(xr) }
    var keep by remember { mutableStateOf(true) }
    var unit by remember { mutableStateOf(SizeUnit.PX) }
    val wPx = natural(res).first * xr
    val hPx = natural(res).second * yr
    fun show(px: Float) = fmt(unit.perInch?.let { px / res * it } ?: px, unit.digits)
    fun toPx(v: Float) = unit.perInch?.let { v / it * res } ?: v
    var wText by remember { mutableStateOf(show(wPx)) }
    var hText by remember { mutableStateOf(show(hPx)) }
    var resText by remember { mutableStateOf(fmt(res, 0)) }
    /** Rewrites every field but the one being typed in. */
    fun sync(except: String = "") {
        val (nw, nh) = natural(res)
        if (except != "w") wText = show(nw * xr)
        if (except != "h") hText = show(nh * yr)
        if (except != "res") resText = fmt(res, 0)
    }
    // A PDF's pixel size sets its resolution (as in GIMP); an SVG's sets its ratios against its size at the resolution.
    fun setWidth(px: Float, except: String) {
        if (kind == PageImport.Kind.PDF) { res = (px * 72f / s.width).coerceIn(1f, 9600f); xr = 1f; yr = 1f }
        else { xr = px / natural(res).first; if (keep) yr = xr }
        sync(except)
    }
    fun setHeight(px: Float, except: String) {
        if (kind == PageImport.Kind.PDF) { res = (px * 72f / s.height).coerceIn(1f, 9600f); xr = 1f; yr = 1f }
        else { yr = px / natural(res).second; if (keep) xr = yr }
        sync(except)
    }
    fun setScale(k: Float) {
        if (kind == PageImport.Kind.PDF) res = (k * 72f).coerceIn(1f, 9600f) else { xr = k; yr = k }
        sync()
    }
    val presets = buildList {
        if (kind == PageImport.Kind.PDF) for (dpi in listOf(72, 150, 300)) add("$dpi ppi" to dpi / 72f)
        else { add("Original" to 1f); add("2×" to 2f); add("4×" to 4f) }
        if (p.insert) add("Fit canvas" to if (kind == PageImport.Kind.PDF) fit else fit * s.width / natural(res).first)
    }

    var pages by remember { mutableStateOf(initial.pages.toSet()) }
    var rangeTextState by remember { mutableStateOf(rangeText(pages)) }
    fun select(new: Set<Int>) { pages = new; rangeTextState = rangeText(new) }
    var asImages by remember { mutableStateOf(false) }
    var reverse by remember { mutableStateOf(false) }
    var transparent by remember { mutableStateOf(initial.fill == null) }
    var fill by remember { mutableStateOf(vm.bg) }
    var annotations by remember { mutableStateOf(true) }

    val chosen = pages.sorted().take(PageImport.MAX_PAGES)
    val budget = if (asImages) MAX_PIXELS else MAX_PIXELS / maxOf(1, chosen.size)
    val tooLarge = sized && chosen.any { i ->
        val w = s.pages[i].width / s.width * wPx; val h = s.pages[i].height / s.height * hPx
        maxOf(w, h) > MAX_DIM || w * h > budget
    }

    val reader by produceState<PageImport.Reader?>(null) {
        if (s.pageCount > 1) value = withContext(Dispatchers.IO) { runCatching { PageImport.Reader(s) }.getOrNull() }
        awaitDispose { value?.close() }
    }

    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("${if (p.insert) "Insert" else "Open"} ${kind.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val info = when (kind) {
                    PageImport.Kind.PDF -> "${s.pageCount} page${if (s.pageCount == 1) "" else "s"} · ${(s.width * 25.4f / 72f).roundToInt()} × ${(s.height * 25.4f / 72f).roundToInt()} mm"
                    PageImport.Kind.SVG -> "Drawing size ${fmt(s.width * 25.4f / PageImport.SVG_DPI, 1)} × ${fmt(s.height * 25.4f / PageImport.SVG_DPI, 1)} mm"
                    PageImport.Kind.TIFF -> "${s.pageCount} pages · ${s.width.roundToInt()} × ${s.height.roundToInt()} px"
                }
                Text(p.name, color = Ui.text, fontSize = 14.sp)
                Text(info, color = Ui.muted, fontSize = 13.sp)

                if (s.pageCount > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(if (p.insert) "Page ${(pages.firstOrNull() ?: 0) + 1} of ${s.pageCount}" else "Pages · ${pages.size} of ${s.pageCount} selected", color = Ui.muted, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        items((0 until s.pageCount).toList()) { i ->
                            val selected = i in pages
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    select(if (p.insert) setOf(i) else if (selected) pages - i else pages + i)
                                }) {
                                val thumb by produceState<Bitmap?>(null, reader, i) {
                                    val r = reader ?: return@produceState
                                    value = withContext(Dispatchers.IO) { runCatching { r.thumbnail(i, 192) }.getOrNull() }
                                }
                                // Small enough that the next page peeks in at the edge, showing the row scrolls.
                                Box(Modifier.size(68.dp).border(if (selected) 3.dp else 1.dp, if (selected) Ui.accent else Ui.border, RoundedCornerShape(6.dp)).padding(4.dp),
                                    contentAlignment = Alignment.Center) {
                                    thumb?.let { Image(it.asImageBitmap(), "Page ${i + 1}") }
                                }
                                Text("${i + 1}", color = if (selected) Ui.accent else Ui.muted, fontSize = 12.sp)
                            }
                        }
                    }
                    if (!p.insert) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip("Select all", pages.size == s.pageCount) { select((0 until minOf(s.pageCount, PageImport.MAX_PAGES)).toSet()) }
                        }
                        OutlinedTextField(rangeTextState, { t -> rangeTextState = t; parseRange(t, s.pageCount)?.let { pages = it } },
                            label = { Text("Select range") }, placeholder = { Text("1-3, 5") }, singleLine = true)
                        if (pages.size > PageImport.MAX_PAGES) Text("At most ${PageImport.MAX_PAGES} pages are opened at once", color = Ui.danger, fontSize = 13.sp)
                        if (pages.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text("Open pages as", color = Ui.muted, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                Chip("Layers", !asImages) { asImages = false }
                                Chip("Images", asImages) { asImages = true }
                            }
                            Toggle("Load in reverse order", reverse) { reverse = it }
                        }
                    }
                }

                if (sized) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, k) in presets) Chip(label, kotlin.math.abs((if (kind == PageImport.Kind.PDF) res / 72f else xr) - k) < 0.002f && (kind == PageImport.Kind.PDF || xr == yr)) { setScale(k) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val keyboard = KeyboardOptions(keyboardType = if (unit == SizeUnit.PX) KeyboardType.Number else KeyboardType.Decimal)
                        OutlinedTextField(wText, { t -> wText = t; number(t)?.let { setWidth(toPx(it), "w") } }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = keyboard)
                        OutlinedTextField(hText, { t -> hText = t; number(t)?.let { setHeight(toPx(it), "h") } }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = keyboard)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        for (u in SizeUnit.entries) Chip(u.label, unit == u) { unit = u; sync() }
                    }
                    // Unlocked, width and height stretch the drawing independently.
                    if (kind == PageImport.Kind.SVG) Toggle("Keep aspect ratio", keep) { keep = it; if (it) { yr = xr; sync() } }
                    OutlinedTextField(resText, { t ->
                        resText = t
                        number(t)?.let { v ->
                            // The physical size and ratios stay, so the pixel size follows.
                            res = v.coerceIn(1f, 9600f)
                            sync("res")
                        }
                    }, label = { Text("Resolution (pixels/in)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if (kind == PageImport.Kind.PDF && PageImport.pdfExtras) Toggle("Render annotations", annotations) { annotations = it }
                if (sized) {
                    Toggle("Transparent background", transparent) { transparent = it }
                    if (!transparent) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Background color", color = Ui.text.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        ColorPicker(vm, "Background color", fill, outline = false) { c -> c?.let { fill = it } }
                    }
                }
                if (tooLarge) Text("Too large: it will be drawn smaller to fit in memory", color = Ui.danger, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = pages.isNotEmpty() && wPx >= 1f && hPx >= 1f, onClick = {
                ok(PageImport.Options(
                    width = if (sized) maxOf(1, wPx.roundToInt()) else s.width.roundToInt(),
                    height = if (sized) maxOf(1, hPx.roundToInt()) else s.height.roundToInt(),
                    resolution = if (sized) res.roundToInt() else 72,
                    pages = chosen, asImages = asImages && chosen.size > 1, reverse = reverse && chosen.size > 1,
                    fill = if (transparent || !sized) null else fill, annotations = annotations,
                ))
            }) { Text(if (p.insert) "Insert" else "Open") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
fun PasswordDialog(l: PageImport.Locked, close: () -> Unit, ok: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close, containerColor = Ui.panel,
        title = { Text("Password") },
        text = {
            Column {
                Text(if (l.wrong) "Wrong password for ${l.name}. Try again:" else "${l.name} is protected by a password.", color = Ui.muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(onClick = { ok(password) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
