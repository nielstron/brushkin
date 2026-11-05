package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.engine.*
import org.junit.Assert.*
import org.junit.Test

class EffectSchedulingTest {
    @Test fun localStrokeReusesDistantFilteredTiles() {
        val previous = Effects.factory
        Effects.factory = { if (it.name == "Blur") GaussianBlurFilter(3f) else ExposureFilter(1f,0f) }
        try {
            val t = TiledBitmap.fromPixels(IntArray(1024*768) { 0xff708090.toInt() },1024,768)
            val l = Layer(name="test",pixels=t,effects=listOf(LayerEffect("Blur",emptyList()),LayerEffect("Exposure",emptyList())))
            val before=Effects.pixelsSync(l)
            t.tiles[0]=TiledBitmap.copyTile(t.tiles[0])
            t.tiles[0]!!.setPixel(120,120,0xffff0000.toInt()); t.touched(0)
            val start=System.nanoTime()
            val after=Effects.pixelsSync(l)
            android.util.Log.i("EffectBenchmark","incrementalMs="+(System.nanoTime()-start)/1e6)
            assertSame("Distant output tile should be retained",before.tiles.last(),after.tiles.last())
            val fullStart=System.nanoTime()
            val full=applyFilterBanded(applyFilterBanded(t,GaussianBlurFilter(3f),null),ExposureFilter(1f,0f),null)
            android.util.Log.i("EffectBenchmark","fullMs="+(System.nanoTime()-fullStart)/1e6)
            assertArrayEquals("Incremental output must match full filtering",full.toPixels(),after.toPixels())
        } finally { Effects.factory=previous }
    }

    @Test fun addingExposureReusesTheExistingBlurStage() {
        val previous=Effects.factory
        val built=mutableListOf<String>()
        Effects.factory={ built.add(it.name); if(it.name == "Blur") GaussianBlurFilter(3f) else ExposureFilter(1f,0f) }
        try {
            val source=TiledBitmap.fromPixels(IntArray(64*64) { 0xff607080.toInt() },64,64)
            val blurred=Layer(name="test",pixels=source,effects=listOf(LayerEffect("Blur",emptyList())))
            Effects.pixelsSync(blurred)
            val combined=Effects.pixelsSync(blurred.copy(effects=blurred.effects+LayerEffect("Exposure",emptyList())))
            assertEquals(listOf("Blur","Exposure"),built)
            assertArrayEquals(applyFilterBanded(applyFilterBanded(source,GaussianBlurFilter(3f),null),ExposureFilter(1f,0f),null).toPixels(),combined.toPixels())
        } finally { Effects.factory=previous }
    }

    @Test fun drawingDoesNotQueueOneFullRenderPerUpdate() {
        val previous = Effects.factory
        Effects.factory = { ExposureFilter(1f, 0f) }
        val t = TiledBitmap(16,16)
        val layer = Layer(name="test", pixels=t, effects=listOf(LayerEffect("Exposure",listOf(1f))))
        val jobs = mutableListOf<() -> Unit>()
        try {
            repeat(20) { t.touched(0); Effects.pixelsAsync(layer, { jobs.add(it) }, {}) }
            assertTrue("Indicate queued work",Effects.computing.value)
            val queued = jobs.size
            jobs.toList().forEach { it() }
            assertFalse("Clear indicator when finished",Effects.computing.value)
            assertEquals("Only one worker should serve the newest drawing state",1,queued)
            assertSame(Effects.pixelsSync(layer), Effects.pixelsAsync(layer, { fail("Already up to date") }, {}))
        } finally { Effects.factory = previous }
    }
}
