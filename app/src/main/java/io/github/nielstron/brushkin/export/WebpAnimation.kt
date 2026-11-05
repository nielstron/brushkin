package io.github.nielstron.brushkin.export

import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Stack

/** GIMP's bottom-to-top layer frames, with optional (Nms) and (combine)/(replace) name tags. */
fun encodeWebpAnimation(stack: Stack, options: ImageExportOptions): ByteArray {
    val handle=NativeExport.animationStart(stack.width,stack.height,if(options.loopForever)0 else options.repeats,options.minimizeSize,options.keyframeDistance)
    var timestamp=0
    var accumulated=emptyList<io.github.nielstron.brushkin.engine.Layer>()
    try {
        for (layer in stack.layers) {
            accumulated=if(layer.name.contains("(combine)",true))accumulated+layer.copy(visible=true) else listOf(layer.copy(visible=true))
            val frame=Compositor.flatten(stack.copy(layers=accumulated))
            try {
                val pixels=IntArray(frame.width*frame.height);frame.getPixels(pixels,0,frame.width,0,0,frame.width,frame.height)
                NativeExport.animationAdd(handle,pixels,frame.width,frame.height,timestamp,options.lossless,options.webpQuality,options.alphaQuality,options.webpSource.ordinal,options.sharpYuv)
            } finally { frame.recycle() }
            val delay=if(options.forceFrameDelay)options.frameDelay else Regex("\\((\\d+)\\s*ms\\)",RegexOption.IGNORE_CASE).find(layer.name)?.groupValues?.get(1)?.toInt() ?: options.frameDelay
            timestamp+=delay.coerceAtLeast(1)
        }
        return NativeExport.animationFinish(handle,timestamp)
    } finally {NativeExport.animationClose(handle)}
}
