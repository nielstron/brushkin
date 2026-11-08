package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt

/** An on/off setting: the label on the left, a checkbox on the right. */
@Composable
internal fun FilterToggleRow(param: Param, value: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val checked = value != 0f
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox) { onChange(if (it) 1f else 0f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Checkbox(checked, null, Modifier.minimumInteractiveComponentSize(), enabled = enabled)
    }
}

/** The label on the left; the current choice in a box on the right, which opens the menu. */
@Composable
internal fun FilterChoiceRow(param: Param, value: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(param.label, color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Box {
            Row(
                Modifier.widthIn(min = 88.dp, max = 200.dp).clip(shape).border(1.dp, if (enabled) Ui.muted else Ui.border, shape)
                    .clickable(enabled, role = Role.DropdownList) { expanded = true }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(param.choices.firstOrNull { it.first == value }?.second ?: formatFilterNumber(value), color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ArrowDropDown, null, tint = Ui.muted)
            }
            DropdownMenu(expanded, { expanded = false }) {
                param.choices.forEach { (number, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onChange(number); expanded = false })
                }
            }
        }
    }
}

/** Slider uses GEGL's ergonomic range/gamma; typed values use its full algorithm range. */
@Composable
internal fun FilterParameterRow(param: Param, value: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember(param.label) { mutableStateOf(formatFilterNumber(value)) }
    var focused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused || text.replace(',', '.').toFloatOrNull()?.let { roundFilterNumber(it) } != value) text = formatFilterNumber(value)
    }
    fun commit() {
        val number = text.replace(',', '.').toFloatOrNull()
        error = number == null || !number.isFinite() || number !in param.allowedRange || (param.integral && number % 1f != 0f)
        if (!error) { val rounded = roundFilterNumber(number!!); text = formatFilterNumber(rounded); onChange(rounded) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(param.label, color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val span = param.range.endInclusive - param.range.start
            if (span.isFinite() && span > 0 && span <= 10000f) {
                val position = ((value - param.range.start) / span).coerceIn(0f, 1f).pow(1f / param.gamma)
                Slider(position, { t ->
                    val number = param.range.start + t.pow(param.gamma) * span
                    val updated = if (param.integral) number.roundToInt().toFloat() else roundFilterNumber(number)
                    text = formatFilterNumber(updated)
                    error = false
                    onChange(updated)
                }, modifier = Modifier.weight(1f).padding(end = 8.dp), enabled = enabled)
            } else Spacer(Modifier.weight(1f))
            OutlinedTextField(text, {
                text = it; error = false
                it.replace(',', '.').toFloatOrNull()?.takeIf { n -> n.isFinite() && n in param.allowedRange && (!param.integral || n % 1f == 0f) }?.let { n -> onChange(roundFilterNumber(n)) }
            },
                modifier = Modifier.width(88.dp).onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = if (enabled) Ui.text else Ui.muted, fontSize = 14.sp),
                singleLine = true, isError = error, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); if (!error) focus.clearFocus() }),
            )
        }
        if (error) Text("Enter ${if (param.integral) "a whole number" else "a number"} from ${param.allowedRange.start} to ${param.allowedRange.endInclusive}", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)
    }
}

private fun roundFilterNumber(value: Float): Float = java.math.BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toFloat()
private fun formatFilterNumber(value: Float): String = java.math.BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
