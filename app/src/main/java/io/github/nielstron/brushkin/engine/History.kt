package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import java.nio.IntBuffer

/**
 * A tile region that differs between two versions. Only one version is stored: the other is in the
 * tile in place, and undo and redo swap them, so [pixels] always holds the version not showing.
 */
internal class TilePatch(
    val x: Int, val y: Int, val w: Int, val h: Int,
    /** A bitmap so that it's native memory: the Java heap limit is far lower. Null when nothing differs. */
    val pixels: Bitmap?,
    /** The tile doesn't exist in this version: it's the bitmap's fill color and stays unallocated. */
    val beforeMissing: Boolean, val afterMissing: Boolean,
) {
    val bytes: Long get() = w * h * 4L + 64

    /** Swaps [pixels] with the tile's region. Raw pixel memory throughout: setPixels would round semi-transparent colors. */
    fun swapped(current: Bitmap?, fill: Int, missing: Boolean, scratch: IntArray): Bitmap? {
        if (w == 0 && current != null && !missing) return current
        if (!readRaw(current, fill, scratch)) error("unexpected tile")
        if (pixels != null) {
            val region = IntArray(w * h).also { pixels.copyPixelsToBuffer(IntBuffer.wrap(it)) }
            for (row in 0 until h) for (column in 0 until w) {
                val inTile = (y + row) * TILE + x + column
                val inPatch = row * w + column
                val held = region[inPatch]; region[inPatch] = scratch[inTile]; scratch[inTile] = held
            }
            pixels.copyPixelsFromBuffer(IntBuffer.wrap(region))
        }
        return if (missing) null else TiledBitmap.newTile().apply { copyPixelsFromBuffer(IntBuffer.wrap(scratch)) }
    }

    companion object {
        /** Bitmap memory is RGBA bytes, so little-endian ints hold ABGR. */
        private fun rawFill(fill: Int) = (fill and 0xFF00FF00.toInt()) or ((fill and 0xFF) shl 16) or ((fill ushr 16) and 0xFF)
        private fun fillIsExact(fill: Int) = (fill ushr 24).let { it == 0 || it == 255 }

        fun rawBitmap(w: Int, h: Int, raw: IntArray): Bitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(IntBuffer.wrap(raw)) }

        fun readRaw(tile: Bitmap?, fill: Int, into: IntArray): Boolean {
            if (tile == null) {
                if (!fillIsExact(fill)) return false
                into.fill(rawFill(fill)); return true
            }
            if (tile.width != TILE || tile.height != TILE || tile.config != Bitmap.Config.ARGB_8888 || tile.isRecycled) return false
            tile.copyPixelsToBuffer(IntBuffer.wrap(into))
            return true
        }

        /** Null when whole tiles are the better form: most of it changed, or the tile is unusual. */
        fun between(before: Bitmap?, after: Bitmap?, fill: Int, a: IntArray, b: IntArray): TilePatch? {
            if (!readRaw(before, fill, a) || !readRaw(after, fill, b)) return null
            var left = TILE; var top = TILE; var right = -1; var bottom = -1
            for (y in 0 until TILE) for (x in 0 until TILE) if (a[y * TILE + x] != b[y * TILE + x]) {
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; bottom = y
            }
            if (right < 0) return TilePatch(0, 0, 0, 0, null, before == null, after == null)
            val w = right - left + 1; val h = bottom - top + 1
            if (w * h * 2 > TILE * TILE) return null
            val region = IntArray(w * h).also { out -> for (row in 0 until h) System.arraycopy(a, (top + row) * TILE + left, out, row * w, w) }
            return TilePatch(left, top, w, h, rawBitmap(w, h, region), before == null, after == null) // "after" is in the tile
        }
    }
}

/**
 * The tiles one step changed in one [TiledBitmap]: index → (before, after). [compact] then keeps only
 * the region that differs, so a brush stroke costs what it painted rather than whole tiles. A patch
 * is exact, and relies on every in-place tile change being recorded here, in order.
 */
class TileDiff(val target: TiledBitmap, changes: Map<Int, Pair<Bitmap?, Bitmap?>>) {
    internal var whole: Map<Int, Pair<Bitmap?, Bitmap?>> = changes; private set
    internal var patches: Map<Int, TilePatch> = emptyMap(); private set

    internal constructor(target: TiledBitmap, whole: Map<Int, Pair<Bitmap?, Bitmap?>>, patches: Map<Int, TilePatch>) : this(target, whole) { this.patches = patches }

    internal fun compact() {
        val a = IntArray(TILE * TILE); val b = IntArray(TILE * TILE)
        val kept = HashMap<Int, Pair<Bitmap?, Bitmap?>>(); val made = HashMap<Int, TilePatch>()
        for ((i, c) in whole) TilePatch.between(c.first, c.second, target.fill, a, b)?.let { made[i] = it } ?: run { kept[i] = c }
        whole = kept; patches = made
    }

    fun undo() {
        for ((i, c) in whole) target.tiles[i] = c.first
        if (patches.isEmpty()) return
        val scratch = IntArray(TILE * TILE)
        for ((i, p) in patches) target.tiles[i] = p.swapped(target.tiles[i], target.fill, p.beforeMissing, scratch)
    }
    fun redo() {
        for ((i, c) in whole) target.tiles[i] = c.second
        if (patches.isEmpty()) return
        val scratch = IntArray(TILE * TILE)
        for ((i, p) in patches) target.tiles[i] = p.swapped(target.tiles[i], target.fill, p.afterMissing, scratch)
    }
    val bytes: Long get() = whole.values.sumOf { (if (it.first != null) 1L else 0L) + (if (it.second != null) 1L else 0L) } * TILE * TILE * 4 + patches.values.sumOf { it.bytes }
}

class HistoryEntry(
    val name: String,
    val before: Stack,
    val after: Stack,
    val diffs: List<TileDiff> = emptyList(),
    /** Rough memory owned by this entry beyond shared tiles (e.g. replaced whole layers). */
    val extraBytes: Long = 0,
) {
    val bytes get() = diffs.sumOf { it.bytes } + extraBytes
}

/**
 * Linear undo history with a memory budget. Undo/redo swap tile references and stack snapshots,
 * so both are O(changed tiles) and never copy pixels.
 */
class History(private val maxBytes: Long = 512L * 1024 * 1024, private val maxEntries: Int = 100) {
    private val undo = ArrayDeque<HistoryEntry>()
    private val redo = ArrayDeque<HistoryEntry>()
    private var total = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val undoNames: List<String> get() = undo.map { it.name }
    val redoNames: List<String> get() = redo.map { it.name }.reversed()

    fun push(e: HistoryEntry) {
        e.diffs.forEach { it.compact() }
        undo.addLast(e)
        total += e.bytes
        for (r in redo) total -= r.bytes
        redo.clear()
        while ((total > maxBytes || undo.size > maxEntries) && undo.size > 1) {
            total -= undo.removeFirst().bytes
        }
    }

    fun undo(): HistoryEntry? {
        val e = undo.removeLastOrNull() ?: return null
        for (d in e.diffs.asReversed()) d.undo()
        redo.addLast(e)
        return e
    }

    fun redo(): HistoryEntry? {
        val e = redo.removeLastOrNull() ?: return null
        for (d in e.diffs) d.redo()
        undo.addLast(e)
        return e
    }

    /** Removes the newest entry without undoing it (its effect is about to be replaced). */
    fun dropLast(): HistoryEntry? {
        if (redo.isNotEmpty()) return null
        val e = undo.removeLastOrNull() ?: return null
        total -= e.bytes
        return e
    }

    fun clear() { undo.clear(); redo.clear(); total = 0 }

    internal val undoEntries: List<HistoryEntry> get() = undo.toList()
    internal val redoEntries: List<HistoryEntry> get() = redo.toList()
    /** Oldest undo step first; the last redo entry is the next to redo, as in [redo]. */
    internal fun restore(undoEntries: List<HistoryEntry>, redoEntries: List<HistoryEntry>) {
        clear(); undo.addAll(undoEntries); redo.addAll(redoEntries)
        total = (undoEntries + redoEntries).sumOf { it.bytes }
    }
}
