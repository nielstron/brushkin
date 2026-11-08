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
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
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
                Triple("Export as sticker…", "export-sticker", Icons.AutoMirrored.Rounded.StickyNote2),
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
private fun <T> Choice(label: String, value: T, options: List<T>, text: (T) -> String, enabled: Boolean = true, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Ui.muted, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
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
            if (xcf) Text("XCF doesn't preserve the project exactly. Use .ora to keep everything.", color = Ui.accent)
        } }, confirmButton = { TextButton(onClick = { save(xcf) }) { Text("Choose location…") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun ExportToggle(label: String, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { change(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, change, enabled = enabled)
        Text(label, color = if (enabled) Ui.text else Ui.muted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExportSlider(label: String, value: Int, range: IntRange, enabled: Boolean = true, change: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), color = if (enabled) Ui.text else Ui.muted)
            var entry by remember(value) { mutableStateOf(value.toString()) }
            OutlinedTextField(entry, { entry = it; it.toIntOrNull()?.takeIf { n -> n in range }?.let(change) },
                enabled = enabled, singleLine = true, modifier = Modifier.width(80.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }
        Slider(value.toFloat(), { change(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled)
    }
}

@Composable
fun ImageExportDialog(info: DocInfo, files: FileActions, close: () -> Unit, save: (ImageExportOptions) -> Unit) {
    var options by remember { mutableStateOf(ImageExportOptions(comment = info.exportMetadata.comment, resolution = info.exportMetadata.resolution, loopForever = info.exportMetadata.animationLoops?.let { it == 0 } ?: true, repeats = maxOf(1,info.exportMetadata.animationLoops ?: 1))) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("image-export", android.content.Context.MODE_PRIVATE) }
    var hasSavedSettings by remember { mutableStateOf(preferences.contains("saved")) }
    var previewEnabled by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var metadataOpen by rememberSaveable { mutableStateOf(false) }
    var editMetadata by rememberSaveable { mutableStateOf(false) }
    var previewBusy by remember { mutableStateOf(false) }
    LaunchedEffect(previewEnabled, options) {
        if (!previewEnabled) { preview?.recycle(); preview = null; return@LaunchedEffect }
        previewBusy = true; previewError = null
        kotlinx.coroutines.delay(200)
        try { val result = files.exportPreview(options); preview?.recycle(); preview = result }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { previewError = e.message }
        finally { previewBusy = false }
    }
    DisposableEffect(Unit) { onDispose { preview?.recycle() } }
    var advanced by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, containerColor = Ui.panel, title = { Text("Export image") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("Format", options.format, ImageFormat.entries, { it.label }) { options = options.copy(format = it) }
            when (options.format) {
                ImageFormat.PNG -> Unit
                ImageFormat.JPEG -> ExportSlider("Quality", options.quality, 0..100, !(options.useOriginalQuality && info.exportMetadata.jpegHeader != null)) { options = options.copy(quality = it) }
                ImageFormat.WEBP -> {
                    ExportToggle("Lossless", options.lossless) { options = options.copy(lossless = it) }
                    ExportSlider("Image quality", options.webpQuality, 0..100, !options.lossless) { options = options.copy(webpQuality = it) }
                    ExportToggle("Save animation", options.animation, info.layers.size > 1) { options = options.copy(animation = it) }
                }
                ImageFormat.GIF -> ExportToggle("As animation", options.animation, info.layers.size > 1) { options = options.copy(animation = it) }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text("Advanced settings", modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (advanced) {
            when (options.format) {
                ImageFormat.PNG -> {
                    Choice("Pixel format", options.pngFormat, PngPixelFormat.entries, { it.label }) { options = options.copy(pngFormat = it) }
                    ExportSlider("Compression level", options.compression, 0..9) { options = options.copy(compression = it) }
                    ExportToggle("Interlacing (Adam7)", options.interlace) { options = options.copy(interlace = it) }
                    ExportToggle("Save color values from transparent pixels", false, false) {}
                    ExportToggle("Save background color", options.saveBackground) { options = options.copy(saveBackground = it) }
                    ExportToggle("Save layer offset", options.saveOffset) { options = options.copy(saveOffset = it) }
                    ExportToggle("Save resolution", options.saveResolution) { options = options.copy(saveResolution = it) }
                    ExportToggle("Save creation time", options.saveCreationTime) { options = options.copy(saveCreationTime = it) }
                    ExportToggle("Save comment", options.saveComment) { options = options.copy(saveComment = it) }

                }
                ImageFormat.JPEG -> {
                    ExportToggle("Use quality settings from original image", options.useOriginalQuality && info.exportMetadata.jpegHeader != null, info.exportMetadata.jpegHeader != null) { options = options.copy(useOriginalQuality = it) }
                    ExportToggle("Show preview", previewEnabled) { previewEnabled = it }

                        ExportSlider("Smoothing", options.smoothing, 0..100) { options = options.copy(smoothing = it) }
                        ExportToggle("Export as CMYK", options.cmyk) { options = options.copy(cmyk = it) }
                        ExportToggle("Progressive", options.progressive) { options = options.copy(progressive = it) }
                        ExportToggle("Use arithmetic coding", options.arithmetic) { options = options.copy(arithmetic = it) }
                        ExportToggle("Optimize", options.optimize) { options = options.copy(optimize = it) }
                        ExportToggle("Use restart markers", options.restartRows > 0) { options = options.copy(restartRows = if (it) 16 else 0) }
                        if (options.restartRows > 0) ExportSlider("Interval (MCU rows)", options.restartRows, 1..64) { options = options.copy(restartRows = it) }
                        Choice("Subsampling", options.subsampling, JpegSampling.entries, { it.label }, enabled = !options.cmyk) { options = options.copy(subsampling = it) }
                        Choice("DCT method", options.dct, JpegDct.entries, { it.label }) { options = options.copy(dct = it) }

                }
                ImageFormat.GIF -> {
                    ExportToggle("Interlace", options.interlace) { options = options.copy(interlace = it) }
                    ExportToggle("Save comment", options.saveComment) { options = options.copy(saveComment = it) }
                    if (options.saveComment) OutlinedTextField(options.comment, { options = options.copy(comment = it) }, label = { Text("GIF comment") })
                    if (options.animation) {
                        ExportToggle("Loop forever", options.loopForever) { options = options.copy(loopForever = it) }
                        if (!options.loopForever) ExportSlider("Number of repeats", options.repeats, 1..1000) { options = options.copy(repeats = it) }
                        ExportSlider("Delay between frames (ms)", options.frameDelay, 1..10000) { options = options.copy(frameDelay = it) }
                        Choice("Frame disposal", options.disposal, FrameDisposal.entries, { it.label }) { options = options.copy(disposal = it) }
                        ExportToggle("Use delay for all frames", options.forceFrameDelay) { options = options.copy(forceFrameDelay = it) }
                        ExportToggle("Use disposal for all frames", options.forceDisposal) { options = options.copy(forceDisposal = it) }
                    }

                }
                ImageFormat.WEBP -> {
                    Choice("Source type", options.webpSource, WebpSource.entries, { it.label }) { options = options.copy(webpSource = it) }
                    ExportSlider("Alpha quality", options.alphaQuality, 0..100, !options.lossless) { options = options.copy(alphaQuality = it) }
                    ExportToggle("Use Sharp YUV", options.sharpYuv, !options.lossless) { options = options.copy(sharpYuv = it) }
                    if (options.animation) {
                        ExportToggle("Loop forever", options.loopForever) { options = options.copy(loopForever = it) }
                        ExportToggle("Minimize output size", options.minimizeSize) { options = options.copy(minimizeSize = it) }
                        ExportSlider("Keyframe distance", options.keyframeDistance, 2..1000, !options.minimizeSize) { options = options.copy(keyframeDistance = it) }
                        ExportSlider("Delay between frames (ms)", options.frameDelay, 1..10000) { options = options.copy(frameDelay = it) }
                        ExportToggle("Use delay for all frames", options.forceFrameDelay) { options = options.copy(forceFrameDelay = it) }
                    }

                }
            }
            if (previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { Image(it.asImageBitmap(), "Export preview", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
            previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (options.format != ImageFormat.GIF) {
            TextButton(onClick = { metadataOpen = !metadataOpen }) {
                Text("Metadata", modifier = Modifier.weight(1f))
                Icon(if (metadataOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (metadataOpen) {
                TextButton(onClick = { editMetadata = true }) { Text("Edit…") }
                ExportToggle("Save Exif data", options.saveExif && info.exportMetadata.exif != null, info.exportMetadata.exif != null) { options = options.copy(saveExif = it) }
                ExportToggle("Save XMP data", options.saveXmp && info.exportMetadata.xmp != null, info.exportMetadata.xmp != null) { options = options.copy(saveXmp = it) }
                ExportToggle("Save IPTC data", options.saveIptc && info.exportMetadata.iptc != null && options.format != ImageFormat.WEBP, info.exportMetadata.iptc != null && options.format != ImageFormat.WEBP) { options = options.copy(saveIptc = it) }
                ExportToggle("Save color profile", options.saveProfile && !(options.format == ImageFormat.JPEG && options.cmyk), !(options.format == ImageFormat.JPEG && options.cmyk)) { options = options.copy(saveProfile = it) }
                ExportToggle("Save thumbnail", options.saveThumbnail) { options = options.copy(saveThumbnail = it) }
                if (options.format == ImageFormat.JPEG) ExportToggle("Save comment", options.saveComment) { options = options.copy(saveComment = it) }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { preferences.getString("saved", null)?.let { options = ImageExportOptions.fromJson(it) } }, enabled = hasSavedSettings) { Text("Load saved settings") }
                TextButton(onClick = { preferences.edit().putString("saved", options.toJson()).apply(); hasSavedSettings = true }) { Text("Save settings") }
            }
            }
        } }, confirmButton = { TextButton(onClick = { save(options) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
    if (editMetadata) AlertDialog(onDismissRequest = { editMetadata = false }, containerColor = Ui.panel,
        title = { Text("Metadata") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(options.comment, { options = options.copy(comment = it) }, label = { Text("Comment") }, modifier = Modifier.fillMaxWidth())
            ExportSlider("Resolution (ppi)", options.resolution, 1..2400) { options = options.copy(resolution = it) }
        } }, confirmButton = { TextButton(onClick = { editMetadata = false }) { Text("Done") } })
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
