package io.github.nielstron.brushkin

import android.graphics.Color
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TiledBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeDownOpacityTest {
    @Test fun opaqueMarksStayOpaqueWhenMergedOntoATranslucentLayer() = runBlocking {
        val engine = Engine()
        try {
            val wash = Layer(name = "Wash", pixels = TiledBitmap.fromPixels(intArrayOf(Color.BLUE, Color.BLUE), 2, 1), opacity = 0.25f)
            val mark = Layer(name = "Mark", pixels = TiledBitmap.fromPixels(intArrayOf(Color.YELLOW, Color.TRANSPARENT), 2, 1))
            engine.openStack(Stack(2, 1, listOf(wash, mark), mark.id), "Postcard").join()
            val before = engine.flattened()
            engine.mergeDown(mark.id).join()

            val merged = engine.snapshot().layers.single()
            assertEquals(wash.name, merged.name)
            val after = engine.flattened()
            assertEquals(Color.YELLOW, after.getPixel(0, 0))
            assertEquals(before.getPixel(1, 0), after.getPixel(1, 0))

            engine.undo().join()
            assertEquals(listOf(wash.id, mark.id), engine.snapshot().layers.map { it.id })
            assertEquals(wash.opacity, engine.snapshot().layers.first().opacity, 0f)
        } finally {
            engine.shutdown()
        }
    }
}
