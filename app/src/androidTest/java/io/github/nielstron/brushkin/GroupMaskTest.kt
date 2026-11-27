package io.github.nielstron.brushkin

import android.graphics.Color
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TiledBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GroupMaskTest {
    @Test fun mergeMaskedGroupBakesCombinedCoverageAndUndoesInOneStep() = runBlocking {
        val engine = Engine()
        try {
            fun tiles(vararg colors: Int) = TiledBitmap.fromPixels(colors, 3, 1)
            val lower = Layer(name = "Lower", pixels = tiles(Color.BLUE, Color.BLUE, Color.BLUE))
            val upper = Layer(name = "Upper", pixels = tiles(Color.RED, Color.RED, Color.TRANSPARENT))
            val group = Layer(
                name = "Window", pixels = TiledBitmap(3, 1), children = listOf(lower, upper),
                mask = tiles(Color.BLACK, Color.GRAY, Color.WHITE), editMask = true,
            )
            engine.openStack(Stack(3, 1, listOf(group), group.id), "Masked group").join()
            val before = engine.flattened()
            assertEquals(0, Color.alpha(before.getPixel(0, 0)))
            assertTrue(Color.alpha(before.getPixel(1, 0)) in 1..254)
            assertEquals(Color.BLUE, before.getPixel(2, 0))

            engine.applyMask().join()
            val baked = engine.snapshot().active!!
            assertTrue(before.sameAs(engine.flattened()))
            assertEquals(group.id, baked.id)
            assertFalse(baked.isGroup)
            assertNull(baked.mask)
            assertFalse(baked.editMask)

            engine.undo().join()
            val restored = engine.snapshot().active!!
            assertEquals(listOf(lower.id, upper.id), restored.children!!.map { it.id })
            assertArrayEquals(group.mask!!.toPixels(), restored.mask!!.toPixels())
            assertTrue(restored.editMask)
            assertTrue(before.sameAs(engine.flattened()))
            engine.redo().join()
            assertTrue(before.sameAs(engine.flattened()))
        } finally {
            engine.shutdown()
        }
    }
}
