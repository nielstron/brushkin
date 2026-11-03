package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.DepthBlurFilter
import io.github.nielstron.brushkin.ml.ImageModels
import io.github.nielstron.brushkin.ml.ModelFile
import io.github.nielstron.brushkin.ml.ModelStore
import io.github.nielstron.brushkin.ml.ObjectSelect
import io.github.nielstron.brushkin.ml.ObjectSelectClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The on-device models, without the UI. Each test is skipped when its model isn't in the cache
 * (download it in the app, or copy it to cache/models/<release>/). Timings go to logcat (tag
 * Brushkin). Run with:
 *   adb shell am instrument -w -e class io.github.nielstron.brushkin.ModelTest \
 *     de.nielstron.brushkin.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ModelTest {
    private val store = ModelStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun need(vararg m: ModelFile) = assumeTrue("model not downloaded", store.ready(m.toList()))
    private fun log(s: String) = android.util.Log.i("Brushkin", "ModelTest: $s")
    private inline fun <T> timed(what: String, f: () -> T): T { val t = System.nanoTime(); return f().also { log("$what ${(System.nanoTime() - t) / 1_000_000} ms") } }

    /** Gray background, red square at (200…500, 200…500), blue disc around (750, 380). */
    private fun scene(w: Int = 1024, h: Int = 768, dx: Int = 0) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.rgb(200, 205, 210))
            drawRect(200f + dx, 200f, 500f + dx, 500f, Paint().apply { color = Color.rgb(220, 40, 30) })
            drawCircle(750f, 380f, 120f, Paint().apply { color = Color.rgb(30, 60, 220); isAntiAlias = true })
        }
    }

    @Test fun objectSelectAndRefine() = runBlocking {
        val client = ObjectSelectClient(store)
        need(*client.files.toTypedArray())
        repeat(2) { round ->
            val img = scene(dx = 40 * round) // new image each round: the encoder runs every time
            client.prewarm()
            val r = timed("object select (round $round)") { client.select(img, listOf(ObjectSelect.Point(350f + 40 * round, 350f, true))) }
            val m = r.mask!!
            log("round $round: square ${m.bytes[350 * m.width + 350 + 40 * round]}, disc ${m.bytes[380 * m.width + 750]}, area ${m.bytes.count { it.toInt() != 0 }}")
            assertTrue(m.bytes[350 * m.width + 350 + 40 * round].toInt() != 0)
            assertTrue(m.bytes[380 * m.width + 750].toInt() == 0)
        }
        // Refining: also the disc, then take the disc away again.
        val img = scene(dx = 40)
        val p1 = ObjectSelect.Point(390f, 350f, true)
        val a = client.select(img, listOf(p1))
        val b = timed("refine +") { client.select(img, listOf(p1, ObjectSelect.Point(750f, 380f, true)), previous = a.logits) }
        assertTrue("disc added", b.mask!!.bytes[380 * 1024 + 750].toInt() != 0)
        val c = timed("refine -") { client.select(img, listOf(p1, ObjectSelect.Point(750f, 380f, true), ObjectSelect.Point(750f, 380f, false)), previous = b.logits) }
        assertTrue("disc removed", c.mask!!.bytes[380 * 1024 + 750].toInt() == 0)
        assertTrue("square kept", c.mask!!.bytes[350 * 1024 + 390].toInt() != 0)
        client.close()
    }

    @Test fun removeObject() = runBlocking {
        need(ImageModels.INPAINT)
        val models = ImageModels(store)
        val img = scene()
        // Remove the blue disc.
        val hole = ByteArray(1024 * 768) { i -> val x = i % 1024; val y = i / 1024; if ((x - 750) * (x - 750) + (y - 380) * (y - 380) < 125 * 125) -1 else 0 }
        val out = timed("inpaint") { models.inpaint(img, hole) }
        val c = out.getPixel(750, 380)
        log("inpainted center ${Integer.toHexString(c)}")
        assertTrue("disc gone", Color.blue(c) < 230 && abs(Color.red(c) - Color.blue(c)) < 60)
        assertEquals(img.getPixel(100, 100), out.getPixel(100, 100))
        models.close()
    }

    @Test fun denoiseDeblurUpscale() = runBlocking {
        val models = ImageModels(store)
        val rnd = java.util.Random(3)
        val img = scene(512, 384)
        val noisy = img.copy(Bitmap.Config.ARGB_8888, true).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val c = getPixel(x, y); fun n(v: Int) = (v + rnd.nextGaussian() * 25).toInt().coerceIn(0, 255)
                setPixel(x, y, Color.rgb(n(Color.red(c)), n(Color.green(c)), n(Color.blue(c))))
            }
        }
        fun err(a: Bitmap, b: Bitmap): Double { var s = 0.0; for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) s += abs(Color.red(a.getPixel(x, y)) - Color.red(b.getPixel(x, y))); return s / (a.width / 3 * a.height / 3) }
        if (store.ready(listOf(ImageModels.DENOISE))) {
            val d = timed("denoise 512×384") { models.denoise(noisy) {} }
            log("denoise error ${err(noisy, img)} -> ${err(d, img)}")
            assertTrue(err(d, img) < err(noisy, img) / 2)
        }
        if (store.ready(listOf(ImageModels.DEBLUR))) {
            val blurred = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(img, 128, 96, true), 512, 384, true)
            assertEquals(512, timed("deblur 512×384") { models.deblur(blurred) {} }.width)
        }
        if (store.ready(listOf(ImageModels.UPSCALE))) {
            val small = Bitmap.createScaledBitmap(img, 256, 192, true)
            val up = timed("upscale ×4 256×192") { models.upscale(small, 4) {} }
            assertEquals(1024, up.width); assertEquals(768, up.height)
            // The square's color survives.
            val c = up.getPixel(600, 600)
            log("upscaled square ${Integer.toHexString(c)}, background ${Integer.toHexString(up.getPixel(100, 100))}")
            assertTrue(Color.red(c) > 180 && Color.blue(c) < 80)
            assertEquals(512, timed("upscale ×2 256×192") { models.upscale(small, 2) {} }.width)
        }
        models.close()
    }

    /** Every tile of a larger image comes back sane (close to the input for a sharp, clean image). */
    @Test fun tilesAreConsistent() = runBlocking {
        val models = ImageModels(store)
        val img = scene(900, 700)
        Canvas(img).drawLine(0f, 0f, 900f, 700f, Paint().apply { color = Color.BLACK; strokeWidth = 6f })
        for ((name, m, f) in listOf(
            Triple("deblur", ImageModels.DEBLUR) { b: Bitmap -> runBlocking { models.deblur(b) {} } },
            Triple("denoise", ImageModels.DENOISE) { b: Bitmap -> runBlocking { models.denoise(b) {} } },
        )) {
            if (!store.ready(listOf(m))) continue
            val out = timed("$name 900×700") { f(img) }
            // Mean difference per 64 px block.
            var worst = 0.0
            for (by in 0 until 700 / 64) for (bx in 0 until 900 / 64) {
                var s = 0.0
                for (y in by * 64 until by * 64 + 64 step 4) for (x in bx * 64 until bx * 64 + 64 step 4) {
                    val a = img.getPixel(x, y); val b = out.getPixel(x, y)
                    s += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                }
                worst = maxOf(worst, s / (16 * 16 * 3))
            }
            log("$name worst block difference $worst")
            assertTrue("$name: a tile differs by $worst", worst < 25)
        }
        models.close()
    }

    @Test fun depthOfField() = runBlocking {
        need(ImageModels.DEPTH)
        val models = ImageModels(store)
        val img = scene()
        val d = timed("depth") { models.depth(img) }
        assertEquals(ImageModels.DEPTH_SIDE * ImageModels.DEPTH_SIDE, d.size)
        assertTrue(d.min() >= 0f && d.max() <= 1f)
        val px = IntArray(1024 * 768).also { img.getPixels(it, 0, 1024, 0, 0, 1024, 768) }
        val f = DepthBlurFilter(d, ImageModels.DEPTH_SIDE, ImageModels.DEPTH_SIDE, 0.34f, 0.45f, 30f, 0.1f)
        val out = timed("depth blur 1024×768") { f.apply(px, 1024, 768) }
        assertEquals(px.size, out.size)
        models.close()
    }
}
