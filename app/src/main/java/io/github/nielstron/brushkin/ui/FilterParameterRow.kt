package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Slider uses GEGL's ergonomic range/gamma; typed values use its full algorithm range. */
@Composable
internal fun FilterParameterRow(param: Param, value: Float, onChange: (Float) -> Unit) {
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
        Text(param.label, color = Ui.text, fontSize = 14.sp)
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
                }, modifier = Modifier.weight(1f).padding(end = 8.dp))
            } else Spacer(Modifier.weight(1f))
            OutlinedTextField(text, {
                text = it; error = false
                it.replace(',', '.').toFloatOrNull()?.takeIf { n -> n.isFinite() && n in param.allowedRange && (!param.integral || n % 1f == 0f) }?.let { n -> onChange(roundFilterNumber(n)) }
            },
                modifier = Modifier.width(88.dp).onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = Ui.text, fontSize = 14.sp),
                singleLine = true, isError = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); if (!error) focus.clearFocus() }),
            )
        }
        if (error) Text("Enter ${if (param.integral) "a whole number" else "a number"} from ${param.allowedRange.start} to ${param.allowedRange.endInclusive}", color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)
    }
}

private fun roundFilterNumber(value: Float): Float = java.math.BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toFloat()
private fun formatFilterNumber(value: Float): String = java.math.BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
