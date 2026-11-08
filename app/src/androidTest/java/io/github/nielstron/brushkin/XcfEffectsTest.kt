package io.github.nielstron.brushkin

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.geglSpecs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class XcfEffectsTest {
    private val w = 32; private val h = 24
    private val px = IntArray(w * h) { 0xFF000000.toInt() or (it * 7 and 0xFFFFFF) }
    private fun layer(effects: List<LayerEffect>) = Layer(name = "L", pixels = TiledBitmap.fromPixels(px, w, h), effects = effects)
    private fun defaults(name: String) = geglSpecs.getValue(name).params.map { it.default }
    private fun roundTrip(l: Layer): Pair<ByteArray, Layer> {
        val out = ByteArrayOutputStream(); Xcf.write(Stack(w, h, listOf(l), l.id), out)
        return out.toByteArray() to Xcf.read(out.toByteArray()).all.single()
    }

    @Test fun effectsStayLiveAndPixelsAreNeverBaked() {
        val blur = LayerEffect("Gaussian Blur", defaults("Gaussian Blur").toMutableList().also { it[0] = 12f })
        val (bytes, back) = roundTrip(layer(listOf(blur)))
        assertEquals("gimp xcf v022", String(bytes, 0, 13))
        assertArrayEquals(px, back.pixels.toPixels())
        assertEquals(listOf(blur), back.effects)
    }

    @Test fun effectsGimpCannotRunKeepTheirPlaceInTheOrder() {
        val first = LayerEffect("Exposure", defaults("Exposure"))
        val posterize = LayerEffect("Posterize", defaults("Posterize"))
        val colorize = LayerEffect("Colorize", listOf(200f, 0.5f, 0f), visible = false)
        val old = LayerEffect("Gaussian Blur", listOf(4f), paramsVersion = 1)
        val last = LayerEffect("Unsharp Mask", defaults("Unsharp Mask"))
        val effects = listOf(first, posterize, colorize, old, last)
        assertEquals(effects, roundTrip(layer(effects)).second.effects)
    }

    @Test fun onlyBrushkinEffectsStayReadableByOlderGimp() {
        val colorize = LayerEffect("Colorize", listOf(200f, 0.5f, 0f))
        val (bytes, back) = roundTrip(layer(listOf(colorize)))
        assertEquals("gimp xcf v011", String(bytes, 0, 13))
        assertEquals(listOf(colorize), back.effects)
    }

    @Test fun textLayersSurvive() {
        val text = TextSpec("Hi", 48f)
        val back = roundTrip(layer(emptyList()).copy(text = text)).second
        assertEquals(text, back.text)
    }

    /** Autosaves from before XCF must still open. */
    @Test fun oldOraFilesOpen() {
        val png = Ora.encode(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), "png")
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("mimetype")); it.write("image/openraster".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("data/layer0.png")); it.write(png); it.closeEntry()
            it.putNextEntry(ZipEntry("stack.xml")); it.write("""<image version="0.0.5" w="$w" h="$h"><stack><layer name="Old" src="data/layer0.png" x="0" y="0" opacity="1" visibility="visible" composite-op="svg:src-over" brushkin-effects="[{&quot;name&quot;:&quot;Exposure&quot;,&quot;params&quot;:[1,0]}]"/></stack></image>""".toByteArray()); it.closeEntry()
        }
        val l = Ora.read(zip.toByteArray().inputStream()).all.single()
        assertEquals("Old", l.name)
        assertEquals(1, l.effects.single().paramsVersion)
        assertArrayEquals(px, l.pixels.toPixels())
    }
}
