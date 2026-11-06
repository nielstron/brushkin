package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.Frame
import io.github.nielstron.brushkin.ui.GlRenderer
import io.github.nielstron.brushkin.ui.buildEffect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sin

/**
 * Headless latency benchmark: every action is timed until the frame the screen would show is
 * finished (engine job joined, effect recomputes settled, GlRenderer frame + glFinish on an
 * offscreen ES3 pbuffer at screen size). Results go to logcat tag "BrushkinBench" and to
 * files/bench.txt of the app under test.
 *
 * Args: -e sizes 1024x768,3000x2000 -e scenarios draw,filter,live,layers,brush -e reps 3
 *       -e segments 40 -e liveSegments 10 -e liveVariants paintOnEffectLayer,blurOnly
 *       -e filterReps 3 -e filters gaussian-blur8,exposure -e screen 1440x3120
 */
class LatencyBenchmarkTest {
    private val tag = "BrushkinBench"
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(ctx.filesDir, "bench.txt")

    private fun arg(k: String, d: String) = args.getString(k, d)
    private fun log(s: String) { Log.i(tag, s); out.appendText(s + "\n") }

    private fun report(size: String, scenario: String, metric: String, v: List<Double>) {
        if (v.isEmpty()) return
        val s = v.sorted()
        fun q(p: Double) = s[((s.size - 1) * p).let { Math.round(it).toInt() }]
        log("RESULT %s %s %s n=%d median=%.1f p90=%.1f min=%.1f max=%.1f".format(size, scenario, metric, s.size, q(0.5), q(0.9), s.first(), s.last()))
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    /** CRC32 of every tile's pixels (missing tiles hash as absent), for before/after equivalence checks. */
    private fun hash(t: TiledBitmap): String {
        val crc = java.util.zip.CRC32()
        val buf = java.nio.ByteBuffer.allocate(TILE * TILE * 4)
        for (i in t.tiles.indices) {
            val tile = t.tiles[i]
            if (tile == null) { crc.update(i); continue }
            buf.clear(); tile.copyPixelsToBuffer(buf); crc.update(buf.array(), 0, buf.position())
        }
        return "%08x".format(crc.value)
    }
    private fun hashLog(size: String, what: String, h: String) = log("HASH $size $what $h")

    // ------------------------------------------------------------------ GL harness

    private class Gl(val w: Int, val h: Int, engine: Engine, docW: Int, docH: Int) : AutoCloseable {
        private val exec = Executors.newSingleThreadExecutor { Thread(it, "bench-gl") }
        val renderer = GlRenderer(engine)
        private lateinit var dpy: EGLDisplay; private lateinit var ctx: EGLContext; private lateinit var surf: EGLSurface
        fun <T> on(f: () -> T): T = exec.submit(Callable(f)).get()
        init {
            on {
                dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                EGL14.eglChooseConfig(dpy, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
                check(n[0] > 0)
                ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx))
                renderer.onSurfaceCreated(null, null)
                renderer.onSurfaceChanged(null, w, h)
            }
            // Fit the document on screen, as when an image is opened.
            val k = minOf(w / docW.toFloat(), h / docH.toFloat())
            val m = Matrix().apply { setScale(k, k); postTranslate((w - docW * k) / 2, (h - docH * k) / 2) }
            renderer.frame = Frame(m, k < 2f, null)
        }
        /** One finished frame (CPU submit + GPU completion), in ms. */
        fun frame(): Double = on {
            val t = System.nanoTime(); renderer.onDrawFrame(null); GLES30.glFinish(); (System.nanoTime() - t) / 1e6
        }
        /** CRC32 of the last rendered frame (glReadPixels of the pbuffer). */
        fun frameHash(): String = on {
            val b = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
            val a = ByteArray(w * h * 4); b.get(a)
            val c = java.util.zip.CRC32(); c.update(a); "%08x".format(c.value)
        }
        override fun close() {
            on {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            }
            exec.shutdown()
        }
    }

    // ------------------------------------------------------------------ content

    /** Photo-like opaque tiles (gradients + shapes), shared bitmaps copied per tile. */
    private fun pattern(w: Int, h: Int, alpha: Int = 255, seed: Int = 0): TiledBitmap {
        val t = TiledBitmap(w, h)
        val bases = List(4) { k ->
            TiledBitmap.newTile().also { b ->
                val c = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(0f, 0f, 256f, 256f, Color.argb(alpha, 40 + 50 * k, 120, 200 - 40 * k), Color.argb(alpha, 230, 90 + 30 * seed % 120, 60), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 256f, 256f, p)
                p.shader = null
                for (j in 0 until 12) {
                    p.color = Color.argb(alpha, (j * 37 + seed * 11) % 255, (j * 71 + k * 50) % 255, (j * 13 + 90) % 255)
                    c.drawCircle((j * 53 % 256).toFloat(), (j * 97 % 256).toFloat(), 10f + j * 3, p)
                }
            }
        }
        for (i in t.tiles.indices) { t.tiles[i] = bases[(i + seed) % 4].copy(Bitmap.Config.ARGB_8888, true); t.touched(i) }
        return t
    }

    private fun white(w: Int, h: Int) = TiledBitmap(w, h).also { t ->
        for (i in t.tiles.indices) t.tiles[i] = TiledBitmap.newTile().apply { eraseColor(Color.WHITE) }
    }

    /** A wavy stroke across the middle, [seg] batches of 4 points at a screen-constant speed. */
    private fun strokeBatches(w: Int, h: Int, seg: Int, row: Int): List<List<StrokePoint>> {
        val step = minOf(6f * w / 1024f, 0.8f * w / (seg * 4))
        val y0 = h * (0.3f + 0.1f * (row % 5))
        var time = 1000L
        var x = w * 0.1f
        return List(seg) {
            List(4) {
                x += step; time += 4
                StrokePoint(x, y0 + sin(x / (w / 8f)) * h * 0.05f, 1f, time = time)
            }
        }
    }

    // ------------------------------------------------------------------ scenarios

    private val segments get() = arg("segments", "40").toInt()
    private val reps get() = arg("reps", "3").toInt()

    private fun gc() { Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc() }

    private fun screen() = arg("screen", "1440x3120").split('x').map { it.toInt() }

    private fun withDoc(w: Int, h: Int, layers: List<Layer>, active: Long, block: (Engine, Gl) -> Unit) {
        val e = Engine()
        val (sw, sh) = screen()
        var gl: Gl? = null
        try {
            runBlocking { e.openStack(Stack(w, h, layers, active), "bench").join() }
            gl = Gl(sw, sh, e, w, h)
            val t0 = System.nanoTime(); val f = gl.frame()
            log("INFO ${w}x$h open firstFrame=%.1f total=%.1f layers=${layers.size}".format(f, ms(t0)))
            block(e, gl)
        } finally {
            gl?.close(); e.shutdown(); gc()
        }
    }

    private fun draw(w: Int, h: Int) {
        val size = "${w}x$h"
        val brushes = linkedMapOf(
            "brush24" to (PaintTool.BRUSH to BrushSettings(size = 24f, hardness = 0.3f)),
            "brush200" to (PaintTool.BRUSH to BrushSettings(size = 200f, hardness = 0.3f)),
            "mypaint" to (PaintTool.BRUSH to GimpFormats.readMyb(ResourceStore.BUILTIN_MYPAINT[0].second, "Pencil").settings),
        )
        val bg = Layer(name = "Background", pixels = white(w, h))
        val paint = Layer(name = "Paint", pixels = TiledBitmap(w, h))
        withDoc(w, h, listOf(bg, paint), paint.id) { e, gl ->
            for ((name, b) in brushes) {
                val (tool, s) = b
                val seg = mutableListOf<Double>(); val engine = mutableListOf<Double>(); val glMs = mutableListOf<Double>()
                val total = mutableListOf<Double>(); val end = mutableListOf<Double>(); val begin = mutableListOf<Double>(); val nextBegin = mutableListOf<Double>()
                for (r in 0..reps) {
                    val warm = r == 0
                    val ts = System.nanoTime()
                    runBlocking {
                        var t = System.nanoTime(); e.strokeBegin(tool, s, Color.rgb(30, 90, 120)).join(); if (!warm) begin.add(ms(t))
                        for (batch in strokeBatches(w, h, segments, r)) {
                            t = System.nanoTime()
                            e.strokeAdd(batch).join()
                            val eng = ms(t)
                            val g = gl.frame()
                            if (!warm) { engine.add(eng); glMs.add(g); seg.add(ms(t)) }
                        }
                        t = System.nanoTime(); e.strokeEnd().join(); gl.frame(); if (!warm) end.add(ms(t))
                        if (!warm) total.add(ms(ts))
                        // A new stroke 150 ms later waits behind the thumbnail job on the engine thread.
                        Thread.sleep(150)
                        t = System.nanoTime(); e.strokeBegin(tool, s, Color.RED).join(); if (!warm) nextBegin.add(ms(t))
                        e.strokeCancel().join()
                        Thread.sleep(400)
                    }
                }
                report(size, "draw/$name", "segmentToFrame", seg)
                report(size, "draw/$name", "segmentEngine", engine)
                report(size, "draw/$name", "segmentGlFrame", glMs)
                report(size, "draw/$name", "strokeBegin", begin)
                report(size, "draw/$name", "strokeEndToFrame", end)
                report(size, "draw/$name", "strokeTotal${segments}seg", total)
                report(size, "draw/$name", "beginAfter150ms", nextBegin)
            }
            val idle = List(5) { gl.frame() }
            report(size, "draw", "idleFrame2layers", idle)
            hashLog(size, "draw/paintLayer", hash(e.stack.find(paint.id)!!.pixels))
            hashLog(size, "draw/frame", gl.frameHash())
        }
    }

    private fun filters(w: Int, h: Int): Map<String, Filter> = linkedMapOf(
        "gaussian-blur8" to GaussianBlurFilter(8f),
        "box-blur8" to GeglFilter("Box Blur", "gegl:box-blur", mapOf("radius" to 8.0)),
        "motion-blur-linear" to MotionBlurFilter(20f, 30f),
        "whirl-pinch" to WhirlPinchFilter(90f, 0.3f, 1f),
        "noise-hsv" to GeglFilter("HSV Noise", "gegl:noise-hsv", mapOf("seed" to 42.0, "hue-distance" to 12.0)),
        "exposure" to ExposureFilter(0.7f, 0.01f),
        "levels-app" to LutFilter.levels(10f, 240f, 1.2f, 0f, 255f),
        "levels-gegl" to GeglFilter("Levels", "gegl:levels", mapOf("in-low" to 0.05, "in-high" to 0.9)),
        "median-blur5" to GeglFilter("Median Blur", "gegl:median-blur", mapOf("radius" to 5.0)),
        "high-pass" to GeglFilter("High Pass", "gegl:high-pass", emptyMap()),
    )

    private fun filter(w: Int, h: Int) {
        val size = "${w}x$h"
        val bg = Layer(name = "Background", pixels = white(w, h))
        val photo = Layer(name = "Photo", pixels = pattern(w, h))
        val selected = arg("filters", "").split(',').filter { it.isNotEmpty() }
        val n = arg("filterReps", "3").toInt()
        withDoc(w, h, listOf(bg, photo), photo.id) { e, gl ->
            for ((name, f) in filters(w, h)) {
                if (selected.isNotEmpty() && name !in selected) continue
                val gpuModes = if (f is LutFilter) listOf(false) else listOf(true, false)
                for (gpu in gpuModes) {
                    NativeGegl.allowOpenCl = gpu
                    val mode = if (f is LutFilter) "kotlin" else if (gpu) "gpu" else "cpu"
                    val apply = mutableListOf<Double>(); val applyEngine = mutableListOf<Double>(); val frame = mutableListOf<Double>()
                    val undo = mutableListOf<Double>(); val begin = mutableListOf<Double>(); var usedCl = false
                    try {
                        for (r in 0..n) {
                            runBlocking {
                                var t = System.nanoTime(); e.filterBegin().join(); val b = ms(t)
                                t = System.nanoTime(); e.filterApply(f).join(); val eng = ms(t)
                                usedCl = usedCl || NativeGegl.lastRenderUsedOpenCl()
                                val fr = gl.frame(); val tot = ms(t)
                                if (r == 0) {
                                    hashLog(size, "filter/$name/$mode/layer", hash(e.stack.find(photo.id)!!.pixels))
                                    hashLog(size, "filter/$name/$mode/frame", gl.frameHash())
                                }
                                Thread.sleep(300)
                                t = System.nanoTime(); e.undo().join(); gl.frame(); val u = ms(t)
                                if (r > 0) { apply.add(tot); applyEngine.add(eng); frame.add(fr); undo.add(u); begin.add(b) }
                                Thread.sleep(300)
                            }
                        }
                    } catch (t: Throwable) {
                        log("ERROR $size filter/$name/$mode ${t.javaClass.simpleName}: ${t.message}")
                    } finally { NativeGegl.allowOpenCl = true }
                    report(size, "filter/$name/$mode", "applyToFrame", apply)
                    report(size, "filter/$name/$mode", "applyEngine", applyEngine)
                    report(size, "filter/$name/$mode", "applyGlFrame", frame)
                    report(size, "filter/$name/$mode", "filterBegin", begin)
                    report(size, "filter/$name/$mode", "undoToFrame", undo)
                    log("INFO $size filter/$name/$mode usedOpenCl=$usedCl")
                    gc()
                }
            }
        }
    }

    /** Frames until no effect recompute is pending; returns total and the time spent waiting on effects. */
    private fun settle(gl: Gl): Pair<Double, Double> {
        var waited = 0.0
        var frames = 0.0
        while (true) {
            frames += gl.frame()
            if (!Effects.computing.value) break
            val t = System.nanoTime()
            while (Effects.computing.value) Thread.sleep(1)
            waited += ms(t)
        }
        return frames to waited
    }

    private fun live(w: Int, h: Int) {
        val size = "${w}x$h"
        val previous = Effects.factory
        Effects.factory = { buildEffect(it) }
        val fx = listOf(LayerEffect("Gaussian Blur", listOf(8f)), LayerEffect("Levels", listOf(10f, 240f, 1.2f, 0f, 255f)))
        val seg = arg("liveSegments", "10").toInt()
        try {
            val only = arg("liveVariants", "").split(',').filter { it.isNotEmpty() }
            for (variant in listOf("paintOnEffectLayer", "paintBelowEffectLayer", "blurOnly", "levelsOnly")) {
                if (only.isNotEmpty() && variant !in only) continue
                val bg = Layer(name = "Background", pixels = pattern(w, h, seed = 1))
                val effects = when (variant) { "blurOnly" -> fx.take(1); "levelsOnly" -> fx.drop(1); else -> fx }
                val top = Layer(name = "Effect", pixels = pattern(w, h, alpha = 160, seed = 2), effects = effects)
                val active = if (variant == "paintBelowEffectLayer") bg.id else top.id
                withDoc(w, h, listOf(bg, top), active) { e, gl ->
                    var t = System.nanoTime(); settle(gl); log("INFO $size live/$variant initialEffect=%.1f".format(ms(t)))
                    val total = mutableListOf<Double>(); val engine = mutableListOf<Double>(); val wait = mutableListOf<Double>(); val frames = mutableListOf<Double>()
                    val end = mutableListOf<Double>()
                    for (r in 0..reps) {
                        runBlocking {
                            e.strokeBegin(PaintTool.BRUSH, BrushSettings(size = 24f, hardness = 0.3f), Color.BLACK).join()
                            for (batch in strokeBatches(w, h, seg, r)) {
                                t = System.nanoTime()
                                e.strokeAdd(batch).join(); val eng = ms(t)
                                val (fr, wt) = settle(gl)
                                if (r > 0) { total.add(ms(t)); engine.add(eng); wait.add(wt); frames.add(fr) }
                            }
                            t = System.nanoTime(); e.strokeEnd().join(); settle(gl); if (r > 0) end.add(ms(t))
                            Thread.sleep(400)
                        }
                    }
                    settle(gl)
                    hashLog(size, "live/$variant/effectLayer", hash(Effects.pixelsSync(e.stack.find(top.id)!!)))
                    hashLog(size, "live/$variant/frame", gl.frameHash())
                    report(size, "live/$variant", "segmentToFinal", total)
                    report(size, "live/$variant", "segmentEngine", engine)
                    report(size, "live/$variant", "effectWait", wait)
                    report(size, "live/$variant", "glFrames", frames)
                    report(size, "live/$variant", "strokeEndToFinal", end)
                }
            }
        } finally { Effects.factory = previous }
    }

    private fun layers(w: Int, h: Int) {
        val size = "${w}x$h"
        val modes = listOf(LayerMode.NORMAL, LayerMode.MULTIPLY, LayerMode.SCREEN, LayerMode.OVERLAY, LayerMode.NORMAL,
            LayerMode.DARKEN, LayerMode.SOFT_LIGHT, LayerMode.NORMAL, LayerMode.DIFFERENCE, LayerMode.NORMAL)
        val ls = modes.mapIndexed { i, m ->
            Layer(name = "L$i", pixels = if (i == 0) pattern(w, h) else pattern(w, h, alpha = 140, seed = i), mode = m, opacity = if (i == 4) 0.7f else 1f)
        }
        val active = ls.last().id
        withDoc(w, h, ls, active) { e, gl ->
            report(size, "layers10", "idleFrame", List(8) { gl.frame() })
            val small = mutableListOf<Double>(); val smallEng = mutableListOf<Double>()
            val smallFrame = mutableListOf<Double>(); val repeatFrame = mutableListOf<Double>()
            val full = mutableListOf<Double>(); val fullUndo = mutableListOf<Double>(); val opacity = mutableListOf<Double>()
            val brush = BrushSettings(size = 24f, hardness = 0.3f)
            for (r in 0..reps) {
                runBlocking {
                    e.strokeBegin(PaintTool.BRUSH, brush, Color.BLUE).join()
                    for (batch in strokeBatches(w, h, 10, r)) {
                        val t = System.nanoTime(); e.strokeAdd(batch).join(); val eng = ms(t); val fr = gl.frame()
                        val again = gl.frame()
                        if (r > 0) { small.add(eng + fr); smallEng.add(eng); smallFrame.add(fr); repeatFrame.add(again) }
                    }
                    e.strokeEnd().join(); gl.frame(); Thread.sleep(400)
                    e.setActive(ls[5].id).join()
                    e.filterBegin().join()
                    var t = System.nanoTime(); e.filterApply(LutFilter.invert()).join(); gl.frame(); if (r > 0) full.add(ms(t))
                    Thread.sleep(300)
                    t = System.nanoTime(); e.undo().join(); gl.frame(); if (r > 0) fullUndo.add(ms(t))
                    t = System.nanoTime(); e.setLayer(ls[3].id, "Opacity") { it.copy(opacity = if (r % 2 == 0) 0.5f else 1f) }.join(); gl.frame(); if (r > 0) opacity.add(ms(t))
                    e.setActive(active).join()
                    Thread.sleep(300)
                }
            }
            gl.frame()
            hashLog(size, "layers10/frame", gl.frameHash())
            report(size, "layers10", "smallEditToFrame", small)
            report(size, "layers10", "smallEditEngine", smallEng)
            report(size, "layers10", "smallEditGlFrame", smallFrame)
            report(size, "layers10", "unchangedFrameAfterEdit", repeatFrame)
            report(size, "layers10", "fullLayerInvertToFrame", full)
            report(size, "layers10", "undoFullLayerToFrame", fullUndo)
            report(size, "layers10", "opacityChangeToFrame", opacity)
        }
    }

    /** Brush-size change: the first dab of a stroke with a new tip (cache miss) vs the same tip. */
    private fun brush(w: Int, h: Int) {
        val size = "${w}x$h"
        val bg = Layer(name = "Background", pixels = white(w, h))
        withDoc(w, h, listOf(bg), bg.id) { e, gl ->
            for (d in listOf(24f, 200f, 600f, 1000f)) {
                val miss = mutableListOf<Double>(); val hit = mutableListOf<Double>()
                for (r in 0..reps) runBlocking {
                    val s = BrushSettings(size = d, hardness = 0.31f + r * 0.01f)
                    val pt = listOf(StrokePoint(w / 2f, h / 2f, 1f))
                    var t = System.nanoTime(); e.strokeBegin(PaintTool.BRUSH, s, Color.BLACK).join(); e.strokeAdd(pt).join(); gl.frame(); if (r > 0) miss.add(ms(t))
                    e.strokeCancel().join()
                    t = System.nanoTime(); e.strokeBegin(PaintTool.BRUSH, s, Color.BLACK).join(); e.strokeAdd(pt).join(); gl.frame(); if (r > 0) hit.add(ms(t))
                    e.strokeCancel().join(); gl.frame()
                }
                runBlocking {
                    e.strokeBegin(PaintTool.BRUSH, BrushSettings(size = d, hardness = 0.3f), Color.BLACK).join()
                    e.strokeAdd(listOf(StrokePoint(w / 2f + d / 3, h / 2f + d / 5, 1f))).join(); e.strokeEnd().join(); gl.frame()
                }
                hashLog(size, "brushSize/${d.toInt()}/layer", hash(e.stack.find(bg.id)!!.pixels))
                report(size, "brushSize/${d.toInt()}", "firstDabNewTip", miss)
                report(size, "brushSize/${d.toInt()}", "firstDabCachedTip", hit)
            }
        }
    }

    @Test fun latency() {
        NativeGegl.prepare(ctx)
        val sizes = arg("sizes", "1024x768,3000x2000,4000x3000").split(',').map { s -> s.split('x').map { it.toInt() } }
        val scenarios = arg("scenarios", "draw,filter,live,layers,brush").split(',')
        log("START ${java.util.Date()} sizes=$sizes scenarios=$scenarios reps=$reps segments=$segments openCl=${NativeGegl.isOpenClActive()} screen=${screen()}")
        for ((w, h) in sizes) for (sc in scenarios) {
            val t = System.nanoTime()
            try {
                when (sc) {
                    "draw" -> draw(w, h); "filter" -> filter(w, h); "live" -> live(w, h)
                    "layers" -> layers(w, h); "brush" -> brush(w, h)
                }
            } catch (t: Throwable) {
                log("ERROR ${w}x$h $sc ${t.javaClass.simpleName}: ${t.message}")
                Log.e(tag, "failed", t)
            }
            log("DONE ${w}x$h $sc in %.1f s".format(ms(t) / 1000))
        }
        log("END")
    }
}
