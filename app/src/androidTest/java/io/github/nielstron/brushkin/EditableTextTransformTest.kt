package io.github.nielstron.brushkin

import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.TextSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableTextTransformTest {
    private val width = 200
    private val height = 120
    private val spec get() = TextSpec("F", 24f).withMatrix(Matrix().apply { setTranslate(32f, 21f) })

    @Test fun editAfterAlignmentKeepsPosition() = runBlocking {
        val engine = Engine()
        try {
            engine.newDocument(width, height, null).join()
            engine.addTextLayer(spec).join()
            engine.alignLayer(0, 0).join()
            val aligned = engine.snapshot().active!!
            engine.updateTextLayer(aligned.id, aligned.text!!.copy(text = "F!")).join()
            val bounds = engine.snapshot().active!!.pixels.contentBounds()!!
            assertEquals(0, bounds.left)
            assertEquals(0, bounds.top)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun editAfterFlippingKeepsMirroredGlyphs() = runBlocking {
        for (horizontal in listOf(true, false)) checkFlip(spec, horizontal)
    }

    @Test fun editAfterFlippingPathTextKeepsMirroredGlyphs() = runBlocking {
        val path = floatArrayOf(18f, 42f, 18f, 42f, 48f, 42f, 118f, 42f, 88f, 42f, 118f, 42f)
        checkFlip(spec.copy(path = path), true)
    }

    private suspend fun checkFlip(original: TextSpec, horizontal: Boolean) {
        val engine = Engine()
        try {
            engine.newDocument(width, height, null).join()
            engine.addTextLayer(original).join()
            engine.flipLayer(horizontal).join()
            val flipped = engine.snapshot().active!!
            // Use the serialized editable text, as when reopening the document to make the edit.
            val reopened = TextSpec.fromJson(flipped.text!!.toJson())!!
            engine.updateTextLayer(flipped.id, reopened.copy(text = "F!")).join()
            val actual = engine.snapshot().active!!.pixels.toPixels()
            val source = original.copy(text = "F!").renderLayer(width, height).toPixels()
            var different = 0
            var ink = 0
            for (y in 0 until height) for (x in 0 until width) {
                val sx = if (horizontal) width - 1 - x else x
                val sy = if (horizontal) y else height - 1 - y
                val expectedInk = (source[sy * width + sx] ushr 24) >= 128
                val actualInk = (actual[y * width + x] ushr 24) >= 128
                if (expectedInk) ink++
                if (actualInk != expectedInk) different++
            }
            assertTrue("Expected visible glyphs", ink > 0)
            // Path glyph hinting can round differently when drawn in a reflected coordinate system.
            assertTrue("Mirrored text differs at $different pixels ($ink ink pixels)", different <= ink / 10)
        } finally {
            engine.shutdown()
        }
    }
}
