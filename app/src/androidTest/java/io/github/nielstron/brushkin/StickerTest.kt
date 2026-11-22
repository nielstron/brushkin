package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.Distance
import io.github.nielstron.brushkin.engine.Sticker
import io.github.nielstron.brushkin.ml.LassoPrompt
import io.github.nielstron.brushkin.ml.ModelStore
import io.github.nielstron.brushkin.ml.ObjectSelectClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The Sticker tool's pieces: loop prompts, mask tidying, the outline, and (with the model) a loop around an object. */
@RunWith(AndroidJUnit4::class)
class StickerTest {
    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 72) = FloatArray(2 * n) { i ->
        val a = (i / 2) * 2 * Math.PI / n
        if (i % 2 == 0) cx + r * cos(a).toFloat() else cy + r * sin(a).toFloat()
    }

    @Test fun distanceTransformIsExact() {
        val w = 37; val h = 23
        val rnd = java.util.Random(1)
        val targets = BooleanArray(w * h) { rnd.nextInt(40) == 0 }
        val d = Distance.squared(w, h) { targets[it] }
        for (i in 0 until w * h) {
            var best = Float.MAX_VALUE
            for (j in 0 until w * h) if (targets[j]) { val dx = (i % w - j % w).toFloat(); val dy = (i / w - j / w).toFloat(); best = minOf(best, dx * dx + dy * dy) }
            assertEquals("pixel $i", best, d[i], 1e-3f)
        }
    }

    @Test fun loopAroundADiscMarksItsMiddleAndTheBoxCorners() {
        val (box, points) = LassoPrompt.of(circle(300f, 300f, 100f), 600, 600)
        assertEquals(200f, box.left, 2f); assertEquals(400f, box.right, 2f); assertEquals(200f, box.top, 2f); assertEquals(400f, box.bottom, 2f)
        val inside = points.filter { it.positive }; val outside = points.filter { !it.positive }
        assertEquals(1, inside.size)
        assertTrue("middle: ${inside[0].x},${inside[0].y}", hypot(inside[0].x - 300f, inside[0].y - 300f) < 8f)
        assertEquals(3, outside.size)
        for (p in outside) assertTrue("in a corner: ${p.x},${p.y}", hypot(p.x - 300f, p.y - 300f) > 104f && box.contains(p.x, p.y))
        // A loop running off the image: the box stays on it.
        val (clamped, _) = LassoPrompt.of(circle(20f, 20f, 100f), 600, 600)
        assertEquals(0f, clamped.left, 0f); assertEquals(0f, clamped.top, 0f)
        // A rectangle-ish loop has no corners outside it.
        assertTrue(LassoPrompt.of(floatArrayOf(10f, 10f, 200f, 10f, 200f, 100f, 10f, 100f), 600, 600).second.none { !it.positive })
    }

    @Test fun nearALoop() {
        val loop = circle(300f, 300f, 100f)
        assertTrue(LassoPrompt.near(loop, 300f, 300f, 0f))
        assertTrue(LassoPrompt.near(loop, 405f, 300f, 10f))
        assertTrue(!LassoPrompt.near(loop, 420f, 300f, 10f))
    }

    @Test fun tidyClipsToTheLoopDropsSpecksAndFillsPinholes() {
        val w = 200; val h = 200
        val m = ByteArray(w * h)
        fun fill(l: Int, t: Int, r: Int, b: Int, v: Byte) { for (y in t until b) for (x in l until r) m[y * w + x] = v }
        fill(40, 40, 160, 160, -1)   // the object
        fill(100, 100, 102, 102, 0)   // a pinhole in it
        fill(60, 10, 63, 13, -1)      // a speck inside the loop
        fill(185, 0, 200, 200, -1)    // something else, outside the loop
        val loop = floatArrayOf(30f, 5f, 170f, 5f, 170f, 170f, 30f, 170f)
        val t = Sticker.tidy(m, w, h, loop, 1f, 1f, 5f)!!
        fun on(x: Int, y: Int) = t[y * w + x].toInt() != 0
        assertTrue(on(50, 50) && on(150, 150))
        assertTrue("pinhole filled", on(100, 100))
        assertTrue("speck removed", !on(61, 11))
        assertTrue("outside the loop cut", !on(190, 100))
        // Halving the coordinates (a model mask at half the image size) halves the loop too.
        val half = ByteArray(100 * 100) { i -> if (i % 100 in 20 until 80 && i / 100 in 20 until 80) -1 else 0 }
        val th = Sticker.tidy(half, 100, 100, floatArrayOf(0f, 0f, 120f, 0f, 120f, 120f, 0f, 120f), 0.5f, 0.5f, 0f)!!
        assertTrue(th[70 * 100 + 50].toInt() == 0 && th[50 * 100 + 50].toInt() != 0)
        assertEquals(null, Sticker.tidy(ByteArray(w * h), w, h, null, 1f, 1f, 0f))
    }

    @Test fun outlineFollowsTheShapeAtTheRequestedWidth() {
        val cut = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(cut).drawCircle(50f, 50f, 40f, Paint().apply { color = Color.RED; isAntiAlias = true })
        val r = Sticker.render(cut, 10f, Color.WHITE, shadow = false)
        val c = 50 + r.pad
        assertEquals(100 + 2 * r.pad, r.bitmap.width)
        assertEquals(Color.RED, r.bitmap.getPixel(c, c))
        assertEquals("on the outline", Color.WHITE, r.bitmap.getPixel(c + 45, c))
        assertEquals("on the outline, diagonally", Color.WHITE, r.bitmap.getPixel(c + 32, c + 32))
        assertEquals("beyond it", 0, Color.alpha(r.bitmap.getPixel(c + 53, c)))
        val s = Sticker.render(cut, 10f, Color.WHITE, shadow = true)
        val cs = 50 + s.pad
        val below = s.bitmap.getPixel(cs, cs + 53)
        assertTrue("shadow under the outline: ${Integer.toHexString(below)}", Color.alpha(below) in 1..200 && Color.red(below) < 40)
        assertEquals(0, Color.alpha(s.bitmap.getPixel(0, 0)))
        val bare = Sticker.render(cut, 0f, Color.WHITE, shadow = false)
        assertEquals(0, Color.alpha(bare.bitmap.getPixel(50 + bare.pad + 45, 50 + bare.pad)))
    }

    /** Gray background, red square at (200…500, 200…500), blue disc around (750, 380) (as in ModelTest). */
    private fun scene() = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.rgb(200, 205, 210))
            drawRect(200f, 200f, 500f, 500f, Paint().apply { color = Color.rgb(220, 40, 30) })
            drawCircle(750f, 380f, 120f, Paint().apply { color = Color.rgb(30, 60, 220); isAntiAlias = true })
        }
    }

    @Test fun loopAroundAnObjectSelectsIt() = runBlocking {
        val client = ObjectSelectClient(ModelStore(InstrumentationRegistry.getInstrumentation().targetContext))
        assumeTrue("model not downloaded", ModelStore(InstrumentationRegistry.getInstrumentation().targetContext).ready(client.files))
        val img = scene()
        // A loose, slightly lopsided loop around the disc, and one around the square that cuts its corners.
        for ((loop, inObject) in listOf(
            circle(760f, 370f, 175f) to { x: Int, y: Int -> hypot(x - 750f, y - 380f) <= 120f },
            circle(350f, 350f, 200f) to { x: Int, y: Int -> x in 200 until 500 && y in 200 until 500 },
        )) {
            val (box, points) = LassoPrompt.of(loop, img.width, img.height)
            val r = client.select(img, points, box, smooth = true)
            val m = Sticker.tidy(r.mask!!.bytes, img.width, img.height, loop, 1f, 1f, 30f)!!
            var both = 0; var either = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val a = m[y * img.width + x].toInt() != 0; val b = inObject(x, y)
                if (a && b) both++
                if (a || b) either++
            }
            val iou = both / either.toFloat()
            android.util.Log.i("Brushkin", "StickerTest: loop IoU $iou")
            assertTrue("IoU $iou", iou > 0.95f)
        }
        client.close()
    }
}
