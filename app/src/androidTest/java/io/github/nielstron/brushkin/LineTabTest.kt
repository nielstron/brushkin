package io.github.nielstron.brushkin

import android.app.Application
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.ui.CanvasView
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LineTabTest {
    @Test fun lineContinuesOnlyFromTheSameDocumentsPreviousEndpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "line-tab-test").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getFilesDir() = dir
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("line-tab-test-$name", mode)
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { EditorViewModel(app).also { store.put("test", it) } }
        try {
            val view = withContext(Dispatchers.Main) {
                vm.selectTool(Tool.PENCIL)
                vm.fg = Color.BLACK
                vm.settings = vm.settings.copy(size = 6f, hardness = 1f, smoothing = 0f)
                CanvasView(context, vm).apply { layout(0, 0, 128, 128) }
            }
            suspend fun tap(x: Float, y: Float) = withContext(Dispatchers.Main) {
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, x, y, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            suspend fun pixel(x: Int, y: Int) = vm.engine.flattened().getPixel(x, y)

            vm.tabs.newTab(128, 128, Color.WHITE).join()
            val first = vm.tabs.active
            tap(16f, 16f)
            withContext(Dispatchers.Main) { vm.lineMode = true }
            vm.tabs.newTab(128, 128, Color.WHITE).join()
            tap(96f, 96f)
            assertEquals(Color.BLACK, pixel(96, 96))
            assertEquals("First tap must leave the rest of the new image blank", Color.WHITE, pixel(56, 56))

            vm.tabs.select(first).join()
            tap(96f, 16f)
            assertEquals("Returning to a tab should continue its own line", Color.BLACK, pixel(56, 16))

            vm.tabs.close(first).join()
            vm.tabs.undoClose().join()
            tap(96f, 80f)
            assertEquals("Undo Close should restore the tab's endpoint", Color.BLACK, pixel(96, 48))
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            dir.deleteRecursively()
        }
    }
}
