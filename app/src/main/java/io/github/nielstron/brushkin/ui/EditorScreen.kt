package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nielstron.brushkin.engine.SelectMode
import kotlin.math.pow
import kotlin.math.roundToInt

class CanvasHolder { var view: CanvasView? = null }

@Composable
fun EditorScreen(vm: EditorViewModel, files: FileActions) {
    val info by vm.engine.info.collectAsState()
    val busy by vm.engine.busy.collectAsState()
    val holder = remember { CanvasHolder() }
    var viewVersion by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    // Pause the GL thread with the activity so it doesn't render (or hold the GPU) in the background.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val glView = holder.view?.gl?.view
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Tool-dependent overlays (paths, clone source, crop) must redraw when the tool changes.
    LaunchedEffect(vm.tool, vm.pathVersion, vm.cloneSource) { holder.view?.invalidate() }

    Box(Modifier.fillMaxSize().background(Ui.ground)) {
        AndroidView(
            factory = { ctx ->
                val canvasView = CanvasView(ctx, vm).also { holder.view = it; it.onViewChanged = { viewVersion++ } }
                android.widget.FrameLayout(ctx).apply {
                    // Linear-light GPU rendering when OpenGL ES 3 is available; the canvas view on
                    // top handles input and overlays. Without GLES3 it draws the document itself.
                    val es3 = (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                        .deviceConfigurationInfo.reqGlEsVersion >= 0x30000
                    if (es3) {
                        val renderer = GlRenderer(vm.engine)
                        val glView = android.opengl.GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(3)
                            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                            preserveEGLContextOnPause = true
                            setRenderer(renderer)
                            renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                        addView(glView)
                        canvasView.gl = CanvasView.Gl(glView, renderer)
                    }
                    addView(canvasView)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar
        Row(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth().height(52.dp)
                .clip(RoundedCornerShape(16.dp)).background(Ui.panel.copy(alpha = 0.94f))
                .border(1.dp, Ui.border, RoundedCornerShape(16.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.showMenu = true }) { Icon(Icons.Rounded.Menu, "Menus and commands", tint = Ui.text) }
            Text(
                info.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 2.dp).clickable { holder.view?.fit() },
            )
            IconButton(onClick = { vm.engine.undo() }, enabled = info.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (info.canUndo) Ui.text else Ui.muted.copy(alpha = 0.5f))
            }
            IconButton(onClick = { vm.engine.redo() }, enabled = info.canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", tint = if (info.canRedo) Ui.text else Ui.muted.copy(alpha = 0.5f))
            }
            IconButton(onClick = { vm.dialog = "export" }) { Icon(Icons.Rounded.IosShare, "Export", tint = Ui.text) }
        }

        // Clipboard actions, shown when there's something to act on. Deleting pixels stays on the
        // Del key and Edit → Clear.
        if ((info.hasSelection || vm.clipHasImage) && !info.floating) {
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 72.dp, end = 12.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Ui.panel.copy(alpha = 0.94f))
                    .border(1.dp, Ui.border, RoundedCornerShape(14.dp)),
            ) {
                if (info.hasSelection) {
                    IconButton(onClick = { vm.copy(cut = true) }) { Icon(Icons.Rounded.ContentCut, "Cut", tint = Ui.text) }
                    IconButton(onClick = { vm.copy() }) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Ui.text) }
                }
                if (vm.clipHasImage) IconButton(onClick = { vm.paste() }) { Icon(Icons.Rounded.ContentPaste, "Paste as new layer", tint = Ui.text) }
            }
        }

        // Side sliders for size and opacity, within reach of the holding thumb.
        if (vm.strokeTool.paint != null && info.floating.not()) {
            SideSliders(vm, Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            if (info.floating && vm.tool != Tool.MOVE) {
                val lifted = vm.tool.confirmTransform
                if (lifted) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.tool == Tool.SCALE) Chip("Keep ratio", vm.keepRatio) { vm.keepRatio = !vm.keepRatio }
                    if (vm.tool == Tool.ROTATE) Chip("Snap 15°", vm.snapRotation) { vm.snapRotation = !vm.snapRotation }
                    when (vm.tool) {
                        Tool.SHEAR -> Text("Drag sideways or up and down", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Tool.PERSPECTIVE -> Text("Drag the corners", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Tool.TRANSFORM_3D -> Text("Drag to tilt", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Tool.HANDLE -> Text("Tap to add up to 4 handles, drag to transform", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Tool.WARP -> Text("Push pixels with your finger", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Tool.CAGE -> Text("Tap to place the cage, then drag its points", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        else -> {}
                    }
                }
                val relift = { vm.handles.clear(); if (vm.tool == Tool.WARP || vm.tool == Tool.CAGE) vm.engine.meshBegin(vm.tool == Tool.CAGE) else vm.engine.liftActive() }
                ConfirmBar(if (lifted) "Apply" else "Place", onCancel = { vm.engine.cancelFloating(); if (lifted) relift() }) {
                    vm.engine.floating?.let { f -> vm.engine.commitFloating(android.graphics.Matrix(f.matrix), vm.tool.label) }
                    // Stay in the tool, ready for the next adjustment.
                    if (lifted) relift()
                }
            }
            else if (vm.tool == Tool.CROP && vm.cropRect != null) ConfirmBar("Crop", onCancel = { vm.cropRect = null; holder.view?.invalidate() }) {
                vm.cropRect?.let { r -> vm.engine.crop(android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())) }
                vm.cropRect = null
            }
            else {
                val editingMask = info.layers.firstOrNull { it.id == info.activeId }?.editMask == true
                if (editingMask) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Editing mask", true) { vm.engine.setEditMask(info.activeId, false) }
                    Chip("Hide (black)", vm.fg == android.graphics.Color.BLACK) { vm.fg = android.graphics.Color.BLACK }
                    Chip("Reveal (white)", vm.fg == android.graphics.Color.WHITE) { vm.fg = android.graphics.Color.WHITE }
                }
                ContextChips(vm, info.hasSelection, holder)
            }
            Spacer(Modifier.height(10.dp))
            Dock(vm, info.layers.size)
        }

        val anyBusy = busy ?: vm.mlBusy
        if (anyBusy != null) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Ui.panel).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Ui.accent, strokeWidth = 2.dp)
                Text("$anyBusy…", color = Ui.text, fontSize = 13.sp)
            }
        }

        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp))

        TextOverlay(vm, holder, viewVersion)
        Sheets(vm, info, files)
        if (vm.showMenu) MenuScreen(vm, info, files, holder)
        Dialogs(vm, info, files)
    }
}

@Composable
private fun ConfirmBar(label: String, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel).border(1.dp, Ui.border, RoundedCornerShape(14.dp)).clickable(onClick = onCancel),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Icon(Icons.Rounded.Close, null, tint = Ui.text); Spacer(Modifier.width(6.dp)); Text("Cancel", color = Ui.text) }
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Ui.accent).clickable(onClick = onApply),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { Icon(Icons.Rounded.Check, null, tint = Ui.onAccent); Spacer(Modifier.width(6.dp)); Text(label, color = Ui.onAccent, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ContextChips(vm: EditorViewModel, hasSelection: Boolean, holder: CanvasHolder? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val t = vm.tool
        when {
            t == Tool.CLONE || t == Tool.HEAL -> {
                Chip(if (vm.cloneSource == null) "Set source" else "New source", vm.settingSource, ToolIcons.target) { vm.settingSource = !vm.settingSource }
                Chip("Line", vm.lineMode, ToolIcons.line) { vm.lineMode = !vm.lineMode }
                if (vm.settingSource) Text("Tap the spot to copy from (or long-press anytime)", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
            t == Tool.DODGE_BURN -> {
                Chip("Dodge", !vm.altEffect) { vm.altEffect = false }
                Chip("Burn", vm.altEffect) { vm.altEffect = true }
            }
            t == Tool.BLUR_SHARPEN -> {
                Chip("Blur", !vm.altEffect) { vm.altEffect = false }
                Chip("Sharpen", vm.altEffect) { vm.altEffect = true }
            }
            t.paint != null -> {
                Chip("Line", vm.lineMode, ToolIcons.line) { vm.lineMode = !vm.lineMode }
                Chip("Pick", vm.pickMode, ToolIcons.picker) { vm.pickMode = !vm.pickMode }
                if (t != Tool.ERASER && t != Tool.SMUDGE) Chip("Erase", vm.eraseToggle, ToolIcons.eraser) { vm.eraseToggle = !vm.eraseToggle }
            }
            t.isSelect -> {
                // Actions on an existing selection come first so they're never scrolled off.
                if (hasSelection) {
                    Chip("Invert", false) { vm.engine.selectInvert() }
                    Chip("Deselect", false) { vm.engine.selectNone() }
                    Chip("Crop", false, ToolIcons.crop) { vm.engine.cropToSelection() }
                }
                for (m in SelectMode.entries) Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, vm.selectMode == m) { vm.selectMode = m }
                if (t == Tool.RECT_SELECT || t == Tool.ELLIPSE_SELECT) {
                    Chip("From center", vm.fromCenter) { vm.fromCenter = !vm.fromCenter }
                    Chip("Fixed 1:1", vm.fixedRatio) { vm.fixedRatio = !vm.fixedRatio }
                }
                if (t == Tool.OBJECT_SELECT) Chip("Whole foreground", false) { vm.objectSelect(-1f, -1f) }
            }
            t == Tool.BUCKET || t == Tool.GRADIENT -> {
                Chip("Pick", vm.pickMode, ToolIcons.picker) { vm.pickMode = !vm.pickMode }
                if (t == Tool.BUCKET) Chip("Fill selection", vm.fillWholeSelection) { vm.fillWholeSelection = !vm.fillWholeSelection }
            }
            t == Tool.PATHS -> {
                @Suppress("UNUSED_EXPRESSION") vm.pathVersion
                Chip("To selection", false) { vm.pathToSelection() }
                Chip("Stroke with ${vm.lastPaintTool.label.lowercase()}", false) { vm.strokePath() }
                Chip(if (vm.path.closed) "Open" else "Close", vm.path.closed) { vm.path.closed = !vm.path.closed; vm.pathVersion++; holder?.view?.invalidate() }
                Chip("Clear", false) { vm.path.clear(); vm.pathVersion++; holder?.view?.invalidate() }
            }
            t == Tool.MOVE || t == Tool.SCALE || t == Tool.ROTATE -> {
                val name = vm.engine.info.collectAsState().value.let { i -> i.layers.firstOrNull { it.id == i.activeId }?.name ?: "" }
                val what = if (hasSelection) "the selection on “$name”" else "“$name”"
                Text(
                    when (t) { Tool.MOVE -> "Drag to move $what"; Tool.SCALE -> "Scaling $what"; else -> "Drag to rotate $what" },
                    color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp), maxLines = 1,
                )
            }
            t == Tool.ALIGN -> {
                for ((label, hv) in listOf("Left" to (0 to -1), "Center" to (1 to -1), "Right" to (2 to -1), "Top" to (-1 to 0), "Middle" to (-1 to 1), "Bottom" to (-1 to 2))) {
                    Chip(label, false) { vm.engine.alignLayer(hv.first, hv.second) }
                }
            }
            t == Tool.FLIP -> {
                Chip("Flip horizontally", false, ToolIcons.mirror) { vm.engine.flipLayer(true) }
                Chip("Flip vertically", false) { vm.engine.flipLayer(false) }
            }
            t == Tool.CROP -> {
                if (hasSelection) Chip("Crop to selection", false, ToolIcons.crop) { vm.engine.cropToSelection() }
                Text("Drag to choose the area to keep", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            t == Tool.TEXT -> Text("Tap the canvas to add text", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            t == Tool.PICKER -> Text("Touch the canvas to pick a color", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Dock(vm: EditorViewModel, layerCount: Int) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(22.dp)).background(Ui.panel).border(1.dp, Ui.border, RoundedCornerShape(22.dp)).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(ToolIcons.of(vm.tool), "Tools (${vm.tool.label})", active = true) { vm.sheet = Sheet.TOOLS }
        DockButton(ToolIcons.sliders, "Options") { vm.sheet = Sheet.OPTIONS }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Ui.text).clickable { vm.sheet = Sheet.INSERT }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Insert image, text, sticker or emoji", tint = Ui.ground, modifier = Modifier.size(28.dp))
        }
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Color") { vm.sheet = Sheet.COLOR },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp, 30.dp)) {
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(Color(vm.bg)).border(2.dp, Ui.panel, CircleShape))
                Box(Modifier.align(Alignment.TopStart).size(23.dp).clip(CircleShape).background(Color(vm.fg)).border(2.dp, Ui.text, CircleShape))
            }
        }
        Box {
            DockButton(ToolIcons.layers, "Layers") { vm.sheet = Sheet.LAYERS }
            Box(
                Modifier.align(Alignment.TopEnd).offset((-2).dp, 4.dp).size(18.dp).clip(CircleShape).background(Color(0xFF3A3A42)),
                contentAlignment = Alignment.Center,
            ) { Text("$layerCount", color = Ui.text, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (active) Ui.accent else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Ui.onAccent else Ui.text.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
    }
}

/** Brush size maps exponentially so small sizes get finer control. */
fun sizeToPos(s: Float) = ((s / 1000f).toDouble().pow(1 / 3.0)).toFloat()
fun posToSize(p: Float) = (1000f * p.toDouble().pow(3.0).toFloat()).coerceIn(1f, 1000f)

@Composable
private fun SideSliders(vm: EditorViewModel, modifier: Modifier) {
    Column(
        modifier.width(44.dp).clip(RoundedCornerShape(22.dp)).background(Ui.panel.copy(alpha = 0.94f)).border(1.dp, Ui.border, RoundedCornerShape(22.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val s = vm.settings
        Text("${s.size.roundToInt()}", color = Ui.muted, fontSize = 10.sp, fontFamily = Ui.mono)
        VerticalSlider(sizeToPos(s.size), Ui.accent, "Brush size") { vm.settings = vm.settings.copy(size = posToSize(it)) }
        Box(Modifier.size(20.dp, 1.dp).background(Ui.border))
        VerticalSlider(s.opacity, Ui.text, "Opacity") { vm.settings = vm.settings.copy(opacity = it.coerceIn(0.01f, 1f)) }
        Text("${(s.opacity * 100).roundToInt()}%", color = Ui.muted, fontSize = 10.sp, fontFamily = Ui.mono)
    }
}

@Composable
private fun VerticalSlider(value: Float, fill: Color, label: String, onChange: (Float) -> Unit) {
    var h by remember { mutableStateOf(1) }
    Box(
        Modifier.width(44.dp).height(130.dp).onSizeChanged { h = it.height }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { o -> onChange(1f - (o.y / h).coerceIn(0f, 1f)) }) { change, _ ->
                    onChange(1f - (change.position.y / h).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(0xFF3A3A42))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(fill))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).offset(y = (-(value.coerceIn(0f, 1f)) * 110).dp).size(20.dp).clip(CircleShape).background(Ui.text)
        )
    }
}
