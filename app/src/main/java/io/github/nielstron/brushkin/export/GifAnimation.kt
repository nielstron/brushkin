package io.github.nielstron.brushkin.export

import android.graphics.Bitmap
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Stack

private fun gifHandle(width:Int,height:Int,options:ImageExportOptions,animated:Boolean):Long {
    val comment=options.comment.takeIf{options.saveComment}.orEmpty()
    require(comment.all{it.code<=127}) { "GIF comments support ASCII characters only" }
    return NativeExport.gifStart(width,height,if(!animated)-1 else if(options.loopForever)0 else options.repeats-1,comment)
}
private fun gifFrame(handle:Long,frame:Bitmap,options:ImageExportOptions,delay:Int,disposal:Int) {
    val pixels=IntArray(frame.width*frame.height);frame.getPixels(pixels,0,frame.width,0,0,frame.width,frame.height)
    NativeExport.gifAdd(handle,pixels,frame.width,frame.height,delay,disposal,options.interlace)
}
fun encodeStaticGif(source:Bitmap,options:ImageExportOptions):ByteArray {
    val handle=gifHandle(source.width,source.height,options,false)
    try {gifFrame(handle,source,options,0,0);return NativeExport.gifFinish(handle)}finally{NativeExport.gifClose(handle)}
}
fun encodeGifAnimation(stack:Stack,options:ImageExportOptions):ByteArray {
    val handle=gifHandle(stack.width,stack.height,options,true)
    fun mode(index:Int):FrameDisposal {
        val name=stack.layers[index].name
        return if(options.forceDisposal)options.disposal else when {
            name.contains("(replace)",true)->FrameDisposal.REPLACE
            name.contains("(combine)",true)->FrameDisposal.COMBINE
            else->options.disposal
        }
    }
    var accumulated=emptyList<io.github.nielstron.brushkin.engine.Layer>()
    try {
        stack.layers.forEachIndexed { index,layer ->
            accumulated=if(mode(index)==FrameDisposal.COMBINE)accumulated+layer.copy(visible=true) else listOf(layer.copy(visible=true))
            val frame=Compositor.flatten(stack.copy(layers=accumulated))
            val delay=if(options.forceFrameDelay)options.frameDelay else Regex("\\((\\d+)\\s*ms\\)",RegexOption.IGNORE_CASE).find(layer.name)?.groupValues?.get(1)?.toInt() ?: options.frameDelay
            val disposal=when(mode((index+1)%stack.layers.size)){FrameDisposal.UNSPECIFIED->0;FrameDisposal.COMBINE->1;FrameDisposal.REPLACE->2}
            try{gifFrame(handle,frame,options,delay,disposal)}finally{frame.recycle()}
        }
        return NativeExport.gifFinish(handle)
    } finally {NativeExport.gifClose(handle)}
}
