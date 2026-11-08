package io.github.nielstron.brushkin.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.nielstron.brushkin.engine.DocInfo
import io.github.nielstron.brushkin.engine.LayerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val RowHeight = 60.dp
private val RowSpacing = 4.dp
private val RowShape = RoundedCornerShape(14.dp)

/** How far one level of nesting indents a row. */
private val Indent = 18.dp

/** How far sideways a drag has to go to change the nesting level. */
private val DepthDragStep = 28.dp

private class RowDrag(val id: Long, val index: Int, val order: List<LayerSlot>, val startScroll: Int) {
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    /** Dropped: holds the preview until the engine has published the new order. */
    var settling by mutableStateOf(false)
}

/**
 * The layers, top-most first. A row's handle drags at once and a long press on the row does the
 * same. The other rows open a gap at the drop position; every row has the same height, so all of
 * this is arithmetic on row positions and the engine only sees the final move.
 */
@Composable
fun LayerList(vm: EditorViewModel, info: DocInfo, modifier: Modifier = Modifier) {
    val e = vm.engine
    val rows = info.layers
    val slots = remember(rows) { rows.map { LayerSlot(it.id, it.depth, it.isGroup, it.expanded) } }
    val latestSlots by rememberUpdatedState(slots)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pitch = with(density) { (RowHeight + RowSpacing).toPx() }
    val depthStep = with(density) { DepthDragStep.toPx() }
    var viewport by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<RowDrag?>(null) }

    fun topOf(d: RowDrag): Float {
        val remaining = d.order.size - LayerDrag.blockSize(d.order, d.index)
        return (d.index + (d.dy + scroll.value - d.startScroll) / pitch).coerceIn(0f, remaining.toFloat())
    }
    fun dropOf(d: RowDrag) = LayerDrag.target(d.order, d.index, topOf(d), d.order[d.index].depth + (d.dx / depthStep).roundToInt())

    // An order other than the one the drag started from means the drop has landed.
    val current = drag?.takeIf { it.order == slots }
    LaunchedEffect(slots) { if (drag?.order != slots) drag = null }
    val drop by remember(current) { derivedStateOf { current?.let(::dropOf) } }
    val block = current?.let { LayerDrag.blockSize(it.order, it.index) } ?: 0

    LaunchedEffect(drop) { if (drop != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(current) {
        val d = current ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        while (!d.settling) {
            val now = withFrameNanos { it }
            val y = topOf(d) * pitch - scroll.value
            val over = if (y < 0) y else (y + pitch - viewport).coerceAtLeast(0f)
            if (over != 0f) scroll.scrollBy((over / pitch).coerceIn(-1f, 1f) * pitch * 8 * (now - last) / 1e9f)
            last = now
        }
    }

    val start = { id: Long ->
        val order = latestSlots
        val index = order.indexOfFirst { it.id == id }
        if (index >= 0) drag = RowDrag(id, index, order, scroll.value)
    }
    val move = { amount: Offset -> drag?.let { it.dx += amount.x; it.dy += amount.y }; Unit }
    val cancel = { drag = null }
    val finish = finish@{
        val d = drag ?: return@finish
        val target = dropOf(d)
        d.settling = true
        scope.launch {
            when (target) {
                is LayerDrop.Between -> e.moveLayerTo(d.id, target.parentId, target.belowId)
                is LayerDrop.Into -> e.moveLayerTo(d.id, target.groupId, null, onTop = true)
            }.join()
            // A drop that changes nothing publishes nothing.
            delay(120)
            if (drag === d) drag = null
        }
        Unit
    }

    Column(
        modifier.onSizeChanged { viewport = it.height }.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(RowSpacing),
    ) {
        rows.forEachIndexed { i, l ->
            key(l.id) {
                val d = current
                val dragged = d?.id == l.id
                val carried = d != null && i in d.index + 1 until d.index + block
                val target = drop
                val rest = if (d != null && i >= d.index + block) i - block else i
                val slot = rest + if (target is LayerDrop.Between && rest >= target.gap) 1 else 0
                val shift by animateFloatAsState(
                    if (d == null || dragged || carried) 0f else (slot - i) * pitch,
                    spring(stiffness = 700f), label = "layer row",
                )
                LayerRow(
                    vm, l, active = l.id == info.activeId,
                    depth = if (dragged && target is LayerDrop.Between) target.depth else l.depth,
                    lifted = dragged, dropTarget = target is LayerDrop.Into && target.groupId == l.id,
                    handle = Modifier.pointerInput(l.id) {
                        detectDragGestures(onDragStart = { start(l.id) }, onDragEnd = finish, onDragCancel = cancel) { change, amount ->
                            change.consume(); move(amount)
                        }
                    },
                    modifier = Modifier
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            alpha = if (carried) 0f else 1f
                            translationY = when {
                                d == null -> 0f
                                !dragged -> shift
                                d.settling && target is LayerDrop.Between -> (target.gap - i) * pitch
                                else -> (topOf(d) - i) * pitch
                            }
                            if (dragged) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 8.dp.toPx(); shape = RowShape }
                        }
                        .pointerInput(l.id) {
                            detectDragGesturesAfterLongPress(onDragStart = { start(l.id) }, onDragEnd = finish, onDragCancel = cancel) { change, amount ->
                                change.consume(); move(amount)
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, label: String) =
    Icon(icon, label, tint = Ui.muted, modifier = Modifier.padding(start = 4.dp).size(16.dp))

@Composable
private fun LayerRow(
    vm: EditorViewModel, l: LayerInfo, active: Boolean, depth: Int, lifted: Boolean, dropTarget: Boolean,
    handle: Modifier, modifier: Modifier,
) {
    val e = vm.engine
    val background = when {
        active -> Color(0xFF2A2822)
        lifted -> Ui.panel2
        else -> Color.Transparent
    }
    val border = when {
        dropTarget -> Ui.accent
        active -> Color(0xFF4A4232)
        else -> Color.Transparent
    }
    Row(
        modifier.fillMaxWidth().height(RowHeight).clip(RowShape).background(background).border(if (dropTarget) 2.dp else 1.dp, border, RowShape)
            .clickable { e.setActive(l.id) }.padding(start = Indent * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (l.isGroup) IconButton(onClick = { e.toggleExpanded(l.id) }, modifier = Modifier.size(32.dp, 48.dp)) {
            Icon(if (l.expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, if (l.expanded) "Collapse group" else "Expand group", tint = Ui.muted)
        }
        IconButton(onClick = { e.setLayer(l.id, if (l.visible) "Hide Layer" else "Show Layer") { it.copy(visible = !it.visible) } }) {
            Icon(if (l.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (l.visible) "Hide ${l.name}" else "Show ${l.name}", tint = if (l.visible) Ui.text else Ui.muted)
        }
        // Tap the layer or mask thumbnail to choose what tools edit (like GIMP).
        val editingPixels = active && !l.editMask
        Box(
            Modifier.size(52.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A40))
                .border(2.dp, if (l.hasMask && editingPixels) Ui.accent else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Edit layer pixels") { e.setEditMask(l.id, false) },
            contentAlignment = Alignment.Center,
        ) {
            l.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
        }
        if (l.hasMask) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(34.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                    .border(2.dp, if (active && l.editMask) Ui.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Edit layer mask") { e.setEditMask(l.id, true) },
                contentAlignment = Alignment.Center,
            ) {
                l.maskThumb?.let { Image(it.asImageBitmap(), "Layer mask", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height), alpha = if (l.maskEnabled) 1f else 0.35f) }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(l.name, color = if (l.visible) Ui.text else Ui.muted, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            val sub = buildList {
                if (l.isGroup) add("Group"); if (l.isText) add("Text"); add(l.mode.label); if (l.opacity < 1f) add("${(l.opacity * 100).roundToInt()}%")
                if (l.effects.isNotEmpty()) add("${l.effects.size} effect" + if (l.effects.size > 1) "s" else "")
                if (l.bounds != null) add("${l.bounds.width()}×${l.bounds.height()}")
                if (active && l.editMask) add("editing mask") else if (l.hasMask && !l.maskEnabled) add("mask off")
            }.joinToString(" · ")
            Text(sub, color = Ui.muted, fontSize = 12.sp, maxLines = 1)
        }
        if (l.linked) StatusIcon(Icons.Rounded.Link, "Linked")
        if (l.lockPosition) StatusIcon(Icons.Rounded.Lock, "Position locked")
        if (l.lockAlpha) StatusIcon(ToolIcons.alphaLock, "Alpha locked")
        Box(handle.size(44.dp, RowHeight), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DragHandle, "Drag to reorder ${l.name}", tint = Ui.muted)
        }
    }
}
