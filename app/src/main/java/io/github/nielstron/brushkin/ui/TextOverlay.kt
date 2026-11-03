package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * GIMP-style on-canvas text: a live text field drawn where the text will be, at the current zoom
 * and rotation, with a style bar above the keyboard.
 */
@Composable
fun TextOverlay(vm: EditorViewModel, holder: CanvasHolder, viewVersion: Int) {
    val te = vm.textEdit ?: return
    val spec = te.spec
    val view = holder.view ?: return
    val density = LocalDensity.current
    @Suppress("UNUSED_EXPRESSION") viewVersion // recompose when the view moves

    // Text box → screen.
    val m = android.graphics.Matrix(spec.matrix()).apply { postConcat(view.viewMatrix) }
    val v = FloatArray(9); m.getValues(v)
    val scale = hypot(v[0], v[3])
    val angle = Math.toDegrees(atan2(v[3], v[0]).toDouble()).toFloat()
    val pad = spec.size * 0.15f * scale
    val fontSp = with(density) { (spec.size * scale).toSp() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(te.layerId) { focus.requestFocus() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset { IntOffset(v[2].roundToInt(), v[5].roundToInt()) }
                .graphicsLayer { rotationZ = angle; transformOrigin = TransformOrigin(0f, 0f) }
                .wrapContentSize(Alignment.TopStart, unbounded = true),
        ) {
            var fieldSize by remember { mutableStateOf(IntSize.Zero) }
            val boxW = spec.boxWidth?.let { with(density) { (it * scale).toDp() } }
            BasicTextField(
                value = spec.text,
                onValueChange = { vm.updateTextEdit(spec.copy(text = it)) },
                textStyle = TextStyle(
                    color = Color(spec.color), fontSize = fontSp,
                    fontFamily = FontFamily(spec.typeface),
                    fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (spec.italic) FontStyle.Italic else FontStyle.Normal,
                    shadow = if (spec.outline) Shadow(Color.White, Offset.Zero, 6f) else null,
                ),
                cursorBrush = SolidColor(Ui.accent),
                modifier = (if (boxW != null) Modifier.width(boxW) else Modifier)
                    .onSizeChanged { fieldSize = it }
                    .border(1.dp, Ui.accent.copy(alpha = 0.8f))
                    .padding(with(density) { pad.toDp() })
                    .focusRequester(focus),
            )
            // Drag the right edge to fix the box width; text then wraps inside it.
            Box(
                Modifier.offset {
                    // Sits on the right edge; rides along the screen edge when the box runs off it.
                    val hw = 28.dp.roundToPx(); val margin = 44.dp.roundToPx()
                    val cos = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()
                    val visible = if (cos > 0.5f) ((view.width - margin - v[2]) / cos).toInt() else fieldSize.width
                    IntOffset(minOf(fieldSize.width, visible) - hw / 2, fieldSize.height / 2 - 22.dp.roundToPx())
                }
                    .size(28.dp, 44.dp).systemGestureExclusion()
                    .pointerInput(scale, angle, v[2]) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val latest = vm.textEdit?.spec ?: return@detectDragGestures
                            // First drag starts from where the handle is shown (possibly clamped to the screen).
                            val start = latest.boxWidth ?: run {
                                val cos = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()
                                val visible = if (cos > 0.5f) (view.width - 44.dp.toPx() - v[2]) / cos else Float.MAX_VALUE
                                minOf(fieldSize.width.toFloat(), visible) / scale
                            }
                            vm.updateTextEdit(latest.copy(boxWidth = (start + drag.x / scale).coerceAtLeast(latest.size)))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp, 28.dp).clip(RoundedCornerShape(5.dp)).background(Ui.accent))
            }
        }

        // Style bar, riding on top of the keyboard.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().background(Ui.panel)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.finishText(cancel = true) }) { Icon(Icons.Rounded.Close, "Cancel text", tint = Ui.text) }
                for ((label, f) in FONTS) Chip(label, spec.family == f) { vm.updateTextEdit(spec.copy(family = f)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("−", false) { vm.updateTextEdit(spec.copy(size = (spec.size / 1.15f).coerceAtLeast(8f))) }
                Text("${spec.size.roundToInt()} px", color = Ui.text, fontSize = 13.sp, fontFamily = Ui.mono)
                Chip("+", false) { vm.updateTextEdit(spec.copy(size = (spec.size * 1.15f).coerceAtMost(2000f))) }
                Chip("B", spec.bold) { vm.updateTextEdit(spec.copy(bold = !spec.bold)) }
                Chip("I", spec.italic) { vm.updateTextEdit(spec.copy(italic = !spec.italic)) }
                Chip("Outline", spec.outline) { vm.updateTextEdit(spec.copy(outline = !spec.outline)) }
                if (spec.boxWidth != null) Chip("Auto width", false) { vm.updateTextEdit(spec.copy(boxWidth = null)) }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(vm.fg)).border(2.dp, if (spec.color == vm.fg) Ui.accent else Ui.border, CircleShape)
                        .clickable { vm.updateTextEdit(spec.copy(color = vm.fg)) },
                )
                if (vm.path.anchors.size >= 2) Chip("Follow path", spec.path != null) {
                    vm.updateTextEdit(
                        if (spec.path != null) spec.copy(path = null)
                        else spec.copy(path = vm.path.anchors.flatMap { a -> listOf(a.p.x, a.p.y, a.hin.x, a.hin.y, a.hout.x, a.hout.y) }.toFloatArray(), pathClosed = vm.path.closed)
                    )
                }
            }
                // Always visible, outside the scrolling options.
                Row(
                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Ui.accent).clickable { vm.finishText() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Ui.onAccent, modifier = Modifier.size(18.dp))
                    Text(" Done", color = Ui.onAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
