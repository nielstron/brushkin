package io.github.nielstron.brushkin

import android.content.ActivityNotFoundException
import android.content.ClipData
import io.github.nielstron.brushkin.export.*
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.github.nielstron.brushkin.engine.Ora
import io.github.nielstron.brushkin.store.StoreIntegration
import io.github.nielstron.brushkin.ui.BrushkinTheme
import io.github.nielstron.brushkin.ui.EditorScreen
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity(), FileActions {
    private val vm: EditorViewModel by viewModels()
    private lateinit var store: StoreIntegration

    private val pickLayer = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { insertImage(it) } }
    private val pickLayerFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { insertImage(it) } }
    private val pickOpen = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { open(it) } }
    private val pickProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { open(it) } }
    private val createProject = registerForActivityResult(ActivityResultContracts.CreateDocument("image/openraster")) { uri -> uri?.let { saveOra(it) } }
    private val pickResources = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() } ?: return@launch
            vm.importResource(displayName(u), bytes)
        }
    }
    private val createXcf = registerForActivityResult(ActivityResultContracts.CreateDocument("image/x-xcf")) { uri -> uri?.let { saveXcf(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = StoreIntegration(this)
        store.start()
        setContent { BrushkinTheme { EditorScreen(vm, this, store) } }
        if (!vm.startupStarted) {
            vm.startupStarted = true
            val shared = incomingUri(intent)
            if (shared != null) open(shared) else vm.restoreAtStartup()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Clipboard contents can only be inspected while focused.
        if (hasFocus) vm.refreshClipboard()
    }

    /** GIMP's keyboard shortcuts for hardware keyboards (text fields get keys first). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val e = vm.engine
        val sel = e.info.value.hasSelection
        if (event.isCtrlPressed) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) e.redo() else e.undo()
                android.view.KeyEvent.KEYCODE_Y -> e.redo()
                android.view.KeyEvent.KEYCODE_C -> vm.copy(merged = event.isShiftPressed)
                android.view.KeyEvent.KEYCODE_X -> vm.copy(cut = true)
                android.view.KeyEvent.KEYCODE_V -> vm.paste()
                android.view.KeyEvent.KEYCODE_A -> if (event.isShiftPressed) e.selectNone() else e.selectAll()
                android.view.KeyEvent.KEYCODE_I -> e.selectInvert()
                android.view.KeyEvent.KEYCODE_S -> saveProject()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_Q && event.isShiftPressed) { e.quickMaskToggle(); return true }
        if ((keyCode == android.view.KeyEvent.KEYCODE_DEL || keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL) && sel) {
            e.clearSelection(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri(intent)?.let { open(it) }
    }

    private fun incomingUri(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }

    private fun prefs() = getSharedPreferences("brushkin", MODE_PRIVATE)

    override fun onResume() {
        super.onResume()
        store.resume()
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Autosave off the main thread so leaving the app never stalls.
        lifecycleScope.launch { vm.autosave() }
    }

    // ------------------------------------------------------------------ FileActions

    override fun pickImageLayer() = pickLayer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    override fun pickImageLayerFile() = pickLayerFile.launch(arrayOf("image/*"))
    override fun openImage() = pickOpen.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    override fun openProject() = pickProject.launch(arrayOf("image/openraster", "image/x-xcf", "application/zip", "application/octet-stream", "image/*"))
    override fun importResources() = pickResources.launch(arrayOf("*/*"))
    override fun saveXcf() = createXcf.launch("${vm.engine.docName.substringBeforeLast('.')}.xcf")
    override fun saveProject() = createProject.launch("${vm.engine.docName.substringBeforeLast('.')}.ora")

    override suspend fun exportPreview(options: ImageExportOptions): Bitmap {
        val source = vm.engine.flattened()
        return try { withContext(Dispatchers.IO) {
            val factor = minOf(1f, 1024f / maxOf(source.width, source.height))
            val preview = if (factor == 1f) source else Bitmap.createScaledBitmap(source, maxOf(1,(source.width*factor).toInt()), maxOf(1,(source.height*factor).toInt()), true)
            try { val encoded = options.encode(preview, vm.engine.info.value.exportMetadata)
                android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.size) ?: error("Preview unavailable for these settings")
            } finally { if (preview !== source) preview.recycle() }
        } } finally { source.recycle() }
    }

    override fun export(options: ImageExportOptions) {
        if (vm.exportStatus != null) return
        vm.exportedImage = null
        vm.exportStatus = "Preparing image"
        val format = options.format.extension
        lifecycleScope.launch {
            var pending: Uri? = null
            try {
                val snapshot = if (options.animation && options.format in listOf(ImageFormat.WEBP,ImageFormat.GIF)) vm.engine.snapshot() else null
                val metadata = vm.engine.info.value.exportMetadata
                val background = vm.bg
                val bmp = vm.engine.flattened()
                val name = "${vm.engine.docName.substringBeforeLast('.')}-${System.currentTimeMillis() / 1000}.$format"
                vm.exportStatus = "Encoding ${options.format.label}"
                val uri = withContext(Dispatchers.IO) {
                    val bytes = try {
                        val encoded = if (snapshot != null) {
                            if (options.format == ImageFormat.GIF) encodeGifAnimation(snapshot, options) else encodeWebpAnimation(snapshot, options)
                        } else options.encode(bmp, metadata)
                        addExportMetadata(encoded, bmp, options, metadata, assets.open("export/sRGB.icc").use { it.readBytes() }, background)
                    } finally { bmp.recycle() }
                    withContext(Dispatchers.Main) { vm.exportStatus = "Writing image" }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, options.format.mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Brushkin")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create file")
                    pending = uri
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    uri
                }
                pending = null
                vm.exportedImage = io.github.nielstron.brushkin.ui.ExportedImage(uri, options.format.mime)
            } catch (t: Throwable) {
                pending?.let { withContext(Dispatchers.IO) { contentResolver.delete(it, null, null) } }
                vm.messages.tryEmit("Export failed: ${t.message}")
            } finally { vm.exportStatus = null }
        }
    }

    override fun openExport(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) { vm.messages.tryEmit("No app is installed to open this file") }
    }

    override fun share() {
        lifecycleScope.launch {
            try {
                val bmp = vm.engine.flattened()
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    File(dir, "${vm.engine.docName.substringBeforeLast('.')}.png").also { f -> f.outputStream().use { it.write(Ora.encode(bmp, "png")) } }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share image"))
            } catch (t: Throwable) {
                vm.messages.tryEmit("Share failed: ${t.message}")
            }
        }
    }

    private val stickerStore by lazy { StickerStore(this) }

    override suspend fun stickerPacks() = withContext(Dispatchers.IO) { stickerStore.packs() }
    override suspend fun addSticker(packId: String?, name: String, emoji: String): StickerPack {
        val image = vm.engine.stickerImage()
        return try { withContext(Dispatchers.IO) { stickerStore.add(image, packId, name, emoji) } }
        finally { image.recycle() }
    }
    override suspend fun removeSticker(packId: String, file: String) = withContext(Dispatchers.IO) { stickerStore.remove(packId, file) }

    override fun importWhatsApp(packId: String) {
        lifecycleScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) { stickerStore.pack(packId) }
                require(pack.stickers.size in WHATSAPP_MIN_STICKERS..WHATSAPP_MAX_STICKERS)
                val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
                    putExtra("sticker_pack_id", pack.id)
                    putExtra("sticker_pack_authority", "$packageName.stickers")
                    putExtra("sticker_pack_name", pack.name)
                    setPackage("com.whatsapp")
                }
                if (intent.resolveActivity(packageManager) == null) intent.setPackage("com.whatsapp.w4b")
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                vm.messages.tryEmit("Install WhatsApp or WhatsApp Business to import this pack. Your stickers are saved in Brushkin.")
            } catch (t: Exception) {
                vm.messages.tryEmit("Sticker import failed: ${t.message}")
            }
        }
    }

    override fun exportTelegram(emoji: String) {
        lifecycleScope.launch {
            try {
                val source = vm.engine.stickerImage()
                val file = try { withContext(Dispatchers.IO) {
                    val bitmap = stickerBitmap(source)
                    try {
                        val dir = File(cacheDir, "share").apply { mkdirs() }
                        File(dir, "sticker-${java.util.UUID.randomUUID()}.webp").also { it.writeBytes(encodeSticker(bitmap, 512_000)) }
                    } finally { bitmap.recycle() }
                } } finally { source.recycle() }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                startActivity(Intent("org.telegram.messenger.CREATE_STICKER_PACK").apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
                    putStringArrayListExtra("STICKER_EMOJIS", arrayListOf(emoji))
                    putExtra("IMPORTER", packageName)
                    clipData = ClipData.newUri(contentResolver, "Sticker", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: ActivityNotFoundException) {
                vm.messages.tryEmit("Install Telegram to import your sticker")
            } catch (t: Exception) {
                vm.messages.tryEmit("Sticker export failed: ${t.message}")
            }
        }
    }


    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Untitled"

    private suspend fun decode(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val result=contentResolver.openInputStream(uri)!!.use { ImageImport.firstFrame(it.readBytes()) }
        if(result.originalWidth!=result.bitmap.width || result.originalHeight!=result.bitmap.height)
            vm.messages.tryEmit("Scaled from ${result.originalWidth}×${result.originalHeight} to ${result.bitmap.width}×${result.bitmap.height} to fit in memory")
        result.bitmap
    }

    private fun insertImage(uri: Uri) {
        lifecycleScope.launch {
            runCatching { decode(uri) }
                .onSuccess { vm.engine.placeFloating(it, displayName(uri).substringBeforeLast('.')) }
                .onFailure { vm.messages.tryEmit("Couldn't open image: ${it.message}") }
        }
    }

    private fun open(uri: Uri) {
        if(vm.importStatus!=null)return
        vm.importStatus="Opening image"
        vm.viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            try {
                if (name.endsWith(".xcf", ignoreCase = true)) {
                    val stack = withContext(Dispatchers.IO) { io.github.nielstron.brushkin.engine.Xcf.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                    vm.engine.openStack(stack, name).join()
                } else if (name.endsWith(".ora", ignoreCase = true) || withContext(Dispatchers.IO) { contentResolver.getType(uri) } == "image/openraster") {
                    val stack = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { Ora.read(it) } }
                    vm.engine.openStack(stack, name).join()
                } else {
                    val result = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { ImageImport.open(it.readBytes(),name) } }
                    if(result.originalWidth!=result.stack.width || result.originalHeight!=result.stack.height)
                        vm.messages.tryEmit("Scaled from ${result.originalWidth}×${result.originalHeight} to ${result.stack.width}×${result.stack.height} to fit in memory")
                    vm.engine.openStack(result.stack,name).join()
                }
            } catch (t: Throwable) {
                vm.messages.tryEmit("Couldn't open $name: ${t.message}")
            } finally { vm.importStatus=null; vm.startupLoading=false }
        }
    }

    private fun saveXcf(uri: Uri) {
        lifecycleScope.launch {
            try {
                val snap = vm.engine.snapshot()
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.use { io.github.nielstron.brushkin.engine.Xcf.write(snap, it) } }
                vm.messages.tryEmit("Saved for GIMP")
            } catch (t: Throwable) {
                vm.messages.tryEmit("Save failed: ${t.message}")
            }
        }
    }

    private fun saveOra(uri: Uri) {
        lifecycleScope.launch {
            try {
                val snap = vm.engine.snapshot()
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.use { Ora.write(snap, it) } }
                vm.messages.tryEmit("Project saved")
            } catch (t: Throwable) {
                vm.messages.tryEmit("Save failed: ${t.message}")
            }
        }
    }
}
