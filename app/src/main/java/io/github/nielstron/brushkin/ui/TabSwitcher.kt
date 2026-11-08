package io.github.nielstron.brushkin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val NEW_TAB_CARD = "new-tab"

/** Chrome's square with the number of open tabs: tap for all tabs, long-press for New and Close. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabCountButton(tabs: DocumentTabs, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val count = tabs.items.size
    Box {
        Box(
            modifier.size(48.dp).clip(CircleShape)
                .combinedClickable(onClick = { tabs.switcherOpen = true }, onLongClick = { menu = true })
                .semantics { contentDescription = (if (count == 1) "1 open image" else "$count open images") + ". Show all tabs." },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).border(2.dp, Ui.text, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text(if (count > 99) ":D" else "$count", color = Ui.text, fontSize = if (count > 9) 10.sp else 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = Ui.panel) {
            DropdownMenuItem(text = { Text("New tab", color = Ui.text) }, leadingIcon = { Icon(Icons.Rounded.Add, null, tint = Ui.text) }, onClick = { menu = false; tabs.newTab() })
            DropdownMenuItem(text = { Text("Close tab", color = Ui.text) }, leadingIcon = { Icon(Icons.Rounded.Close, null, tint = Ui.text) }, onClick = { menu = false; tabs.close(tabs.active) })
        }
    }
}

/** Chrome's gesture on the toolbar: swipe sideways for the next or previous tab. */
fun Modifier.swipeBetweenTabs(tabs: DocumentTabs): Modifier = pointerInput(tabs) {
    var dragged = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = { if (abs(dragged) > 48.dp.toPx()) tabs.selectRelative(if (dragged < 0) 1 else -1) },
    ) { change, amount -> change.consume(); dragged += amount }
}

/** Chrome's tablet tab strip, inside the top bar. Tapping the current tab renames it, like its name does on phones. */
@Composable
fun TabStrip(tabs: DocumentTabs, modifier: Modifier = Modifier, onRename: (DocumentTab) -> Unit) {
    Row(modifier.fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tab in tabs.items) key(tab.id) {
            val current = tab === tabs.active
            Row(
                Modifier.width(168.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (current) Ui.panel2 else Color.Transparent)
                    .clickable { if (current) onRename(tab) else tabs.select(tab) }.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tabTitle(tab.name), color = if (current) Ui.text else Ui.muted, fontSize = 13.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = "Close ${tab.name}") { tabs.close(tab) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Ui.muted, modifier = Modifier.size(16.dp))
                }
            }
        }
        IconButton(onClick = { tabs.newTab() }) { Icon(Icons.Rounded.Add, "New tab", tint = Ui.text) }
    }
}

/** Chrome's grid of open tabs. Swipe a card away to close it; long-press and drag to reorder, or just long-press to rename. */
@Composable
fun TabSwitcher(tabs: DocumentTabs, onRename: (DocumentTab) -> Unit) {
    AnimatedVisibility(tabs.switcherOpen, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut() + scaleOut(targetScale = 0.94f)) {
        BackHandler { tabs.switcherOpen = false }
        LaunchedEffect(Unit) { tabs.refreshThumbnail() }
        Column(Modifier.fillMaxSize().background(Ui.ground).safeDrawingPadding().clickable(enabled = false) {}) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tabs.switcherOpen = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to canvas", tint = Ui.text) }
                val count = tabs.items.size
                Text(if (count == 1) "1 tab" else "$count tabs", color = Ui.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            }
            Box(Modifier.weight(1f)) {
                val grid = rememberLazyGridState()
                val scope = rememberCoroutineScope()
                var draggedId by remember { mutableStateOf<Long?>(null) }
                var dragOffset by remember { mutableStateOf(Offset.Zero) }
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(tabs.items, key = { _, t -> t.id }) { _, tab ->
                        val dragged = draggedId == tab.id
                        val swipe = remember { Animatable(0f) }
                        var cardWidth by remember { mutableStateOf(1) }
                        TabCard(
                            tab, tab === tabs.active,
                            onOpen = { tabs.select(tab); tabs.switcherOpen = false },
                            onClose = { tabs.close(tab) },
                            modifier = Modifier
                                .animateItem(placementSpec = if (dragged) null else spring(stiffness = 500f))
                                .zIndex(if (dragged) 1f else 0f)
                                .onSizeChanged { cardWidth = it.width }
                                .graphicsLayer {
                                    translationX = swipe.value + if (dragged) dragOffset.x else 0f
                                    translationY = if (dragged) dragOffset.y else 0f
                                    alpha = 1f - (abs(swipe.value) / cardWidth).coerceIn(0f, 0.8f)
                                    if (dragged) { scaleX = 1.04f; scaleY = 1.04f }
                                }
                                .pointerInput(tab.id) {
                                    detectHorizontalDragGestures(
                                        onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                                        onDragEnd = {
                                            scope.launch {
                                                if (abs(swipe.value) > cardWidth * 0.4f) {
                                                    swipe.animateTo(if (swipe.value < 0) -cardWidth * 1.2f else cardWidth * 1.2f)
                                                    tabs.close(tab).join()
                                                }
                                                // Still here if it couldn't be closed, or back again after undo.
                                                swipe.snapTo(0f)
                                            }
                                        },
                                    ) { change, amount -> change.consume(); scope.launch { swipe.snapTo(swipe.value + amount) } }
                                }
                                .pointerInput(tab.id) {
                                    var moved = false
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedId = tab.id; dragOffset = Offset.Zero; moved = false },
                                        onDragCancel = { draggedId = null; tabs.finishMoving() },
                                        onDragEnd = { draggedId = null; if (moved) tabs.finishMoving() else onRename(tab) },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        if (dragOffset.getDistance() > viewConfiguration.touchSlop) moved = true
                                        val visible = grid.layoutInfo.visibleItemsInfo
                                        val here = visible.firstOrNull { it.key == tab.id } ?: return@detectDragGesturesAfterLongPress
                                        val center = here.offset.toOffset() + Offset(here.size.width / 2f, here.size.height / 2f) + dragOffset
                                        val target = visible.firstOrNull {
                                            it.key != tab.id && it.key != NEW_TAB_CARD && center.x in it.offset.x.toFloat()..(it.offset.x + it.size.width).toFloat() &&
                                                center.y in it.offset.y.toFloat()..(it.offset.y + it.size.height).toFloat()
                                        } ?: return@detectDragGesturesAfterLongPress
                                        // The card moves to the other slot; keep it under the finger.
                                        dragOffset += (here.offset - target.offset).toOffset()
                                        tabs.move(here.index, target.index)
                                    }
                                },
                        )
                    }
                    item(key = NEW_TAB_CARD) {
                        NewTabCard(Modifier.animateItem(placementSpec = spring(stiffness = 500f))) { tabs.newTab(); tabs.switcherOpen = false }
                    }
                }
            }
        }
    }
}

/** The empty card after the last tab: tapping it opens a new one. */
@Composable
private fun NewTabCard(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Ui.panel).border(1.dp, Ui.border, shape).clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New tab", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(Modifier.padding(end = 8.dp).fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)).background(Ui.panel2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = Ui.muted, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun TabCard(tab: DocumentTab, current: Boolean, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(Ui.panel).border(if (current) 2.dp else 1.dp, if (current) Ui.accent else Ui.border, shape)
            .clickable(onClickLabel = "Open ${tab.name}", onClick = onOpen).padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tabTitle(tab.name), color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Close ${tab.name}", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Ui.muted, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.padding(end = 8.dp).fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)).background(Ui.panel2), contentAlignment = Alignment.Center) {
            tab.thumbnail?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** "Closed …  Undo", until it times out and the closed tabs are gone for good. */
@Composable
fun TabUndoBar(tabs: DocumentTabs, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    val closed = tabs.closingCount
    LaunchedEffect(closed) {
        if (closed == 0) return@LaunchedEffect
        val text = if (closed == 1) "Closed ${tabTitle(tabs.closingName)}" else "Closed $closed tabs"
        // Closing another tab restarts this with the new count.
        when (host.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Long)) {
            SnackbarResult.ActionPerformed -> tabs.undoClose()
            SnackbarResult.Dismissed -> tabs.finishClosing()
        }
    }
    SnackbarHost(host, modifier)
}
