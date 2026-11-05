package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TiledBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Static images retain Android's format/color support; native decoders add editable animation and JPEG compatibility. */
object ImageImport {
    data class Raster(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)
    data class Document(val stack: Stack, val originalWidth: Int, val originalHeight: Int)
    private fun gif(data: ByteArray) = data.size >= 6 && data.copyOfRange(0,6).toString(Charsets.US_ASCII) in listOf("GIF87a","GIF89a")
    private fun webp(data: ByteArray) = data.size >= 12 && data.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF" && data.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WEBP"
    private fun jpeg(data: ByteArray) = data.size>=2 && data[0]==0xff.toByte() && data[1]==0xd8.toByte()

    fun firstFrame(data: ByteArray, maxDim: Int=8192, maxPixels: Long=40_000_000): Raster {
        var width=0; var height=0
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                width=info.size.width; height=info.size.height
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired=true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val factor=maxOf(maxOf(width,height).toFloat()/maxDim,sqrt(width.toDouble()*height/maxPixels).toFloat(),1f)
                if(factor>1f)decoder.setTargetSize(maxOf(1,(width/factor).toInt()),maxOf(1,(height/factor).toInt()))
            }.let { if(it.config==Bitmap.Config.ARGB_8888)it else it.copy(Bitmap.Config.ARGB_8888,true).also { _ -> it.recycle() } }
        } catch (e: ImageDecoder.DecodeException) {
            if(!jpeg(data))throw e
            val pixels=NativeDecode.jpeg(data,maxDim,maxPixels)
            val decoded=Bitmap.createBitmap(pixels,2,pixels[0],pixels[0],pixels[1],Bitmap.Config.ARGB_8888)
            orient(decoded,ExportMetadata.read(data).exif)
        }
        return Raster(bitmap,if(width>0)width else bitmap.width,if(height>0)height else bitmap.height)
    }

    fun open(data: ByteArray, name: String): Document {
        val metadata=ExportMetadata.read(data)
        val animation=when { gif(data)->NativeDecode.gif(data);webp(data) && data.size>=21 && data.copyOfRange(12,16).toString(Charsets.US_ASCII)=="VP8X" && (data[20].toInt() and 2)!=0 ->NativeDecode.webp(data);else->null }
        if(animation!=null && animation.size>1) {
            val info=animation[0];val w=info[0];val h=info[1]
            val layers=animation.drop(1).mapIndexed { index,pixels ->
                val image=Bitmap.createBitmap(pixels,1,w,w,h,Bitmap.Config.ARGB_8888)
                try { Layer(name=if(animation.size>2)"Frame ${index+1} (${pixels[0]}ms) (replace)" else name,pixels=TiledBitmap.fromBitmap(image)) }
                finally {image.recycle()}
            }
            return Document(Stack(w,h,layers,layers.last().id,exportMetadata=metadata.copy(animationLoops=info[2])),w,h)
        }
        val decoded=firstFrame(data)
        val image=decoded.bitmap
        val layer=try {Layer(name=name,pixels=TiledBitmap.fromBitmap(image))}finally{image.recycle()}
        return Document(Stack(layer.pixels.width,layer.pixels.height,listOf(layer),layer.id,exportMetadata=metadata),decoded.originalWidth,decoded.originalHeight)
    }

    private fun orient(source: Bitmap, exif: ByteArray?): Bitmap {
        if(exif==null)return source
        val bytes=ByteBuffer.wrap(exif).order(if(exif[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val offset=bytes.getInt(4);var orientation=1
        repeat(bytes.getShort(offset).toInt() and 65535) { i -> val p=offset+2+i*12;if((bytes.getShort(p).toInt() and 65535)==0x112)orientation=bytes.getShort(p+8).toInt() and 65535 }
        val values=when(orientation) {
            2->floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,1f)
            3->floatArrayOf(-1f,0f,0f,0f,-1f,0f,0f,0f,1f)
            4->floatArrayOf(1f,0f,0f,0f,-1f,0f,0f,0f,1f)
            5->floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
            6->floatArrayOf(0f,-1f,0f,1f,0f,0f,0f,0f,1f)
            7->floatArrayOf(0f,-1f,0f,-1f,0f,0f,0f,0f,1f)
            8->floatArrayOf(0f,1f,0f,-1f,0f,0f,0f,0f,1f)
            else->return source
        }
        return Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{setValues(values)},true).also{if(it!==source)source.recycle()}
    }
}
