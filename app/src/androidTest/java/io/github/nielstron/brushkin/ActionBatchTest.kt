package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionBatchTest {
    private fun layer(name: String,color: Int) = Layer(name=name,pixels=TiledBitmap.fromPixels(IntArray(64){color},8,8))
    @Test fun visibleCopiesAndMergesPreserveHiddenLayersAndUndo() = runBlocking {
        val e=Engine()
        try {
            val visible=layer("Visible",0x800000ff.toInt());val hidden=layer("Hidden",Color.RED).copy(visible=false)
            val nested=layer("Nested",Color.GREEN).copy(visible=false)
            val group=Layer(name="Group",pixels=TiledBitmap(8,8),children=listOf(visible,nested))
            e.openStack(Stack(8,8,listOf(hidden,group),visible.id),"test").join()
            e.newFromVisible().join();assertEquals(3,e.snapshot().layers.size)
            assertEquals(128,e.snapshot().active!!.pixels.toPixels()[0] ushr 24)
            e.undo().join();e.mergeVisible().join()
            val merged=e.snapshot();assertNotNull(merged.find(hidden.id));assertNotNull(merged.find(nested.id));assertNull(merged.find(visible.id))
            assertEquals(128,Compositor.flatten(merged).getPixel(0,0) ushr 24)
            e.undo().join();assertNotNull(e.snapshot().find(visible.id))
        } finally {e.shutdown()}
    }
    @Test fun groupMergeDoesNotConsumeNeighbor() = runBlocking {
        val e=Engine()
        try {
            val bottom=layer("Bottom",Color.GREEN)
            val child=layer("Child",Color.RED)
            val group=Layer(name="Group",pixels=TiledBitmap(8,8),opacity=0.5f,children=listOf(child))
            e.openStack(Stack(8,8,listOf(bottom,group),group.id),"test").join()
            val before=Compositor.flatten(e.snapshot()).getPixel(0,0)
            e.mergeGroup().join();val s=e.snapshot()
            assertEquals(2,s.layers.size);assertFalse(s.active!!.isGroup);assertEquals(bottom.id,s.layers.first().id)
            assertEquals(before,Compositor.flatten(s).getPixel(0,0))
        } finally {e.shutdown()}
    }
    @Test fun selectionOperationsAndOutlines() = runBlocking {
        val e=Engine()
        try {
            val l=layer("Layer",Color.TRANSPARENT)
            val mask=ByteArray(64){i->if(i%8 in 2..5 && i/8 in 2..5)255.toByte() else 0}
            e.openStack(Stack(8,8,listOf(l),l.id,selection=Selection.fromBytes(mask,8,8)),"test").join()
            e.selectionOutline(Color.RED,null).join()
            assertEquals(Color.RED,e.snapshot().active!!.pixels.toPixels()[3*8+3]);assertEquals(0,e.snapshot().active!!.pixels.toPixels()[0])
            e.undo().join();e.selectionOutline(Color.BLUE,2f).join()
            assertEquals(0,e.snapshot().active!!.pixels.toPixels()[3*8+3]);assertTrue(e.snapshot().active!!.pixels.toPixels()[2*8+2] ushr 24>0)
            e.resizeLayerToSelection().join();assertEquals(Rect(2,2,6,6),e.snapshot().active!!.bounds)
            assertEquals(8,e.snapshot().width)
            e.alphaToSelection(SelectMode.SUBTRACT).join();assertNotNull(e.snapshot().selection)
            val soft=Selection.fromBytes(ByteArray(64){if(it==0)127 else if(it==1)128.toByte() else 0},8,8)
            e.openStack(Stack(8,8,listOf(l),l.id,selection=soft),"test").join();e.sharpenSelection().join()
            assertEquals(Rect(1,0,2,1),e.snapshot().selection!!.bounds)
        } finally {e.shutdown()}
    }
}
