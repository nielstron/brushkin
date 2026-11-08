package io.github.nielstron.brushkin

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.DocumentTab
import io.github.nielstron.brushkin.ui.DocumentTabs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocumentTabsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private object Host : DocumentTabs.Host {
        override fun canSwitch() = true
        override fun status(label: String?) {}
        override fun message(text: String) {}
        override fun leaving(tab: DocumentTab?) {}
        override fun arrived(tab: DocumentTab) {}
    }

    private fun stack(color: Int, w: Int = 64, h: Int = 48): Stack {
        val layer = Layer(name = "L", pixels = TiledBitmap.fromPixels(IntArray(w * h) { color }, w, h))
        return Stack(w, h, listOf(layer), layer.id)
    }

    private fun color(e: Engine) = e.stack.active!!.pixels.toPixels()[0]

    private fun test(block: suspend CoroutineScope.(dir: File, prefs: android.content.SharedPreferences) -> Unit) = runBlocking {
        val dir = File(context.cacheDir, "tabs-test").apply { deleteRecursively(); mkdirs() }
        val prefs = context.getSharedPreferences("tabs-test", 0).apply { edit().clear().commit() }
        withContext(Dispatchers.Main) {
            val scope = this
            try { block(scope, dir, prefs) } finally { dir.deleteRecursively() }
        }
    }

    private fun tabs(e: Engine, dir: File, prefs: android.content.SharedPreferences, scope: CoroutineScope) = DocumentTabs(e, dir, prefs, scope, Host)

    private suspend fun fresh(e: Engine) { e.newDocument(64, 48, 0xFFFFFFFF.toInt()).join() }

    @Test fun switchingKeepsPixelsAndUndo() = test { dir, prefs ->
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, CoroutineScope(coroutineContext + SupervisorJob()))
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        assertEquals("A blank first tab is reused", 1, t.items.size)
        val a = t.active
        e.fillSelection(0xFF445566.toInt()).join()
        assertTrue(e.info.value.canUndo)
        t.open(stack(0xFF778899.toInt()), "b.png").join()
        assertEquals(2, t.items.size)
        assertEquals(0xFF778899.toInt(), color(e))
        assertFalse(e.info.value.canUndo)
        t.select(a).join()
        assertEquals("a.png", e.docName)
        assertEquals(0xFF445566.toInt(), color(e))
        e.undo().join()
        assertEquals("Undo history came along", 0xFF112233.toInt(), color(e))
        e.shutdown()
    }

    @Test fun closedTabComesBackWithUndo() = test { dir, prefs ->
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, CoroutineScope(coroutineContext + SupervisorJob()))
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        val a = t.active
        t.open(stack(0xFF778899.toInt()), "b.png").join()
        t.close(t.active).join()
        assertEquals(1, t.items.size)
        assertSame(a, t.active)
        assertEquals(0xFF112233.toInt(), color(e))
        t.undoClose().join()
        assertEquals(listOf("a.png", "b.png"), t.items.map { it.name })
        assertEquals("b.png", t.active.name)
        assertEquals(0xFF778899.toInt(), color(e))
        e.shutdown()
    }

    @Test fun closingTheLastTabLeavesABlankOneThatUndoRemoves() = test { dir, prefs ->
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, CoroutineScope(coroutineContext + SupervisorJob()))
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        t.close(t.active).join()
        assertEquals(listOf("Untitled"), t.items.map { it.name })
        t.undoClose().join()
        assertEquals(listOf("a.png"), t.items.map { it.name })
        assertEquals(0xFF112233.toInt(), color(e))
        e.shutdown()
    }

    @Test fun allTabsSurviveARestart() = test { dir, prefs ->
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, scope)
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        t.open(stack(0xFF778899.toInt(), 32, 32), "b.png").join()
        t.autosave()
        t.select(t.items[0]).join()
        t.autosave()
        delay(1500) // The tab that went to the background saves in the background.
        prefs.edit().commit()
        e.shutdown()

        val e2 = Engine(); fresh(e2)
        val t2 = tabs(e2, dir, prefs, scope)
        assertEquals(listOf("a.png", "b.png"), t2.items.map { it.name })
        t2.restore()
        assertEquals("a.png", t2.active.name)
        assertEquals(0xFF112233.toInt(), color(e2))
        t2.select(t2.items[1]).join()
        assertEquals(32, e2.stack.width)
        assertEquals(0xFF778899.toInt(), color(e2))
        e2.shutdown()
    }

    @Test fun renamingReachesTheEngineAndSurvivesSwitchingAndRestart() = test { dir, prefs ->
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, scope)
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        val a = t.active
        t.open(stack(0xFF778899.toInt()), "b.png").join()
        t.rename(t.active, "bad/name?.png").join()
        assertEquals("bad_name_.png", e.docName)
        assertEquals("bad_name_.png", e.info.value.name)
        t.rename(a, "parked.png").join()
        t.select(a).join()
        assertEquals("A background tab keeps its new name when it comes back", "parked.png", e.docName)
        delay(300); prefs.edit().commit(); e.shutdown()
        assertEquals(listOf("parked.png", "bad_name_.png"), tabs(Engine(), dir, prefs, scope).items.map { it.name })
    }

    @Test fun typedTitlesKeepTheirDots() = test { dir, prefs ->
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, CoroutineScope(coroutineContext + SupervisorJob()))
        t.restore()
        t.renameTitle(t.active, "v1.2 sketch")
        e.post {}.join(); delay(100)
        assertEquals("v1.2 sketch.xcf", t.active.name)
        assertEquals("v1.2 sketch", io.github.nielstron.brushkin.ui.tabTitle(t.active.name))
        e.shutdown()
    }

    @Test fun closedTabsFilesAreDeletedOnceFinished() = test { dir, prefs ->
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val e = Engine(); fresh(e)
        val t = tabs(e, dir, prefs, scope)
        t.restore()
        t.open(stack(0xFF112233.toInt()), "a.png").join()
        t.open(stack(0xFF778899.toInt()), "b.png").join()
        t.autosave()
        val id = t.active.id
        val file = File(dir, "tabs/$id.xcf")
        assertTrue(file.exists())
        t.close(t.active).join()
        assertTrue("Kept for undo", file.exists())
        t.finishClosing()
        delay(300)
        assertFalse(file.exists())
        e.shutdown()
    }
}
