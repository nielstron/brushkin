package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import android.util.Base64
import io.github.nielstron.brushkin.engine.Ora
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Original metadata is kept with the document, independently of image pixels. */
data class ExportMetadata(val exif: ByteArray? = null, val xmp: ByteArray? = null, val iptc: ByteArray? = null,
    val jpegHeader: ByteArray? = null, val comment: String = "", val resolution: Int = 72) {
    fun toJson() = JSONObject().apply {
        for ((name, value) in listOf("exif" to exif,"xmp" to xmp,"iptc" to iptc,"jpeg" to jpegHeader))
            value?.let { put(name, Base64.encodeToString(it, Base64.NO_WRAP)) }
        put("comment", comment); put("resolution", resolution)
    }.toString()
    companion object {
        fun fromJson(text: String): ExportMetadata {
            val j=JSONObject(text)
            fun read(k:String)=if(j.has(k)) Base64.decode(j.getString(k),Base64.NO_WRAP) else null
            return ExportMetadata(read("exif"),read("xmp"),read("iptc"),read("jpeg"),j.optString("comment"), j.optInt("resolution",72))
        }
        fun read(data: ByteArray): ExportMetadata {
            var exif:ByteArray?=null;var xmp:ByteArray?=null;var iptc:ByteArray?=null;var jpeg:ByteArray?=null;var comment="";var resolution=72
            if(data.size>=2 && data[0]==0xff.toByte() && data[1]==0xd8.toByte()) {
                var p=2
                while(p+4<=data.size) {
                    require(data[p]==0xff.toByte());val marker=data[p+1].toInt() and 255
                    if(marker==0xd9)break
                    val n=((data[p+2].toInt() and 255) shl 8)+(data[p+3].toInt() and 255)
                    if(marker==0xda) {jpeg=data.copyOfRange(0,p+2+n);break}
                    val b=data.copyOfRange(p+4,p+2+n)
                    if(marker==0xe0 && b.startsWithBytes("JFIF\u0000".toByteArray()) && b.size>=12) {
                        val density=((b[8].toInt() and 255) shl 8)+(b[9].toInt() and 255)
                        if(density>0)resolution=when(b[7].toInt()){1->density;2->(density*2.54).toInt();else->resolution}
                    }
                    if(marker==0xe1 && b.startsWithBytes("Exif\u0000\u0000".toByteArray()))exif=b.copyOfRange(6,b.size)
                    if(marker==0xe1 && b.startsWithBytes(XMP_HEADER))xmp=b.copyOfRange(XMP_HEADER.size,b.size)
                    if(marker==0xed)iptc=b
                    if(marker==0xfe)comment=b.toString(Charsets.UTF_8)
                    p+=2+n
                }
            } else if(data.size>=8 && data[0]==137.toByte() && data.copyOfRange(1,4).toString(Charsets.US_ASCII)=="PNG") {
                for((type,b) in pngChunks(data)) when(type) {
                    "eXIf" -> exif=b
                    "pHYs" -> if(b.size==9 && b[8]==1.toByte())resolution=(ByteBuffer.wrap(b,0,4).int*0.0254).toInt().coerceAtLeast(1)
                    "tEXt" -> {
                        val key=b.takeWhile{it!=0.toByte()}.toByteArray().toString(Charsets.UTF_8)
                        val text=b.copyOfRange(key.toByteArray().size+1,b.size).toString(Charsets.UTF_8)
                        if(key=="Comment")comment=text
                        if(key=="Raw profile type iptc") {val hex=text.trim().lines().drop(2).joinToString("").filterNot{it.isWhitespace()};iptc=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()}
                    }
                    "iTXt" -> {
                        val name=b.takeWhile{it!=0.toByte()}.toByteArray().toString(Charsets.UTF_8)
                        val start=name.toByteArray().size+1
                        if(name=="XML:com.adobe.xmp") {
                            val compressed=b[start].toInt()==1;var pos=start+2
                            repeat(2){while(b[pos]!=0.toByte())pos++;pos++}
                            val payload=b.copyOfRange(pos,b.size);xmp=if(compressed)payload.inputStream().let{InflaterInputStream(it).readBytes()}else payload
                        }
                        if(name=="Comment") {var pos=start+2;repeat(2){while(b[pos]!=0.toByte())pos++;pos++};comment=b.copyOfRange(pos,b.size).toString(Charsets.UTF_8)}
                    }
                }
            } else if(data.size>=12 && data.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF") {
                var p=12
                while(p+8<=data.size) {val type=data.copyOfRange(p,p+4).toString(Charsets.US_ASCII);val n=ByteBuffer.wrap(data,p+4,4).order(ByteOrder.LITTLE_ENDIAN).int
                    val b=data.copyOfRange(p+8,p+8+n);when(type){"EXIF"->exif=b;"XMP "->xmp=b;"IPTC"->iptc=b};p+=8+n+(n and 1)
                }
            }
            exif?.let { data ->
                val b=ByteBuffer.wrap(data).order(if(data[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val first=b.getInt(4);val count=b.getShort(first).toInt() and 65535
                var value:Double?=null;var unit=2
                for(i in 0 until count){val p=first+2+12*i;val tag=b.getShort(p).toInt() and 65535
                    if(tag==0x11a && b.getShort(p+2).toInt()==5){val offset=b.getInt(p+8);val numerator=b.getInt(offset).toLong() and 0xffffffffL;val denominator=b.getInt(offset+4).toLong() and 0xffffffffL;if(denominator!=0L)value=numerator.toDouble()/denominator}
                    if(tag==0x128)unit=b.getShort(p+8).toInt() and 65535
                }
                value?.let { resolution=(it*if(unit==3)2.54 else 1.0).toInt().coerceAtLeast(1) }
            }
            return ExportMetadata(exif,xmp,iptc,jpeg,comment,resolution)
        }
    }
}
private val XMP_HEADER="http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
private fun ByteArray.startsWithBytes(prefix:ByteArray)=size>=prefix.size && prefix.indices.all{this[it]==prefix[it]}
private fun pngChunks(data:ByteArray):List<Pair<String,ByteArray>> {
    val chunks=ArrayList<Pair<String,ByteArray>>();var p=8
    while(p+12<=data.size) {val n=ByteBuffer.wrap(data,p,4).int;val type=data.copyOfRange(p+4,p+8).toString(Charsets.US_ASCII);chunks+=type to data.copyOfRange(p+8,p+8+n);p+=12+n}
    return chunks
}
private fun pngChunk(type:String,payload:ByteArray):ByteArray {
    val out=ByteArrayOutputStream();val d=DataOutputStream(out);d.writeInt(payload.size);d.writeBytes(type);d.write(payload)
    d.writeInt(CRC32().apply{update(type.toByteArray());update(payload)}.value.toInt());return out.toByteArray()
}
private fun pngText(key:String,value:ByteArray)=key.toByteArray()+byteArrayOf(0,0,0,0,0)+value

/** Correct orientation/dimensions after editing; discard stale embedded thumbnails. */
private fun normalizedExif(original:ByteArray?,width:Int,height:Int,thumbnail:ByteArray?,resolution:Int,cmyk:Boolean):ByteArray? {
    if(original==null && thumbnail==null)return null
    val src=original?.copyOf() ?: byteArrayOf(73,73,42,0,8,0,0,0,0,0,0,0,0,0)
    val order=if(src[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val b=ByteBuffer.wrap(src).order(order)
    fun patch(offset:Int) {
        val count=b.getShort(offset).toInt() and 65535
        for(i in 0 until count){val p=offset+2+12*i;val tag=b.getShort(p).toInt() and 65535;val type=b.getShort(p+2).toInt() and 65535
            val value=when(tag){0x112->1;0x100,0xa002->width;0x101,0xa003->height;0x128->2;0xa001->if(cmyk)65535 else 1;else->null}
            if((tag==0x11a||tag==0x11b)&&type==5){val offset=b.getInt(p+8);b.putInt(offset,resolution);b.putInt(offset+4,1)}
            if(value!=null && b.getInt(p+4)==1){if(type==3)b.putShort(p+8,value.toShort())else if(type==4)b.putInt(p+8,value)}
            if(tag==0x8769)patch(b.getInt(p+8))
        }
    }
    val first=b.getInt(4);patch(first);val next=first+2+(b.getShort(first).toInt() and 65535)*12;b.putInt(next,if(thumbnail==null)0 else src.size)
    if(thumbnail==null)return src
    val dir=ByteBuffer.allocate(42).order(order);dir.putShort(3)
    fun entry(tag:Int,type:Int,value:Int){dir.putShort(tag.toShort());dir.putShort(type.toShort());dir.putInt(1);if(type==3){dir.putShort(value.toShort());dir.putShort(0)}else dir.putInt(value)}
    entry(0x103,3,6);entry(0x201,4,src.size+42);entry(0x202,4,thumbnail.size);dir.putInt(0)
    return src+dir.array()+thumbnail
}

fun addExportMetadata(encoded:ByteArray,source:Bitmap,options:ImageExportOptions,metadata:ExportMetadata,icc:ByteArray?,background:Int):ByteArray {
    val thumb=if(options.saveThumbnail){val factor=minOf(128f/source.width,128f/source.height,1f);val b=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*factor).toInt()),maxOf(1,(source.height*factor).toInt()),true);try{Ora.encode(b,"jpg",75)}finally{if(b!==source)b.recycle()}}else null
    val exif=normalizedExif(metadata.exif.takeIf{options.saveExif},source.width,source.height,thumb,options.resolution,options.format==ImageFormat.JPEG && options.cmyk)
    val xmp=metadata.xmp.takeIf{options.saveXmp};val iptc=metadata.iptc.takeIf{options.saveIptc};val profile=icc.takeIf{options.saveProfile && !(options.format==ImageFormat.JPEG && options.cmyk)}
    val comment=options.comment.takeIf{options.saveComment && it.isNotEmpty()}
    return when(options.format) {
        ImageFormat.GIF -> encoded
        ImageFormat.JPEG -> {
            val out=ByteArrayOutputStream();out.write(encoded,0,2)
            fun marker(type:Int,b:ByteArray){require(b.size<=65533){"Metadata is too large for a JPEG marker"};out.write(255);out.write(type);out.write((b.size+2) shr 8);out.write((b.size+2) and 255);out.write(b)}
            exif?.let{marker(0xe1,"Exif\u0000\u0000".toByteArray()+it)};xmp?.let{marker(0xe1,XMP_HEADER+it)};iptc?.let{marker(0xed,it)}
            profile?.asList()?.chunked(65519)?.let{parts->parts.forEachIndexed{i,v->marker(0xe2,"ICC_PROFILE\u0000".toByteArray()+byteArrayOf((i+1).toByte(),parts.size.toByte())+v.toByteArray())}}
            comment?.let{marker(0xfe,it.toByteArray())};out.write(encoded,2,encoded.size-2);out.toByteArray()
        }
        ImageFormat.PNG -> {
            val out=ByteArrayOutputStream();out.write(encoded,0,8)
            for((type,b) in pngChunks(encoded)) {
                out.write(pngChunk(type,b))
                if(type=="IHDR") {
                    exif?.let{out.write(pngChunk("eXIf",it))};xmp?.let{out.write(pngChunk("iTXt",pngText("XML:com.adobe.xmp",it)))}
                    profile?.let{val z=ByteArrayOutputStream();DeflaterOutputStream(z).use{stream->stream.write(it)};out.write(pngChunk("iCCP","sRGB".toByteArray()+byteArrayOf(0,0)+z.toByteArray()))}
                    if(options.saveBackground){val isGray=options.pngFormat.ordinal>=5;val depth=if(options.pngFormat in listOf(PngPixelFormat.RGB16,PngPixelFormat.RGBA16,PngPixelFormat.GRAY16,PngPixelFormat.GRAYA16))16 else 8
                        val r=(background shr 16)and 255;val g=(background shr 8)and 255;val bl=background and 255;val values=if(isGray)listOf((299*r+587*g+114*bl+500)/1000)else listOf(r,g,bl)
                        val z=ByteArrayOutputStream();DataOutputStream(z).use{d->values.forEach{d.writeShort(if(depth==16)it*257 else it)}};out.write(pngChunk("bKGD",z.toByteArray()))}
                    if(options.saveOffset)out.write(pngChunk("oFFs",ByteArray(9)))
                    if(options.saveResolution){val z=ByteBuffer.allocate(9).putInt((options.resolution/0.0254).toInt()).putInt((options.resolution/0.0254).toInt()).put(1).array();out.write(pngChunk("pHYs",z))}
                    if(options.saveCreationTime){val t=LocalDateTime.now(java.time.ZoneOffset.UTC);out.write(pngChunk("tIME",ByteBuffer.allocate(7).putShort(t.year.toShort()).put(t.monthValue.toByte()).put(t.dayOfMonth.toByte()).put(t.hour.toByte()).put(t.minute.toByte()).put(t.second.toByte()).array()))}
                    comment?.let{out.write(pngChunk("iTXt",pngText("Comment",it.toByteArray())))}
                    iptc?.let{val hex=it.joinToString(""){v->"%02x".format(v)};out.write(pngChunk("tEXt",("Raw profile type iptc\u0000\niptc\n${it.size}\n$hex\n").toByteArray()))}
                }
            };out.toByteArray()
        }
        ImageFormat.WEBP -> NativeExport.webpMetadata(encoded,exif,xmp,profile)
    }
}
