package io.github.nielstron.brushkin.ui

import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.gestures.detectTransformGestures
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
    // Move & scale mode: the keyboard goes away and the text box follows one or two fingers.
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(te.layerId, moving) { if (!moving) focus.requestFocus() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Box(Modifier.fillMaxSize()) {
        // Keep the text being typed in view: above the keyboard and the style bar, below the top bar.
        var barTop by remember { mutableStateOf(Float.MAX_VALUE) }
        var fieldH by remember { mutableStateOf(0) }
        var fieldW by remember { mutableStateOf(0) }
        val topLimit = with(density) { 96.dp.toPx() }
        LaunchedEffect(barTop, fieldH, te.layerId) {
            if (barTop == Float.MAX_VALUE || fieldH == 0) return@LaunchedEffect
            val cos = kotlin.math.abs(kotlin.math.cos(Math.toRadians(angle.toDouble()))).toFloat().coerceAtLeast(0.3f)
            val top = v[5]; val bottom = v[5] + fieldH * cos
            val margin = with(density) { 16.dp.toPx() }
            val dy = when {
                bottom > barTop - margin && bottom - top < barTop - margin - topLimit -> (barTop - margin) - bottom
                top < topLimit -> topLimit - top
                else -> 0f
            }
            if (kotlin.math.abs(dy) > 1f) view.panBy(0f, dy)
        }
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
                // Meme text shows in capitals (same length per character, so the cursor maps 1:1).
                visualTransformation = if (spec.family == io.github.nielstron.brushkin.engine.TextSpec.MEME_FONT)
                    androidx.compose.ui.text.input.VisualTransformation { s ->
                        androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(String(s.text.map { it.uppercaseChar() }.toCharArray())), androidx.compose.ui.text.input.OffsetMapping.Identity)
                    } else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = TextStyle(
                    color = Color(spec.color), fontSize = fontSp,
                    fontFamily = FontFamily(spec.typeface),
                    fontWeight = if (spec.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (spec.italic) FontStyle.Italic else FontStyle.Normal,
                    shadow = if (spec.outline) Shadow(Color(spec.outlineColor), Offset.Zero, 6f) else null,
                    textAlign = if (spec.center) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                ),
                cursorBrush = SolidColor(Ui.accent),
                modifier = (if (boxW != null) Modifier.width(boxW) else Modifier)
                    .onSizeChanged { fieldSize = it; fieldH = it.height; fieldW = it.width }
                    // Move mode: a thicker dashed outline shows the box can be dragged.
                    .then(
                        if (moving) Modifier.drawBehind {
                            drawRect(Ui.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
                        } else Modifier.border(1.dp, Ui.accent.copy(alpha = 0.8f))
                    )
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

        // Rotation handle above the text box (screen coordinates, so it can follow the finger).
        run {
            val cos = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat(); val sin = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
            fun local(x: Float, y: Float) = Offset(v[2] + x * cos - y * sin, v[5] + x * sin + y * cos)
            val off = with(density) { 36.dp.toPx() }
            val fw = fieldW.toFloat(); val fh = fieldH.toFloat()
            val center = local(fw / 2, fh / 2)
            val handle = local(fw / 2, -off)
            val r = with(density) { 14.dp.toPx() }
            var handleRoot by remember { mutableStateOf(Offset.Zero) }
            val currentCenter by androidx.compose.runtime.rememberUpdatedState(center)
            val currentRoot by androidx.compose.runtime.rememberUpdatedState(handleRoot)
            if (fw > 0 && spec.path == null) Box(
                Modifier.offset { IntOffset((handle.x - r).roundToInt(), (handle.y - r).roundToInt()) }
                    .size(28.dp).onGloballyPositioned { handleRoot = it.positionInRoot() }
                    .clip(CircleShape).background(Ui.panel).border(2.dp, Ui.accent, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val latest = vm.textEdit?.spec ?: return@detectDragGestures
                            // Finger in screen coordinates; the box turns so its top faces the finger.
                            val q = currentRoot + change.position; val c = currentCenter
                            val target = Math.toDegrees(atan2((q.y - c.y).toDouble(), (q.x - c.x).toDouble())).toFloat() + 90f
                            val vv = FloatArray(9); android.graphics.Matrix(latest.matrix()).apply { postConcat(view.viewMatrix) }.getValues(vv)
                            val now = Math.toDegrees(atan2(vv[3], vv[0]).toDouble()).toFloat()
                            val inv = android.graphics.Matrix(); view.viewMatrix.invert(inv)
                            val cd = floatArrayOf(c.x, c.y); inv.mapPoints(cd)
                            vm.updateTextEdit(latest.withMatrix(latest.matrix().apply { postRotate(target - now, cd[0], cd[1]) }))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(ToolIcons.rotate, "Rotate text", tint = Ui.accent, modifier = Modifier.size(16.dp)) }
        }

        // Move mode, on top of the text field so a drag starting on the text moves it too:
        // one finger moves, two fingers also scale and rotate.
        if (moving) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    val latest = vm.textEdit?.spec ?: return@detectTransformGestures
                    // Screen → document: the inverse of the view transform.
                    val inv = android.graphics.Matrix(); view.viewMatrix.invert(inv)
                    val pts = floatArrayOf(centroid.x - pan.x, centroid.y - pan.y, centroid.x, centroid.y)
                    inv.mapPoints(pts)
                    val tm = latest.matrix().apply { postTranslate(pts[2] - pts[0], pts[3] - pts[1]) }
                    if (rotation != 0f) tm.postRotate(rotation, pts[2], pts[3])
                    var s = latest
                    if (zoom != 1f) {
                        // Scale the text (and a fixed box) around the fingers.
                        val z = zoom.coerceIn(0.5f, 2f)
                        val newSize = (latest.size * z).coerceIn(8f, 2000f); val k = newSize / latest.size
                        val o = floatArrayOf(0f, 0f); tm.mapPoints(o)
                        tm.postTranslate((pts[2] - o[0]) * (1 - k), (pts[3] - o[1]) * (1 - k))
                        s = s.copy(size = newSize, boxWidth = latest.boxWidth?.let { it * k })
                    }
                    vm.updateTextEdit(s.withMatrix(tm))
                }
            },
        )

        // Style bar, riding on top of the keyboard. Icons instead of words so it stays compact.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().background(Ui.panel)
            .onGloballyPositioned { barTop = it.positionInRoot().y }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.finishText(cancel = true) }) { Icon(Icons.Rounded.Close, "Cancel text", tint = Ui.text) }
                FontPicker(spec.family) { vm.updateTextEdit(spec.copy(family = it)) }
                RoundToggle("Bold", spec.bold) { vm.updateTextEdit(spec.copy(bold = !spec.bold)) }
                RoundToggle("Italic", spec.italic) { vm.updateTextEdit(spec.copy(italic = !spec.italic)) }
                ColorPicker(vm, "Text color", spec.color, outline = false) { c -> vm.updateTextEdit(spec.copy(color = c!!)) }
                ColorPicker(vm, "Outline", if (spec.outline) spec.outlineColor else null, outline = true) { c ->
                    vm.updateTextEdit(if (c == null) spec.copy(outline = false) else spec.copy(outline = true, outlineColor = c))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Move, scale and rotate: drag the box, pinch and twist (keyboard hidden meanwhile).
                RoundIcon(ToolIcons.move, "Move, scale and rotate", moving) { moving = !moving; if (moving) focusManager.clearFocus() }
                RoundIcon(Icons.Rounded.Remove, "Smaller", false) { vm.updateTextEdit(spec.copy(size = (spec.size / 1.15f).coerceAtLeast(8f))) }
                Text("${spec.size.roundToInt()} px", color = Ui.text, fontSize = 13.sp, fontFamily = Ui.mono)
                RoundIcon(Icons.Rounded.Add, "Larger", false) { vm.updateTextEdit(spec.copy(size = (spec.size * 1.15f).coerceAtMost(2000f))) }
                if (spec.boxWidth != null) Chip("Auto width", false) { vm.updateTextEdit(spec.copy(boxWidth = null)) }
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

/** A round icon button that can be on or off. */
@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(if (selected) Ui.accentSoft else Ui.panel)
        .border(1.dp, if (selected) Ui.accent else Ui.border, CircleShape).clickable(onClickLabel = label, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, label, tint = if (selected) Ui.accent else Ui.text, modifier = Modifier.size(20.dp)) }

/** Bold / italic toggles: the letter itself shows the style. */
@Composable
private fun RoundToggle(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(if (selected) Ui.accentSoft else Ui.panel)
        .border(1.dp, if (selected) Ui.accent else Ui.border, CircleShape).clickable(onClickLabel = label, onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    Text(
        if (label == "Bold") "B" else "I", color = if (selected) Ui.accent else Ui.text, fontSize = 17.sp, fontFamily = FontFamily.Serif,
        fontWeight = if (label == "Bold") FontWeight.Black else FontWeight.Normal, fontStyle = if (label == "Italic") FontStyle.Italic else FontStyle.Normal,
    )
}

/** The current font, in itself; tapping lists the fonts, each in its own face. */
@Composable
private fun FontPicker(family: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun face(f: String) = FontFamily(io.github.nielstron.brushkin.engine.TextSpec("", 1f, f).typeface)
    val label = FONTS.firstOrNull { it.second == family }?.first ?: "Font"
    Box {
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Ui.panel).border(1.dp, Ui.border, RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Font") { open = true }.padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Ui.text, fontSize = 15.sp, fontFamily = remember(family) { face(family) })
            Icon(Icons.Rounded.ExpandMore, null, tint = Ui.muted, modifier = Modifier.size(20.dp))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ui.panel) {
            for ((l, f) in FONTS) androidx.compose.material3.DropdownMenuItem(
                text = { Text(l, color = if (f == family) Ui.accent else Ui.text, fontSize = 17.sp, fontFamily = remember(f) { face(f) }) },
                onClick = { open = false; onPick(f) },
            )
        }
    }
}

/**
 * A color swatch (filled for the text color, a ring for the outline; crossed out when there's no
 * outline) with a menu of colors: foreground, background, black, white and recent ones.
 */
@Composable
internal fun ColorPicker(vm: EditorViewModel, label: String, color: Int?, outline: Boolean, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Ui.panel).border(1.dp, Ui.border, CircleShape).clickable(onClickLabel = label) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            val c = color?.let { Color(it) }
            if (!outline) Box(Modifier.size(24.dp).clip(CircleShape).background(c ?: Color.Transparent).border(1.dp, Ui.border, CircleShape))
            else Box(Modifier.size(24.dp).border(5.dp, c ?: Ui.muted.copy(alpha = 0.4f), CircleShape)) {
                if (c == null) Box(Modifier.align(Alignment.Center).size(28.dp, 2.dp).graphicsLayer { rotationZ = -45f }.background(Ui.muted))
            }
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ui.panel) {
            Text(label, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (outline) androidx.compose.material3.DropdownMenuItem(text = { Text("No outline", color = Ui.text) }, onClick = { open = false; onPick(null) })
            val choices = (listOf(vm.fg, vm.bg, android.graphics.Color.BLACK, android.graphics.Color.WHITE) + vm.recentColors).distinct().take(12)
            // Swatches in rows of six.
            for (row in choices.chunked(6)) Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in row) Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (c == color) Ui.accent else Ui.border, CircleShape)
                        .clickable { open = false; onPick(c) },
                )
            }
        }
    }
}
