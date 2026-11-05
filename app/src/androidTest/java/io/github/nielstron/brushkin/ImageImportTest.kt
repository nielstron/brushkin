package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportTest {
    private fun stack():Stack {
        fun layer(name:String,color:Int):Layer {
            val bitmap=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            return try{Layer(name=name,pixels=TiledBitmap.fromBitmap(bitmap))}finally{bitmap.recycle()}
        }
        val first=layer("First (120ms)",Color.RED);val second=layer("Second (250ms)",Color.GREEN)
        return Stack(24,16,listOf(first,second),second.id)
    }
    @Test fun gifPlayOnceStaysPlayOnce() {
        val bytes=encodeGifAnimation(stack(),ImageExportOptions(format=ImageFormat.GIF,animation=true,loopForever=false,repeats=1,disposal=FrameDisposal.REPLACE))
        val imported=ImageImport.open(bytes,"animation.gif").stack
        assertEquals(1,imported.exportMetadata.animationLoops)
    }
    @Test fun animatedFilesImportAsTimedLayers() {
        for(format in listOf(ImageFormat.GIF,ImageFormat.WEBP)) {
            val options=ImageExportOptions(format=format,animation=true,lossless=true,disposal=FrameDisposal.REPLACE,loopForever=false,repeats=3)
            val data=if(format==ImageFormat.GIF)encodeGifAnimation(stack(),options)else encodeWebpAnimation(stack(),options)
            val imported=ImageImport.open(data,"animation.${format.extension}").stack
            assertEquals(2,imported.layers.size);assertEquals(3,imported.exportMetadata.animationLoops)
            assertTrue(imported.layers[0].name.contains("120ms"));assertTrue(imported.layers[1].name.contains("250ms"))
            val first=imported.layers[0].pixels.toBitmap();val last=imported.layers[1].pixels.toBitmap()
            assertEquals(Color.RED,first.getPixel(5,5));assertEquals(Color.GREEN,last.getPixel(5,5));first.recycle();last.recycle()
        }
    }
    @Test fun independentFixturesCompositeDisposalAndTransparency() {
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        for(format in listOf("gif","webp")) {
            val imported=ImageImport.open(assets.open("decoder/disposal.$format").use{it.readBytes()},"test.$format").stack
            assertEquals(4,imported.layers.size);assertEquals(3,imported.exportMetadata.animationLoops)
            imported.layers.forEachIndexed { i,layer ->
                val actual=layer.pixels.toBitmap();val expected=android.graphics.BitmapFactory.decodeStream(assets.open("decoder/$format-$i.png"))
                for(y in 0 until 24)for(x in 0 until 40)assertEquals("$format frame $i pixel $x,$y",expected.getPixel(x,y),actual.getPixel(x,y))
                actual.recycle();expected.recycle()
            }
        }
    }
    @Test fun arithmeticJpegFallsBackAndStillObeysSizeLimit() {
        val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val bytes=ImageExportOptions(format=ImageFormat.JPEG,arithmetic=true,quality=95).encode(bitmap)
        val imported=ImageImport.firstFrame(bytes,maxDim=60,maxPixels=10000).bitmap
        assertTrue(imported.width<=60);assertTrue(imported.height<=60)
        assertTrue(Color.red(imported.getPixel(2,2))>240);bitmap.recycle();imported.recycle()
    }
    @Test fun staticImagesRemainSrgbAndPreserveDimensions() {
        val image=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        for(format in listOf(ImageFormat.PNG,ImageFormat.JPEG,ImageFormat.WEBP)) {
            val data=ImageExportOptions(format=format).encode(image);val imported=ImageImport.open(data,"test.${format.extension}").stack
            assertEquals(32,imported.width);assertEquals(16,imported.height);assertEquals(1,imported.layers.size)
        };image.recycle()
    }

    @Test fun jpegFallbackAppliesExifRotation() {
        val source=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888)
        for(y in 0 until 16)for(x in 0 until 24)source.setPixel(x,y,if(y<8){if(x<12)Color.RED else Color.GREEN}else{if(x<12)Color.BLUE else Color.WHITE})
        val jpeg=ImageExportOptions(format=ImageFormat.JPEG,arithmetic=true,quality=100,subsampling=JpegSampling.FULL).encode(source)
        val tiff=java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{put('I'.code.toByte());put('I'.code.toByte());putShort(42);putInt(8);putShort(1);putShort(0x112);putShort(3);putInt(1);putShort(6);putShort(0);putInt(0)}.array()
        val exif="Exif\u0000\u0000".toByteArray()+tiff
        val out=java.io.ByteArrayOutputStream();out.write(jpeg,0,2);java.io.DataOutputStream(out).apply{writeShort(0xffe1);writeShort(exif.size+2);write(exif);write(jpeg,2,jpeg.size-2)}
        val decoded=ImageImport.firstFrame(out.toByteArray()).bitmap
        assertEquals(16,decoded.width);assertEquals(24,decoded.height)
        assertTrue(Color.blue(decoded.getPixel(3,3))>240);assertTrue(Color.red(decoded.getPixel(12,3))>240)
        assertTrue(Color.green(decoded.getPixel(12,20))>240);source.recycle();decoded.recycle()
    }
    @Test fun wideGamutStaticImagesConvertToDocumentSrgb() {
        val source=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888,true,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)).apply{eraseColor(Color.RED)}
        val out=java.io.ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,out)
        val decoded=ImageImport.firstFrame(out.toByteArray()).bitmap
        assertEquals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),decoded.colorSpace)
        assertTrue(Color.red(decoded.getPixel(1,1))>250);assertTrue(Color.green(decoded.getPixel(1,1))<5);source.recycle();decoded.recycle()
    }
    @Test fun excessiveAnimationSizeIsRejectedBeforeAllocatingFrames() {
        val bytes=encodeGifAnimation(stack(),ImageExportOptions(format=ImageFormat.GIF,animation=true))
        bytes[6]=255.toByte();bytes[7]=255.toByte();bytes[8]=255.toByte();bytes[9]=255.toByte()
        val error=assertThrows(IllegalArgumentException::class.java){ImageImport.open(bytes,"huge.gif")}
        assertTrue(error.message.orEmpty().contains("too large"))
    }

}
