package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.ml.ObjectSelectClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times object selection without touching the UI (skipped when the model isn't downloaded).
 * Timings go to logcat (tag Brushkin). Run alone with:
 *   adb shell am instrument -w -e class io.github.nielstron.brushkin.ObjectSelectBenchmark \
 *     de.nielstron.brushkin.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ObjectSelectBenchmark {
    @Test fun timeEncoderAndDecoder() = runBlocking {
        val client = ObjectSelectClient(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeFalse("model not downloaded", client.needsDownload())
        repeat(3) { round ->
            // A different image each round so the encoder runs every time.
            val img = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
            Canvas(img).apply {
                drawColor(Color.rgb(230, 230, 230))
                drawRect(200f + 60f * round, 200f, 500f + 60f * round, 500f, Paint().apply { color = Color.RED })
                drawCircle(750f, 380f, 120f, Paint().apply { color = Color.BLUE })
            }
            client.prewarm(); val t = System.nanoTime(); val m = client.select(img, 350 + 60 * round, 350)!!; android.util.Log.i("Brushkin", "Benchmark round $round: first tap took ${(System.nanoTime() - t) / 1_000_000} ms")
            val inside = m.bytes[350 * m.width + 350 + 60 * round].toInt() != 0
            val outside = m.bytes[380 * m.width + 750].toInt() != 0
            android.util.Log.i("Brushkin", "Benchmark round $round: square selected=$inside, circle selected=$outside")
            client.select(img, 360, 360) // decoder only
        }
        client.close()
    }
}
