package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.BrushSettings
import io.github.nielstron.brushkin.engine.ColorBalanceFilter
import io.github.nielstron.brushkin.engine.DesaturateFilter
import io.github.nielstron.brushkin.engine.GaussianBlurFilter
import io.github.nielstron.brushkin.engine.HueSaturationFilter
import io.github.nielstron.brushkin.engine.LutFilter
import io.github.nielstron.brushkin.engine.NoiseFilter
import io.github.nielstron.brushkin.engine.PaintTool
import io.github.nielstron.brushkin.engine.PixelizeFilter
import io.github.nielstron.brushkin.engine.SelectMode
import io.github.nielstron.brushkin.engine.Selection
import io.github.nielstron.brushkin.engine.StrokeOp
import io.github.nielstron.brushkin.engine.StrokePoint
import io.github.nielstron.brushkin.engine.ThresholdFilter
import io.github.nielstron.brushkin.engine.TiledBitmap
import io.github.nielstron.brushkin.engine.UnsharpMaskFilter
import io.github.nielstron.brushkin.engine.floodFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Engine behaviour on a real device: every tool, filter and selection path. */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val w = 600; private val h = 400

    /** White left half, a red/blue stripe pattern on the right so effect tools have detail. */
    private fun canvas(): TiledBitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < w / 2) Color.WHITE else if ((x / 10 + y / 10) % 2 == 0) 0xFFB05A3C.toInt() else 0xFF3C6EA0.toInt()
        }
        return TiledBitmap.fromPixels(px, w, h)
    }

    private fun stroke(t: TiledBitmap, tool: PaintTool, s: BrushSettings = BrushSettings(size = 40f, hardness = 1f), color: Int = Color.BLACK,
                       sel: Selection? = null, clone: Point? = null, pts: List<StrokePoint> = listOf(StrokePoint(100f, 200f, 1f), StrokePoint(500f, 200f, 1f))) {
        val op = StrokeOp(t, tool, s, color, false, sel, clone)
        op.add(pts); op.catchUp(); op.finish()
    }

    @Test fun paintBlendsInLinearLight() {
        val t = canvas()
        // A single hard dab at 50% opacity: black over white is 50% linear light → sRGB ≈ 188.
        stroke(t, PaintTool.BRUSH, BrushSettings(size = 40f, hardness = 1f, opacity = 0.5f, pressureOpacity = false, smoothing = 0f), pts = listOf(StrokePoint(100f, 200f, 1f)))
        val c = t.toPixels()[200 * w + 100]
        assertTrue("got ${Color.red(c)}", abs(Color.red(c) - 188) <= 3)
    }

    @Test fun everyPaintToolChangesPixels() {
        for (tool in PaintTool.entries) {
            val t = canvas()
            val before = t.toPixels()
            val clone = if (tool == PaintTool.CLONE || tool == PaintTool.HEAL) Point(7, 15) else null
            // Effect tools act on the striped half.
            val pts = if (tool.isEffect || tool == PaintTool.SMUDGE) listOf(StrokePoint(330f, 150f, 1f), StrokePoint(560f, 260f, 1f)) else listOf(StrokePoint(60f, 200f, 1f), StrokePoint(560f, 200f, 1f))
            stroke(t, tool, BrushSettings(size = 50f, hardness = 0.8f, rate = 0.8f), Color.BLACK, clone = clone, pts = pts)
            val after = t.toPixels()
            val changed = before.indices.count { before[it] != after[it] }
            assertTrue("$tool changed $changed pixels", changed > 200)
        }
    }

    @Test fun strokesAreContinuousAcrossTiles() {
        val t = canvas()
        // Several input batches, like real touch events, crossing the tile border at x = 256.
        val op = StrokeOp(t, PaintTool.BRUSH, BrushSettings(size = 20f, hardness = 1f), Color.BLACK, false, null)
        for (x in 150..350 step 13) op.add(listOf(StrokePoint(x.toFloat(), 120f, 1f)))
        op.catchUp(); op.finish()
        val px = t.toPixels()
        for (x in 160..340) assertTrue("gap at x=$x: %08X".format(px[120 * w + x]), Color.red(px[120 * w + x]) < 60)
    }

    @Test fun selectionClipsStrokes() {
        val t = canvas()
        val sel = Selection.combine(null, Path().apply { addRect(0f, 0f, 200f, 400f, Path.Direction.CW) }, w, h, SelectMode.REPLACE, false, 0f)
        stroke(t, PaintTool.BRUSH, sel = sel)
        val px = t.toPixels()
        assertNotEquals(Color.WHITE, px[200 * w + 150])   // inside
        assertEquals(Color.WHITE, px[200 * w + 250])      // outside, still white
    }

    @Test fun everyFilterRuns() {
        val src = canvas().toPixels()
        val filters = listOf(
            LutFilter.curves(mapOf()), LutFilter.brightnessContrast(0.3f, 0.2f), LutFilter.levels(20f, 230f, 1.2f, 30f, 200f),
            LutFilter.invert(), LutFilter.posterize(4), HueSaturationFilter(90f, 0.2f, 0f), ColorBalanceFilter(0.2f, 0f, -0.1f),
            ThresholdFilter(100, 255), DesaturateFilter(), GaussianBlurFilter(12f), UnsharpMaskFilter(3f, 1f), PixelizeFilter(16), NoiseFilter(0.2f),
            io.github.nielstron.brushkin.engine.DropShadowFilter(10f, 10f, 6f, 0.6f, Color.BLACK),
            io.github.nielstron.brushkin.engine.VignetteFilter(0.8f, 0.5f, 0.8f, Color.BLACK),
            io.github.nielstron.brushkin.engine.MotionBlurFilter(20f, 30f),
            io.github.nielstron.brushkin.engine.ColorToAlphaFilter(Color.WHITE),
            io.github.nielstron.brushkin.engine.ShadowsHighlightsFilter(0.5f, 0.3f, 20f),
            io.github.nielstron.brushkin.engine.ExposureFilter(0.7f, 0f),
            io.github.nielstron.brushkin.engine.ColorizeFilter(200f, 0.5f, 0f),
            io.github.nielstron.brushkin.engine.ChannelMixerFilter(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), false),
            io.github.nielstron.brushkin.engine.AutoLevelsFilter("White Balance", true, 0.01f),
            io.github.nielstron.brushkin.engine.AutoLevelsFilter("Stretch Contrast", false, 0f),
            io.github.nielstron.brushkin.engine.NoiseReductionFilter(2),
            io.github.nielstron.brushkin.engine.RippleFilter(8f, 40f, false),
            io.github.nielstron.brushkin.engine.WhirlPinchFilter(120f, 0.3f, 1f),
            io.github.nielstron.brushkin.engine.LensDistortionFilter(0.4f, 0f, 0f),
        )
        for (f in filters) {
            val out = f.apply(src, w, h)
            assertEquals(f.name, src.size, out.size)
            if (f.name !in setOf("Curves", "Drop Shadow", "Noise Reduction")) assertTrue("${f.name} had no effect", src.indices.any { src[it] != out[it] })
        }
    }

    @Test fun bandedFiltersMatchWholeImage() {
        val t = canvas()
        val src = t.toPixels()
        // Blur ends early only at true image edges; inner band seams must be invisible.
        for (f in listOf(GaussianBlurFilter(9f), UnsharpMaskFilter(4f, 1.5f), PixelizeFilter(16), LutFilter.levels(20f, 230f, 1.2f, 30f, 200f))) {
            val whole = f.apply(src, w, h)
            val banded = io.github.nielstron.brushkin.engine.applyFilterBanded(t, f, null, bandPixels = w * 37).toPixels()
            val diff = src.indices.maxOf { i -> maxOf(abs(Color.red(whole[i]) - Color.red(banded[i])), abs(Color.blue(whole[i]) - Color.blue(banded[i]))) }
            assertTrue("${f.name} banded differs by $diff", diff <= 1)
        }
    }

    @Test fun dropShadowAppearsBesideContent() {
        // A small opaque square on transparency: the shadow shows up below-right of it.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 100..199 && y in 100..199) Color.RED else 0 }
        val out = io.github.nielstron.brushkin.engine.DropShadowFilter(20f, 20f, 0f, 1f, Color.BLACK).apply(px, w, h)
        assertEquals(Color.RED, out[150 * w + 150])
        assertEquals(255, Color.alpha(out[210 * w + 210]))
        assertEquals(0, Color.red(out[210 * w + 210]))
        assertEquals(0, Color.alpha(out[50 * w + 50]))
    }

    @Test fun noiseReductionSmoothsNoise() {
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val noisy = NoiseFilter(0.15f).apply(flat, w, h)
        val clean = io.github.nielstron.brushkin.engine.NoiseReductionFilter(3).apply(noisy, w, h)
        fun spread(p: IntArray) = p.map { Color.red(it) }.let { v -> val m = v.average(); v.sumOf { (it - m) * (it - m) } / v.size }
        assertTrue("${spread(clean)} vs ${spread(noisy)}", spread(clean) < spread(noisy) * 0.5)
    }

    @Test fun floodFillStopsAtEdges() {
        val px = canvas().toPixels()
        val m = floodFill(px, w, h, 10, 10, 15)
        assertEquals(-1, m[10 * w + 10].toInt())
        assertEquals(0, m[10 * w + 400].toInt())
    }

    @Test fun scissorsFollowEdges() {
        // A dark square on white: the live wire between two points on its top edge hugs the edge
        // even though the straight line would cut across white.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 100..400 && y in 100..300) Color.BLACK else Color.WHITE }
        val p = io.github.nielstron.brushkin.engine.Scissors.liveWire(px, w, h, 100, 120, 130, 100)
        // Every point stays within 2px of the square's boundary.
        var k = 0
        while (k < p.size) {
            val x = p[k]; val y = p[k + 1]
            val dEdge = minOf(abs(x - 100f), abs(y - 100f))
            assertTrue("($x,$y) off edge", dEdge <= 2.5f)
            k += 2
        }
    }

    @Test fun foregroundSelectFindsObject() {
        // Red disc on a blue background; a sloppy square outline around it.
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x - 300) * (x - 300) + (y - 200) * (y - 200) < 80 * 80) Color.RED else Color.BLUE }
        val rough = ByteArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 200..400 && y in 100..300) -1 else 0 }
        val m = io.github.nielstron.brushkin.engine.ForegroundSelect.extract(px, w, h, rough, 40)
        assertEquals(-1, m[200 * w + 300].toInt())        // center
        assertEquals(-1, m[200 * w + 375].toInt())        // just inside the disc edge
        assertEquals(0, m[110 * w + 210].toInt())         // corner of the outline: background
        assertEquals(0, m[200 * w + 390].toInt())         // just outside the disc
    }

    @Test fun symmetryMirrorsDabs() {
        val t = TiledBitmap(w, h)
        val sym = io.github.nielstron.brushkin.engine.Symmetry(io.github.nielstron.brushkin.engine.Symmetry.Kind.MANDALA, 300f, 200f, 4)
        val op = StrokeOp(t, PaintTool.BRUSH, BrushSettings(size = 20f, hardness = 1f, smoothing = 0f), Color.BLACK, false, null, null, sym)
        op.add(listOf(StrokePoint(400f, 200f, 1f))); op.finish()
        val px = t.toPixels()
        // 4-fold rotation around (300,200): dabs at (400,200), (300,300), (200,200), (300,100).
        for ((x, y) in listOf(400 to 200, 300 to 300, 200 to 200, 300 to 100)) assertTrue("no dab at $x,$y", Color.alpha(px[y * w + x]) > 200)
        assertEquals(0, Color.alpha(px[200 * w + 300]))
    }

    @Test fun dynamicsVaryDabs() {
        // Random size: dabs along a line differ in coverage width.
        val t = TiledBitmap(w, h)
        val op = StrokeOp(t, PaintTool.BRUSH, BrushSettings(size = 30f, hardness = 1f, spacing = 2f, smoothing = 0f, randomSize = 0.9f, pressureSize = false), Color.BLACK, false, null)
        op.add((0..8).map { StrokePoint(40f + it * 60f, 200f, 1f) }); op.finish()
        val px = t.toPixels()
        val heights = (0..8).map { k -> val x = 40 + k * 60; (170..230).count { y -> Color.alpha(px[y * w + x]) > 128 } }
        assertTrue(heights.toString(), heights.toSet().size > 2)
    }

    @Test fun generatedBrushShapes() {
        fun coverage(s: BrushSettings): Int {
            val tip = io.github.nielstron.brushkin.engine.TipCache.get(64f, s, false)
            val b = java.nio.ByteBuffer.allocate(tip.rowBytes * tip.height); tip.copyPixelsToBuffer(b)
            return b.array().count { (it.toInt() and 0xFF) > 128 }
        }
        val circle = coverage(BrushSettings(hardness = 1f))
        val square = coverage(BrushSettings(hardness = 1f, genShape = 1))
        val diamond = coverage(BrushSettings(hardness = 1f, genShape = 2))
        val star = coverage(BrushSettings(hardness = 1f, spikes = 5, aspect = 0.3f))
        // Areas relative to the circle: square 4/π, diamond 2/π, a thin 5-point star much less.
        assertTrue("$square $circle", abs(square / circle.toFloat() - 4 / Math.PI.toFloat()) < 0.08f)
        assertTrue("$diamond $circle", abs(diamond / circle.toFloat() - 2 / Math.PI.toFloat()) < 0.08f)
        assertTrue("$star $circle", star < circle * 0.75f && star > circle * 0.3f + 1)
    }
}
