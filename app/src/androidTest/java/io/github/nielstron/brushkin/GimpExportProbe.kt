package io.github.nielstron.brushkin

import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.Guide
import io.github.nielstron.brushkin.engine.LayerExtent
import io.github.nielstron.brushkin.engine.SavedChannel
import io.github.nielstron.brushkin.engine.TiledBitmap
import io.github.nielstron.brushkin.engine.Xcf
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writes an XCF with every extension we use, for checking in GIMP (not an assertion test). */
@RunWith(AndroidJUnit4::class)
class GimpExportProbe {
    @Test fun writeProbe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Xcf.read(InstrumentationRegistry.getInstrumentation().context.assets.open("test8.xcf").readBytes())
        val l = base.all.first { it.name == "Red multiply" }
        val (px, outside) = LayerExtent.transform(l, Matrix().apply { setTranslate(-120f, 0f) }, base.width, base.height)
        val w = base.width; val h = base.height
        val chPx = IntArray(w * h) { i -> if (i % w < w / 2) -1 else 0xFF000000.toInt() }
        val st = base.replace(l.copy(pixels = px, outside = outside, linked = true, lockPosition = true))
            .copy(guides = listOf(Guide(true, 100), Guide(false, 50)), channels = listOf(SavedChannel(name = "Probe channel", mask = TiledBitmap.fromPixels(chPx, w, h, io.github.nielstron.brushkin.engine.MASK_BLACK))))
        File(ctx.filesDir, "probe.xcf").outputStream().use { Xcf.write(st, it) }
    }
}
