package io.github.nielstron.brushkin

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nielstron.brushkin.engine.BrushSettings
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.PaintTool
import io.github.nielstron.brushkin.engine.StrokePoint
import io.github.nielstron.brushkin.engine.TextSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableTextStrokeTest {
    private suspend fun dab(engine: Engine, tool: PaintTool) {
        engine.strokeBegin(tool, BrushSettings(size = 12f, hardness = 1f, smoothing = 0f), Color.BLACK).join()
        engine.strokeAdd(listOf(StrokePoint(70f, 44f, 1f))).join()
    }

    @Test fun cancelAndEmptyEraserStrokeKeepTextEditable() = runBlocking {
        val engine = Engine()
        try {
            engine.newDocument(96, 64, null).join()
            engine.addTextLayer(TextSpec("Hi", 16f)).join()
            val pixels = engine.stack.active!!.pixels.toPixels()
            val history = engine.info.value.undoNames
            val messages = mutableListOf<String>()
            engine.onMessage = { messages.add(it) }

            dab(engine, PaintTool.BRUSH)
            assertFalse(pixels.contentEquals(engine.stack.active!!.pixels.toPixels()))
            engine.strokeCancel().join()
            assertEquals("Hi", engine.stack.active!!.text?.text)
            assertArrayEquals(pixels, engine.stack.active!!.pixels.toPixels())

            dab(engine, PaintTool.ERASER)
            engine.strokeEnd().join()
            assertEquals("Hi", engine.stack.active!!.text?.text)
            assertArrayEquals(pixels, engine.stack.active!!.pixels.toPixels())
            assertEquals(history, engine.info.value.undoNames)
            assertTrue(messages.toString(), messages.isEmpty())
        } finally {
            engine.shutdown()
        }
    }

    @Test fun paintingPixelsRasterizesWithUndoButPaintingMaskKeepsText() = runBlocking {
        val engine = Engine()
        try {
            engine.newDocument(96, 64, null).join()
            engine.addTextLayer(TextSpec("Hi", 16f)).join()
            val pixels = engine.stack.active!!.pixels.toPixels()
            dab(engine, PaintTool.BRUSH)
            engine.strokeEnd().join()
            val painted = engine.stack.active!!.pixels.toPixels()
            assertFalse(pixels.contentEquals(painted))
            assertNull(engine.stack.active!!.text)
            engine.undo().join()
            assertEquals("Hi", engine.stack.active!!.text?.text)
            assertArrayEquals(pixels, engine.stack.active!!.pixels.toPixels())
            engine.redo().join()
            assertNull(engine.stack.active!!.text)
            assertArrayEquals(painted, engine.stack.active!!.pixels.toPixels())

            engine.undo().join()
            engine.addMask(Engine.MaskInit.WHITE).join()
            val mask = engine.stack.active!!.mask!!.toPixels()
            dab(engine, PaintTool.BRUSH)
            engine.strokeEnd().join()
            assertFalse(mask.contentEquals(engine.stack.active!!.mask!!.toPixels()))
            assertArrayEquals(pixels, engine.stack.active!!.pixels.toPixels())
            assertEquals("Hi", engine.stack.active!!.text?.text)
        } finally {
            engine.shutdown()
        }
    }
}
