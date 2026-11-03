package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Document-level operations through the engine thread. */
@RunWith(AndroidJUnit4::class)
class EngineOpsTest {
    @Test fun transformWholeImage() = runBlocking {
        val e = Engine()
        e.newDocument(200, 100, Color.WHITE).join()
        e.addLayer("Top").join()
        e.fillSelection(Color.RED).join() // whole top layer red
        e.liftActive(wholeImage = true).join()
        assertNotNull(e.floating)
        // A quarter turn: every layer rotates and the canvas becomes 100 × 200.
        e.commitFloating(Matrix().apply { setRotate(90f) }, "Rotate").join()
        val s = e.snapshot()
        assertEquals(100, s.width); assertEquals(200, s.height)
        assertEquals(2, s.layers.size)
        s.layers.forEach { assertEquals(100, it.pixels.width); assertEquals(200, it.pixels.height) }
        assertEquals(Color.RED, e.flattened().getPixel(50, 150))
        // Undo brings back the original size.
        e.undo().join()
        assertEquals(200, e.snapshot().width)
        e.shutdown()
    }
}
