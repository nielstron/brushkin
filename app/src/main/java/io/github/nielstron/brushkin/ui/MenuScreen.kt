package io.github.nielstron.brushkin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nielstron.brushkin.engine.DesaturateFilter
import io.github.nielstron.brushkin.engine.DocInfo
import io.github.nielstron.brushkin.engine.LutFilter

data class Cmd(val section: String, val label: String, val hint: String = "", val run: () -> Unit)

/** Plain-language descriptions so search finds commands by what people want to do. */
val KEYWORDS = mapOf(
    "Curves…" to "brighten darken adjust contrast tones shadows highlights exposure",
    "Levels…" to "adjust brightness contrast black point white point exposure",
    "Brightness-Contrast…" to "make lighter darker brighter dimmer more contrast",
    "Hue-Saturation…" to "change colors make colors more vivid or muted saturation",
    "Color Balance…" to "warmer cooler white balance remove color cast tint",
    "Threshold…" to "pure black and white two tone",
    "Posterize…" to "reduce number of colors flat cartoon look",
    "Desaturate" to "make black and white grayscale monochrome remove color",
    "Invert" to "negative opposite colors",
    "Gaussian Blur…" to "blur soften smooth out of focus",
    "Motion Blur…" to "speed movement streak blur",
    "Noise Reduction…" to "denoise smooth grain clean up photo",
    "Drop Shadow…" to "shadow behind depth",
    "Vignette…" to "darken edges corners frame",
    "Ripple…" to "wave distort water",
    "Whirl and Pinch…" to "twist swirl distort",
    "Lens Distortion…" to "barrel pincushion fisheye distort",
    "Exposure…" to "brighter darker light stops photo",
    "Shadows-Highlights…" to "recover shadows brighten dark areas tone down highlights",
    "Colorize…" to "tint one color sepia monochrome",
    "Channel Mixer…" to "mix color channels black and white conversion",
    "Color to Alpha…" to "remove white background make transparent",
    "Auto: White Balance" to "fix color cast automatically correct",
    "Auto: Stretch Contrast" to "improve contrast automatically levels",
    "Unsharp Mask…" to "sharpen crisp details enhance",
    "Pixelize…" to "pixelate mosaic censor hide face",
    "RGB Noise…" to "add grain film noise texture",
    "Select foreground (AI)" to "remove background cut out person subject object automatically",
    "Crop to content" to "autocrop image trim transparent solid background borders",
    "Fit canvas to layers" to "crop image to layers resize canvas reveal off canvas layers",
    "Crop to selection" to "trim cut image to the selected area",
    "Scale image…" to "resize make smaller bigger change resolution",
    "Upscale ×2 (AI)" to "super resolution enhance enlarge increase resolution sharpen small photo",
    "Upscale ×4 (AI)" to "super resolution enhance enlarge increase resolution sharpen small photo",
    "Denoise (AI)" to "remove noise grain clean up night photo noise reduction",
    "Deblur (AI)" to "sharpen blurry shaky photo fix motion blur camera shake",
    "Depth of field (AI)…" to "bokeh portrait mode blur background focus lens",
    "Remove selected object (AI)" to "erase delete person thing from photo content aware fill inpaint heal magic eraser",
    "Canvas size…" to "extend add padding border more space",
    "Rotate 90° clockwise" to "turn image right",
    "Rotate 90° counter-clockwise" to "turn image left",
    "Flip horizontally" to "mirror image left right",
    "Flip vertically" to "upside down mirror",
    "Export image…" to "save picture to gallery photos png jpeg jpg webp gif animation quality",
    "Export as sticker…" to "whatsapp telegram sticker pack",
    "Share image…" to "send picture to someone",
    "Save project (.ora)…" to "save work with layers to continue later",
    "Open image…" to "load photo from gallery",
    "Open project (.ora)…" to "open xcf gimp project layers file",
    "Save as…" to "export gimp xcf ora openraster project desktop layers",
    "New layer group" to "folder organize layers",
    "Put layer in new group" to "folder organize layers",
    "Open project (.ora / .xcf)…" to "open xcf gimp ora project layers file",
    "New image…" to "new blank canvas start drawing",
    "New layer" to "add empty layer",
    "Merge down" to "combine layer with the one below",
    "Flatten image" to "merge all layers into one",
    "Clear" to "delete erase selected pixels",
    "Fill with FG color" to "paint selection with color",
    "Invert" to "select everything else opposite",
    "Feather…" to "soften selection edges",
    "Save to channel" to "store remember selection mask alpha channel",
    "Layer boundary size…" to "resize layer extent dimensions smaller clip area",
    "Crop layer to content" to "trim empty transparent border autocrop layer",
    "Layer to image size" to "fit layer canvas size",
    "Import brushes, gradients, palettes…" to "gimp resources patterns mypaint add load",
    "Show grid" to "lines squares alignment overlay",
    "Show rulers" to "measure pixels guides",
    "Snap to grid" to "align magnet",
    "Snap to guides" to "align magnet",
    "New guide (centered)" to "middle center line alignment",
    "Grid spacing…" to "grid size cells",
    "Border…" to "outline ring band around selection edge",
    "Toggle quick mask" to "paint the selection with brushes red overlay",
    "Undo" to "go back revert mistake",
    "Undo history…" to "go back several steps",
    "Move layer" to "drag position shift layer",
    "Scale layer" to "resize bigger smaller layer",
    "Rotate layer" to "turn angle tilt layer",
    "Move" to "drag position shift layer",
    "Scale" to "resize bigger smaller",
    "Rotate" to "turn angle tilt straighten",
    "Flip" to "mirror horizontal vertical",
    "Add layer mask (white)" to "hide parts non destructively",
    "Smudge" to "smear blend colors with finger",
    "Clone" to "copy area stamp duplicate",
    "Heal" to "remove blemish spot fix skin retouch",
    "Dodge / Burn" to "lighten or darken parts",
    "Blur / Sharpen" to "soften or sharpen parts with a brush",
    "Bucket Fill" to "fill area with color paint bucket",
    "Gradient" to "color fade blend",
    "Text" to "type words caption title",
    "Color Picker" to "eyedropper sample color",
    "Paths" to "bezier pen curve vector precise shape outline",
    "Fuzzy" to "magic wand select similar area",
    "Object" to "AI select person subject thing by tapping",
    "Crop" to "trim cut edges",
    "Eraser" to "remove delete paint",
    "Paintbrush" to "draw paint brush",
)

fun commands(vm: EditorViewModel, info: DocInfo, files: FileActions, view: () -> CanvasView?): List<Cmd> {
    val e = vm.engine
    fun dlg(d: String) = { vm.dialog = d }
    return listOf(
        Cmd("File", "New image…", run = dlg("new")),
        Cmd("File", "Open image…", run = { files.openImage() }),
        Cmd("File", "Open project (.ora / .xcf)…", run = { files.openProject() }),
        Cmd("File", "Rename image…", run = dlg("rename-tab:${vm.tabs.active.id}")),
        Cmd("File", "Save as…", "project with layers", run = dlg("save-project")),
        Cmd("File", "Import brushes, gradients, palettes…", ".gbr .gih .ggr .gpl .pat .myb", run = { files.importResources() }),
        Cmd("File", "Export image…", "PNG, JPEG, WebP or GIF", run = dlg("export-image")),
        Cmd("File", "Export as sticker…", "WhatsApp or Telegram", run = dlg("export-sticker")),
        Cmd("File", "Share image…", run = { files.share() }),

        Cmd("Edit", "Undo", run = { e.undo() }),
        Cmd("Edit", "Redo", run = { e.redo() }),
        Cmd("Edit", "Undo history…", run = { vm.sheet = Sheet.HISTORY }),
        Cmd("Edit", "Cut", "Ctrl+X", run = { vm.copy(cut = true) }),
        Cmd("Edit", "Copy", "Ctrl+C", run = { vm.copy() }),
        Cmd("Edit", "Copy visible", run = { vm.copy(merged = true) }),
        Cmd("Edit", "Paste as new layer", "Ctrl+V", run = { vm.paste() }),
        Cmd("Edit", "Clear", "erase selection", run = { e.clearSelection() }),
        Cmd("Edit", "Fill with FG color", run = { e.fillSelection(vm.fg, "Fill with FG Color") }),
        Cmd("Edit", "Fill with BG color", run = { e.fillSelection(vm.bg, "Fill with BG Color") }),
        Cmd("Edit", "Remove selected object (AI)", "fills it in from the surroundings", run = { vm.removeObject() }),

        Cmd("Select", "All", run = { e.selectAll() }),
        Cmd("Select", "None", run = { e.selectNone() }),
        Cmd("Select", "Invert", run = { e.selectInvert() }),
        Cmd("Select", "Feather…", run = dlg("feather")),
        Cmd("Select", "Grow…", run = dlg("grow")),
        Cmd("Select", "Shrink…", run = dlg("shrink")),
        Cmd("Select", "Border…", run = dlg("border")),
        Cmd("Select", "Select foreground (AI)", "on-device", run = { vm.selectForeground() }),
        Cmd("Select", "Save to channel", "keep this selection for later", run = { e.saveSelectionToChannel() }),
        Cmd("Select", "Toggle quick mask", "Shift+Q", run = { e.quickMaskToggle() }),
        Cmd("Select", "Alpha to selection", run = { e.alphaToSelection() }),
        Cmd("Select", "Crop to selection", run = { e.cropToSelection() }),


        Cmd("View", "Fit image in window", run = { view()?.fit() }),
        Cmd("View", "Zoom 100%", run = { view()?.zoomTo(1f) }),
        Cmd("View", "Zoom 200%", run = { view()?.zoomTo(2f) }),
        Cmd("View", "Reset rotation", run = { view()?.resetRotation() }),
        Cmd("View", "Flip view horizontally", "doesn't change the image", run = { view()?.flipView() }),
        Cmd("View", if (vm.showGrid) "Hide grid" else "Show grid", "${vm.gridSize.toInt()} px", run = { vm.showGrid = !vm.showGrid; view()?.invalidate() }),
        Cmd("View", "Grid spacing…", run = dlg("grid")),
        Cmd("View", if (vm.showRulers) "Hide rulers" else "Show rulers", "drag from a ruler to add a guide", run = { vm.showRulers = !vm.showRulers; view()?.invalidate() }),
        Cmd("View", if (vm.snapGrid) "Don't snap to grid" else "Snap to grid", run = { vm.snapGrid = !vm.snapGrid }),
        Cmd("View", if (vm.snapGuides) "Don't snap to guides" else "Snap to guides", run = { vm.snapGuides = !vm.snapGuides }),
        Cmd("Image", "New guide (centered)", "horizontal and vertical", run = { e.addGuide(io.github.nielstron.brushkin.engine.Guide(true, info.width / 2)); e.addGuide(io.github.nielstron.brushkin.engine.Guide(false, info.height / 2)) }),
        Cmd("Image", "Remove all guides", run = { e.clearGuides() }),

        Cmd("Image", "Flip horizontally", run = { e.flipImage(true) }),
        Cmd("Image", "Flip vertically", run = { e.flipImage(false) }),
        Cmd("Image", "Rotate 90° clockwise", run = { e.rotateImage(90) }),
        Cmd("Image", "Rotate 90° counter-clockwise", run = { e.rotateImage(270) }),
        Cmd("Image", "Rotate 180°", run = { e.rotateImage(180) }),
        Cmd("Image", "Scale image…", run = dlg("scale")),
        Cmd("Image", "Upscale ×2 (AI)", "enlarge with detail, on-device", run = { vm.upscale(2) }),
        Cmd("Image", "Upscale ×4 (AI)", "enlarge with detail, on-device", run = { vm.upscale(4) }),
        Cmd("Image", "Canvas size…", run = dlg("canvas")),
        Cmd("Image", "Crop to selection", run = { e.cropToSelection() }),
        Cmd("Image", "Crop to content", run = { e.cropToContent() }),
        Cmd("Image", "Fit canvas to layers", run = { e.fitCanvasToLayers() }),
        Cmd("Image", "Flatten image", run = { e.flatten() }),

        Cmd("Layer", "New layer", run = { e.addLayer() }),
        Cmd("Layer", "New layer group", run = { e.addGroup() }),
        Cmd("Layer", "Layer boundary size…", "make the layer smaller than the image", run = dlg("layer-bounds")),
        Cmd("Layer", "Layer to image size", run = { e.layerToImageSize() }),
        Cmd("Layer", "Crop layer to content", run = { e.cropLayerToContent() }),
        Cmd("Layer", "Put layer in new group", run = { e.groupActive() }),
        Cmd("Layer", "Move layer into group above", run = { e.moveIntoGroupAbove(info.activeId) }),
        Cmd("Layer", "Move layer out of group", run = { e.moveOutOfGroup(info.activeId) }),
        Cmd("Layer", "Duplicate layer", run = { e.duplicateLayer() }),
        Cmd("Layer", "Delete layer", run = { e.deleteLayer(info.activeId) }),
        Cmd("Layer", "Merge down", run = { e.mergeDown(info.activeId) }),
        Cmd("Layer", "Raise layer", run = { e.moveLayer(info.activeId, 1) }),
        Cmd("Layer", "Lower layer", run = { e.moveLayer(info.activeId, -1) }),
        Cmd("Layer", "Flip layer horizontally", run = { e.flipLayer(true) }),
        Cmd("Layer", "Flip layer vertically", run = { e.flipLayer(false) }),
        Cmd("Layer", "Add layer mask (white)", run = { e.addMask(io.github.nielstron.brushkin.engine.Engine.MaskInit.WHITE) }),
        Cmd("Layer", "Add layer mask from selection", run = { e.addMask(io.github.nielstron.brushkin.engine.Engine.MaskInit.SELECTION) }),
        Cmd("Layer", "Apply layer mask", run = { e.applyMask() }),
        Cmd("Layer", "Delete layer mask", run = { e.deleteMask() }),
        Cmd("Layer", "Edit layer mask", run = { e.setEditMask(info.activeId, true) }),
        Cmd("Layer", "Move or rotate layer", run = { vm.selectTool(Tool.MOVE) }),
        Cmd("Layer", "Scale layer", run = { vm.selectTool(Tool.SCALE) }),
        Cmd("Layer", "Layers…", run = { vm.sheet = Sheet.LAYERS }),

        Cmd("Colors", "Curves…", "live preview", run = dlg("filter:Curves")),
        Cmd("Colors", "Levels…", run = dlg("filter:Levels")),
        Cmd("Colors", "Brightness-Contrast…", run = dlg("filter:Brightness-Contrast")),
        Cmd("Colors", "Hue-Saturation…", run = dlg("filter:Hue-Saturation")),
        Cmd("Colors", "Color Balance…", run = dlg("filter:Color Balance")),
        Cmd("Colors", "Threshold…", run = dlg("filter:Threshold")),
        Cmd("Colors", "Posterize…", run = dlg("filter:Posterize")),
        Cmd("Colors", "Desaturate", run = { e.filterBegin(); e.filterApply(DesaturateFilter()) }),
        Cmd("Colors", "Exposure…", run = dlg("filter:Exposure")),
        Cmd("Colors", "Shadows-Highlights…", run = dlg("filter:Shadows-Highlights")),
        Cmd("Colors", "Colorize…", run = dlg("filter:Colorize")),
        Cmd("Colors", "Channel Mixer…", run = dlg("filter:Channel Mixer")),
        Cmd("Colors", "Color to Alpha…", "make a color transparent", run = dlg("filter:Color to Alpha")),
        Cmd("Colors", "Auto: White Balance", run = { e.filterBegin(); e.filterApply(io.github.nielstron.brushkin.engine.AutoLevelsFilter("White Balance", true, 0.005f)) }),
        Cmd("Colors", "Auto: Stretch Contrast", run = { e.filterBegin(); e.filterApply(io.github.nielstron.brushkin.engine.AutoLevelsFilter("Stretch Contrast", false, 0f)) }),
        Cmd("Colors", "Invert", run = { e.filterBegin(); e.filterApply(LutFilter.invert()) }),

        Cmd("Filters", "Denoise (AI)", "camera noise, on-device", run = { vm.denoise() }),
        Cmd("Filters", "Deblur (AI)", "camera shake, motion blur", run = { vm.deblur() }),
        Cmd("Filters", "Depth of field (AI)…", "blur the background", run = { vm.depthOfField() }),
        Cmd("Filters", "Gaussian Blur…", run = dlg("filter:Gaussian Blur")),
        Cmd("Filters", "Motion Blur…", run = dlg("filter:Motion Blur")),
        Cmd("Filters", "Noise Reduction…", run = dlg("filter:Noise Reduction")),
        Cmd("Filters", "Drop Shadow…", run = dlg("filter:Drop Shadow")),
        Cmd("Filters", "Vignette…", run = dlg("filter:Vignette")),
        Cmd("Filters", "Ripple…", run = dlg("filter:Ripple")),
        Cmd("Filters", "Whirl and Pinch…", run = dlg("filter:Whirl and Pinch")),
        Cmd("Filters", "Lens Distortion…", run = dlg("filter:Lens Distortion")),
        Cmd("Filters", "Unsharp Mask…", run = dlg("filter:Unsharp Mask")),
        Cmd("Filters", "Pixelize…", run = dlg("filter:Pixelize")),
        Cmd("Filters", "RGB Noise…", run = dlg("filter:RGB Noise")),
    ) + GEGL_ACTIONS.keys.map { name -> Cmd("Filters", "$name…", run = dlg("filter:$name")) } + panelCommands(vm, info, files, view) + Tool.entries.filter { !it.isAction }.map { t -> Cmd("Tools", t.label, t.group.label, run = { vm.selectTool(t) }) }
}

@Composable
fun MenuScreen(vm: EditorViewModel, info: DocInfo, files: FileActions, holder: CanvasHolder, storeCommands: List<Cmd> = emptyList()) {
    var query by remember { mutableStateOf("") }
    val open = remember { mutableStateListOf<String>() }
    val brushes by vm.resources.brushes.collectAsState()
    val gradients by vm.resources.gradients.collectAsState()
    val patterns by vm.resources.patterns.collectAsState()
    val palettes by vm.resources.palettes.collectAsState()
    val mypaint by vm.resources.mypaint.collectAsState()
    val all = commands(vm, info, files) { holder.view } + resourceCommands(vm, brushes, gradients, patterns, palettes, mypaint) + storeCommands
    val search = remember(all) { CommandSearch(all) }
    BackHandler { vm.showMenu = false }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    fun run(c: Cmd) { vm.recentCommands.record(c); vm.showMenu = false; c.run() }

    Column(Modifier.fillMaxSize().background(Ui.panel).safeDrawingPadding().imePadding().clickable(enabled = false) {}) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.showMenu = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to canvas", tint = Ui.text) }
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Ui.panel2).border(1.dp, Ui.accent, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Ui.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (query.isEmpty()) Text("Search every command and filter", color = Ui.muted, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Ui.text, fontSize = 15.sp), cursorBrush = SolidColor(Ui.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.trim().length == 1) {
                item { Text("Type more for results", color = Ui.muted, modifier = Modifier.padding(24.dp)) }
            } else if (query.isNotBlank()) {
                val hits = search.search(query)
                items(hits) { c -> CmdRow(c, showSection = true) { run(c) } }
                if (hits.isEmpty()) item { Text("No command matches “$query”", color = Ui.muted, modifier = Modifier.padding(24.dp)) }
            } else {
                val recent = vm.recentCommands.resolve(all)
                if (recent.isNotEmpty()) {
                    item(key = "recent-header") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.History, null, tint = Ui.muted, modifier = Modifier.size(18.dp))
                            Text("Recent operations", color = Ui.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    items(recent, key = { "recent/${it.section}/${it.label}" }) { c -> CmdRow(c, showSection = true) { run(c) } }
                    item(key = "recent-divider") { Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.border)) }
                }
                for (section in all.map { it.section }.distinct()) {
                    val expanded = section in open
                    item(key = "h$section") {
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable { if (expanded) open.remove(section) else open.add(section) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(section, color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Ui.muted)
                        }
                    }
                    if (expanded) items(all.filter { it.section == section }, key = { "${it.section}/${it.label}" }) { c -> CmdRow(c, false) { run(c) } }
                    item(key = "d$section") { Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.border)) }
                }
            }
        }
    }
}

@Composable
private fun CmdRow(c: Cmd, showSection: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(start = if (showSection) 16.dp else 28.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSection) { Text(c.section, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(64.dp)); Spacer(Modifier.width(4.dp)) }
        Text(c.label, color = Ui.text.copy(alpha = 0.9f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (c.hint.isNotEmpty()) Text(c.hint, color = Ui.muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp))
    }
}
