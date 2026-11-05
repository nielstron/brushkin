package io.github.nielstron.brushkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.geglSpecs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GeglTest {
    @Test fun medianSliderUsesUiRangeInsteadOfAlgorithmLimits() {
        val props=org.json.JSONArray(NativeGegl.describe("gegl:median-blur"))
        val radius=(0 until props.length()).map { props.getJSONObject(it) }.first { it.getString("key")=="radius" }
        assertEquals(0.0,radius.getDouble("min"),0.0)
        assertEquals(100.0,radius.getDouble("max"),0.0)
    }

    @Test fun exposureUsesLinearLightAndPreservesStraightAlpha() {
        val src = intArrayOf(0xff808080.toInt(), 0x80808080.toInt(), 0xff000000.toInt())
        val out = ExposureFilter(1f, 0f).apply(src, 3, 1)
        // Doubling 0.216 linear-light intensity encodes to sRGB 175–176, not clipped 256.
        assertTrue((out[0] and 255) in 175..176)
        assertEquals(128, out[1] ushr 24)
        assertEquals(out[0] and 0xffffff, out[1] and 0xffffff)
        assertEquals(src[2], out[2])
    }

    @Test fun blurDoesNotBleedRgbFromFullyTransparentPixels() {
        val src = IntArray(33*33) { 0x000000ff }
        src[16*33+16] = 0xffff0000.toInt()
        val out = GaussianBlurFilter(2f).apply(src,33,33)
        assertTrue(out[16*33+16] ushr 24 in 1..254)
        out.filter { it ushr 24 > 0 }.forEach {
            assertTrue((it shr 16 and 255) >= 250)
            assertEquals(0, it and 255)
        }
    }

    @Test fun previewPixelBlocksNeverRoundDownToZero() {
        assertEquals(64, PixelizeFilter(1).scaled(0.1f).apply(IntArray(64) { -1 },8,8).size)
    }

    @Test fun catalogDefaultsRenderAndRemainVisible() {
        val w=32; val h=32
        val src=IntArray(w*h) { i -> 0xff000000.toInt() or ((i%w*8) shl 16) or ((i/w*8) shl 8) or 80 }
        for ((name,spec) in geglSpecs) {
            try {
                assertFalse("$name must apply its default settings",spec.defaultIsIdentity)
                val out=spec.build(spec.params.map { it.default }).apply(src,w,h)
                assertEquals(name,src.size,out.size)
                assertTrue("$name produced an empty result",out.any { it ushr 24 > 0 })
            } catch (e: Exception) { throw AssertionError("GEGL catalog: $name",e) }
        }
    }

    @Test fun fullImageFilteringMatchesEnginePathWithoutBandSeams() {
        val w=37; val h=1300
        val src=IntArray(w*h) { i -> 0xff000000.toInt() or ((i%256) shl 16) or ((i/w%256) shl 8) }
        val filter=MotionBlurFilter(9f,30f)
        val expected=filter.apply(src,w,h)
        val bitmap=android.graphics.Bitmap.createBitmap(src,w,h,android.graphics.Bitmap.Config.ARGB_8888)
        val tiled=TiledBitmap.fromBitmap(bitmap)
        val actual=applyFilterBanded(tiled,filter,null).toBitmap()
        val pixels=IntArray(w*h); actual.getPixels(pixels,0,w,0,0,w,h)
        val reference=android.graphics.Bitmap.createBitmap(expected,w,h,android.graphics.Bitmap.Config.ARGB_8888)
        val quantized=IntArray(w*h); reference.getPixels(quantized,0,w,0,0,w,h)
        assertArrayEquals(quantized,pixels)
        reference.recycle()
        bitmap.recycle(); actual.recycle()
    }
}
