package io.github.nielstron.brushkin.ui

import kotlin.math.floor
import kotlin.math.roundToInt

/** What dragging needs to know about a row of the layer list, which runs top-most first. */
data class LayerSlot(val id: Long, val depth: Int, val isGroup: Boolean, val expanded: Boolean)

/** Where a dragged row lands. */
sealed interface LayerDrop {
    /** In [gap] of the remaining rows, [depth] levels in: inside [parentId], directly above sibling [belowId]. */
    data class Between(val gap: Int, val depth: Int, val parentId: Long?, val belowId: Long?) : LayerDrop

    /** On top of the contents of the group [groupId], whether or not that group is open. */
    data class Into(val groupId: Long) : LayerDrop
}

object LayerDrag {
    /** Rows that travel together when [index] is dragged: the row and, for a group, its contents. */
    fun blockSize(rows: List<LayerSlot>, index: Int): Int {
        val depth = rows[index].depth
        var end = index + 1
        while (end < rows.size && rows[end].depth > depth) end++
        return end - index
    }

    /**
     * The drop for the row at [index] when its top edge is at [top] (in row heights, measured
     * over the rows that remain without the dragged block). The middle of a group row means
     * "into it"; [wantedDepth] picks the level wherever the neighbouring rows leave a choice.
     */
    fun target(rows: List<LayerSlot>, index: Int, top: Float, wantedDepth: Int): LayerDrop {
        val rest = rows.take(index) + rows.drop(index + blockSize(rows, index))
        val position = top.coerceIn(0f, rest.size.toFloat())
        val row = floor(position).toInt()
        if (row in rest.indices && rest[row].isGroup && position - row in 0.2f..0.8f) return LayerDrop.Into(rest[row].id)

        val gap = position.roundToInt()
        val above = rest.getOrNull(gap - 1)
        val min = rest.getOrNull(gap)?.depth ?: 0
        val max = when {
            above == null -> 0
            above.isGroup && above.expanded -> above.depth + 1
            else -> above.depth
        }
        val depth = wantedDepth.coerceIn(min, maxOf(min, max))
        val parent = if (depth == 0) null else rest.take(gap).lastOrNull { it.depth == depth - 1 }
        val below = rest.drop(gap).takeWhile { it.depth >= depth }.firstOrNull { it.depth == depth }
        return LayerDrop.Between(gap, depth, parent?.id, below?.id)
    }
}
