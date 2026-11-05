package io.github.nielstron.brushkin.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.nielstron.brushkin.engine.DocInfo
import io.github.nielstron.brushkin.export.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SaveMenuDialog(close: () -> Unit, open: (String) -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Ui.panel, title = { Text("Save") },
        text = { Column {
            for ((label, action, icon) in listOf(
                Triple("Save as…", "save-project", Icons.Rounded.SaveAs),
                Triple("Export image…", "export-image", Icons.Rounded.Image),
                Triple("Export as sticker…", "export-sticker", Icons.Rounded.StickyNote2),
            )) {
                Row(Modifier.fillMaxWidth().clickable { open(action) }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(icon, null, tint = Ui.text)
                    Text(label, color = Ui.text)
                }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, text: (T) -> String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Ui.muted, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text(value), modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { select(option); expanded = false }) }
            }
        }
    }
}

@Composable
fun SaveProjectDialog(close: () -> Unit, save: (Boolean) -> Unit) {
    var xcf by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, containerColor = Ui.panel, title = { Text("Save as") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("Project format", xcf, listOf(false, true), { if (it) "GIMP (.xcf)" else "OpenRaster (.ora)" }) { xcf = it }
            Text(if (xcf) "Save a layered project for GIMP." else "Keep layers and editing data for reopening in Brushkin.", color = Ui.muted)
        } }, confirmButton = { TextButton(onClick = { save(xcf) }) { Text("Choose location…") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun ImageExportDialog(info: DocInfo, close: () -> Unit, save: (ImageExportOptions) -> Unit) {
    var format by rememberSaveable { mutableStateOf(ImageFormat.PNG) }
    var quality by rememberSaveable { mutableIntStateOf(92) }
    var lossless by rememberSaveable { mutableStateOf(true) }
    val options = ImageExportOptions(format, quality, lossless)
    AlertDialog(onDismissRequest = close, containerColor = Ui.panel, title = { Text("Export image") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("Format", format, ImageFormat.entries, { it.label }) { format = it }
            if (format == ImageFormat.WEBP) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lossless", modifier = Modifier.weight(1f)); Switch(lossless, { lossless = it })
            }
            if (format == ImageFormat.JPEG || (format == ImageFormat.WEBP && !lossless)) {
                Text("Quality: $quality%")
                Slider(quality.toFloat(), { quality = it.roundToInt() }, valueRange = 1f..100f)
            }
        } }, confirmButton = { TextButton(onClick = { save(options) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun StickerExportDialog(info: DocInfo, files: FileActions, close: () -> Unit) {
    var telegram by rememberSaveable { mutableStateOf(false) }
    var emoji by rememberSaveable { mutableStateOf("🎨") }
    var packs by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("My Brushkin stickers") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pack = packs.firstOrNull { it.id == selected }
    fun work(action: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Sticker export failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { packs = files.stickerPacks(); selected = packs.lastOrNull()?.id }
        catch (e: Exception) { message = e.message }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Ui.panel, title = { Text("Export as sticker") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("App", telegram, listOf(false, true), { if (it) "Telegram" else "WhatsApp" }) { telegram = it }
            Text(if (info.hasSelection) "Exports your selection as a transparent cutout." else "Exports the visible image. Select an object first to cut away its background.", color = Ui.muted)
            Choice("Emoji", emoji, listOf("🎨", "😀", "❤️", "👍", "😂", "🥰", "😎", "🎉", "😢", "😡", "🐱", "🌟"), { it }) { emoji = it }
            if (telegram) {
                Text("Telegram will ask you to name and confirm a new sticker pack.", color = Ui.muted)
            } else {
                Choice("Sticker pack", selected, packs.map { it.id } + listOf(null), { id -> packs.firstOrNull { it.id == id }?.name ?: "New pack…" }) { selected = it }
                if (selected == null) OutlinedTextField(name, { name = it.take(128) }, label = { Text("Pack name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${pack?.stickers?.size ?: 0} / 30 stickers · WhatsApp needs at least 3 different stickers.", color = Ui.muted)
                Button(onClick = { work {
                    val before = pack?.stickers?.size ?: 0
                    val updated = files.addSticker(selected, pack?.name ?: name, emoji)
                    packs = files.stickerPacks(); selected = updated.id
                    message = if (updated.stickers.size == before) "This image is already in the pack." else "Sticker added."
                } }, enabled = !busy && (pack?.stickers?.size ?: 0) < 30 && (pack != null || name.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text("Add current image to pack") }
                if (pack != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pack.stickers.forEachIndexed { index, sticker ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            var bitmap by remember(sticker.file) { mutableStateOf<android.graphics.Bitmap?>(null) }
                            LaunchedEffect(sticker.file) { bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(StickerStore(context).asset(pack.id, sticker.file).absolutePath) } }
                            bitmap?.let { Image(it.asImageBitmap(), "Sticker ${index + 1}", Modifier.size(60.dp)) }
                            IconButton(onClick = { work { files.removeSticker(pack.id, sticker.file); packs = files.stickerPacks() } }, enabled = !busy) { Icon(Icons.Rounded.Close, "Remove sticker ${index + 1}") }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = Ui.text) }
        } }, confirmButton = {
            TextButton(onClick = { if (telegram) { close(); files.exportTelegram(emoji) } else { close(); files.importWhatsApp(requireNotNull(selected)) } },
                enabled = !busy && (telegram || (pack?.stickers?.size ?: 0) >= 3)) { Text(if (telegram) "Open Telegram" else "Add to WhatsApp") }
        }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Close") } })
}
