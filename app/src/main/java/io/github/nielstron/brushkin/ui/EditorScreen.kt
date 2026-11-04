package io.github.nielstron.brushkin.ui

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
    LaunchedEffect(vm.tool, vm.pathVersion, vm.cloneSource, vm.perspPlane, vm.perspEditing) { holder.view?.invalidate() }

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

        // Selection and clipboard: × to deselect (any tool) and a chevron with the rest.
        // Deleting pixels stays on the Del key and Edit → Clear.
        if ((info.hasSelection || vm.clipHasImage) && !info.floating) {
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (vm.showRulers) 94.dp else 72.dp, end = 12.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Ui.panel.copy(alpha = 0.94f))
                    .border(1.dp, Ui.border, RoundedCornerShape(14.dp)),
            ) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.ExpandMore, if (info.hasSelection) "Selection actions" else "Paste", tint = Ui.text) }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Ui.panel) {
                        @Composable
                        fun Item(label: String, icon: ImageVector, run: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, color = Ui.text) }, leadingIcon = { Icon(icon, null, tint = Ui.text) }, onClick = { menu = false; run() },
                        )
                        if (info.hasSelection) {
                            Item("Cut", Icons.Rounded.ContentCut) { vm.copy(cut = true) }
                            Item("Copy", Icons.Rounded.ContentCopy) { vm.copy() }
                        }
                        if (vm.clipHasImage) Item("Paste as new layer", Icons.Rounded.ContentPaste) { vm.paste() }
                        if (info.hasSelection) {
                            Item("Invert selection", ToolIcons.fuzzy) { vm.engine.selectInvert() }
                            Item("Crop to selection", ToolIcons.crop) { vm.engine.cropToSelection() }
                        }
                    }
                }
                if (info.hasSelection) IconButton(onClick = { vm.engine.selectNone(); vm.objectNew(); holder.view?.invalidate() }) { Icon(Icons.Rounded.Close, "Deselect", tint = Ui.text) }
            }
        }

        androidx.compose.runtime.LaunchedEffect(vm.showGrid, vm.gridSize, vm.showRulers, vm.symmetryKind, vm.symmetrySegments) { holder.view?.invalidate() }

        // Side sliders for size and opacity, within reach of the holding thumb.
        if (vm.strokeTool.paint != null && info.floating.not()) {
            SideSliders(vm, Modifier.align(Alignment.CenterStart).padding(start = if (vm.showRulers) 26.dp else 10.dp))
        }
        // Picking an object: whether taps add to it or take away from it, apart from the chips below.
        if (vm.tool.picksObjects && vm.objPoints.isNotEmpty()) {
            ObjectSidePanel(vm, holder, Modifier.align(Alignment.CenterStart).padding(start = if (vm.showRulers) 26.dp else 10.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            if (info.floating && vm.tool != Tool.MOVE) {
                val lifted = vm.tool.confirmTransform
                if (lifted) Row(Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.tool != Tool.WARP && vm.tool != Tool.CAGE) TargetChips(!vm.transformWhole) { vm.changeTransformTarget(!it) }
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
                val relift = { vm.handles.clear(); if (vm.tool == Tool.WARP || vm.tool == Tool.CAGE) vm.engine.meshBegin(vm.tool == Tool.CAGE) else vm.engine.liftActive(vm.transformWhole) }
                ConfirmBar(if (lifted) "Apply" else "Place", onCancel = { vm.engine.cancelFloating(); if (lifted) relift() }) {
                    vm.engine.floating?.let { f -> vm.engine.commitFloating(android.graphics.Matrix(f.matrix), vm.tool.label) }
                    // Stay in the tool, ready for the next adjustment.
                    if (lifted) relift()
                }
            }
            else if (vm.tool == Tool.CROP && vm.cropRect != null) ConfirmBar("Crop", onCancel = { vm.cropRect = null; holder.view?.invalidate() }) {
                vm.cropRect?.let { r ->
                    val rect = android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                    if (vm.cropLayer) vm.engine.setLayerBoundary(rect) else vm.engine.crop(rect)
                }
                vm.cropRect = null
            }
            else {
                val editingMask = info.layers.firstOrNull { it.id == info.activeId }?.editMask == true
                if (editingMask) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Editing mask", true) { vm.engine.setEditMask(info.activeId, false) }
                    Chip("Hide (black)", vm.fg == android.graphics.Color.BLACK) { vm.fg = android.graphics.Color.BLACK }
                    Chip("Reveal (white)", vm.fg == android.graphics.Color.WHITE) { vm.fg = android.graphics.Color.WHITE }
                }
                if (vm.favorites.isNotEmpty()) FavoriteBar(vm)
                ContextChips(vm, info.hasSelection, holder)
            }
            Spacer(Modifier.height(10.dp))
            Dock(vm, info.layers.size)
        }

        val anyBusy = busy ?: vm.mlBusy
        val progress by vm.engine.progress.collectAsState()
        if (anyBusy != null) {
            // Full-width card: what's happening, and a bar that fills when the progress is known.
            Column(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp).padding(horizontal = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Ui.panel).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canCancel = vm.engine.cancellable.collectAsState().value || (busy == null && vm.mlBusy != null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$anyBusy…", color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (canCancel) Text("Cancel", color = Ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.cancelWork() }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                val p = progress.takeIf { busy != null }
                val mod = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = mod, color = Ui.accent, trackColor = Ui.panel2, drawStopIndicator = {})
                else LinearProgressIndicator(modifier = mod, color = Ui.accent, trackColor = Ui.panel2)
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
        val quickMask = vm.engine.info.collectAsState().value.quickMask
        if (quickMask) Chip("Quick mask", true) { vm.engine.quickMaskToggle() }
        if (vm.pipette) {
            Text("Touch the image to pick a color", color = Ui.text, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 4.dp))
            Chip("Cancel", false) { vm.pipette = false; vm.sheet = Sheet.COLOR }
        } else when {
            t == Tool.PERSPECTIVE_CLONE -> {
                Chip("Modify plane", vm.perspEditing) { vm.perspEditing = true; holder?.view?.invalidate() }
                Chip("Clone", !vm.perspEditing) { vm.perspEditing = false; holder?.view?.invalidate() }
                if (!vm.perspEditing) Chip(if (vm.cloneSource == null) "Set source" else "New source", vm.settingSource, ToolIcons.target) { vm.settingSource = !vm.settingSource }
            }
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
                if (t == Tool.OBJECT_SELECT) ObjectChips(vm, holder, removeChip = hasSelection)
                if (t != Tool.OBJECT_SELECT || vm.objPoints.isEmpty())
                    for (m in SelectMode.entries) Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, vm.selectMode == m) { vm.selectMode = m }
                if (t == Tool.SCISSORS) {
                    androidx.compose.runtime.LaunchedEffect(vm.scissorsVersion) { holder?.view?.invalidate() }
                    Chip("Close & select", false) { vm.scissorsClose() }
                    Chip("Undo point", false) { vm.scissorsUndo(); holder?.view?.invalidate() }
                    Chip("Clear", false) { vm.scissorsClear(); holder?.view?.invalidate() }
                }
                if (!quickMask) Chip("Quick mask", false) { vm.engine.quickMaskToggle() }
            }
            t == Tool.REMOVE_OBJECT -> {
                if (hasSelection) Chip("Remove", true) { vm.removeObject() }
                ObjectChips(vm, holder, removeChip = false)
                if (!hasSelection) Text("Tap the object to remove", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 4.dp))
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
                val what = if (vm.transformWhole) "the image" else if (hasSelection) "the selection on “$name”" else "“$name”"
                TargetChips(!vm.transformWhole) { vm.changeTransformTarget(!it) }
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
                TargetChips(!vm.transformWhole) { vm.transformWhole = !it }
                Chip("Flip horizontally", false, ToolIcons.mirror) { if (vm.transformWhole) vm.engine.flipImage(true) else vm.engine.flipLayer(true) }
                Chip("Flip vertically", false) { if (vm.transformWhole) vm.engine.flipImage(false) else vm.engine.flipLayer(false) }
            }
            t == Tool.CROP -> {
                TargetChips(vm.cropLayer) { vm.cropLayer = it }
                if (hasSelection) Chip("Crop to selection", false, ToolIcons.crop) { vm.engine.cropToSelection() }
                Text("Drag to choose the area to keep", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            t == Tool.MEME -> Text("Tap the top or bottom of the image", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
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

/** Object selection: while an object is being picked, taps add to it (Include) or take away from it (Exclude). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ObjectChips(vm: EditorViewModel, holder: CanvasHolder?, removeChip: Boolean) {
    Chip("Whole foreground", false) { vm.selectForeground() }
    if (removeChip) Chip("Remove object", false) { vm.removeObject() }
}

/** Whether a transform acts on the active layer or the whole image. */
@Composable
private fun TargetChips(layer: Boolean, onChange: (layer: Boolean) -> Unit) {
    Chip("Layer", layer) { onChange(true) }
    Chip("Image", !layer) { onChange(false) }
}

/** The user's favorite tools, one tap away (long-press a tool in the tool list to add or remove it). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FavoriteBar(vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in vm.favorites) {
            val active = vm.tool == t
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Ui.accent else Ui.panel)
                    .border(1.dp, Ui.border, RoundedCornerShape(14.dp))
                    .combinedClickable(onClickLabel = t.label, onClick = { vm.pickTool(t) }, onLongClick = { vm.sheet = Sheet.TOOLS }),
                contentAlignment = Alignment.Center,
            ) { Icon(ToolIcons.of(t), t.label, tint = if (active) Ui.onAccent else Ui.text, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Include / exclude taps and starting over, for the object being picked. */
@Composable
private fun ObjectSidePanel(vm: EditorViewModel, holder: CanvasHolder, modifier: Modifier) {
    Column(
        modifier.width(52.dp).clip(RoundedCornerShape(26.dp)).background(Ui.panel.copy(alpha = 0.94f)).border(1.dp, Ui.border, RoundedCornerShape(26.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun Btn(icon: ImageVector, label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (selected) color else Color.Transparent)
                    .border(2.dp, color, CircleShape).clickable(onClickLabel = label, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, label, tint = if (selected) Color.White else color, modifier = Modifier.size(24.dp)) }
        }
        Btn(Icons.Rounded.Add, "Taps include", !vm.objSubtract, Color(0xFF3DBE5C)) { vm.objSubtract = false }
        Btn(Icons.Rounded.Remove, "Taps exclude", vm.objSubtract, Color(0xFFE5484D)) { vm.objSubtract = true }
        Box(Modifier.size(24.dp, 1.dp).background(Ui.border))
        Btn(Icons.Rounded.Close, "New object", false, Ui.muted) { vm.objectNew(); holder.view?.invalidate() }
    }
}
