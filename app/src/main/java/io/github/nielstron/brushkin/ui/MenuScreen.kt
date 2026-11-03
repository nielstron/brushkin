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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

fun commands(vm: EditorViewModel, info: DocInfo, files: FileActions, view: () -> CanvasView?): List<Cmd> {
    val e = vm.engine
    fun dlg(d: String) = { vm.dialog = d }
    return listOf(
        Cmd("File", "New image…", run = dlg("new")),
        Cmd("File", "Open image…", run = { files.openImage() }),
        Cmd("File", "Open project (.ora)…", run = { files.openProject() }),
        Cmd("File", "Save project (.ora)…", "keeps layers", run = { files.saveProject() }),
        Cmd("File", "Export as PNG", "to Pictures/Brushkin", run = { files.export("png") }),
        Cmd("File", "Export as JPEG", run = { files.export("jpg") }),
        Cmd("File", "Export as WebP", run = { files.export("webp") }),
        Cmd("File", "Share image…", run = { files.share() }),

        Cmd("Edit", "Undo", run = { e.undo() }),
        Cmd("Edit", "Redo", run = { e.redo() }),
        Cmd("Edit", "Undo history…", run = { vm.sheet = Sheet.HISTORY }),
        Cmd("Edit", "Cut", run = { e.copy(cut = true) }),
        Cmd("Edit", "Copy", run = { e.copy() }),
        Cmd("Edit", "Copy visible", run = { e.copy(merged = true) }),
        Cmd("Edit", "Paste as new layer", run = { e.paste() }),
        Cmd("Edit", "Clear", "erase selection", run = { e.clearSelection() }),
        Cmd("Edit", "Fill with FG color", run = { e.fillSelection(vm.fg, "Fill with FG Color") }),
        Cmd("Edit", "Fill with BG color", run = { e.fillSelection(vm.bg, "Fill with BG Color") }),

        Cmd("Select", "All", run = { e.selectAll() }),
        Cmd("Select", "None", run = { e.selectNone() }),
        Cmd("Select", "Invert", run = { e.selectInvert() }),
        Cmd("Select", "Feather…", run = dlg("feather")),
        Cmd("Select", "Grow…", run = dlg("grow")),
        Cmd("Select", "Shrink…", run = dlg("shrink")),
        Cmd("Select", "Alpha to selection", run = { e.alphaToSelection() }),

        Cmd("View", "Fit image in window", run = { view()?.fit() }),
        Cmd("View", "Zoom 100%", run = { view()?.zoomTo(1f) }),
        Cmd("View", "Zoom 200%", run = { view()?.zoomTo(2f) }),
        Cmd("View", "Reset rotation", run = { view()?.resetRotation() }),
        Cmd("View", "Flip view horizontally", "doesn't change the image", run = { view()?.flipView() }),

        Cmd("Image", "Flip horizontally", run = { e.flipImage(true) }),
        Cmd("Image", "Flip vertically", run = { e.flipImage(false) }),
        Cmd("Image", "Rotate 90° clockwise", run = { e.rotateImage(90) }),
        Cmd("Image", "Rotate 90° counter-clockwise", run = { e.rotateImage(270) }),
        Cmd("Image", "Rotate 180°", run = { e.rotateImage(180) }),
        Cmd("Image", "Scale image…", run = dlg("scale")),
        Cmd("Image", "Canvas size…", run = dlg("canvas")),
        Cmd("Image", "Crop to selection", run = { e.cropToSelection() }),
        Cmd("Image", "Flatten image", run = { e.flatten() }),

        Cmd("Layer", "New layer", run = { e.addLayer() }),
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
        Cmd("Layer", "Transform layer", run = { vm.selectTool(Tool.TRANSFORM) }),
        Cmd("Layer", "Layers…", run = { vm.sheet = Sheet.LAYERS }),

        Cmd("Colors", "Curves…", "live preview", run = dlg("filter:Curves")),
        Cmd("Colors", "Levels…", run = dlg("filter:Levels")),
        Cmd("Colors", "Brightness-Contrast…", run = dlg("filter:Brightness-Contrast")),
        Cmd("Colors", "Hue-Saturation…", run = dlg("filter:Hue-Saturation")),
        Cmd("Colors", "Color Balance…", run = dlg("filter:Color Balance")),
        Cmd("Colors", "Threshold…", run = dlg("filter:Threshold")),
        Cmd("Colors", "Posterize…", run = dlg("filter:Posterize")),
        Cmd("Colors", "Desaturate", run = { e.filterBegin(); e.filterApply(DesaturateFilter()) }),
        Cmd("Colors", "Invert", run = { e.filterBegin(); e.filterApply(LutFilter.invert()) }),

        Cmd("Filters", "Gaussian Blur…", run = dlg("filter:Gaussian Blur")),
        Cmd("Filters", "Unsharp Mask…", run = dlg("filter:Unsharp Mask")),
        Cmd("Filters", "Pixelize…", run = dlg("filter:Pixelize")),
        Cmd("Filters", "RGB Noise…", run = dlg("filter:RGB Noise")),
    ) + Tool.entries.map { t -> Cmd("Tools", t.label, t.group.label, run = { vm.selectTool(t) }) }
}

@Composable
fun MenuScreen(vm: EditorViewModel, info: DocInfo, files: FileActions, holder: CanvasHolder) {
    var query by remember { mutableStateOf("") }
    val open = remember { mutableStateListOf<String>() }
    val all = remember(info, vm.fg, vm.bg) { commands(vm, info, files) { holder.view } }
    BackHandler { vm.showMenu = false }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    fun run(c: Cmd) { vm.showMenu = false; c.run() }

    Column(Modifier.fillMaxSize().background(Ui.panel).statusBarsPadding().navigationBarsPadding().imePadding().clickable(enabled = false) {}) {
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
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                // Rank label matches above section and hint matches; every query word must match somewhere.
                val words = q.split(' ').filter { it.isNotBlank() }
                fun score(c: Cmd): Int? {
                    val label = c.label.lowercase(); val full = "${c.section} ${c.label}".lowercase(); val hint = c.hint.lowercase()
                    if (words.any { it !in full && it !in hint }) return null
                    return when {
                        label.startsWith(q) -> 0
                        q in label -> 1
                        q in full -> 2
                        words.all { it in full } -> 3
                        else -> 4
                    }
                }
                val hits = all.mapNotNull { c -> score(c)?.let { c to it } }.sortedBy { it.second }.map { it.first }
                items(hits) { c -> CmdRow(c, showSection = true) { run(c) } }
                if (hits.isEmpty()) item { Text("No command matches “$query”", color = Ui.muted, modifier = Modifier.padding(24.dp)) }
            } else {
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
        if (c.hint.isNotEmpty()) Text(c.hint, color = Ui.muted, fontSize = 12.sp)
    }
}
