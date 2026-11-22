package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.ui.posToSize
import io.github.nielstron.brushkin.ui.sizeToPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushSizeMappingTest {
    private val positions = (0..1000).map { it / 1000f }

    @Test fun `endpoints are 1 and 1000`() {
        assertEquals(1f, posToSize(0f), 1e-4f)
        assertEquals(1000f, posToSize(1f), 1e-2f)
        assertEquals(0f, sizeToPos(1f), 1e-6f)
        assertEquals(1f, sizeToPos(1000f), 1e-6f)
    }

    @Test fun `position round-trips through size`() {
        for (p in positions) assertEquals(p, sizeToPos(posToSize(p)), 1e-5f)
    }

    @Test fun `a stored size shows and comes back unchanged`() {
        for (s in listOf(1f, 1.5f, 3f, 4f, 10f, 24f, 40f, 80f, 615f, 1000f)) assertEquals(s, posToSize(sizeToPos(s)), s * 1e-5f)
    }

    @Test fun `size grows by the same factor per step`() {
        val sizes = positions.map(::posToSize)
        for (i in 1 until sizes.size) assertTrue(sizes[i] > sizes[i - 1])
        val step = sizes[1] / sizes[0]
        for (i in 1 until sizes.size) assertEquals(step, sizes[i] / sizes[i - 1], 1e-4f)
    }
}
