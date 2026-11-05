package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeExportTest {
    private fun image() = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, Color.argb(if (x < 8) 0 else if (x < 16) 128 else 255, x*4, y*8, 128))
    }
    @Test fun settingsSurviveSaveAndReload() {
        val options = ImageExportOptions(format = ImageFormat.JPEG, quality = 67, smoothing = 13, progressive = false,
            subsampling = JpegSampling.FULL, dct = JpegDct.FLOAT, interlace = true, comment = "Test Ω", saveComment = true, frameDelay = 333, repeats = 7, disposal = FrameDisposal.REPLACE, forceDisposal = true)
        assertEquals(options, ImageExportOptions.fromJson(options.toJson()))
    }
    @Test fun pngPixelFormatsInterlacingAndCompression() {
        val source = image()
        for (format in PngPixelFormat.entries) for (interlace in listOf(false,true)) {
            val bytes = ImageExportOptions(pngFormat = format, interlace = interlace).encode(source)
            val decoded = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertNotNull(decoded); assertEquals(64, decoded.width); assertEquals(32, decoded.height)
            assertEquals(if (interlace) 1 else 0, bytes[28].toInt())
            assertEquals(if (format in listOf(PngPixelFormat.RGB16,PngPixelFormat.RGBA16,PngPixelFormat.GRAY16,PngPixelFormat.GRAYA16)) 16 else 8, bytes[24].toInt())
            if (format == PngPixelFormat.AUTO || format == PngPixelFormat.RGBA8) assertEquals(source.getPixel(24,12),decoded.getPixel(24,12))
            decoded.recycle()
        }
        val small=ImageExportOptions(compression=9).encode(source);val large=ImageExportOptions(compression=0).encode(source)
        assertTrue(small.size < large.size);source.recycle()
    }
    @Test fun jpegAdvancedSettingsProduceDecodableImages() {
        val source=image()
        for (sampling in JpegSampling.entries) for (arithmetic in listOf(false,true)) {
            val bytes=ImageExportOptions(format=ImageFormat.JPEG, subsampling=sampling, arithmetic=arithmetic, smoothing=10, restartRows=2).encode(source)
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            // Android's decoder does not support arithmetic JPEG, but the encoder must emit its SOF10 marker.
            if (!arithmetic) { assertNotNull(decoded);assertEquals(64,decoded.width);decoded.recycle() }
            else assertTrue(bytes.indices.any { it+1<bytes.size && bytes[it]==0xff.toByte() && bytes[it+1]==0xca.toByte() })
        }
        source.recycle()
    }
    @Test fun webpLosslessPreservesPixelsAndAlpha() {
        val source=image()
        for (preset in WebpSource.entries) {
            val bytes=ImageExportOptions(format=ImageFormat.WEBP, lossless=true, webpQuality=1, alphaQuality=1, webpSource=preset).encode(source)
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertEquals(source.getPixel(24,12),decoded.getPixel(24,12));assertEquals(source.getPixel(12,12),decoded.getPixel(12,12));decoded.recycle()
        }
        source.recycle()
    }
    @Test fun metadataAndOriginalJpegQualityRoundTrip() {
        val source=image()
        val original=ImageExportOptions(format=ImageFormat.JPEG,quality=73).encode(source)
        val originalMetadata=ExportMetadata.read(original)
        assertNotNull(originalMetadata.jpegHeader)
        val options=ImageExportOptions(format=ImageFormat.JPEG,useOriginalQuality=true,saveThumbnail=true,saveComment=true,comment="Example Ω")
        val encoded=options.encode(source,originalMetadata)
        val complete=addExportMetadata(encoded,source,options,originalMetadata,null,Color.WHITE)
        val read=ExportMetadata.read(complete)
        assertEquals("Example Ω",read.comment);assertNotNull(read.exif)
        assertNotNull(BitmapFactory.decodeByteArray(complete,0,complete.size))
        val pngOptions=ImageExportOptions(saveComment=true,comment="PNG Ω",saveCreationTime=true,saveBackground=true,interlace=true)
        val png=addExportMetadata(pngOptions.encode(source),source,pngOptions,read,null,Color.WHITE)
        val pngMetadata=ExportMetadata.read(png)
        assertEquals("PNG Ω",pngMetadata.comment);assertNotNull(pngMetadata.exif)
        val pngDecoded=BitmapFactory.decodeByteArray(png,0,png.size);assertNotNull(pngDecoded);pngDecoded.recycle()
        val webpOptions=ImageExportOptions(format=ImageFormat.WEBP,lossless=true)
        val xmp="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".toByteArray()
        val webp=addExportMetadata(webpOptions.encode(source),source,webpOptions,read.copy(xmp=xmp),null,Color.WHITE)
        assertArrayEquals(xmp,ExportMetadata.read(webp).xmp)
        assertNotNull(BitmapFactory.decodeByteArray(webp,0,webp.size));source.recycle()
    }
    @Test fun webpAnimationContainsTwoFrames() {
        val red=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val green=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
        val first=io.github.nielstron.brushkin.engine.Layer(name="First (100ms)",pixels=io.github.nielstron.brushkin.engine.TiledBitmap.fromBitmap(red))
        val second=io.github.nielstron.brushkin.engine.Layer(name="Second (200ms)",pixels=io.github.nielstron.brushkin.engine.TiledBitmap.fromBitmap(green))
        val stack=io.github.nielstron.brushkin.engine.Stack(24,24,listOf(first,second),first.id)
        val data=encodeWebpAnimation(stack,ImageExportOptions(format=ImageFormat.WEBP,animation=true,lossless=true))
        var offset=12;var frames=0
        while(offset+8<=data.size){val type=data.copyOfRange(offset,offset+4).toString(Charsets.US_ASCII);val n=java.nio.ByteBuffer.wrap(data,offset+4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int;if(type=="ANMF")frames++;offset+=8+n+(n and 1)}
        assertEquals(2,frames);red.recycle();green.recycle()
    }

    @Test fun gifPreservesTransparencyAndExportsLayerDelays() {
        val source=image()
        val still=ImageExportOptions(format=ImageFormat.GIF,interlace=true).encode(source)
        assertEquals("GIF89a",still.copyOfRange(0,6).toString(Charsets.US_ASCII))
        val decoded=BitmapFactory.decodeByteArray(still,0,still.size)
        assertNotNull(decoded);assertEquals(0,Color.alpha(decoded.getPixel(2,5)));assertEquals(255,Color.alpha(decoded.getPixel(30,5)));decoded.recycle()
        val a=io.github.nielstron.brushkin.engine.Layer(name="One (120 ms)",pixels=io.github.nielstron.brushkin.engine.TiledBitmap.fromBitmap(source))
        val second=source.copy(Bitmap.Config.ARGB_8888,true).apply{eraseColor(Color.GREEN)}
        val b=io.github.nielstron.brushkin.engine.Layer(name="Two (250ms)",pixels=io.github.nielstron.brushkin.engine.TiledBitmap.fromBitmap(second))
        val stack=io.github.nielstron.brushkin.engine.Stack(64,32,listOf(a,b),a.id)
        val bytes=encodeGifAnimation(stack,ImageExportOptions(format=ImageFormat.GIF,animation=true,disposal=FrameDisposal.REPLACE,interlace=true))
        val delays=ArrayList<Int>();var images=0;var p=13
        fun blocks(){while(true){val n=bytes[p++].toInt() and 255;if(n==0)break;p+=n}}
        while(p<bytes.size) when(bytes[p++].toInt() and 255){
            0x21->{val type=bytes[p++].toInt() and 255;if(type==0xf9){assertEquals(4,bytes[p++].toInt());p++;delays+=((bytes[p].toInt() and 255)+((bytes[p+1].toInt() and 255) shl 8));p+=4}else blocks()}
            0x2c->{images++;val flags=bytes[p+8].toInt() and 255;assertTrue(flags and 64 != 0);p+=9;if(flags and 128 != 0)p+=3*(1 shl ((flags and 7)+1));p++;blocks()}
            0x3b->break
            else->error("Invalid GIF record")
        }
        assertEquals(2,images);assertEquals(listOf(12,25),delays)
        val animated=android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        assertTrue(animated is android.graphics.drawable.AnimatedImageDrawable)
        source.recycle();second.recycle()
    }

    @Test fun cmykJpegRoundTripsToRgb() {
        val source=image();val encoded=ImageExportOptions(format=ImageFormat.JPEG,cmyk=true,quality=100,progressive=false).encode(source)
        val decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.size);assertNotNull(decoded)
        val expected=source.getPixel(40,20);val actual=decoded.getPixel(40,20)
        assertTrue(kotlin.math.abs(Color.red(actual)-Color.red(expected))<8)
        assertTrue(kotlin.math.abs(Color.green(actual)-Color.green(expected))<8)
        assertTrue(kotlin.math.abs(Color.blue(actual)-Color.blue(expected))<8)
        source.recycle();decoded.recycle()
    }

    @Test fun metadataSurvivesProjectSave() {
        val source=image();val layer=io.github.nielstron.brushkin.engine.Layer(name="Layer",pixels=io.github.nielstron.brushkin.engine.TiledBitmap.fromBitmap(source))
        val meta=ExportMetadata(xmp="<test/>".toByteArray(),comment="Project Ω",resolution=300)
        val stack=io.github.nielstron.brushkin.engine.Stack(64,32,listOf(layer),layer.id,exportMetadata=meta)
        val out=java.io.ByteArrayOutputStream();io.github.nielstron.brushkin.engine.Ora.write(stack,out)
        val restored=io.github.nielstron.brushkin.engine.Ora.read(out.toByteArray().inputStream()).exportMetadata
        assertArrayEquals(meta.xmp,restored.xmp);assertEquals("Project Ω",restored.comment);assertEquals(300,restored.resolution);source.recycle()
    }

}
