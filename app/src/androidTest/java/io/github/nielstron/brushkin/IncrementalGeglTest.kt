package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.geglSpecs
import org.junit.Assert.*
import org.junit.Test

class IncrementalGeglTest {
    @Test fun cachedFiltersMatchFullRenderAfterPaintingAndUndo() {
        val filters=listOf(GaussianBlurFilter(3f),GaussianBlurFilter(20f),ExposureFilter(1f,0f),MotionBlurFilter(9f,30f)) +
            geglSpecs.map { (_,spec) -> spec.build(spec.params.map { it.default }) }
        val w=289; val h=271
        for (filter in filters) {
            val renderer=filter.incremental(w,h) ?: continue
            renderer.use {
                val t=TiledBitmap.fromPixels(IntArray(w*h) { i ->
                    0xff000000.toInt() or ((i%w%256) shl 16) or ((i/w%256) shl 8) or 80
                },w,h)
                fun verify(phase: String) {
                    val actual=renderer.render(t).toPixels()
                    val expected=applyFilterBanded(t,filter,null).toPixels()
                    assertArrayEquals("${filter.name}: $phase",expected,actual)
                }
                verify("initial")
                val old=t.tiles.last()
                t.tiles[t.tiles.lastIndex]=TiledBitmap.copyTile(old)
                t.tiles.last()!!.eraseColor(0x804488cc.toInt()); t.touched(t.tiles.lastIndex)
                verify("paint at image boundary")
                t.tiles[t.tiles.lastIndex]=old; t.touched(t.tiles.lastIndex)
                verify("undo")
            }
        }
    }
}
