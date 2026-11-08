package io.github.nielstron.brushkin.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.HistorySnapshot
import io.github.nielstron.brushkin.engine.HistoryStore
import io.github.nielstron.brushkin.engine.Ora
import io.github.nielstron.brushkin.engine.ParkedDocument
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.blankStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val DEFAULT_WIDTH = 1600
private const val DEFAULT_HEIGHT = 2400
private const val THUMBNAIL_SIZE = 480
private const val NOT_SAVED = -1L
/** Background documents kept in memory, undo history included; older ones wait on disk. */
private const val MAX_PARKED = 2

/** What tools remember about one image and the view of it, kept while the tab is in the background. */
class TabEdit(
    val cloneSource: android.graphics.PointF?, val cloneOffset: android.graphics.Point?,
    val perspPlane: FloatArray?, val perspOffset: android.graphics.PointF?,
    val pathAnchors: List<Anchor>, val pathClosed: Boolean, val view: CanvasView.SavedView?,
)

/** A file name as tabs show it: the extension says little and takes up room. */
fun tabTitle(name: String) = name.substringBeforeLast('.').ifEmpty { name }

class DocumentTab internal constructor(val id: Long, name: String) {
    var name by mutableStateOf(name); internal set
    var thumbnail by mutableStateOf<ImageBitmap?>(null); internal set
    /** The document while it's in memory in the background; null when active or only on disk. */
    internal var parked: ParkedDocument? = null
    internal var savedVersion = NOT_SAVED
    internal var edit: TabEdit? = null
    internal var lastUsed = 0L
    internal var closed = false
    /** Created empty in this session; a file opened now can take its place if it's still untouched. */
    internal var blank = false
}

/**
 * The open images. One [Engine] shows the active tab; the others are parked in memory with their
 * undo history or, once saved and no longer recent, exist only as files (Chrome's frozen tabs).
 * Every tab is autosaved to its own file, listed in a manifest, so all of them survive a restart.
 */
class DocumentTabs(
    private val engine: Engine,
    private val filesDir: File,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        /** False while long work runs that expects the current document to stay. */
        fun canSwitch(): Boolean
        fun status(label: String?)
        fun message(text: String)
        /** Before the document goes away: [tab] is null when it's only the startup placeholder. */
        fun leaving(tab: DocumentTab?)
        fun arrived(tab: DocumentTab)
    }

    private class Closed(val tab: DocumentTab, val index: Int, val wasActive: Boolean, val replacement: DocumentTab?)
    private class SaveJob(val tab: DocumentTab, val stack: Stack, val version: Long, val thumbnail: Bitmap, val history: HistorySnapshot?)

    val items = mutableStateListOf<DocumentTab>()
    var active by mutableStateOf(DocumentTab(0, "Untitled")); private set
    var switcherOpen by mutableStateOf(false)
    private val closing = mutableStateListOf<Closed>()
    /** Closed tabs that can still be brought back. */
    val closingCount get() = closing.size
    val closingName get() = closing.lastOrNull()?.tab?.name.orEmpty()

    private val tabsDir = File(filesDir, "tabs")
    /** Replaced by the app with a folder outside device backups; undo history can be large. */
    internal var historyDir = File(filesDir, "history")
    /** Serializes changes to the tab list and which document is showing. */
    private val lock = Mutex()
    private val writeLock = Mutex()
    /** Whether the engine holds the active tab yet, rather than the blank startup document. */
    private var resident = false
    private var clock = 0L
    private var nextId = 1L

    init {
        val saved = runCatching { prefs.getString("tabs", null)?.let(::JSONObject) }.getOrNull()
        val list = saved?.optJSONArray("tabs")
        if (list != null && list.length() > 0) {
            for (i in 0 until list.length()) list.getJSONObject(i).let { items.add(DocumentTab(it.getLong("id"), it.getString("name"))) }
            nextId = maxOf(saved.optLong("next", 1), items.maxOf { it.id } + 1)
            active = items.firstOrNull { it.id == saved.optLong("active") } ?: items[0]
        } else {
            // Before tabs there was one autosaved image; it becomes the first tab, in the same file.
            active = DocumentTab(0, prefs.getString("name", "Untitled") ?: "Untitled")
            items.add(active)
        }
    }

    private fun fileOf(tab: DocumentTab) = if (tab.id == 0L) File(filesDir, "autosave.ora") else File(tabsDir, "${tab.id}.ora")
    private fun historyFileOf(tab: DocumentTab) = File(historyDir, "${tab.id}.history")
    private fun thumbnailOf(tab: DocumentTab) = File(tabsDir, "${tab.id}.thumb.png")

    private fun persist() {
        val list = JSONArray()
        for (t in items) list.put(JSONObject().put("id", t.id).put("name", t.name))
        prefs.edit().putString("tabs", JSONObject().put("active", active.id).put("next", nextId).put("tabs", list).toString()).apply()
    }

    private fun operation(guarded: Boolean = true, block: suspend () -> Unit): Job = scope.launch {
        lock.withLock {
            if (guarded && !host.canSwitch()) { host.message("Wait until the current operation has finished"); return@withLock }
            block()
        }
    }

    // ------------------------------------------------------------ startup

    /** Brings back the active tab; the rest load when they're first opened. */
    suspend fun restore() = lock.withLock {
        withContext(Dispatchers.IO) {
            tabsDir.mkdirs()
            val known = items.flatMap { listOf("${it.id}.ora", "${it.id}.thumb.png") }.toSet()
            tabsDir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
            if (items.none { it.id == 0L }) File(filesDir, "autosave.ora").delete()
            val histories = items.map { "${it.id}.history" }.toSet()
            historyDir.listFiles()?.filter { it.name !in histories }?.forEach { it.delete() }
        }
        val tab = active
        if (fileOf(tab).exists()) {
            load(tab)?.let { show(tab, it) }
        }
        if (!resident) {
            // Nothing to restore (or unreadable): the blank startup document is this tab.
            engine.post {}.join()
            resident = true
            tab.blank = !fileOf(tab).exists()
        }
        persist()
        scope.launch { loadThumbnails() }
        Unit
    }

    private suspend fun loadThumbnails() {
        for (tab in items.toList()) {
            if (tab.thumbnail != null) continue
            val image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumbnailOf(tab).path) } ?: continue
            if (tab.thumbnail == null) tab.thumbnail = image.asImageBitmap()
        }
    }

    // ------------------------------------------------------------ switching

    private suspend fun load(tab: DocumentTab): ParkedDocument? {
        val file = fileOf(tab)
        host.status("Opening ${tab.name}")
        try {
            val restored = withContext(Dispatchers.IO) { HistoryStore.read(historyFileOf(tab), file.length(), file.lastModified(), tab.name) }
            if (restored != null) { tab.savedVersion = restored.version; return restored }
            val stack = if (file.exists()) withContext(Dispatchers.IO) { file.inputStream().use { Ora.read(it) } }
                else withContext(Dispatchers.Default) { blankStack(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.WHITE) }
            tab.savedVersion = if (file.exists()) 0 else NOT_SAVED
            return ParkedDocument(stack, tab.name)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            android.util.Log.w("Brushkin", "couldn't load tab", t)
            host.message("Couldn't open ${tab.name}: ${t.message ?: t.javaClass.simpleName}")
            return null
        } finally {
            host.status(null)
        }
    }

    /** Shows [doc] as [tab]; the previous tab is parked and saved unless it [isClosing]. Holds [lock]. */
    private suspend fun show(tab: DocumentTab, doc: ParkedDocument, isClosing: Boolean = false) {
        val outgoing = if (resident) active else null
        host.leaving(outgoing)
        val out = engine.exchangeDocument(doc)
        tab.parked = null
        tab.lastUsed = ++clock
        active = tab
        resident = true
        host.arrived(tab)
        if (outgoing == null) return
        outgoing.parked = out
        if (isClosing) return
        val job = SaveJob(outgoing, engine.snapshot(out), out.version, engine.thumbnail(THUMBNAIL_SIZE, out), engine.historySnapshot(out))
        outgoing.thumbnail = job.thumbnail.asImageBitmap()
        scope.launch { write(job); evict() }
    }

    fun select(tab: DocumentTab) = operation {
        if (tab === active || tab !in items) return@operation
        val doc = tab.parked ?: load(tab) ?: return@operation
        show(tab, doc)
        persist()
    }

    /** The tab [delta] places from the active one, if there is one. */
    fun selectRelative(delta: Int) {
        items.getOrNull(items.indexOf(active) + delta)?.let { select(it) }
    }

    fun newTab(width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, background: Int? = Color.WHITE) = operation {
        val stack = withContext(Dispatchers.Default) { blankStack(width, height, background) }
        val tab = DocumentTab(nextId++, "Untitled").apply { blank = true }
        items.add(tab)
        show(tab, ParkedDocument(stack, tab.name))
        persist()
        saveActive()
    }

    /** Opens an image in a new tab, or in place of a blank tab nothing has been done to yet. */
    fun open(stack: Stack, name: String) = operation(guarded = false) {
        val doc = ParkedDocument(stack, name)
        val info = engine.info.value
        val unused = if (resident) active.blank && !info.canUndo && !info.canRedo else !fileOf(active).exists()
        if (unused) {
            val tab = active
            resident = false // What's showing is thrown away, not parked.
            show(tab, doc)
            tab.name = name; tab.blank = false; tab.savedVersion = NOT_SAVED
        } else {
            val tab = DocumentTab(nextId++, name)
            items.add(tab)
            show(tab, doc)
        }
        persist()
        saveActive()
    }

    fun close(tab: DocumentTab) = operation(guarded = tab === active) {
        val index = items.indexOf(tab)
        if (index < 0) return@operation
        val wasActive = tab === active
        // There's always a tab: closing the last one leaves a new blank one.
        val replacement = if (items.size == 1) DocumentTab(nextId++, "Untitled").apply { blank = true; items.add(this) } else null
        if (wasActive) {
            val next = replacement ?: items.getOrNull(index + 1) ?: items[index - 1]
            val doc = next.parked ?: load(next)
            if (doc == null) { replacement?.let { items.remove(it) }; return@operation }
            show(next, doc, isClosing = true)
        }
        tab.closed = true
        items.remove(tab)
        closing.add(Closed(tab, index, wasActive, replacement))
        persist()
    }

    /** Brings back everything closed since the last undo bar went away. */
    fun undoClose() = operation(guarded = false) {
        val closed = closing.toList()
        closing.clear()
        for (c in closed.asReversed()) { c.tab.closed = false; items.add(c.index.coerceAtMost(items.size), c.tab) }
        // Busy with something else: they come back in the background instead of being lost.
        if (host.canSwitch()) closed.lastOrNull { it.wasActive }?.tab?.let { tab -> tab.parked?.let { show(tab, it) } }
        for (r in closed.mapNotNull { it.replacement }) {
            if (r === active || r.parked?.edited == true) continue
            r.closed = true
            items.remove(r)
            deleteFiles(r)
        }
        persist()
    }

    /** The undo bar is gone: closed tabs are now really gone. */
    fun finishClosing() {
        val closed = closing.toList()
        closing.clear()
        for (c in closed) { c.tab.parked = null; deleteFiles(c.tab) }
    }

    private fun deleteFiles(tab: DocumentTab) {
        scope.launch(Dispatchers.IO) { writeLock.withLock { fileOf(tab).delete(); thumbnailOf(tab).delete(); historyFileOf(tab).delete() } }
    }

    /** Names [tab] after a file, e.g. the one it was just saved as. */
    fun rename(tab: DocumentTab, name: String) = operation(guarded = false) {
        // The name also becomes the file name of exports and shares.
        val clean = name.trim().replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
        if (clean.isEmpty() || clean == tab.name || tab !in items) return@operation
        tab.name = clean
        tab.blank = false
        tab.parked?.name = clean
        if (tab === active) engine.renameDocument(clean).join()
        persist()
    }

    /** Applies a title the user typed; names keep an extension so that dots in the title survive [tabTitle]. */
    fun renameTitle(tab: DocumentTab, title: String) {
        if (title.isBlank() || title.trim() == tabTitle(tab.name)) return
        rename(tab, "${title.trim()}.${tab.name.substringAfterLast('.', "ora")}")
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    fun finishMoving() = persist()

    // ------------------------------------------------------------ saving

    private suspend fun saveJobForActive(): SaveJob? = lock.withLock {
        if (!resident) return null
        val tab = active
        val version = engine.info.value.version
        if (version == tab.savedVersion) return null
        SaveJob(tab, engine.snapshot(), version, engine.thumbnail(THUMBNAIL_SIZE), engine.historySnapshot())
    }

    /** Saves the active tab if it changed since its last save. */
    suspend fun autosave() {
        val job = saveJobForActive() ?: return
        job.tab.thumbnail = job.thumbnail.asImageBitmap()
        write(job)
    }

    /** From inside an [operation], which already holds the lock. */
    private fun saveActive() {
        scope.launch { autosave() }
    }

    private suspend fun write(job: SaveJob) = writeLock.withLock {
        val tab = job.tab
        if (tab.closed || job.version == tab.savedVersion) return@withLock
        runCatching {
            withContext(Dispatchers.IO) {
                tabsDir.mkdirs()
                val tmp = File(tabsDir, "${tab.id}.tmp")
                tmp.outputStream().use { Ora.write(job.stack, it) }
                // Image and history replace their files together, so a save cut off by the app being
                // ended leaves the previous pair intact; the history matches the image it's written with.
                val pending = File(historyDir, "${tab.id}.pending")
                val historySaved = job.history != null && runCatching {
                    historyDir.mkdirs()
                    HistoryStore.write(job.history, pending, tmp.length(), tmp.lastModified())
                }.onFailure { android.util.Log.w("Brushkin", "couldn't save undo history", it) }.isSuccess
                check(tmp.renameTo(fileOf(tab))) { "couldn't replace the saved file" }
                if (historySaved) pending.renameTo(historyFileOf(tab)) else historyFileOf(tab).delete()
                thumbnailOf(tab).outputStream().use { job.thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            tab.savedVersion = job.version
        }.onFailure { android.util.Log.w("Brushkin", "autosave failed", it) }
        Unit
    }

    /** Lets go of background documents beyond the newest [keep]; only saved ones, so nothing is lost. */
    private fun evict(keep: Int = MAX_PARKED) {
        items.filter { it !== active && it.parked != null }.sortedByDescending { it.lastUsed }.drop(keep)
            .filter { it.parked?.version == it.savedVersion }.forEach { it.parked = null }
    }

    fun releaseBackgroundTabs() = evict(keep = 0)

    /** Brings the active tab's picture up to date, for the tab switcher. */
    fun refreshThumbnail() {
        val tab = active
        scope.launch {
            val image = lock.withLock { if (resident && tab === active) engine.thumbnail(THUMBNAIL_SIZE) else null } ?: return@launch
            tab.thumbnail = image.asImageBitmap()
        }
    }
}
