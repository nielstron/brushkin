package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.LayerMode
import io.github.nielstron.brushkin.engine.Ora
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.Xcf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Files made by GIMP 3.2 must render like GIMP's own flattened export, and our writers must round-trip. */
@RunWith(AndroidJUnit4::class)
class FileFormatTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    /** [skip]: region excluded from comparison. */
    private fun maxDiff(a: Bitmap, b: Bitmap, skip: android.graphics.Rect? = null): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var m = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            if (skip?.contains(x, y) == true) continue
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            m = maxOf(m, abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)))
        }
        return m
    }

    private fun check(name: String) {
        val stack = Xcf.read(assets.open("$name.xcf").readBytes())
        assertEquals(400, stack.width); assertEquals(300, stack.height)
        val names = stack.all.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("Background", "Red multiply", "My group", "Inside group")))
        val red = stack.all.first { it.name == "Red multiply" }
        assertEquals(LayerMode.MULTIPLY, red.mode)
        assertNotNull("mask", red.mask)
        assertTrue(stack.all.first { it.name == "My group" }.isGroup)
        val ref = BitmapFactory.decodeStream(assets.open("$name-flat.png"))
        val ours = Compositor.flatten(stack, Color.WHITE)
        // Brushkin composites in linear light like GIMP 2.10+, so the whole image must match.
        val d = maxDiff(ours, ref)
        val probe = listOf(20 to 20, 100 to 100, 175 to 100, 300 to 200, 175 to 180).joinToString { (x, y) -> "($x,$y) ours=%08X gimp=%08X".format(ours.getPixel(x, y), ref.getPixel(x, y)) }
        assertTrue("$name differs from GIMP by $d: $probe", d <= 6)

        // Round-trips through our own writers.
        for (fmt in listOf("xcf")) {
            val out = ByteArrayOutputStream()
            Xcf.write(stack, out)
            val back: Stack = Xcf.read(out.toByteArray())
            assertEquals(stack.all.map { it.name }, back.all.map { it.name })
            assertTrue(back.all.first { it.name == "Red multiply" }.mask != null)
            val d2 = maxDiff(Compositor.flatten(back, Color.WHITE), ours)
            assertTrue("$name $fmt round-trip differs by $d2", d2 <= 2)
        }
    }

    @Test fun guidesRoundTrip() {
        val stack = Xcf.read(assets.open("test8.xcf").readBytes()).copy(guides = listOf(io.github.nielstron.brushkin.engine.Guide(true, 120), io.github.nielstron.brushkin.engine.Guide(false, 77)))
        for (fmt in listOf("xcf")) {
            val out = ByteArrayOutputStream()
            Xcf.write(stack, out)
            val back: Stack = Xcf.read(out.toByteArray())
            assertEquals(fmt, stack.guides, back.guides)
        }
    }

    @Test fun readsGimpGuides() {
        // Written by GIMP 3: a vertical guide at x=20 and a horizontal one at y=30.
        val st = Xcf.read(assets.open("gimp-guides.xcf").readBytes())
        assertEquals(setOf(io.github.nielstron.brushkin.engine.Guide(true, 20), io.github.nielstron.brushkin.engine.Guide(false, 30)), st.guides.toSet())
    }

    @Test fun channelsAndSelectionRoundTrip() {
        val base = Xcf.read(assets.open("test8.xcf").readBytes())
        val sel = io.github.nielstron.brushkin.engine.Selection.combine(null, android.graphics.Path().apply { addRect(10f, 10f, 110f, 60f, android.graphics.Path.Direction.CW) }, base.width, base.height, io.github.nielstron.brushkin.engine.SelectMode.REPLACE, false, 0f)
        val px = IntArray(base.width * base.height) { i -> if (i % base.width < 200) Color.WHITE else Color.BLACK }
        val ch = io.github.nielstron.brushkin.engine.SavedChannel(name = "Left half", mask = io.github.nielstron.brushkin.engine.TiledBitmap.fromPixels(px, base.width, base.height, io.github.nielstron.brushkin.engine.MASK_BLACK), color = 0xFF3366CC.toInt(), opacity = 0.4f, visible = true)
        val stack = base.copy(selection = sel, channels = listOf(ch))
        for (fmt in listOf("xcf")) {
            val out = ByteArrayOutputStream()
            Xcf.write(stack, out)
            val back: Stack = Xcf.read(out.toByteArray())
            val c = back.channels.single()
            assertEquals(fmt, "Left half", c.name); assertEquals(fmt, 0xFF3366CC.toInt(), c.color); assertTrue(fmt, c.visible)
            assertEquals(fmt, Color.WHITE, c.mask.toPixels()[50 * base.width + 100]); assertEquals(fmt, Color.BLACK, c.mask.toPixels()[50 * base.width + 300])
            if (fmt == "xcf") assertEquals(android.graphics.Rect(10, 10, 110, 60), back.selection?.bounds)
        }
    }

    @Test fun gimp8bit() = check("test8")
    @Test fun gimp16bitLinear() = check("test16")
    @Test fun gimp64bitDouble() = check("test64")
}
