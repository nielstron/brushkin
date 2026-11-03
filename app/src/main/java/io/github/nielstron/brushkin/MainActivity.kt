package io.github.nielstron.brushkin

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import io.github.nielstron.brushkin.engine.Ora
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
    private val autosave by lazy { File(filesDir, "autosave.ora") }

    private val pickLayer = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { insertImage(it) } }
    private val pickLayerFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { insertImage(it) } }
    private val pickOpen = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { open(it) } }
    private val pickProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { open(it) } }
    private val createProject = registerForActivityResult(ActivityResultContracts.CreateDocument("image/openraster")) { uri -> uri?.let { saveOra(it) } }
    private val createXcf = registerForActivityResult(ActivityResultContracts.CreateDocument("image/x-xcf")) { uri -> uri?.let { saveXcf(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BrushkinTheme { EditorScreen(vm, this) } }
        if (savedInstanceState == null) {
            val shared = incomingUri(intent)
            when {
                shared != null -> open(shared)
                autosave.exists() -> lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { autosave.inputStream().use { Ora.read(it) } } }
                        .onSuccess { vm.engine.openStack(it, prefs().getString("name", "Untitled") ?: "Untitled") }
                }
            }
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
    override fun saveXcf() = createXcf.launch("${vm.engine.docName.substringBeforeLast('.')}.xcf")
    override fun saveProject() = createProject.launch("${vm.engine.docName.substringBeforeLast('.')}.ora")

    override fun export(format: String) {
        lifecycleScope.launch {
            try {
                val bmp = vm.engine.flattened()
                val name = "${vm.engine.docName.substringBeforeLast('.')}-${System.currentTimeMillis() / 1000}.$format"
                withContext(Dispatchers.IO) {
                    val bytes = Ora.encode(bmp, format)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, mime(format))
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Brushkin")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create file")
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
                vm.messages.tryEmit("Saved to Pictures/Brushkin/$name")
            } catch (t: Throwable) {
                vm.messages.tryEmit("Export failed: ${t.message}")
            }
        }
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

    private fun mime(f: String) = when (f) { "jpg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Untitled"

    /** Images up to 8192 px / 40 MP open at full size; larger ones are scaled down (and we say so). */
    private suspend fun decode(uri: Uri, maxDim: Int = 8192, maxPixels: Long = 40_000_000): Bitmap = withContext(Dispatchers.IO) {
        var scaledFrom: android.util.Size? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
            val s = info.size
            val k = maxOf(maxOf(s.width, s.height).toFloat() / maxDim, kotlin.math.sqrt(s.width.toDouble() * s.height / maxPixels).toFloat())
            if (k > 1f) { d.setTargetSize((s.width / k).toInt(), (s.height / k).toInt()); scaledFrom = s }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, true) else it }
            .also { b -> scaledFrom?.let { vm.messages.tryEmit("Scaled from ${it.width}×${it.height} to ${b.width}×${b.height} to fit in memory") } }
    }

    private fun insertImage(uri: Uri) {
        lifecycleScope.launch {
            runCatching { decode(uri) }
                .onSuccess { vm.engine.placeFloating(it, displayName(uri).substringBeforeLast('.')) }
                .onFailure { vm.messages.tryEmit("Couldn't open image: ${it.message}") }
        }
    }

    private fun open(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            try {
                if (name.endsWith(".xcf", ignoreCase = true)) {
                    val stack = withContext(Dispatchers.IO) { io.github.nielstron.brushkin.engine.Xcf.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                    vm.engine.openStack(stack, name)
                } else if (name.endsWith(".ora", ignoreCase = true) || withContext(Dispatchers.IO) { contentResolver.getType(uri) } == "image/openraster") {
                    val stack = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { Ora.read(it) } }
                    vm.engine.openStack(stack, name)
                } else {
                    vm.engine.openImage(decode(uri), name)
                }
            } catch (t: Throwable) {
                vm.messages.tryEmit("Couldn't open $name: ${t.message}")
            }
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
