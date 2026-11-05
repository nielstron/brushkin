package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EffectPreviewTest {
    private fun checkPreview(append: Boolean) = runBlocking {
        val previous=Effects.factory
        Effects.factory={ ExposureFilter(1f,0f) }
        val engine=Engine()
        try {
            val layer=Layer(name="test",pixels=TiledBitmap.fromPixels(IntArray(64*64) { 0xff506070.toInt() },64,64),
                effects=listOf(LayerEffect("Exposure",listOf(1f))))
            engine.openStack(Stack(64,64,listOf(layer),layer.id),"test").join()
            engine.filterBegin(asEffect=append).join()
            val filter=LutFilter.invert()
            engine.filterPreview(filter)
            withTimeout(10000) { while(engine.preview == null) delay(10) }
            val expected=if(append) applyFilterBanded(Effects.pixelsSync(layer),filter,null)
                else applyFilterBanded(applyFilterBanded(layer.pixels,filter,null),ExposureFilter(1f,0f),null)
            assertArrayEquals("Preview must match the committed filter order",expected.toPixels(),engine.preview!!.tiles.toPixels())
            engine.filterApply(filter, afterEffects=append).join()
            val applied=engine.snapshot().active!!
            assertEquals(if(append) 0 else 1,applied.effects.size)
            assertArrayEquals(expected.toPixels(),Effects.pixelsSync(applied).toPixels())
            engine.undo().join()
            assertEquals(1,engine.snapshot().active!!.effects.size)
            assertEquals(0xff506070.toInt(),layer.pixels.toPixels()[0])
        } finally { engine.shutdown(); Effects.factory=previous }
    }
    @Test fun appendedEffectIncludesExistingEffects() = checkPreview(true)
    @Test fun pixelEditPreviewKeepsExistingEffectsAboveIt() = checkPreview(false)
}
