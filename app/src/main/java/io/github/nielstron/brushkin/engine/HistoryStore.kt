package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** A document's undo state, consistent as of the moment it was captured on the engine thread. */
internal class HistorySnapshot(
    val stack: Stack,
    val undo: List<HistoryEntry>,
    val redo: List<HistoryEntry>,
    /** Tile arrays change as edits are undone, so they're copied; the tiles themselves never change. */
    val tiles: IdentityHashMap<TiledBitmap, Array<Bitmap?>>,
    /** Patches swap their pixels on undo, so these are copied too. */
    val patchPixels: IdentityHashMap<TilePatch, Bitmap>,
    val version: Long,
)

/**
 * Keeps undo history across the process being killed. Undo swaps tiles inside shared
 * [TiledBitmap]s, so the whole object graph is stored, each tile and bitmap once. A file only
 * counts while the autosaved image it was written with is unchanged.
 */
internal object HistoryStore {
    private const val MAGIC = 0x424B4832 // "BKH2"
    /** Tile data kept on disk, newest steps first; older steps only live in memory. */
    const val MAX_BYTES = 128L * 1024 * 1024

    fun capture(stack: Stack, history: History, version: Long, maxBytes: Long = MAX_BYTES): HistorySnapshot? {
        var budget = maxBytes
        fun newestWithinBudget(entries: List<HistoryEntry>): List<HistoryEntry> {
            val kept = ArrayList<HistoryEntry>()
            for (e in entries.asReversed()) { if (e.bytes > budget) break; budget -= e.bytes; kept.add(e) }
            return kept.asReversed()
        }
        val undo = newestWithinBudget(history.undoEntries)
        val redo = newestWithinBudget(history.redoEntries)
        if (undo.isEmpty() && redo.isEmpty()) return null
        val tiles = IdentityHashMap<TiledBitmap, Array<Bitmap?>>()
        fun add(t: TiledBitmap?) { if (t != null && t !in tiles) tiles[t] = t.tiles.copyOf() }
        for (s in listOf(stack) + (undo + redo).flatMap { listOf(it.before, it.after) }) tiledBitmaps(s).forEach(::add)
        (undo + redo).forEach { e -> e.diffs.forEach { add(it.target) } }
        val patchPixels = IdentityHashMap<TilePatch, Bitmap>()
        (undo + redo).forEach { e -> e.diffs.forEach { d -> d.patches.values.forEach { p -> p.pixels?.let { patchPixels[p] = it.copy(Bitmap.Config.ARGB_8888, false) } } } }
        return HistorySnapshot(stack, undo, redo, tiles, patchPixels, version)
    }

    private fun tiledBitmaps(s: Stack): List<TiledBitmap> =
        s.all.flatMap { listOfNotNull(it.pixels, it.mask) } + listOfNotNull(s.quickMask) + s.channels.map { it.mask }

    /** Replaces [file] only once it's complete. */
    fun write(s: HistorySnapshot, file: File, imageLength: Long, imageModified: Long) {
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { raw ->
                DataOutputStream(raw).run { writeInt(MAGIC); writeLong(imageLength); writeLong(imageModified); flush() }
                val deflater = Deflater(Deflater.BEST_SPEED)
                try {
                    val deflated = DeflaterOutputStream(raw, deflater, 1 shl 16)
                    val out = DataOutputStream(BufferedOutputStream(deflated, 1 shl 16))
                    Writer(s, out).write()
                    out.flush(); deflated.finish()
                } finally { deflater.end() }
            }
            check(tmp.renameTo(file)) { "couldn't replace the history file" }
        } finally { tmp.delete() }
    }

    /** Null if there's no usable history for this image; the image alone still opens. */
    fun read(file: File, imageLength: Long, imageModified: Long, name: String): ParkedDocument? {
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { raw ->
                val head = DataInputStream(raw)
                if (head.readInt() != MAGIC || head.readLong() != imageLength || head.readLong() != imageModified) return null
                Reader(DataInputStream(BufferedInputStream(InflaterInputStream(raw), 1 shl 16))).read(name)
            }
        } catch (t: Throwable) { // Includes running out of memory: losing history must never lose the image.
            android.util.Log.w("Brushkin", "couldn't restore undo history", t)
            null
        }
    }

    private class Writer(val s: HistorySnapshot, val out: DataOutputStream) {
        private val bitmaps = IdentityHashMap<Bitmap, Int>()
        private val tiled = IdentityHashMap<TiledBitmap, Int>()
        private val stacks = IdentityHashMap<Stack, Int>()
        private val orderedBitmaps = ArrayList<Bitmap>()
        private val orderedTiled = ArrayList<TiledBitmap>()
        private val orderedStacks = ArrayList<Stack>()
        private var buffer = ByteBuffer.allocate(TILE * TILE * 4)

        private fun collect(b: Bitmap?) { if (b != null && b !in bitmaps) { bitmaps[b] = orderedBitmaps.size; orderedBitmaps.add(b) } }
        private fun collect(t: Stack) {
            if (t in stacks) return
            stacks[t] = orderedStacks.size; orderedStacks.add(t)
            t.all.forEach { l -> l.outside.values.forEach { collect(it) } }
        }
        private fun id(b: Bitmap?) = if (b == null) -1 else bitmaps.getValue(b)
        private fun id(t: TiledBitmap?) = if (t == null) -1 else tiled.getValue(t)

        fun write() {
            for ((t, array) in s.tiles) { tiled[t] = orderedTiled.size; orderedTiled.add(t); array.forEach { collect(it) } }
            val entries = s.undo + s.redo
            collect(s.stack)
            for (e in entries) { collect(e.before); collect(e.after); e.diffs.forEach { d -> d.whole.values.forEach { collect(it.first); collect(it.second) } } }

            out.writeInt(orderedBitmaps.size); orderedBitmaps.forEach(::bitmap)
            out.writeInt(orderedTiled.size)
            for (t in orderedTiled) { out.writeInt(t.width); out.writeInt(t.height); out.writeInt(t.fill); s.tiles.getValue(t).forEach { out.writeInt(id(it)) } }
            out.writeInt(orderedStacks.size); orderedStacks.forEach(::stack)
            out.writeLong(s.version); out.writeInt(stacks.getValue(s.stack))
            out.writeInt(s.undo.size); s.undo.forEach(::entry)
            out.writeInt(s.redo.size); s.redo.forEach(::entry)
        }

        private fun bitmap(b: Bitmap) {
            check(b.config == Bitmap.Config.ARGB_8888 && !b.isRecycled) { "unexpected bitmap" }
            val size = b.width * b.height * 4
            if (buffer.capacity() < size) buffer = ByteBuffer.allocate(size)
            buffer.clear(); b.copyPixelsToBuffer(buffer)
            out.writeInt(b.width); out.writeInt(b.height); out.write(buffer.array(), 0, size)
        }
        private fun string(v: String?) {
            if (v == null) { out.writeInt(-1); return }
            val bytes = v.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes)
        }
        private fun stack(t: Stack) {
            out.writeInt(t.width); out.writeInt(t.height); out.writeLong(t.activeId)
            layers(t.layers)
            val selection = t.selection?.bytes()
            out.writeBoolean(selection != null)
            if (selection != null) { out.writeInt(selection.size); out.write(selection) }
            out.writeInt(id(t.quickMask))
            out.writeInt(t.guides.size); t.guides.forEach { out.writeBoolean(it.vertical); out.writeInt(it.pos) }
            out.writeInt(t.channels.size)
            for (c in t.channels) { out.writeLong(c.id); string(c.name); out.writeInt(id(c.mask)); out.writeInt(c.color); out.writeFloat(c.opacity); out.writeBoolean(c.visible) }
            string(t.exportMetadata.toJson())
        }
        private fun layers(list: List<Layer>) {
            out.writeInt(list.size)
            for (l in list) {
                out.writeLong(l.id); string(l.name); out.writeInt(id(l.pixels)); out.writeBoolean(l.visible); out.writeFloat(l.opacity); string(l.mode.name)
                out.writeBoolean(l.lockAlpha); out.writeBoolean(l.lockPixels); out.writeInt(id(l.mask)); out.writeBoolean(l.maskEnabled); out.writeBoolean(l.editMask)
                out.writeBoolean(l.children != null); l.children?.let { layers(it) }
                out.writeBoolean(l.expanded); string(l.text?.toJson())
                out.writeInt(l.outside.size); l.outside.forEach { (k, v) -> out.writeLong(k); out.writeInt(id(v)) }
                out.writeBoolean(l.bounds != null); l.bounds?.let { out.writeInt(it.left); out.writeInt(it.top); out.writeInt(it.right); out.writeInt(it.bottom) }
                out.writeBoolean(l.lockPosition); out.writeBoolean(l.linked); string(LayerEffect.listToJson(l.effects))
            }
        }
        private fun entry(e: HistoryEntry) {
            string(e.name); out.writeInt(stacks.getValue(e.before)); out.writeInt(stacks.getValue(e.after)); out.writeLong(e.extraBytes)
            out.writeInt(e.diffs.size)
            for (d in e.diffs) {
                out.writeInt(id(d.target)); out.writeInt(d.whole.size)
                d.whole.forEach { (i, c) -> out.writeInt(i); out.writeInt(id(c.first)); out.writeInt(id(c.second)) }
                out.writeInt(d.patches.size)
                d.patches.forEach { (i, p) ->
                    out.writeInt(i); out.writeInt(p.x); out.writeInt(p.y); out.writeInt(p.w); out.writeInt(p.h)
                    out.writeBoolean(p.beforeMissing); out.writeBoolean(p.afterMissing)
                    s.patchPixels[p]?.let { held ->
                        val raw = IntArray(p.w * p.h).also { held.copyPixelsToBuffer(java.nio.IntBuffer.wrap(it)) }
                        raw.forEach(out::writeInt)
                    }
                }
            }
        }
    }

    private class Reader(val input: DataInputStream) {
        private lateinit var bitmaps: Array<Bitmap>
        private lateinit var tiled: Array<TiledBitmap>
        private fun bitmap(i: Int) = if (i < 0) null else bitmaps[i]
        private fun tiledOrNull(i: Int) = if (i < 0) null else tiled[i]

        fun read(name: String): ParkedDocument {
            var buffer = ByteArray(TILE * TILE * 4)
            bitmaps = Array(input.readInt()) {
                val w = input.readInt(); val h = input.readInt(); val size = w * h * 4
                if (buffer.size < size) buffer = ByteArray(size)
                input.readFully(buffer, 0, size)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(buffer, 0, size)) }
            }
            tiled = Array(input.readInt()) {
                TiledBitmap(input.readInt(), input.readInt(), input.readInt()).also { t -> for (i in t.tiles.indices) t.tiles[i] = bitmap(input.readInt()) }
            }
            val stacks = Array(input.readInt()) { stack() }
            val version = input.readLong()
            val current = stacks[input.readInt()]
            fun entries() = List(input.readInt()) { entry(stacks) }
            val undo = entries(); val redo = entries()
            return ParkedDocument(current, name, History().apply { restore(undo, redo) }, version)
        }
        private fun string(): String? = input.readInt().let { n -> if (n < 0) null else ByteArray(n).also(input::readFully).toString(Charsets.UTF_8) }
        private fun stack(): Stack {
            val w = input.readInt(); val h = input.readInt(); val active = input.readLong()
            val layers = layers()
            val selection = if (input.readBoolean()) Selection.fromBytes(ByteArray(input.readInt()).also(input::readFully), w, h) else null
            val quick = tiledOrNull(input.readInt())
            val guides = List(input.readInt()) { Guide(input.readBoolean(), input.readInt()) }
            val channels = List(input.readInt()) {
                SavedChannel(input.readLong(), string()!!, tiled[input.readInt()], input.readInt(), input.readFloat(), input.readBoolean())
            }
            return Stack(w, h, layers, active, selection, quick, guides, channels, io.github.nielstron.brushkin.export.ExportMetadata.fromJson(string()!!))
        }
        private fun layers(): List<Layer> = List(input.readInt()) {
            val id = input.readLong(); val name = string()!!; val pixels = tiled[input.readInt()]; val visible = input.readBoolean(); val opacity = input.readFloat()
            val mode = LayerMode.valueOf(string()!!); val lockAlpha = input.readBoolean(); val lockPixels = input.readBoolean()
            val mask = tiledOrNull(input.readInt()); val maskEnabled = input.readBoolean(); val editMask = input.readBoolean()
            val children = if (input.readBoolean()) layers() else null
            val expanded = input.readBoolean(); val text = string()?.let { TextSpec.fromJson(it) }
            val outside = HashMap<Long, Bitmap>().also { m -> repeat(input.readInt()) { val k = input.readLong(); m[k] = bitmaps[input.readInt()] } }
            val bounds = if (input.readBoolean()) Rect(input.readInt(), input.readInt(), input.readInt(), input.readInt()) else null
            val lockPosition = input.readBoolean(); val linked = input.readBoolean(); val effects = LayerEffect.listFromJson(string()!!)
            Layer(id, name, pixels, visible, opacity, mode, lockAlpha, lockPixels, mask, maskEnabled, editMask, children, expanded, text, outside, bounds, lockPosition, linked, effects)
        }
        private fun entry(stacks: Array<Stack>): HistoryEntry {
            val name = string()!!; val before = stacks[input.readInt()]; val after = stacks[input.readInt()]; val extra = input.readLong()
            val diffs = List(input.readInt()) {
                val target = tiled[input.readInt()]
                val changes = HashMap<Int, Pair<Bitmap?, Bitmap?>>()
                repeat(input.readInt()) { val i = input.readInt(); changes[i] = bitmap(input.readInt()) to bitmap(input.readInt()) }
                val patches = HashMap<Int, TilePatch>()
                repeat(input.readInt()) {
                    val i = input.readInt(); val x = input.readInt(); val y = input.readInt(); val w = input.readInt(); val h = input.readInt()
                    val beforeMissing = input.readBoolean(); val afterMissing = input.readBoolean()
                    patches[i] = TilePatch(x, y, w, h, if (w == 0) null else TilePatch.rawBitmap(w, h, IntArray(w * h) { input.readInt() }), beforeMissing, afterMissing)
                }
                TileDiff(target, changes, patches)
            }
            return HistoryEntry(name, before, after, diffs, extra)
        }
    }
}
