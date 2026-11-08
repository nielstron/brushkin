package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.ui.LayerDrag
import io.github.nielstron.brushkin.ui.LayerDrop
import io.github.nielstron.brushkin.ui.LayerSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerDragTest {
    private fun layer(id: Long, depth: Int = 0) = LayerSlot(id, depth, isGroup = false, expanded = false)
    private fun group(id: Long, depth: Int = 0, expanded: Boolean = true) = LayerSlot(id, depth, isGroup = true, expanded = expanded)

    // Top to bottom: 1, group 2 holding 3 and 4, 5.
    private val rows = listOf(layer(1), group(2), layer(3, 1), layer(4, 1), layer(5))

    @Test fun `a group travels with its contents`() {
        assertEquals(3, LayerDrag.blockSize(rows, 1))
        assertEquals(1, LayerDrag.blockSize(rows, 2))
    }

    @Test fun `not moving is a drop back in place`() {
        assertEquals(LayerDrop.Between(0, 0, null, 2L), LayerDrag.target(rows, 0, 0f, 0))
        assertEquals(LayerDrop.Between(3, 1, 2L, null), LayerDrag.target(rows, 3, 3f, 1))
    }

    @Test fun `dragging to the very top leaves a group`() {
        assertEquals(LayerDrop.Between(0, 0, null, 1L), LayerDrag.target(rows, 2, 0f, 1))
    }

    @Test fun `dragging between children enters the group`() {
        assertEquals(LayerDrop.Between(3, 1, 2L, 4L), LayerDrag.target(rows, 4, 3f, 0))
    }

    @Test fun `the middle of a group row drops into it, even closed`() {
        assertEquals(LayerDrop.Into(2L), LayerDrag.target(rows, 0, 0.5f, 0))
        val closed = listOf(layer(1), group(2, expanded = false), layer(5))
        assertEquals(LayerDrop.Into(2L), LayerDrag.target(closed, 2, 1.5f, 0))
    }

    @Test fun `below a group the sideways drag picks the level`() {
        assertEquals(LayerDrop.Between(4, 1, 2L, null), LayerDrag.target(rows, 4, 4f, 1))
        assertEquals(LayerDrop.Between(4, 0, null, null), LayerDrag.target(rows, 4, 4f, 0))
    }

    @Test fun `an empty open group accepts a row just below it`() {
        val empty = listOf(group(1), layer(2))
        assertEquals(LayerDrop.Between(1, 1, 1L, null), LayerDrag.target(empty, 1, 1f, 1))
        assertEquals(LayerDrop.Between(1, 0, null, null), LayerDrag.target(empty, 1, 1f, 0))
    }

    @Test fun `positions past the ends are clamped`() {
        assertEquals(LayerDrop.Between(0, 0, null, 1L), LayerDrag.target(rows, 4, -7f, 0))
        assertEquals(LayerDrop.Between(4, 0, null, null), LayerDrag.target(rows, 0, 99f, 0))
    }
}
