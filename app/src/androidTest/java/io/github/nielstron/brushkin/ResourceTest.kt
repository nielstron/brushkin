package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.BrushSettings
import io.github.nielstron.brushkin.engine.GimpFormats
import io.github.nielstron.brushkin.engine.GradientRes
import io.github.nielstron.brushkin.engine.PaintTool
import io.github.nielstron.brushkin.engine.PaletteRes
import io.github.nielstron.brushkin.engine.StrokeOp
import io.github.nielstron.brushkin.engine.StrokePoint
import io.github.nielstron.brushkin.engine.TiledBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

/** GIMP / MyPaint resource files and the tools that use them. */
@RunWith(AndroidJUnit4::class)
class ResourceTest {
    @Test fun gbrRoundTrip() {
        val mask = ByteArray(6 * 4) { (it * 10).toByte() }
        val b = GimpFormats.readGbr(GimpFormats.writeGbr("Dots", mask, 6, 4, spacing = 40), "x")
        assertEquals("Dots", b.name); assertEquals(0.4f, b.spacing, 1e-6f)
        val f = b.frames.single(); assertEquals(6, f.width); assertEquals(4, f.height)
        assertEquals(50, Color.alpha(f.getPixel(5, 0)))
    }

    @Test fun gihCells() {
        // A brush pipe: name line, parameter line, then two .gbr cells.
        val cell = GimpFormats.writeGbr("c", ByteArray(9) { -1 }, 3, 3)
        val bytes = "Pipe\n2 ncells:2 dim:1 rank0:2 sel0:random\n".toByteArray() + cell + cell
        val b = GimpFormats.readGih(bytes, "x")
        assertEquals("Pipe", b.name); assertEquals(2, b.frames.size)
    }

    @Test fun patternRoundTrip() {
        val bmp = Bitmap.createBitmap(intArrayOf(Color.RED, Color.GREEN, Color.BLUE, 0x80FFFFFF.toInt()), 2, 2, Bitmap.Config.ARGB_8888)
        val p = GimpFormats.readPat(GimpFormats.writePat("Check", bmp), "x")
        assertEquals("Check", p.name)
        assertEquals(Color.GREEN, p.bitmap.getPixel(1, 0)); assertEquals(0x80, Color.alpha(p.bitmap.getPixel(1, 1)))
    }

    @Test fun grayscalePattern() {
        // GIMP also writes 1-byte (gray) patterns.
        val name = "G\u0000".toByteArray()
        val b = ByteBuffer.allocate(24 + name.size + 2).putInt(24 + name.size).putInt(1).putInt(2).putInt(1).putInt(1).put("GPAT".toByteArray()).put(name).put(0).put(-1)
        val p = GimpFormats.readPat(b.array(), "x")
        assertEquals(Color.BLACK, p.bitmap.getPixel(0, 0)); assertEquals(Color.WHITE, p.bitmap.getPixel(1, 0))
    }

    @Test fun ggrParsesGimpFile() {
        // Two segments as GIMP writes them (the second one sine-blended, right color = BG).
        val text = """GIMP Gradient
Name: Sunset
2
0.000000 0.500000 0.400000 1.000000 0.000000 0.000000 1.000000 1.000000 1.000000 0.000000 1.000000 0 0
0.400000 0.700000 1.000000 1.000000 1.000000 0.000000 1.000000 0.000000 0.000000 1.000000 1.000000 2 0 0 3
"""
        val g = GimpFormats.readGgr(text, "x")
        assertEquals("Sunset", g.name); assertEquals(2, g.segments.size)
        val (cols, pos) = g.stops(Color.BLACK, Color.CYAN)
        assertEquals(Color.RED, cols.first()); assertEquals(Color.CYAN, cols.last())
        assertEquals(0f, pos.first(), 0f); assertEquals(1f, pos.last(), 0f)
        // Round trip through our writer.
        val back = GimpFormats.readGgr(GimpFormats.writeGgr(g), "y")
        assertEquals(g.segments.map { it.blend to it.k1 }, back.segments.map { it.blend to it.k1 })
    }

    @Test fun gplRoundTrip() {
        val text = "GIMP Palette\nName: Mine\nColumns: 4\n#\n255   0   0\tRed\n  0 128 255 Sky\n"
        val p = GimpFormats.readGpl(text, "x")
        assertEquals("Mine", p.name); assertEquals(listOf(Color.RED, Color.rgb(0, 128, 255)), p.colors)
        assertEquals(p.colors, GimpFormats.readGpl(GimpFormats.writeGpl(PaletteRes("Mine", p.colors)), "y").colors)
    }

    @Test fun mybMapsSettings() {
        val json = """{"version":3,"settings":{"radius_logarithmic":{"base_value":2.3,"inputs":{"pressure":[[0,0],[1,0.5]]}},"opaque":{"base_value":0.5},"hardness":{"base_value":0.4},"dabs_per_actual_radius":{"base_value":5},"elliptical_dab_ratio":{"base_value":3},"elliptical_dab_angle":{"base_value":30}}}"""
        val s = GimpFormats.readMyb(json, "Soft").settings
        assertTrue(s.size in 30f..60f); assertEquals(0.5f, s.opacity, 1e-3f); assertEquals(0.4f, s.hardness, 1e-3f)
        assertEquals(0.1f, s.spacing, 1e-3f); assertTrue(s.pressureSize); assertEquals(1 / 3f, s.aspect, 1e-3f); assertEquals(30f, s.angle, 1e-3f)
    }

    @Test fun gradientBlendFunctions() {
        val seg = GradientRes.Segment(0f, 0.25f, 1f, Color.BLACK, Color.WHITE, blend = 0)
        // The middle point maps to 50%.
        assertEquals(0.5f, GradientRes.blendFactor(seg, 0.25f), 1e-4f)
        for (b in 0..5) { val s = seg.copy(blend = b); assertEquals(0f, GradientRes.blendFactor(s, 0f), 1e-4f); assertEquals(1f, GradientRes.blendFactor(s, 1f), 1e-4f) }
    }

    @Test fun perspectiveCloneWithTranslationMatchesClone() {
        val w = 400; val h = 300
        val px = IntArray(w * h) { i -> val x = i % w; if ((x / 10) % 2 == 0) Color.RED else Color.BLUE }
        fun run(persp: Boolean): IntArray {
            val t = TiledBitmap.fromPixels(px, w, h)
            val m = android.graphics.Matrix().apply { setTranslate(-50f, 0f) } // source = dest − 50 px
            val op = StrokeOp(t, PaintTool.CLONE, BrushSettings(size = 40f, hardness = 1f, smoothing = 0f, pressureSize = false, pressureOpacity = false), Color.BLACK, false, null,
                android.graphics.Point(-50, 0), null, if (persp) m else null)
            op.add(listOf(StrokePoint(205f, 150f, 1f), StrokePoint(305f, 150f, 1f))); op.finish()
            return t.toPixels()
        }
        val a = run(false); val b = run(true)
        val diff = a.indices.count { abs(Color.red(a[it]) - Color.red(b[it])) > 8 }
        assertTrue("differs in $diff px", diff < 200)
        assertTrue(a.indices.any { a[it] != px[it] })
    }

    @Test fun patternEditing() {
        val ops = io.github.nielstron.brushkin.engine.PatternOps
        val chk = ops.generate(0, 8, Color.BLACK, Color.WHITE)
        assertEquals(16, chk.width); assertEquals(Color.BLACK, chk.getPixel(2, 2)); assertEquals(Color.WHITE, chk.getPixel(10, 2))
        // Offset by half swaps the checker's cells.
        assertEquals(Color.BLACK, ops.offsetHalf(chk).getPixel(10, 10))
        assertEquals(32, ops.scale(chk, 2f).width)
        // Seamless: a left-to-right ramp gets matching opposite edges.
        val ramp = Bitmap.createBitmap(IntArray(64 * 64) { i -> val v = (i % 64) * 4; Color.rgb(v, v, v) }, 64, 64, Bitmap.Config.ARGB_8888)
        val sm = ops.seamless(ramp)
        val jump = { b: Bitmap -> (0 until 64).maxOf { y -> abs(Color.red(b.getPixel(0, y)) - Color.red(b.getPixel(63, y))) } }
        assertTrue("before ${jump(ramp)} after ${jump(sm)}", jump(sm) < 20 && jump(ramp) > 200)
    }

    private fun freshStore(): Pair<io.github.nielstron.brushkin.engine.ResourceStore, java.io.File> {
        val dir = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "res-test-${System.nanoTime()}")
        return io.github.nielstron.brushkin.engine.ResourceStore(dir).also { it.load() } to dir
    }

    @Test fun paletteEditorPersists() {
        val (store, dir) = freshStore()
        // What the palette editor does: New → + (add FG) → long-press (remove).
        var p = store.savePalette(PaletteRes("Mine", listOf(Color.RED)))
        p = store.savePalette(PaletteRes(p.name, p.colors + Color.BLUE), p)
        p = store.savePalette(PaletteRes(p.name, p.colors + Color.GREEN), p)
        p = store.savePalette(PaletteRes(p.name, p.colors.filterIndexed { i, _ -> i != 1 }), p)
        assertEquals(listOf(Color.RED, Color.GREEN), p.colors)
        assertEquals(1, store.palettes.value.size)
        // Reloaded from disk (the app restarting) it's the same palette, as a GIMP .gpl file.
        val again = io.github.nielstron.brushkin.engine.ResourceStore(dir).also { it.load() }
        assertEquals(listOf("Mine"), again.palettes.value.map { it.name })
        assertEquals(listOf(Color.RED, Color.GREEN), again.palettes.value.single().colors)
        assertTrue(java.io.File(dir, p.file!!).readText().startsWith("GIMP Palette"))
    }

    @Test fun gradientEditorPersists() {
        val (store, dir) = freshStore()
        // Stops red@0, white@0.3, blue@1 → two linear segments (what the editor's Save does).
        val stops = listOf(0f to Color.RED, 0.3f to Color.WHITE, 1f to Color.BLUE)
        val segs = stops.zipWithNext { a, b -> GradientRes.Segment(a.first, (a.first + b.first) / 2, b.first, a.second, b.second) }
        val g = store.saveGradient(GradientRes("Flag", segs))
        val (cols, pos) = g.stops(0, 0)
        assertEquals(Color.WHITE, io.github.nielstron.brushkin.ui.sampleStops(cols, pos, 0.3f))
        // Editing it again replaces the same file instead of adding a copy.
        val g2 = store.saveGradient(GradientRes("Flag", segs.dropLast(1)), g)
        assertEquals(g.file, g2.file)
        val again = io.github.nielstron.brushkin.engine.ResourceStore(dir).also { it.load() }
        val loaded = again.gradients.value.single { it.name == "Flag" }
        assertEquals(1, loaded.segments.size)
        assertEquals(Color.WHITE, loaded.segments.single().c1)
    }

    @Test fun brushAndPatternSavesPersist() {
        val (store, dir) = freshStore()
        // "Save as brush" from the brush editor: a 5-point star tip.
        val tip = io.github.nielstron.brushkin.engine.TipCache.get(48f, BrushSettings(hardness = 1f, spikes = 5, aspect = 0.4f), false)
        val buf = ByteBuffer.allocate(tip.rowBytes * tip.height); tip.copyPixelsToBuffer(buf)
        val mask = ByteArray(48 * 48).also { for (y in 0 until 48) System.arraycopy(buf.array(), y * tip.rowBytes, it, y * 48, 48) }
        val b = store.saveBrush("Star", mask, 48, 48)
        assertEquals(b, store.brushByName["Star"])
        val p = store.savePattern("Dots", io.github.nielstron.brushkin.engine.PatternOps.generate(2, 10, Color.BLACK, Color.WHITE))
        val renamed = store.updatePattern(p, "Polka", io.github.nielstron.brushkin.engine.PatternOps.seamless(p.bitmap))
        val again = io.github.nielstron.brushkin.engine.ResourceStore(dir).also { it.load() }
        assertEquals(listOf("Star"), again.brushes.value.map { it.name })
        assertEquals(listOf("Polka"), again.patterns.value.map { it.name })
        assertEquals(renamed.bitmap.getPixel(5, 5), again.patterns.value.single().bitmap.getPixel(5, 5))
        // A brush made in the editor paints with its shape: the star covers less than a disc.
        val px = again.brushByName["Star"]!!.frames.single()
        val ab = ByteBuffer.allocate(px.rowBytes * px.height); px.copyPixelsToBuffer(ab)
        val covered = ab.array().count { (it.toInt() and 0xFF) > 128 }
        assertTrue("$covered", covered in 300..1500)
    }
}
