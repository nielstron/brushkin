package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The copy-reduced GEGL paths (fused R/B swap, persistent per-layer buffers, tile-wise readback)
 * must give byte-identical tiles to Brushkin 0.6.3's whole-image path, which is kept as
 * [NativeGegl.renderLegacy] plus [legacyApply] (0.6.3's applyFilterBanded for whole-image filters).
 */
class GeglEquivalenceTest {
    private val tag = "GeglEquivalence"
    private val w = 700; private val h = 530 // edge tiles on both axes

    init { NativeGegl.prepare(InstrumentationRegistry.getInstrumentation().targetContext) }

    private fun legacyRender(g: GeglFilter, px: IntArray, w: Int, h: Int) =
        NativeGegl.renderLegacy(px, w, h, g.operation, g.parameters.keys.toTypedArray(), g.parameters.values.toDoubleArray(), NativeGegl.allowOpenCl)

    /** 0.6.3's applyFilterBanded for a whole-image filter (one band, no margin). */
    private fun legacyApply(target: TiledBitmap, g: GeglFilter, sel: Bitmap?): TiledBitmap {
        val w = target.width; val h = target.height
        val result = TiledBitmap(w, h, target.fill)
        val src = target.readRows(0, h)
        val res = legacyRender(g, src, w, h)
        val maskBand = sel?.let { Selection.maskBytes(Bitmap.createBitmap(it, 0, 0, w, h), w, h) }
        result.writeRows(0, maskBlend(src.copyOfRange(0, w * h), res.copyOfRange(0, w * h), maskBand, w, h), h)
        result.dropEmpty()
        return result
    }

    private fun filters(): List<Filter> = listOf(
        // point operations
        ExposureFilter(0.7f, 0.01f),
        GeglFilter("Levels", "gegl:levels", mapOf("in-low" to 0.05, "in-high" to 0.9)),
        GeglFilter("Value Invert", "gegl:value-invert"),
        // area operations
        GaussianBlurFilter(6f),
        GeglFilter("Box Blur", "gegl:box-blur", mapOf("radius" to 5.0)),
        GeglFilter("Median Blur", "gegl:median-blur", mapOf("radius" to 3.0)),
        MotionBlurFilter(15f, 30f),
        PixelizeFilter(8),
        WhirlPinchFilter(90f, 0.3f, 1f),
        // meta operations (sub-graphs)
        GeglFilter("High Pass", "gegl:high-pass"),
        UnsharpMaskFilter(3f, 0.8f),
        GeglFilter("Softglow", "gegl:softglow"),
        // global statistics, noise, and a source without an input pad
        GeglFilter("Stretch Contrast", "gegl:stretch-contrast"),
        GeglFilter("HSV Noise", "gegl:noise-hsv", mapOf("seed" to 42.0, "hue-distance" to 12.0)),
        GeglFilter("Checkerboard", "gegl:checkerboard", mapOf("x" to 16.0, "y" to 16.0)),
    )

    // ------------------------------------------------------------------ content

    private fun opaque(w: Int, h: Int) = TiledBitmap.fromPixels(IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val edge = if ((x / 37 + y / 23) % 2 == 0) 60 else 0
        Color.rgb((x * 255 / w + edge) % 256, (y * 255 / h) % 256, ((x xor y) and 255))
    }, w, h)

    /** Semi-transparent and fully transparent pixels, plus whole missing (null) tiles. */
    private fun transparent(w: Int, h: Int) = TiledBitmap.fromPixels(IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val a = when {
            x < 256 && y < 256 -> 0            // a missing tile
            (x / 16 + y / 16) % 3 == 0 -> 0    // transparent pixels inside tiles
            else -> (x + 2 * y) % 256
        }
        Color.argb(a, (x * 3) and 255, (y * 5) and 255, 200)
    }, w, h)

    /** Mask-like content: gray coverage over a white fill (white tiles are missing). */
    private fun mask(w: Int, h: Int) = TiledBitmap.fromPixels(IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (x < 300) MASK_WHITE else Color.rgb((x + y) % 256, (x + y) % 256, (x + y) % 256)
    }, w, h, MASK_WHITE)

    private fun selection(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { b ->
        Canvas(b).drawCircle(w / 2f, h / 2f, h / 2.5f, Paint().apply {
            shader = RadialGradient(w / 2f, h / 2f, h / 2.5f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
    }

    private fun assertSameTiles(label: String, expected: TiledBitmap, actual: TiledBitmap) {
        assertEquals("$label: size", expected.width to expected.height, actual.width to actual.height)
        assertEquals("$label: fill", expected.fill, actual.fill)
        val a = IntArray(TILE * TILE); val b = IntArray(TILE * TILE)
        for (i in expected.tiles.indices) {
            val e = expected.tiles[i]; val t = actual.tiles[i]
            if ((e == null) != (t == null)) fail("$label: tile $i is ${if (e == null) "missing" else "present"} in the old path only")
            if (e == null || t == null || e.sameAs(t)) continue
            e.getPixels(a, 0, TILE, 0, 0, TILE, TILE); t.getPixels(b, 0, TILE, 0, 0, TILE, TILE)
            val k = a.indices.firstOrNull { a[it] != b[it] }
            fail("$label: tile $i differs" + (k?.let { " at (${expected.tileX(i) + it % TILE}, ${expected.tileY(i) + it / TILE}): %08x vs %08x".format(a[it], b[it]) } ?: " in premultiplied memory"))
        }
    }

    /** Whether the old path gives the same result twice (OpenCL kernels need not be deterministic). */
    private fun deterministic(g: GeglFilter, t: TiledBitmap): Boolean {
        val px = t.toPixels()
        return legacyRender(g, px, t.width, t.height).contentEquals(legacyRender(g, px, t.width, t.height))
    }

    private inline fun <T> withOpenCl(gpu: Boolean, block: () -> T): T {
        NativeGegl.allowOpenCl = gpu
        try { return block() } finally { NativeGegl.allowOpenCl = true }
    }

    // ------------------------------------------------------------------ tests

    /** One-shot renders of plain arrays, including transparent pixels with leftover color. */
    @Test fun arrayRenderMatchesLegacy() {
        val rnd = java.util.Random(7)
        val px = IntArray(w * h) { rnd.nextInt() }.also { for (i in it.indices step 5) it[i] = it[i] and 0x00ffffff }
        for (gpu in listOf(false, true)) withOpenCl(gpu) {
            for (f in filters()) {
                val g = f.gegl!!
                val expected = legacyRender(g, px, w, h)
                val actual = g.apply(px, w, h)
                if (!expected.contentEquals(actual)) {
                    if (gpu && !legacyRender(g, px, w, h).contentEquals(expected)) { Log.w(tag, "${g.operation}: GPU not deterministic, skipped"); continue }
                    assertArrayEquals("${g.operation} gpu=$gpu", expected, actual)
                }
            }
        }
    }

    /** Tile-wise renders, temporary and persistent, with and without a selection, opaque/transparent/mask content. */
    @Test fun tiledRenderMatchesLegacy() {
        val contents = mapOf("opaque" to opaque(w, h), "transparent" to transparent(w, h), "mask" to mask(w, h))
        val sel = selection(w, h)
        for (gpu in listOf(false, true)) withOpenCl(gpu) {
            for ((name, t) in contents) for (f in filters()) {
                val g = f.gegl!!
                if (gpu && !deterministic(g, t)) { Log.w(tag, "${g.operation}: GPU not deterministic, skipped"); continue }
                val label = "${g.operation} $name gpu=$gpu"
                assertSameTiles("$label temporary", legacyApply(t, g, null), applyFilterBanded(t, f, null))
                assertSameTiles("$label persistent", legacyApply(t, g, null), applyFilterBanded(t, f, null, persistent = true))
                // Same layer again: nothing to upload.
                assertSameTiles("$label persistent+selection", legacyApply(t, g, sel), applyFilterBanded(t, f, sel, persistent = true))
                assertEquals("$label: second render uploads nothing", 0, LayerBuffers.lastUploadedTiles)
            }
            LayerBuffers.clear()
        }
    }

    /**
     * Live-effect sessions (fused uploads, tile-wise readback) against the old full render.
     * Sessions always allow OpenCL (createSession ignores [NativeGegl.allowOpenCl], as in 0.6.3),
     * so the reference must too: GPU and CPU paths of e.g. gegl:gaussian-blur differ by rounding.
     */
    @Test fun effectSessionsMatchLegacy() {
        withOpenCl(true) {
            for (f in filters()) {
                val g = f.gegl!!
                if (g.operation == "gegl:checkerboard") continue // no input to update
                if (!deterministic(g, transparent(w, h))) { Log.w(tag, "${g.operation}: GPU not deterministic, skipped"); continue }
                val renderer = f.incremental(w, h) ?: continue
                renderer.use {
                    val t = transparent(w, h)
                    fun verify(phase: String) = assertSameTiles("${g.operation}: $phase", legacyApply(t, g, null), renderer.render(t))
                    verify("initial")
                    val i = t.tiles.lastIndex - 1
                    val old = t.tiles[i]
                    t.tiles[i] = TiledBitmap.copyTile(old).apply { eraseColor(0x804488cc.toInt()) }; t.touched(i)
                    verify("paint")
                    t.tiles[i] = old; t.touched(i)
                    verify("undo")
                }
            }
        }
    }

    /**
     * The persistent buffer through the engine: strokes, undo/redo, clearing to empty tiles, layer
     * boundary and canvas resizes, duplicate, merge and a new engine (activity recreate).
     */
    @Test fun persistentBufferFollowsEdits() = runBlocking {
        withOpenCl(false) {
            val blur = GaussianBlurFilter(5f); val exposure = ExposureFilter(0.5f, 0f)
            val bg = Layer(name = "Background", pixels = opaque(w, h))
            val photo = Layer(name = "Photo", pixels = transparent(w, h))
            var e = Engine()
            try {
                e.openStack(Stack(w, h, listOf(bg, photo), photo.id), "test").join()
                suspend fun check(label: String, f: Filter, uploads: Int? = null) {
                    val before = e.snapshot().active!!.pixels.copy() // tiles are copy-on-write
                    val expected = legacyApply(before, f.gegl!!, null)
                    e.filterBegin().join(); e.filterApply(f).join()
                    assertSameTiles(label, expected, e.snapshot().active!!.pixels)
                    uploads?.let { assertEquals("$label: uploaded tiles", it, LayerBuffers.lastUploadedTiles) }
                    Log.i(tag, "$label uploaded=${LayerBuffers.lastUploadedTiles}")
                }
                check("first apply", blur)
                assertTrue(LayerBuffers.lastUploadedTiles > 0)
                e.undo().join()
                check("after undo", exposure, uploads = 0)
                e.undo().join()
                // A short stroke inside the top-right tile (index 2).
                e.strokeBegin(PaintTool.BRUSH, BrushSettings(size = 12f, hardness = 1f, smoothing = 0f), Color.RED).join()
                e.strokeAdd(listOf(StrokePoint(560f, 60f, 1f), StrokePoint(620f, 90f, 1f))).join()
                e.strokeEnd().join()
                check("after stroke", blur, uploads = 1)
                e.undo().join(); e.undo().join() // the blur, then the stroke
                e.redo().join() // the stroke again: the buffer still holds its tile
                check("after stroke redo", exposure, uploads = 0)
                e.undo().join(); e.undo().join() // the exposure, then the stroke
                check("after stroke undo", blur, uploads = 1)
                e.undo().join()
                e.selectAll().join(); e.clearSelection().join(); e.selectNone().join()
                check("after clearing", exposure)
                e.undo().join(); e.undo().join(); e.undo().join(); e.undo().join() // exposure, select none, clear, select all
                check("after clear undo", blur)
                e.undo().join()
                e.setLayerBoundary(Rect(100, 80, 600, 450)).join()
                check("after layer boundary", exposure)
                e.undo().join(); e.undo().join()
                e.canvasSize(800, 600, 30, 20).join()
                check("after canvas resize", blur)
                e.undo().join(); e.undo().join()
                check("after canvas resize undo", exposure)
                e.undo().join()
                e.duplicateLayer().join()
                check("duplicate", blur)
                e.undo().join()
                e.mergeDown(e.snapshot().active!!.id).join()
                check("merged", exposure)
                val doc = e.snapshot()
                e.shutdown()
                e = Engine()
                e.openStack(doc, "reopened").join()
                check("new engine", blur)
            } finally { e.shutdown() }
        }
    }
}
