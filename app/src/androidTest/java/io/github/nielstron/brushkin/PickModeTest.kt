package io.github.nielstron.brushkin

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TiledBitmap
import io.github.nielstron.brushkin.ui.CanvasView
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PickModeTest {
    @Test fun rectangleDragAfterPaintbrushPickCreatesSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "pick-mode-test").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("pick-mode-test-$name", mode)
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { EditorViewModel(app).also { store.put("test", it) } }
        try {
            val pixels = TiledBitmap.fromPixels(IntArray(128 * 96) { Color.RED }, 128, 96)
            val layer = Layer(name = "Sample", pixels = pixels)
            vm.engine.openStack(Stack(128, 96, listOf(layer), layer.id), "test").join()
            val view = withContext(Dispatchers.Main) {
                CanvasView(context, vm).apply { layout(0, 0, 128, 96) }
            }
            suspend fun gesture(tool: Tool, drag: Boolean) = withContext(Dispatchers.Main) {
                vm.selectTool(tool)
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, elapsed: Long, x: Float, y: Float) {
                    val event = MotionEvent.obtain(down, down + elapsed, action, x, y, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN, 0, 16f, 16f)
                if (drag) touch(MotionEvent.ACTION_MOVE, 20, 96f, 72f)
                touch(MotionEvent.ACTION_UP, 40, if (drag) 96f else 16f, if (drag) 72f else 16f)
            }
            withContext(Dispatchers.Main) { vm.pickMode = true; vm.fg = Color.BLUE }
            gesture(Tool.BRUSH, drag = false)
            withContext(Dispatchers.Main) {
                withTimeout(5000) { while (vm.recentColors.first() != Color.RED) delay(10) }
                assertEquals(Color.RED, vm.fg)
                vm.fg = Color.BLUE
            }
            gesture(Tool.RECT_SELECT, drag = true)
            assertEquals(Rect(16, 16, 96, 72), vm.engine.snapshot().selection?.bounds)
            assertEquals(Color.BLUE, vm.fg)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            dir.deleteRecursively()
        }
    }
}
