package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt

private val FIELD_HEIGHT = 40.dp

/** Wide enough for the longest number the setting's slider range can show. */
private fun numberFieldWidth(param: Param, digits: Int): Dp {
    val range = if (param.range.endInclusive - param.range.start > 0) param.range else param.allowedRange
    val whole = maxOf(formatFilterNumber(range.start, 0).length, formatFilterNumber(range.endInclusive, 0).length)
    val characters = whole + if (digits > 0) digits.coerceAtMost(3) + 1 else 0
    return (characters * 9 + 20).dp.coerceIn(56.dp, 104.dp)
}

private val Param.unitText get() = when (unit) {
    "pixel-distance", "pixel-coordinate" -> "px"
    "degree" -> when (direction) { "cw" -> "° clockwise"; "ccw" -> "° counter-clockwise"; else -> "°" }
    "percent" -> "%"
    "kelvin" -> "K"
    else -> ""
}

/** The setting's name and unit, with its explanation one tap away. */
@Composable
private fun ParamLabel(param: Param, label: String, help: String, enabled: Boolean, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    var showHelp by remember(help) { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            if (param.unitText.isNotEmpty()) Text(param.unitText, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            if (help.isNotBlank()) Icon(Icons.Rounded.Info, "About $label", tint = Ui.muted,
                modifier = Modifier.padding(start = 6.dp).size(16.dp).clip(CircleShape).clickable { showHelp = !showHelp })
            action?.invoke()
        }
        if (showHelp) Text(help, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
    }
}

/** An on/off setting: the label on the left, a checkbox on the right. */
@Composable
internal fun FilterToggleRow(param: Param, value: Float, enabled: Boolean = true, label: String = param.label, help: String = param.description, onChange: (Float) -> Unit) {
    val checked = value != 0f
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox) { onChange(if (it) 1f else 0f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ParamLabel(param, label, help, enabled, Modifier.weight(1f).padding(end = 12.dp))
        Checkbox(checked, null, Modifier.minimumInteractiveComponentSize(), enabled = enabled)
    }
}

/** The label on the left; the current choice in a box on the right, which opens the menu. */
@Composable
internal fun FilterChoiceRow(param: Param, value: Float, enabled: Boolean = true, label: String = param.label, help: String = param.description, onChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ParamLabel(param, label, help, enabled, Modifier.weight(1f).padding(end = 12.dp))
        Box {
            Row(
                Modifier.widthIn(min = 88.dp, max = 200.dp).height(FIELD_HEIGHT).clip(shape).border(1.dp, if (enabled) Ui.muted else Ui.border, shape)
                    .clickable(enabled, role = Role.DropdownList) { expanded = true }.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(param.choices.firstOrNull { it.first == value }?.second ?: formatFilterNumber(value, param.digits), color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ArrowDropDown, null, tint = Ui.muted)
            }
            DropdownMenu(expanded, { expanded = false }) {
                param.choices.forEach { (number, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onChange(number); expanded = false })
                }
            }
        }
    }
}

/** Slider uses GEGL's ergonomic range/gamma; typed values use its full algorithm range. */
@Composable
internal fun FilterParameterRow(param: Param, value: Float, enabled: Boolean = true, label: String = param.label, help: String = param.description, action: (@Composable () -> Unit)? = null, onChange: (Float) -> Unit) {
    val digits = if (param.integral) 0 else param.digits
    val error = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val span = param.range.endInclusive - param.range.start
        val hasSlider = span.isFinite() && span > 0 && span <= 10000f
        if (hasSlider) ParamLabel(param, label, help, enabled, action = action)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasSlider) {
                Slider(param.sliderPosition(value), { t ->
                    error.value = false
                    onChange(param.sliderValue(t, digits))
                }, modifier = Modifier.weight(1f).padding(end = 8.dp), enabled = enabled)
            } else ParamLabel(param, label, help, enabled, Modifier.weight(1f).padding(end = 12.dp), action)
            NumberField(param, value, enabled, error, onChange)
        }
        if (error.value) NumberError(param)
    }
}

/** A low/high pair: one label and one track with two handles, a number box at each end. */
@Composable
internal fun FilterRangeRow(low: Param, high: Param, lowValue: Float, highValue: Float, enabled: Boolean = true, label: String = low.label, help: String = low.description, onLow: (Float) -> Unit, onHigh: (Float) -> Unit) {
    val lowError = remember { mutableStateOf(false) }
    val highError = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ParamLabel(low, label, help, enabled)
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(low, lowValue, enabled, lowError, onLow)
            val start = low.sliderPosition(lowValue)
            RangeSlider(start..maxOf(start, low.sliderPosition(highValue)), { positions ->
                val digits = if (low.integral) 0 else low.digits
                lowError.value = false; highError.value = false
                low.sliderValue(positions.start, digits).let { if (it != lowValue) onLow(it) }
                low.sliderValue(positions.endInclusive, digits).let { if (it != highValue) onHigh(it) }
            }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled)
            NumberField(high, highValue, enabled, highError, onHigh)
        }
        if (lowError.value) NumberError(low)
        if (highError.value) NumberError(high)
    }
}

private fun Param.sliderPosition(value: Float) = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f).pow(1f / gamma)
internal fun Param.sliderValue(position: Float, digits: Int): Float {
    val number = range.start + position.pow(gamma) * (range.endInclusive - range.start)
    return if (integral) number.roundToInt().toFloat().coerceIn(allowedRange) else rounded(number, digits)
}
/** Rounding to the shown precision must not carry a value past what GEGL allows. */
private fun Param.rounded(value: Float, digits: Int) = roundFilterNumber(value, digits).coerceIn(allowedRange)

@Composable
private fun NumberField(param: Param, value: Float, enabled: Boolean, error: MutableState<Boolean>, onChange: (Float) -> Unit) {
    val focus = LocalFocusManager.current
    val digits = if (param.integral) 0 else param.digits
    var text by remember(param) { mutableStateOf(formatFilterNumber(value, digits)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused || text.replace(',', '.').toFloatOrNull()?.let { param.rounded(it, digits) } != value) text = formatFilterNumber(value, digits)
    }
    fun commit() {
        val number = text.replace(',', '.').toFloatOrNull()
        error.value = number == null || !number.isFinite() || number !in param.allowedRange || (param.integral && number % 1f != 0f)
        if (!error.value) { val rounded = param.rounded(number!!, digits); text = formatFilterNumber(rounded, digits); onChange(rounded) }
    }
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(text, {
        text = it; error.value = false
        it.replace(',', '.').toFloatOrNull()?.takeIf { n -> n.isFinite() && n in param.allowedRange && (!param.integral || n % 1f == 0f) }?.let { n -> onChange(param.rounded(n, digits)) }
    },
        modifier = Modifier.width(numberFieldWidth(param, digits)).height(FIELD_HEIGHT).clip(shape)
            .border(1.dp, if (error.value) Ui.danger else if (focused) Ui.accent else if (enabled) Ui.muted else Ui.border, shape).onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
        textStyle = androidx.compose.ui.text.TextStyle(color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp, textAlign = TextAlign.End),
        singleLine = true, enabled = enabled, cursorBrush = SolidColor(Ui.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); if (!error.value) focus.clearFocus() }),
        decorationBox = { field -> Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterEnd) { field() } },
    )
}

@Composable
private fun NumberError(param: Param) =
    Text("Enter ${if (param.integral) "a whole number" else "a number"} from ${param.allowedRange.start} to ${param.allowedRange.endInclusive}", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)

internal fun roundFilterNumber(value: Float, digits: Int): Float = java.math.BigDecimal(value.toString()).setScale(digits.coerceIn(0, 6), java.math.RoundingMode.HALF_UP).toFloat()
private fun formatFilterNumber(value: Float, digits: Int): String = java.math.BigDecimal(value.toString()).setScale(digits.coerceIn(0, 6), java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
