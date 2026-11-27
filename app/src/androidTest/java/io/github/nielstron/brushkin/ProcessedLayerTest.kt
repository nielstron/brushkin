package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.SelectMode
import io.github.nielstron.brushkin.engine.TextSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProcessedLayerTest {
    @Test fun unchangedModelOutputKeepsEditableTextAndHistory() = runBlocking {
        val e = Engine()
        try {
            e.newDocument(280, 300, Color.WHITE).join()
            val text = TextSpec("Hi", 20f)
            e.addTextLayer(text).join()
            val history = e.info.value.undoNames
            val before = e.flattened()
            val messages = mutableListOf<String>()
            e.onMessage = { messages.add(it) }

            e.processActive("Deblur") { src, _, _ -> src.copy(Bitmap.Config.ARGB_8888, true) }.join()
            assertEquals(history, e.info.value.undoNames)
            assertSame(text, e.stack.active!!.text)
            assertTrue(before.sameAs(e.flattened()))
            assertEquals(listOf("Deblur made no changes"), messages)
        } finally { e.shutdown() }
    }

    @Test fun onlyChangesInsideTheSelectionCreateAnUndoStep() = runBlocking {
        val e = Engine()
        try {
            e.newDocument(20, 20, Color.WHITE).join()
            val selection = Path().apply { addRect(0f, 0f, 10f, 10f, Path.Direction.CW) }
            e.select(selection, SelectMode.REPLACE, false, 0f, "Rectangle").join()
            val history = e.info.value.undoNames
            e.processActive("Deblur") { src, _, _ -> src.apply { setPixel(15, 15, Color.BLUE) } }.join()
            assertEquals(history, e.info.value.undoNames)
            assertEquals(Color.WHITE, e.flattened().getPixel(15, 15))

            e.processActive("Deblur") { src, _, _ ->
                src.apply { setPixel(3, 3, Color.RED); setPixel(15, 15, Color.BLUE) }
            }.join()
            assertEquals(history + "Deblur", e.info.value.undoNames)
            val changed = e.flattened()
            assertEquals(Color.RED, changed.getPixel(3, 3))
            assertEquals(Color.WHITE, changed.getPixel(15, 15))
            e.undo().join()
            assertEquals(Color.WHITE, e.flattened().getPixel(3, 3))
        } finally { e.shutdown() }
    }
}
