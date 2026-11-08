package io.github.nielstron.brushkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.geglSpecs
import io.github.nielstron.brushkin.ui.sliderValue
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

    @Test fun gaussianBlurSliderIsGeneratedFromGegl() {
        val sigma=geglSpecs.getValue("Gaussian Blur").params.first()
        assertEquals(1.5f,sigma.default,0f)
        assertEquals(0.24f,sigma.range.start,1e-5f)
        assertEquals(100f,sigma.range.endInclusive,0f)
        assertEquals(3f,sigma.gamma,0f)
    }

    @Test fun effectsSavedBeforeGeglSlidersKeepTheirMeaning() {
        val src=IntArray(64) { i -> 0xff000000.toInt() or (i*4 shl 8) or 80 }
        val saved=LayerEffect.fromJson(org.json.JSONObject("""{"name":"Exposure","params":[1,0]}"""))
        assertArrayEquals(ExposureFilter(1f,0f).apply(src,8,8),io.github.nielstron.brushkin.ui.buildEffect(saved)!!.apply(src,8,8))
        assertEquals(2,LayerEffect.fromJson(LayerEffect("Exposure",emptyList()).toJson()).paramsVersion)
    }

    @Test fun colorToAlphaRemovesTheChosenColor() {
        val out=ColorToAlphaFilter(0xffffffff.toInt()).apply(intArrayOf(-1,0xff000000.toInt()),2,1)
        assertEquals(0,out[0] ushr 24)
        assertEquals(255,out[1] ushr 24)
    }

    @Test fun metadataReachesTheSettings() {
        val vignette=geglSpecs.getValue("Vignette").params
        assertEquals("Circle",vignette.first { it.key=="shape" }.choices.first().second)
        assertEquals("circle",vignette.first { it.key=="shape" }.nicks.first())
        val exposure=geglSpecs.getValue("Exposure").params.first { it.key=="black-level" }
        assertTrue("Black level needs more than 2 decimals: ${exposure.digits}",exposure.digits>2)
        assertTrue(geglSpecs.getValue("RGB Noise").params.first { it.key=="seed" }.seed)
        val blur=geglSpecs.getValue("Gaussian Blur").params
        assertEquals(mapOf(0 to 1),io.github.nielstron.brushkin.ui.axisPairs(blur))
        assertEquals("pixel-distance",blur[0].unit)
        assertFalse(blur[0].description.isBlank())
        assertFalse(geglSpecs.getValue("Gaussian Blur").description.isBlank())
        val lens=geglSpecs.getValue("Lens Blur").params
        val start=lens.indexOfFirst { it.role=="range-start" }
        assertTrue(io.github.nielstron.brushkin.ui.rangeEndAfter(lens,start))
        assertEquals("Highlight threshold",lens[start].meta["range-label"])
        val shadow=geglSpecs.getValue("Drop Shadow").colors.single { it.key=="color" }
        assertEquals(0xFF000000.toInt(),shadow.color)
    }

    @Test fun categoriesGroupTheMenu() {
        GeglOperations.loadMenuSections()
        assertEquals("Filters: Blur",GeglOperations.menuSection("Box Blur"))
        assertEquals("Filters: Tone mapping",GeglOperations.menuSection("Fattal Tone Mapping"))
    }

    @Test fun pickedColorReachesTheFilter() {
        val src=IntArray(64*64) { 0xffffffff.toInt() }
        val spec=geglSpecs.getValue("Vignette")
        val values=spec.params.map { it.default }
        val black=spec.build(values).apply(src,64,64)[0]
        val red=spec.build(values,mapOf("color" to 0xffff0000.toInt())).apply(src,64,64)[0]
        assertNotEquals(black,red)
    }

    /** Float can't hold bounds like 0.1 exactly, and every slider can reach its ends. */
    @Test fun everySliderEndIsAcceptedByGegl() {
        for ((name,spec) in geglSpecs) {
            val operation=GeglOperations.operation(name)!!
            for (p in spec.params) {
                val span=p.range.endInclusive-p.range.start
                if (p.choices.isNotEmpty() || p.toggle || !span.isFinite() || span<=0 || span>10000f) continue
                for (position in listOf(0f,1f)) {
                    val value=p.sliderValue(position,if (p.integral) 0 else p.digits)
                    try { NativeGegl.destroySession(NativeGegl.createSession(8,8,operation,arrayOf(p.key),doubleArrayOf(value.toDouble()),false)) }
                    catch (e: Exception) { throw AssertionError("$name ${p.key} at $value",e) }
                }
            }
        }
    }

    @Test fun everyGeglConditionEvaluates() {
        for ((name,spec) in geglSpecs) for (p in spec.params) {
            val current=spec.params.associate { it.key to it.default }
            try {
                io.github.nielstron.brushkin.ui.evaluateCondition(p.visibleWhen,"visible",p,spec.params,current)
                io.github.nielstron.brushkin.ui.evaluateCondition(p.enabledWhen,"sensitive",p,spec.params,current)
            } catch (e: Exception) { throw AssertionError("$name ${p.key}: ${p.visibleWhen} / ${p.enabledWhen}",e) }
        }
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
