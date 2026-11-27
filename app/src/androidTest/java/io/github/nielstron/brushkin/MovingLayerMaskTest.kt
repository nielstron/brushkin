package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.SelectMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MovingLayerMaskTest {
    @Test fun wholeLayerDragPreviewsAndCommitsMaskWithPixels() = runBlocking {
        val e = Engine()
        try {
            val band = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888)
            Canvas(band).drawRect(4f, 4f, 20f, 12f, android.graphics.Paint().apply { color = Color.RED })
            e.openImage(band, "Band").join()
            val window = Path().apply { addRect(7f, 5f, 12f, 10f, Path.Direction.CW) }
            e.select(window, SelectMode.REPLACE, false, 0f, "Mask window").join()
            e.addMask(Engine.MaskInit.SELECTION).join()
            e.selectNone().join()
            e.setEditMask(e.stack.activeId, false).join()
            val original = e.flattened()
            val mask = e.stack.active!!.mask!!.toPixels()
            assertEquals(Color.RED, original.getPixel(8, 6))
            assertEquals(Color.TRANSPARENT, original.getPixel(17, 6))

            // Cross the canvas edge and return during one direct-transform session.
            for (dx in listOf(-10f, 30f)) {
                e.liftActive(continueSession = true).join()
                val f = e.floating!!
                f.matrix.postTranslate(dx, 0f)
                val preview = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888)
                Compositor.draw(Canvas(preview), e.stack, floating = f)
                e.commitMove(dx, 0f).join()
                assertTrue("Drag preview must match committed pixels", preview.sameAs(e.flattened()))
            }
            val moved = e.flattened()
            assertEquals(Color.RED, moved.getPixel(28, 6))
            assertEquals(Color.TRANSPARENT, moved.getPixel(37, 6))
            assertEquals(Color.TRANSPARENT, moved.getPixel(8, 6))
            e.undo().join()
            e.undo().join()
            assertTrue(original.sameAs(e.flattened()))
            assertArrayEquals(mask, e.stack.active!!.mask!!.toPixels())
            e.redo().join()
            e.redo().join()
            assertTrue(moved.sameAs(e.flattened()))
        } finally { e.shutdown() }
    }
}
