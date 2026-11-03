package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap

/** Tile reference swaps for one [TiledBitmap]: index → (before, after). */
class TileDiff(val target: TiledBitmap, val changes: Map<Int, Pair<Bitmap?, Bitmap?>>) {
    fun undo() { for ((i, c) in changes) target.tiles[i] = c.first }
    fun redo() { for ((i, c) in changes) target.tiles[i] = c.second }
    val bytes: Long get() = changes.values.sumOf { (if (it.first != null) 1L else 0L) + (if (it.second != null) 1L else 0L) } * TILE * TILE * 4
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
}
