package io.github.nielstron.brushkin.ui

import io.github.nielstron.brushkin.engine.*

/** Actions also exposed by sheets and tool controls, sharing the same handlers. */
fun panelCommands(vm: EditorViewModel, info: DocInfo, files: FileActions, view: () -> CanvasView?): List<Cmd> = buildList {
    val e = vm.engine
    fun cmd(section: String, label: String, hint: String = "", run: () -> Unit) { add(Cmd(section,label,hint,run)) }
    fun options(tool: Tool? = null) { if (tool != null) vm.selectTool(tool); vm.sheet = Sheet.OPTIONS }
    fun layers() { vm.sheet = Sheet.LAYERS }
    fun redraw() { view()?.invalidate() }

    cmd("File", "Insert image from gallery…", "open photo as a new layer") { files.pickImageLayer() }
    cmd("File", "Insert image from files…", "open image as a new layer") { files.pickImageLayerFile() }
    cmd("Tools", "Tool options…", "brush size opacity hardness flow spacing smoothing dynamics pressure tilt threshold feather antialias aspect ratio symmetry") { options() }
    cmd("Colors", "Foreground and background colors…", "palette swatches color picker") { vm.sheet = Sheet.COLOR }
    cmd("Colors", "Swap foreground and background colors") { vm.swapColors() }

    info.layers.firstOrNull { it.id == info.activeId }?.let { l ->
        cmd("Layer", if (l.visible) "Hide layer" else "Show layer", l.name) { e.setLayer(l.id,"Layer Visibility") { it.copy(visible = !it.visible) } }
        cmd("Layer", "Rename layer…", l.name) { vm.dialog = "rename-layer:${l.id}" }
        cmd("Layer", "Layer opacity…", l.name) { layers() }
        cmd("Layer", "Layer blend mode…", "multiply screen overlay ${l.name}") { layers() }
        cmd("Layer", if (l.lockAlpha) "Unlock alpha" else "Lock alpha", "preserve transparency") { e.setLayer(l.id,"Lock Alpha") { it.copy(lockAlpha = !it.lockAlpha) } }
        cmd("Layer", if (l.lockPosition) "Unlock layer position" else "Lock layer position") { e.setLayerFlags(l.id,lockPosition = !l.lockPosition) }
        cmd("Layer", if (l.linked) "Unlink layer" else "Link layer", "move transform layers together") { e.setLayerFlags(l.id,linked = !l.linked) }
        if (l.isGroup) cmd("Layer", if (l.expanded) "Collapse layer group" else "Expand layer group", l.name) { e.toggleExpanded(l.id) }
        if (l.isText) cmd("Layer", "Edit text…", l.name) { vm.dialog = "text-edit:${l.id}" }
        cmd("Layer", "Edit layer pixels", "stop editing mask") { e.setEditMask(l.id,false) }
        if (l.hasMask) {
            cmd("Layer", if (l.maskEnabled) "Disable layer mask" else "Enable layer mask") { e.setLayer(l.id,"Toggle Mask") { it.copy(maskEnabled = !it.maskEnabled) } }
            cmd("Layer", "Mask to selection", "replace selection using layer mask") { e.maskToSelection() }
        } else {
            for ((label,init) in listOf("black" to Engine.MaskInit.BLACK,"alpha" to Engine.MaskInit.ALPHA,"grayscale" to Engine.MaskInit.GRAYSCALE))
                cmd("Layer", "Add layer mask ($label)", "initialize mask") { e.addMask(init) }
        }
        for ((i,fx) in l.effects.withIndex()) {
            cmd("Layer", "${if (fx.visible) "Hide" else "Show"} effect “${fx.name}”", "layer filter visibility") {
                e.setEffects(l.id,"Toggle Effect") { list -> list.mapIndexed { k,x -> if (k == i) x.copy(visible = !x.visible) else x } }
            }
            cmd("Layer", "Remove effect “${fx.name}”", "delete layer filter") { e.setEffects(l.id,"Remove Effect") { list -> list.filterIndexed { k,_ -> k != i } } }
        }
        if (l.effects.isNotEmpty()) cmd("Layer", "Merge layer effects", "rasterize bake filters into pixels") { e.mergeEffects(l.id) }
    }
    for ((id,name,visible) in info.channels) {
        cmd("Select", "Load channel “$name”", "saved selection replace add subtract intersect using current selection mode") { e.channelToSelection(id,vm.selectMode) }
        cmd("Select", "${if (visible) "Hide" else "Show"} channel “$name”", "saved selection overlay") { e.updateChannel(id,"Channel Visibility") { it.copy(visible = !it.visible) } }
        cmd("Select", "Delete channel “$name”", "remove saved selection") { e.updateChannel(id,"Delete Channel") { null } }
    }
    cmd("Select", "Saved channels…", "stored selection masks") { layers() }
    for (mode in SelectMode.entries) cmd("Select", "Selection mode: ${mode.name.lowercase()}", "combine replace add subtract intersect") { vm.selectMode = mode }

    cmd("Paths", "Path to selection", "convert bezier curve into a selection") { vm.pathToSelection() }
    cmd("Paths", "Stroke path", "paint outline using ${vm.lastPaintTool.label}") { vm.strokePath() }
    cmd("Paths", if (vm.path.closed) "Open path" else "Close path", "bezier curve") { vm.path.closed = !vm.path.closed; vm.pathVersion++; redraw() }
    cmd("Paths", "Clear path", "remove bezier nodes") { vm.path.clear(); vm.pathVersion++; redraw() }
    for ((label,hv) in listOf("left" to (0 to -1),"center" to (1 to -1),"right" to (2 to -1),"top" to (-1 to 0),"middle" to (-1 to 1),"bottom" to (-1 to 2)))
        cmd("Layer", "Align layer $label", "position relative to canvas") { e.alignLayer(hv.first,hv.second) }

    cmd("Resources", "Brushes…", "brush tip shape library generated image brush") { options(vm.tool.takeIf { it.paint != null } ?: Tool.BRUSH) }
    cmd("Resources", "Create brush from selection", "new brush from active layer pixels") { vm.selectionToResource(brush = true) }
    cmd("Resources", "Save generated brush", "save current brush shape as gbr") { vm.saveGeneratedBrush() }
    cmd("Resources", "Gradients…", "gradient presets stops colors") { options(Tool.GRADIENT) }
    cmd("Resources", "Edit gradient…", "color stops gradient editor") { vm.dialog = "gradient-edit" }
    cmd("Resources", "Reverse gradient", "swap gradient direction") { vm.gradientReverse = !vm.gradientReverse }
    cmd("Resources", "Patterns…", "pattern library tile texture") { options(Tool.BUCKET); vm.fillWithPattern = true }
    cmd("Resources", "Edit pattern…", "create rename scale offset seamless delete pattern") { vm.dialog = "pattern-edit" }
    cmd("Resources", "Create pattern from selection", "new pattern from active layer pixels") { vm.selectionToResource(brush = false) }
    cmd("Tools", "Fill with pattern", "bucket texture tiled fill selection") {
        vm.selectTool(Tool.BUCKET); vm.fillWithPattern = true
        if (vm.pattern == null) vm.pattern = vm.resources.patterns.value.firstOrNull()
        vm.sheet = Sheet.OPTIONS
    }
    cmd("Tools", "Fill with color", "bucket solid foreground") { vm.selectTool(Tool.BUCKET); vm.fillWithPattern = false }
    cmd("Resources", "Palettes…", "color swatches palette editor") { vm.sheet = Sheet.COLOR }
    cmd("Resources", "New palette", "create color swatches") {
        vm.palette = vm.resources.savePalette(PaletteRes("Palette ${vm.resources.palettes.value.size + 1}",listOf(vm.fg)))
        vm.sheet = Sheet.COLOR
    }
    cmd("Resources", "Use default palette", "color swatches") { vm.palette = null; vm.sheet = Sheet.COLOR }
    vm.palette?.let { palette -> cmd("Resources", "Add foreground color to palette", palette.name) {
        vm.palette = vm.resources.savePalette(PaletteRes(palette.name,palette.colors + vm.fg),palette)
    } }

    cmd("Tools", "Paint straight lines", "line mode brush clone heal pencil") { vm.lineMode = true }
    cmd("Tools", "Paint freehand", "disable line mode") { vm.lineMode = false }
    cmd("Tools", if (vm.pickMode) "Stop picking colors while painting" else "Pick colors while painting", "eyedropper paint tool") { vm.pickMode = !vm.pickMode }
    cmd("Tools", if (vm.eraseToggle) "Stop erasing with brush" else "Erase with brush", "erase toggle current paint tool") { vm.eraseToggle = !vm.eraseToggle }
    cmd("Tools", "Set clone or healing source", "sample source for clone heal perspective clone") {
        if (vm.tool !in listOf(Tool.CLONE,Tool.HEAL,Tool.PERSPECTIVE_CLONE)) vm.selectTool(Tool.CLONE)
        vm.settingSource = true
    }
    cmd("Tools", "Dodge", "lighten brush") { vm.selectTool(Tool.DODGE_BURN); vm.altEffect = false }
    cmd("Tools", "Burn", "darken brush") { vm.selectTool(Tool.DODGE_BURN); vm.altEffect = true }
    cmd("Tools", "Blur brush", "soften brush") { vm.selectTool(Tool.BLUR_SHARPEN); vm.altEffect = false }
    cmd("Tools", "Sharpen brush", "detail brush") { vm.selectTool(Tool.BLUR_SHARPEN); vm.altEffect = true }
    cmd("Tools", "Transform image", "target whole image") { vm.changeTransformTarget(true) }
    cmd("Tools", "Transform layer", "target active layer") { vm.changeTransformTarget(false) }
    cmd("Tools", if (vm.snapRotation) "Disable rotation snapping" else "Snap rotation to 15°") { vm.snapRotation = !vm.snapRotation }
    cmd("Tools", if (vm.keepRatio) "Unlock transform aspect ratio" else "Keep transform aspect ratio") { vm.keepRatio = !vm.keepRatio }
    cmd("Tools", "Crop layer", "crop active layer boundary") { vm.selectTool(Tool.CROP); vm.cropLayer = true }
    cmd("Tools", "Crop image", "crop whole canvas") { vm.selectTool(Tool.CROP); vm.cropLayer = false }
    cmd("Tools", "Symmetry off", "disable mirrored painting") { vm.symmetryKind = null }
    for (kind in Symmetry.Kind.entries) cmd("Tools", "Symmetry: ${kind.label}", "mirrored painting mandala") { vm.symmetryKind = kind }
    cmd("Tools", if (vm.tool in vm.favorites) "Remove current tool from favorites" else "Add current tool to favorites", "toolbar shortcut") { vm.toggleFavorite(vm.tool) }
    if (vm.tool in vm.favorites) {
        cmd("Tools", "Move favorite tool left", "toolbar order") { vm.moveFavorite(vm.tool,-1) }
        cmd("Tools", "Move favorite tool right", "toolbar order") { vm.moveFavorite(vm.tool,1) }
    }
    if (vm.tool == Tool.SCISSORS) {
        cmd("Select", "Close scissors and select") { vm.scissorsClose() }
        cmd("Select", "Undo scissors point") { vm.scissorsUndo(); redraw() }
        cmd("Select", "Clear scissors points") { vm.scissorsClear(); redraw() }
    }
    if (vm.tool.picksObjects) {
        cmd("Select", "Object taps include", "add foreground points") { vm.objSubtract = false }
        cmd("Select", "Object taps exclude", "subtract background points") { vm.objSubtract = true }
        cmd("Select", "New object", "reset object selection points") { vm.objectNew(); redraw() }
    }
}

/** Named resources remain searchable, including resources imported while the menu is open. */
fun resourceCommands(vm: EditorViewModel, brushes: List<ImageBrush>, gradients: List<GradientRes>, patterns: List<PatternRes>, palettes: List<PaletteRes>, mypaint: List<MyPaintRes>): List<Cmd> = buildList {
    for (brush in brushes) add(Cmd("Resources","Use brush “${brush.name}”",run = {
        if (vm.tool.paint == null) vm.selectTool(Tool.BRUSH)
        vm.settings = vm.settings.copy(tipImage = brush.name,spacing = brush.spacing.coerceIn(0.02f,1f))
    }))
    for (gradient in gradients) add(Cmd("Resources","Use gradient “${gradient.name}”",run = { vm.gradient = gradient; vm.selectTool(Tool.GRADIENT) }))
    for (pattern in patterns) add(Cmd("Resources","Use pattern “${pattern.name}”",run = { vm.pattern = pattern; vm.fillWithPattern = true; vm.selectTool(Tool.BUCKET) }))
    for (palette in palettes) add(Cmd("Resources","Use palette “${palette.name}”",run = { vm.palette = palette; vm.sheet = Sheet.COLOR }))
    for (brush in mypaint) add(Cmd("Resources","Use MyPaint brush “${brush.name}”",run = { vm.selectTool(Tool.MYPAINT); vm.myPaintBrush = brush.name; vm.settings = brush.settings }))
}
