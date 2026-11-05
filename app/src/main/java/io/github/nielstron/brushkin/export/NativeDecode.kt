package io.github.nielstron.brushkin.export

object NativeDecode {
    init { System.loadLibrary("brushkin_export") }
    /** Header [width,height,loop count], followed by [delay milliseconds,ARGB pixels] per frame. */
    external fun gif(data: ByteArray): Array<IntArray>
    external fun webp(data: ByteArray): Array<IntArray>
    /** Width and height followed by ARGB pixels; libjpeg's scaled decode obeys the existing import limits. */
    external fun jpeg(data: ByteArray, maxDimension: Int, maxPixels: Long): IntArray
}
