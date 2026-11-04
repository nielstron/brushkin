package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /** A series of direct transforms resamples the original once per drag: 3 × 10° equals 1 × 30°. */
    @Test fun directTransformsDontAccumulateBlur() = runBlocking {
        fun doc(): Engine = Engine().also { e ->
            runBlocking {
                e.newDocument(200, 200, null).join()
                // A sharp checkerboard: any extra resampling shows.
                e.fillSelection(Color.BLACK).join()
                for (y in 0 until 200 step 20) for (x in 0 until 200 step 20) if ((x + y) / 20 % 2 == 0) {
                    e.select(android.graphics.Path().apply { addRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, android.graphics.Path.Direction.CW) },
                        io.github.nielstron.brushkin.engine.SelectMode.REPLACE, false, 0f, "s").join()
                    e.fillSelection(Color.WHITE).join()
                }
                e.selectNone().join()
            }
        }
        suspend fun rotate(e: Engine, deg: Float) {
            e.liftActive(continueSession = true).join()
            val f = e.floating!!
            e.commitFloating(Matrix(f.initial).apply { postRotate(deg, 100f, 100f) }, "Rotate").join()
        }
        val a = doc(); repeat(3) { rotate(a, 10f) }
        val b = doc(); rotate(b, 30f)
        val pa = a.flattened(); val pb = b.flattened()
        var maxDiff = 0
        for (y in 0 until 200) for (x in 0 until 200) maxDiff = maxOf(maxDiff, kotlin.math.abs(Color.red(pa.getPixel(x, y)) - Color.red(pb.getPixel(x, y))))
        android.util.Log.i("Brushkin", "EngineOpsTest: 3x10° vs 30° max difference $maxDiff")
        assertTrue("3 × 10° differs from 30° by $maxDiff", maxDiff <= 2)
        a.shutdown(); b.shutdown()
    }
}
