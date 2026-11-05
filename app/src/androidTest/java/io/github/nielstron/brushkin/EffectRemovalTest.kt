package io.github.nielstron.brushkin
import io.github.nielstron.brushkin.engine.*
import org.junit.Assert.*
import org.junit.Test
class EffectRemovalTest {
    @Test fun removeBlurFromLargeImage() {
        val previous=Effects.factory
        Effects.factory={ if(it.name=="Gaussian Blur") GaussianBlurFilter(50f) else ExposureFilter(1f,0f) }
        try {
            val source=TiledBitmap(4096,4096)
            source.tiles.indices.forEach { i -> source.tiles[i]=TiledBitmap.newTile().apply { eraseColor(0xff506070.toInt() + (i%16)*0x10000) } }
            val layer=Layer(name="large",pixels=source,effects=listOf(LayerEffect("Gaussian Blur",listOf(50f)),LayerEffect("Exposure",listOf(1f,0f))))
            Effects.pixelsSync(layer)
            android.util.Log.i("EffectRemoval","blur+exposure ready; pssKb="+android.os.Debug.getPss())
            val start=System.nanoTime()
            val result=Effects.pixelsSync(layer.copy(effects=layer.effects.drop(1)))
            android.util.Log.i("EffectRemoval","removed blur in ms="+(System.nanoTime()-start)/1e6+"; pssKb="+android.os.Debug.getPss())
            val expected=ExposureFilter(1f,0f).apply(intArrayOf(0xff506070.toInt()),1,1)[0]
            assertEquals(expected,result.tiles[0]!!.getPixel(0,0))
            assertSame(source,Effects.pixelsSync(layer.copy(effects=emptyList())))
        } finally { Effects.factory=previous }
    }
}
