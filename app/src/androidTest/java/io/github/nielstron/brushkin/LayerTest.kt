package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.LayerExtent
import io.github.nielstron.brushkin.engine.LayerMode
import io.github.nielstron.brushkin.engine.Ora
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TiledBitmap
import io.github.nielstron.brushkin.engine.Xcf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Layer extents (off-canvas content, smaller boundaries) and group modes. */
@RunWith(AndroidJUnit4::class)
class LayerTest {
    private val w = 600; private val h = 400

    private fun square(x: Int, y: Int, c: Int = Color.RED): Layer {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (yy in y until y + 50) for (xx in x until x + 50) b.setPixel(xx, yy, c)
        return Layer(name = "Sq", pixels = TiledBitmap.fromBitmap(b))
    }

    @Test fun contentMovedOffCanvasComesBack() {
        val l = square(20, 20)
        val (px, outside) = LayerExtent.transform(l, Matrix().apply { setTranslate(-300f, 0f) }, w, h)
        assertEquals(0, Color.alpha(px.toPixels()[45 * w + 45]))          // gone from the canvas…
        assertTrue(outside.isNotEmpty())                                     // …but kept outside
        val moved = l.copy(pixels = px, outside = outside)
        assertEquals(Rect(-280, 20, -230, 70), LayerExtent.bounds(moved))
        val (back, out2) = LayerExtent.transform(moved, Matrix().apply { setTranslate(300f, 0f) }, w, h)
        assertEquals(Color.RED, back.toPixels()[45 * w + 45])
        assertTrue(out2.isEmpty())
    }

    @Test fun extentsSurviveFiles() {
        val base = square(20, 20)
        val (px, outside) = LayerExtent.transform(base, Matrix().apply { setTranslate(-40f, -40f) }, w, h)
        val off = base.copy(pixels = px, outside = outside, linked = true, lockPosition = true)
        val small = square(300, 200, Color.BLUE).copy(name = "Small", bounds = Rect(280, 180, 380, 280))
        val stack = Stack(w, h, listOf(off, small), small.id)
        for (fmt in listOf("xcf")) {
            val out = ByteArrayOutputStream()
            Xcf.write(stack, out)
            val back = Xcf.read(out.toByteArray())
            val a = back.all.first { it.name == "Sq" }; val b = back.all.first { it.name == "Small" }
            assertEquals(fmt, Rect(-20, -20, 30, 30), LayerExtent.bounds(a))
            assertTrue(fmt, a.linked && a.lockPosition)
            assertEquals(fmt, Rect(280, 180, 380, 280), b.bounds)
            assertEquals(fmt, Color.BLUE, b.pixels.toPixels()[220 * w + 320])
        }
    }

    @Test fun passThroughGroupBlendsWithBelow() {
        // A multiply layer inside a pass-through group darkens the background; isolated, it can't.
        val bg = Layer(name = "bg", pixels = TiledBitmap.fromPixels(IntArray(w * h) { Color.YELLOW }, w, h))
        val mul = square(100, 100, Color.rgb(128, 128, 255)).copy(mode = LayerMode.MULTIPLY)
        fun flat(mode: LayerMode): Int {
            val g = Layer(name = "g", pixels = TiledBitmap(w, h), children = listOf(mul), mode = mode)
            return Compositor.flatten(Stack(w, h, listOf(bg, g), mul.id), Color.WHITE).getPixel(120, 120)
        }
        val through = flat(LayerMode.PASS_THROUGH); val isolated = flat(LayerMode.NORMAL)
        assertTrue("pass-through %08X".format(through), Color.blue(through) < 40 && Color.red(through) > 100)  // yellow × lavender
        assertEquals(Color.rgb(128, 128, 255), isolated or 0xFF000000.toInt())
        assertNotNull(through)
    }

    @Test fun effectsAreNonDestructive() {
        io.github.nielstron.brushkin.engine.Effects.factory = { io.github.nielstron.brushkin.engine.LutFilter.invert() }
        val l = square(100, 100, Color.RED).copy(effects = listOf(io.github.nielstron.brushkin.engine.LayerEffect("Invert", emptyList())))
        val flat = Compositor.flatten(Stack(w, h, listOf(l), l.id), Color.WHITE)
        assertEquals(Color.CYAN, flat.getPixel(120, 120))                 // shown inverted…
        assertEquals(Color.RED, l.pixels.toPixels()[120 * w + 120])        // …pixels untouched
        val hidden = l.copy(effects = l.effects.map { it.copy(visible = false) })
        assertEquals(Color.RED, Compositor.flatten(Stack(w, h, listOf(hidden), l.id), Color.WHITE).getPixel(120, 120))
        // Saved and loaded with the image, still editable.
        val out = ByteArrayOutputStream(); Xcf.write(Stack(w, h, listOf(l), l.id), out)
        assertEquals(l.effects, Xcf.read(out.toByteArray()).all.single().effects)
    }
}
