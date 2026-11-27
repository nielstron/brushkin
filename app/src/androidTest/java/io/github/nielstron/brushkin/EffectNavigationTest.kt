package io.github.nielstron.brushkin

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EffectNavigationTest {
    private class TestApp(context: Context, private val directory: File) : Application() {
        init { attachBaseContext(context) }
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("effect-navigation-$name",mode)
    }
    private class TouchSequence(private val view: CanvasView) {
        private val start = SystemClock.uptimeMillis()

        fun send(action: Int, time: Long, vararg points: Pair<Float, Float>) {
            val properties = Array(points.size) { i ->
                MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coords = Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first; y = points[i].second; pressure = 1f; size = 1f
                }
            }
            val event = MotionEvent.obtain(start, start + time, action, points.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun quickPinchKeepsEditsAndStationaryTapsNavigateHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "canvas-history-test").apply { mkdirs() }
        val store = ViewModelStore()
        withContext(Dispatchers.Main) {
            val vm = EditorViewModel(TestApp(context, dir)).also { store.put("test", it) }
            try {
                vm.selectTool(Tool.BRUSH)
                vm.engine.newDocument(64, 64, null).join()
                vm.engine.fillSelection(Color.RED).join()
                suspend fun pixel() = vm.engine.snapshot().active!!.pixels.toPixels().first()
                val view = CanvasView(context, vm)
                val scale = view.scale
                val touch = TouchSequence(view)
                val anchor = 20f to 32f
                val near = 60f to 32f
                val far = 140f to 32f
                val third = 40f to 60f
                val secondIndex = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
                val thirdIndex = 2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT

                touch.send(MotionEvent.ACTION_DOWN, 0, anchor)
                touch.send(MotionEvent.ACTION_POINTER_DOWN or secondIndex, 20, anchor, near)
                touch.send(MotionEvent.ACTION_MOVE, 60, anchor, far)
                touch.send(MotionEvent.ACTION_POINTER_UP or secondIndex, 100, anchor, far)
                touch.send(MotionEvent.ACTION_UP, 120, anchor)
                assertEquals(3 * scale, view.scale, 0.01f)
                assertEquals("Moving the second finger must preserve the edit", Color.RED, pixel())

                touch.send(MotionEvent.ACTION_DOWN, 200, anchor)
                touch.send(MotionEvent.ACTION_POINTER_DOWN or secondIndex, 220, anchor, near)
                touch.send(MotionEvent.ACTION_POINTER_UP or secondIndex, 240, anchor, near)
                touch.send(MotionEvent.ACTION_UP, 260, anchor)
                assertEquals("A two-finger tap still undoes", Color.TRANSPARENT, pixel())

                touch.send(MotionEvent.ACTION_DOWN, 300, anchor)
                touch.send(MotionEvent.ACTION_POINTER_DOWN or secondIndex, 320, anchor, near)
                touch.send(MotionEvent.ACTION_POINTER_DOWN or thirdIndex, 330, anchor, near, third)
                touch.send(MotionEvent.ACTION_POINTER_UP or thirdIndex, 340, anchor, near, third)
                touch.send(MotionEvent.ACTION_POINTER_UP or secondIndex, 350, anchor, near)
                touch.send(MotionEvent.ACTION_UP, 360, anchor)
                assertEquals("A three-finger tap still redoes", Color.RED, pixel())
            } finally { store.clear(); dir.deleteRecursively() }
        }
    }

    @Test fun panAndPinchWhilePreviewingOnlyChangeTheView() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"effect-navigation-test").apply { mkdirs() }
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main) { EditorViewModel(TestApp(context,dir)).also { store.put("test",it) } }
        try {
            val pixels=IntArray(64*64) { 0xff326784.toInt() }
            val layer=Layer(name="Sample",pixels=TiledBitmap.fromPixels(pixels,64,64))
            vm.engine.openStack(Stack(64,64,listOf(layer),layer.id),"test").join()
            vm.engine.filterBegin(false).join()
            vm.engine.filterPreview(GaussianBlurFilter(2f))
            withTimeout(5000) { while (vm.engine.preview == null) delay(20) }
            val preview=vm.engine.preview
            withContext(Dispatchers.Main) {
                vm.selectTool(Tool.BRUSH)
                vm.dialog="filter:Gaussian Blur"
                val view=CanvasView(context,vm)
                view.layout(0,0,600,800)
                val before=FloatArray(9).also { view.viewMatrix.getValues(it) }
                val touch = TouchSequence(view)
                touch.send(MotionEvent.ACTION_DOWN,0,200f to 200f)
                touch.send(MotionEvent.ACTION_MOVE,40,250f to 230f)
                touch.send(MotionEvent.ACTION_UP,80,250f to 230f)
                val after=FloatArray(9).also { view.viewMatrix.getValues(it) }
                assertEquals(before[Matrix.MTRANS_X]+50,after[Matrix.MTRANS_X],0.01f)
                assertEquals(before[Matrix.MTRANS_Y]+30,after[Matrix.MTRANS_Y],0.01f)
                val scale=view.scale
                touch.send(MotionEvent.ACTION_DOWN,100,200f to 200f)
                touch.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),120,200f to 200f,300f to 200f)
                touch.send(MotionEvent.ACTION_MOVE,160,150f to 200f,350f to 200f)
                touch.send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,150f to 200f,350f to 200f)
                touch.send(MotionEvent.ACTION_UP,240,150f to 200f)
                assertEquals(scale*2,view.scale,0.01f)
                assertEquals("filter:Gaussian Blur",vm.dialog)
            }
            assertArrayEquals(pixels,vm.engine.snapshot().active!!.pixels.toPixels())
            assertSame(preview,vm.engine.preview)
        } finally { withContext(Dispatchers.Main) { store.clear() }; dir.deleteRecursively() }
    }
}
