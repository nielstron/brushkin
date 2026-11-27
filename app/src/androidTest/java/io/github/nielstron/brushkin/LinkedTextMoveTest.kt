package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TextSpec
import io.github.nielstron.brushkin.engine.TiledBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedTextMoveTest {
    @Test fun movingTextCarriesItsLinkedRasterLayer() = checkLinkedMove(textActive = true)

    @Test fun editingTextAfterMovingItsLinkedRasterLayerKeepsPosition() = checkLinkedMove(textActive = false)

    private fun checkLinkedMove(textActive: Boolean) = runBlocking {
        val e = Engine()
        val w = 120
        val h = 80
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixel(4, 8, Color.RED) }
        val marker = Layer(name = "Mark", pixels = TiledBitmap.fromBitmap(bitmap), linked = true, bounds = Rect(0, 0, 10, 10))
        val locked = Layer(name = "Locked", pixels = marker.pixels.copy(), linked = true, lockPosition = true)
        val spec = TextSpec("A17", 16f).withMatrix(Matrix().apply { setTranslate(12f, 18f) })
        val title = Layer(name = "A17", pixels = spec.renderLayer(w, h), text = spec, linked = true)
        val before = title.pixels.contentBounds()!!
        val moved = Rect(before).apply { offset(20, 7) }
        try {
            e.openStack(Stack(w, h, listOf(marker, locked, title), if (textActive) title.id else marker.id), "Caption").join()
            e.liftActive().join()
            e.commitMove(20f, 7f).join()
            fun bounds(id: Long) = e.stack.find(id)!!.pixels.contentBounds()
            assertEquals(Rect(24, 15, 25, 16), bounds(marker.id))
            assertEquals(Rect(20, 7, 30, 17), e.stack.find(marker.id)!!.bounds)
            assertEquals(Rect(4, 8, 5, 9), bounds(locked.id))
            assertEquals(moved, bounds(title.id))

            val movedText = e.stack.find(title.id)!!.text!!
            e.updateTextLayer(title.id, movedText.copy(color = Color.BLUE)).join()
            assertEquals(moved, bounds(title.id))

            e.undo().join()
            assertEquals(moved, bounds(title.id))
            e.undo().join()
            assertEquals(before, bounds(title.id))
            assertEquals(Rect(4, 8, 5, 9), bounds(marker.id))
            assertEquals(spec.matrix.toList(), e.stack.find(title.id)!!.text!!.matrix.toList())
        } finally {
            e.shutdown()
        }
    }
}
