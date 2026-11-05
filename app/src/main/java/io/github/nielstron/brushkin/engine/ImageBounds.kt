package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Rect

object ImageBounds {
    /** Trim a transparent or uniform-color border, using adjacent corners to identify the background. */
    fun content(image: Bitmap): Rect? {
        val w=image.width;val h=image.height
        val corners=intArrayOf(image.getPixel(0,0),image.getPixel(w-1,0),image.getPixel(0,h-1),image.getPixel(w-1,h-1))
        val adjacent=listOf(0 to 1,0 to 2,1 to 3,2 to 3)
        val transparent=adjacent.any{(a,b)->corners[a] ushr 24==0 && corners[b] ushr 24==0}
        val background=when {
            corners[0]==corners[1] || corners[0]==corners[2]->corners[0]
            corners[3]==corners[1] || corners[3]==corners[2]->corners[3]
            else->null
        }
        if(!transparent && background==null)return Rect(0,0,w,h)
        var left=w;var top=h;var right=0;var bottom=0
        val row=IntArray(w)
        for(y in 0 until h){
            image.getPixels(row,0,w,0,y,w,1)
            for(x in 0 until w)if(if(transparent)row[x] ushr 24!=0 else row[x]!=background){
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x+1);bottom=y+1
            }
        }
        return if(right>left && bottom>top)Rect(left,top,right,bottom)else null
    }

    /** All layer boundaries, including hidden/grouped layers and content beyond the current canvas. */
    fun layers(stack: Stack): Rect? {
        var result:Rect?=null
        for(layer in stack.all)if(!layer.isGroup){
            val bounds=Rect(layer.bounds ?: Rect(0,0,stack.width,stack.height))
            if(layer.bounds==null && layer.outside.isNotEmpty())LayerExtent.bounds(layer)?.let{bounds.union(it)}
            if(!bounds.isEmpty){if(result==null)result=bounds else result.union(bounds)}
        }
        return result
    }
}
