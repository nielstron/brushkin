package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val COORDINATE_UNITS = setOf("pixel-coordinate", "relative-coordinate")

/** Where a setting starts: GEGL's default, or for an output extent the image's own size. */
internal fun Param.initialValue(width: Int, height: Int): Float = when {
    role != "output-extent" -> default
    unit == "pixel-coordinate" -> 0f
    unit == "pixel-distance" && axis == "x" -> width.toFloat()
    unit == "pixel-distance" && axis == "y" -> height.toFloat()
    else -> default
}.let { if (role == "output-extent") it.coerceIn(allowedRange) else it }

/** Primary and secondary colors follow the current foreground and background. */
internal fun Param.initialColor(fg: Int, bg: Int): Int = when (role) {
    "color-primary" -> fg
    "color-secondary" -> bg
    else -> color ?: 0
}

/** Whether a sorted range's end follows the setting at [i]; such a pair is one control. */
internal fun rangeEndAfter(params: List<Param>, i: Int) =
    params[i].role == "range-start" && params.getOrNull(i + 1)?.role == "range-end" && params[i].meta["range-sorted"] != "false"

/** Indexes (x, y) of consecutive axis properties. */
internal fun axisPairs(params: List<Param>): Map<Int, Int> =
    params.indices.filter { it + 1 < params.size && params[it].axis == "x" && params[it + 1].axis == "y" }.associateWith { it + 1 }

/** The panel's settings, drawn and constrained as GEGL's metadata describes. */
@Composable
internal fun FilterSettings(vm: EditorViewModel, spec: FilterSpec, initial: List<Float>, values: SnapshotStateMap<Int, Float>, initialColors: Map<String, Int>, pickedColors: SnapshotStateMap<String, Int>) {
    val params = spec.params
    fun value(i: Int) = values[i] ?: initial[i]
    val current = params.mapIndexed { i, p -> p.key to value(i) }.toMap()
    val pairs = remember(spec) { axisPairs(params) }
    val partner = remember(pairs) { pairs.entries.associate { (x, y) -> y to x } }
    val linked = remember(spec) { mutableStateMapOf<Int, Boolean>() }
    val rangeEnds = remember(spec) { params.indices.filter { rangeEndAfter(params, it) }.map { it + 1 }.toSet() }
    fun coordinates(x: Int) = params[x].unit in COORDINATE_UNITS || params[pairs.getValue(x)].unit in COORDINATE_UNITS
    fun isLinked(x: Int) = !coordinates(x) && (linked[x] ?: (initial[x] == initial[pairs.getValue(x)]))
    fun set(i: Int, v: Float) {
        values[i] = v
        val x = if (i in pairs) i else partner[i]
        if (x != null && isLinked(x)) { val other = if (i == x) pairs.getValue(x) else x; values[other] = v.coerceIn(params[other].allowedRange) }
        val p = params[i]
        if (p.meta["range-sorted"] != "false") {
            if (p.role == "range-start" && params.getOrNull(i + 1)?.role == "range-end" && v > value(i + 1)) values[i + 1] = v
            if (p.role == "range-end" && params.getOrNull(i - 1)?.role == "range-start" && v < value(i - 1)) values[i - 1] = v
        }
    }
    fun place(x: Int, px: Float, py: Float) {
        val info = vm.engine.info.value
        fun position(i: Int, at: Float, size: Int) = params[i].let { p ->
            roundFilterNumber(if (p.unit == "relative-coordinate") at / size.coerceAtLeast(1) else at, if (p.integral) 0 else p.digits).coerceIn(p.allowedRange)
        }
        values[x] = position(x, px, info.width)
        pairs.getValue(x).let { y -> values[y] = position(y, py, info.height) }
    }
    var placing by remember(spec) { mutableStateOf<Int?>(null) }
    if (vm.filterPoint == null) placing = null

    params.forEachIndexed { i, p ->
        if (i in rangeEnds || !p.isVisible(params, current)) return@forEachIndexed
        val enabled = p.isEnabled(params, current)
        val label = p.displayLabel(params, current)
        val help = p.displayDescription(params, current)
        when {
            i + 1 in rangeEnds -> FilterRangeRow(p, params[i + 1], value(i), value(i + 1), enabled, p.meta["range-label"] ?: label, help, { set(i, it) }, { set(i + 1, it) })
            p.toggle -> FilterToggleRow(p, value(i), enabled, label, help) { set(i, it) }
            p.choices.isNotEmpty() -> FilterChoiceRow(p, value(i), enabled, label, help) { set(i, it) }
            else -> FilterParameterRow(p, value(i), enabled, label, help, action = when {
                p.seed -> ({ RowAction(Icons.Rounded.Casino, "New random seed", false, enabled) { set(i, kotlin.random.Random.nextInt(1 shl 24).toFloat()) } })
                i in pairs && coordinates(i) -> ({
                    RowAction(Icons.Rounded.TouchApp, "Place on the image", placing == i, enabled) {
                        if (placing == i) { vm.filterPoint = null; placing = null } else { vm.filterPicking = null; placing = i; vm.filterPoint = { x, y -> place(i, x, y) } }
                    }
                })
                i in pairs -> ({ RowAction(if (isLinked(i)) Icons.Rounded.Link else Icons.Rounded.LinkOff, "Link X and Y", isLinked(i), enabled) {
                    val link = !isLinked(i); linked[i] = link
                    if (link) values[pairs.getValue(i)] = value(i).coerceIn(params[pairs.getValue(i)].allowedRange)
                } })
                else -> null
            }) { set(i, it) }
        }
        if (placing == i) Text("Touch the image to place it", color = Ui.muted, fontSize = 12.sp)
    }
    spec.colors.forEach { p ->
        if (!p.isVisible(params, current)) return@forEach
        FilterColorRow(vm, p, pickedColors[p.key] ?: initialColors.getValue(p.key), p.isEnabled(params, current), p.displayLabel(params, current)) { pickedColors[p.key] = it }
    }
}

@Composable
private fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(32.dp).padding(start = 4.dp), enabled = enabled) {
        Icon(icon, description, tint = if (active) Ui.accent else Ui.muted, modifier = Modifier.size(18.dp))
    }
}

/** The label on the left; the color's swatch and a picker from the image on the right. */
@Composable
private fun FilterColorRow(vm: EditorViewModel, param: Param, color: Int, enabled: Boolean, label: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        if (enabled) {
            val picking = vm.filterPicking != null
            RowAction(Icons.Rounded.Colorize, "Pick $label from the image", picking, true) {
                vm.filterPoint = null
                vm.filterPicking = if (picking) null else { c -> onChange(c); vm.filterPicking = null }
            }
            ColorPicker(vm, label, color, outline = false) { it?.let(onChange) }
        } else Box(Modifier.padding(8.dp).size(24.dp).clip(CircleShape).background(Color(color)).border(1.dp, Ui.border, CircleShape))
    }
}
