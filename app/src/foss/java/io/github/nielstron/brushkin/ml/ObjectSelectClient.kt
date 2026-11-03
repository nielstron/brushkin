package io.github.nielstron.brushkin.ml

import android.content.Context
import android.graphics.Bitmap

/** The FOSS build has no ML Kit (it needs proprietary Google Play services): no object selection. */
object ObjectSelect {
    class Mask(val bytes: ByteArray, val width: Int, val height: Int)
}

@Suppress("UNUSED_PARAMETER")
class ObjectSelectClient(context: Context) {
    companion object {
        const val AVAILABLE = false
    }

    suspend fun select(image: Bitmap, x: Int, y: Int): ObjectSelect.Mask? =
        throw ObjectSelectUnavailable("Object selection isn't part of this build")

    fun close() {}
}
