package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HistoryStoreTest {
    private val w = 64; private val h = 48
    private val px = IntArray(w * h) { 0xff000000.toInt() or (it * 5 and 0xffffff) }
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "undo-test.history")

    private suspend fun edited(): Engine {
        val e = Engine()
        val layer = Layer(name = "L", pixels = TiledBitmap.fromPixels(px, w, h))
        e.openStack(Stack(w, h, listOf(layer), layer.id), "Doc").join()
        return e
    }
    private suspend fun exposure(e: Engine, stops: Float) { e.filterBegin().join(); e.filterApply(ExposureFilter(stops, 0f)).join() }
    private suspend fun pixels(e: Engine) = e.snapshot().layers[0].pixels.toPixels()

    @Test fun undoAndRedoWorkAfterRestoring() = runBlocking<Unit> {
        val e = edited()
        exposure(e, 0.5f); val first = pixels(e)
        exposure(e, 0.5f); val second = pixels(e)
        exposure(e, 0.5f); e.undo().join()
        HistoryStore.write(e.historySnapshot()!!, file, 10, 20)
        e.shutdown()

        val doc = HistoryStore.read(file, 10, 20, "Doc")!!
        val r = Engine(); r.newDocument(8, 8, -1).join(); r.exchangeDocument(doc)
        assertArrayEquals(second, pixels(r))
        r.undo().join(); assertArrayEquals(first, pixels(r))
        r.undo().join(); assertArrayEquals(px, pixels(r))
        r.redo().join(); r.redo().join(); assertArrayEquals(second, pixels(r))
        r.redo().join(); assertNotEquals(second.toList(), pixels(r).toList())
        r.shutdown(); file.delete()
    }

    @Test fun aHistoryForAnotherImageIsIgnored() = runBlocking<Unit> {
        val e = edited(); exposure(e, 0.5f)
        HistoryStore.write(e.historySnapshot()!!, file, 10, 20)
        e.shutdown()
        assertNull(HistoryStore.read(file, 11, 20, "Doc"))
        assertNull(HistoryStore.read(file, 10, 21, "Doc"))
        file.writeBytes(file.readBytes().copyOf(40))
        assertNull("A cut-off file is not an error", HistoryStore.read(file, 10, 20, "Doc"))
        file.delete()
    }

    @Test fun onlyTheNewestStepsWithinTheBudgetAreKept() = runBlocking<Unit> {
        val tile = TiledBitmap.newTile().apply { eraseColor(0xffff0000.toInt()) }
        val other = TiledBitmap.newTile().apply { eraseColor(0xff0000ff.toInt()) }
        val stack = Layer(name = "L", pixels = TiledBitmap(TILE, TILE)).let { Stack(TILE, TILE, listOf(it), it.id) }
        val target = stack.layers[0].pixels
        val history = History()
        repeat(3) { history.push(HistoryEntry("step $it", stack, stack, listOf(TileDiff(target, mapOf(0 to (tile to other)))))) }
        val oneStep = (2L * TILE * TILE * 4)
        val kept = HistoryStore.capture(stack, history, 1, maxBytes = oneStep * 2)!!
        assertEquals(listOf("step 1", "step 2"), kept.undo.map { it.name })
        assertNull(HistoryStore.capture(stack, History(), 1))
    }

    @Test fun everyDocumentFieldSurvives() = runBlocking<Unit> {
        val pixels = TiledBitmap.fromPixels(px, w, h)
        val mask = TiledBitmap.fromPixels(IntArray(w * h) { -1 }, w, h, MASK_WHITE)
        val selectionMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { eraseColor(0xff000000.toInt()) }
        val child = Layer(name = "Child", pixels = pixels, text = TextSpec("Hi", 48f))
        val outside = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff112233.toInt()) }
        val group = Layer(name = "Group", pixels = TiledBitmap(w, h), children = listOf(child), expanded = false)
        val layer = Layer(name = "L", pixels = TiledBitmap.fromPixels(px, w, h), visible = false, opacity = 0.5f, mode = LayerMode.MULTIPLY, lockAlpha = true, lockPixels = true,
            mask = mask, maskEnabled = false, editMask = true, outside = mapOf(7L to outside), bounds = Rect(-1, -2, 30, 40), lockPosition = true, linked = true,
            effects = listOf(LayerEffect("Exposure", listOf(1f, 0f), colors = mapOf("c" to 5))))
        val channel = SavedChannel(name = "C", mask = TiledBitmap(w, h, MASK_BLACK), color = 9, opacity = 0.25f, visible = true)
        val stack = Stack(w, h, listOf(group, layer), layer.id, Selection.fromMask(selectionMask), TiledBitmap(w, h, MASK_WHITE), listOf(Guide(true, 12)), listOf(channel),
            io.github.nielstron.brushkin.export.ExportMetadata(comment = "Note"))
        val history = History(); history.push(HistoryEntry("step", stack, stack))
        HistoryStore.write(HistoryStore.capture(stack, history, 3)!!, file, 1, 2)
        val back = HistoryStore.read(file, 1, 2, "Doc")!!
        val s = back.stack
        assertEquals(3L, back.version); assertTrue(back.edited)
        assertEquals(stack.activeId, s.activeId); assertEquals(stack.guides, s.guides); assertEquals("Note", s.exportMetadata.comment)
        assertNotNull(s.selection); assertEquals(MASK_WHITE, s.quickMask!!.fill)
        s.channels.single().let { assertEquals(channel.id, it.id); assertEquals("C", it.name); assertEquals(9, it.color); assertEquals(0.25f, it.opacity, 0f); assertTrue(it.visible); assertEquals(MASK_BLACK, it.mask.fill) }
        val g = s.layers[0]; assertFalse(g.expanded); assertEquals(child.text, g.children!!.single().text); assertEquals(child.id, g.children!!.single().id)
        val l = s.layers[1]
        assertEquals(layer.copy(pixels = l.pixels, mask = l.mask, outside = l.outside), l)
        assertEquals(0xff112233.toInt(), l.outside.getValue(7L).getPixel(0, 0))
        assertArrayEquals(px, l.pixels.toPixels())
        file.delete()
    }

    /** A new field must be added to [HistoryStore], or it's silently lost across a restart. */
    @Test fun storedFieldsAreKeptUpToDate() {
        fun fields(c: Class<*>) = c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf("id","name","pixels","visible","opacity","mode","lockAlpha","lockPixels","mask","maskEnabled","editMask","children","expanded","text","outside","bounds","lockPosition","linked","effects"), fields(Layer::class.java))
        assertEquals(setOf("width","height","layers","activeId","selection","quickMask","guides","channels","exportMetadata"), fields(Stack::class.java))
        assertEquals(setOf("id","name","mask","color","opacity","visible"), fields(SavedChannel::class.java))
    }
}
