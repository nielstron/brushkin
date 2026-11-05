package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.export.ExportMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCropTest {
    private fun pixels(w: Int, h: Int, color: (Int, Int) -> Int): TiledBitmap {
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        for(y in 0 until h)for(x in 0 until w)image.setPixel(x,y,color(x,y))
        return TiledBitmap.fromBitmap(image)
    }

    @Test fun cropVisibleContentTranslatesDocumentAndCanBeUndone() = runBlocking {
        val px=pixels(12,10){x,y->if(x in 3..7 && y in 2..5)Color.RED else Color.TRANSPARENT}
        val mask=pixels(12,10){_,_->Color.WHITE}
        val layer=Layer(name="Content",pixels=px,mask=mask)
        val selection=Selection.fromBytes(ByteArray(120){if(it==4+3*12)255.toByte()else 0},12,10)
        val channel=SavedChannel(name="Saved",mask=pixels(12,10){x,y->if(x==4 && y==3)Color.WHITE else Color.BLACK})
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id,selection=selection,
                guides=listOf(Guide(true,4),Guide(false,3)),channels=listOf(channel),
                exportMetadata=ExportMetadata(comment="Keep this")),"test").join()
            engine.cropToContent().join()
            val result=engine.snapshot()
            assertEquals(5,result.width);assertEquals(4,result.height)
            assertEquals(Color.RED,result.layers.single().pixels.toBitmap().getPixel(0,0))
            assertEquals(Color.WHITE,result.layers.single().mask!!.toBitmap().getPixel(1,1))
            assertEquals(Rect(1,1,2,2),result.selection!!.bounds)
            assertEquals(listOf(Guide(true,1),Guide(false,1)),result.guides)
            assertEquals(Color.WHITE,result.channels.single().mask.toBitmap().getPixel(1,1))
            assertEquals("Keep this",result.exportMetadata.comment)
            engine.undo().join();assertEquals(12,engine.snapshot().width)
            engine.redo().join();assertEquals(5,engine.snapshot().width)
        } finally {engine.shutdown()}
    }

    @Test fun cropUsesVisibleCompositeAndUniformBackground() = runBlocking {
        val background=Layer(name="Background",pixels=pixels(12,10){_,_->Color.YELLOW})
        val content=Layer(name="Content",pixels=pixels(12,10){x,y->if(x in 3..7 && y in 2..5)Color.RED else 0})
        val hidden=Layer(name="Hidden",pixels=pixels(12,10){_,_->Color.BLUE},visible=false)
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(background,content,hidden),content.id),"test").join()
            engine.cropToContent().join()
            val result=engine.snapshot()
            assertEquals(5,result.width);assertEquals(4,result.height)
            assertEquals(3,result.layers.size);assertFalse(result.layers.last().visible)
        } finally {engine.shutdown()}
    }

    @Test fun cropHonorsMasksAndEmptyImagesDoNotChange() = runBlocking {
        val layer=Layer(name="Masked",pixels=pixels(12,10){_,_->Color.RED},
            mask=pixels(12,10){x,y->if(x in 3..7 && y in 2..5)Color.WHITE else Color.BLACK})
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id),"test").join()
            engine.cropToContent().join()
            assertEquals(5,engine.snapshot().width);assertEquals(4,engine.snapshot().height)
            for(color in listOf(Color.TRANSPARENT,Color.YELLOW)) {
                val empty=Layer(name="Empty",pixels=pixels(12,10){_,_->color})
                engine.openStack(Stack(12,10,listOf(empty),empty.id),"test").join()
                engine.cropToContent().join()
                assertEquals(12,engine.snapshot().width);assertEquals(10,engine.snapshot().height)
            }
        } finally {engine.shutdown()}
    }

    @Test fun fitIncludesHiddenGroupedLayerBoundaries() = runBlocking {
        val first=Layer(name="First",pixels=TiledBitmap(12,10),bounds=Rect(3,2,6,5))
        val hidden=Layer(name="Hidden",pixels=TiledBitmap(12,10),bounds=Rect(5,4,9,7),visible=false)
        val group=Layer(name="Group",pixels=TiledBitmap(12,10),children=listOf(hidden))
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(first,group),first.id),"test").join()
            engine.fitCanvasToLayers().join()
            val result=engine.snapshot()
            assertEquals(6,result.width);assertEquals(5,result.height)
            assertEquals(Rect(0,0,3,3),result.find(first.id)!!.bounds)
            assertEquals(Rect(2,2,6,5),result.find(hidden.id)!!.bounds)
            assertFalse(result.find(hidden.id)!!.visible)
        } finally {engine.shutdown()}
    }

    @Test fun fitRevealsOffCanvasPixelsWithoutTilePadding() = runBlocking {
        val image=Bitmap.createBitmap(7,6,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val (px,outside,bounds)=LayerExtent.fromPlaced(image,-3,-2,12,10)
        val layer=Layer(name="Outside",pixels=px,outside=outside,bounds=bounds)
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id),"test").join()
            engine.fitCanvasToLayers().join()
            val result=engine.snapshot()
            assertEquals(7,result.width);assertEquals(6,result.height)
            val visible=Compositor.flatten(result)
            assertEquals(Color.BLUE,visible.getPixel(0,0));assertEquals(Color.BLUE,visible.getPixel(6,5))
            engine.undo().join();assertEquals(12,engine.snapshot().width)
        } finally {engine.shutdown()}
    }

    @Test fun fitRetainsFullBoundaryOfLayerLargerThanCanvas() = runBlocking {
        val image=Bitmap.createBitmap(18,14,Bitmap.Config.ARGB_8888).apply{setPixel(4,3,Color.RED)}
        val (px,outside,bounds)=LayerExtent.fromPlaced(image,-3,-2,12,10)
        val layer=Layer(name="Larger",pixels=px,outside=outside,bounds=bounds)
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id),"test").join()
            engine.canvasSize(12,10,0,0).join()
            engine.fitCanvasToLayers().join()
            assertEquals(18,engine.snapshot().width);assertEquals(14,engine.snapshot().height)
        } finally {engine.shutdown()}
    }

    @Test fun resizingPreservesPixelsInPartialEdgeTiles() = runBlocking {
        val image=Bitmap.createBitmap(7,6,Bitmap.Config.ARGB_8888).apply{eraseColor(0x800000FF.toInt())}
        val (px,outside,bounds)=LayerExtent.fromPlaced(image,8,7,12,10)
        val layer=Layer(name="Outside",pixels=px,outside=outside,bounds=bounds)
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id),"test").join()
            engine.canvasSize(12,10,0,0).join()
            engine.canvasSize(20,20,0,0).join()
            val result=engine.snapshot().layers.single().pixels.toBitmap()
            assertEquals(0x800000FF.toInt(),result.getPixel(14,12))
            assertEquals(0x800000FF.toInt(),result.getPixel(8,7))
        } finally {engine.shutdown()}
    }

    @Test fun canvasResizeKeepsMetadata() = runBlocking {
        val layer=Layer(name="Layer",pixels=TiledBitmap(12,10))
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id,exportMetadata=ExportMetadata(comment="Keep this",animationLoops=3)),"test").join()
            engine.canvasSize(10,8,-1,-1).join()
            val result=engine.snapshot()
            assertEquals("Keep this",result.exportMetadata.comment);assertEquals(3,result.exportMetadata.animationLoops)
        } finally {engine.shutdown()}
    }
    @Test fun canvasResizeTranslatesEditableText() = runBlocking {
        val spec=TextSpec("A",4f).withMatrix(android.graphics.Matrix().apply{setTranslate(5f,6f)})
        val layer=Layer(name="Text",pixels=TiledBitmap(12,10),text=spec)
        val engine=Engine()
        try {
            engine.openStack(Stack(12,10,listOf(layer),layer.id),"test").join()
            engine.canvasSize(10,8,-1,-1).join()
            val point=floatArrayOf(0f,0f);engine.snapshot().layers.single().text!!.matrix().mapPoints(point)
            assertEquals(4f,point[0]);assertEquals(5f,point[1])
        } finally {engine.shutdown()}
    }

}
