package io.github.nielstron.brushkin

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.Compositor
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.ui.CanvasView
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTransformGestureTest {
    private class TestApp(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("image-transform-test-$name", mode)
    }

    private fun CanvasView.gesture(x: Float, y: Float, endX: Float = x, endY: Float = y) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long, docX: Float, docY: Float) {
            val p = floatArrayOf(docX, docY)
            viewMatrix.mapPoints(p)
            val event = MotionEvent.obtain(start, time, action, p[0], p[1], 0)
            try { onTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start, x, y)
        if (endX != x || endY != y) send(MotionEvent.ACTION_MOVE, start + 40, endX, endY)
        send(MotionEvent.ACTION_UP, start + 60, endX, endY)
    }

    private fun checkBothTargets(tool: Tool, check: suspend (CanvasView, Engine, Boolean) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            val store = ViewModelStore()
            val vm = EditorViewModel(TestApp(context)).also { store.put("test", it) }
            val engine = vm.engine
            try {
                for (whole in listOf(false, true)) {
                    vm.selectTool(Tool.BRUSH)
                    vm.changeTransformTarget(whole)
                    engine.newDocument(120, 80, Color.BLUE).join()
                    engine.addLayer("Foreground", Color.RED).join()
                    val view = CanvasView(context, vm).apply {
                        layout(0, 0, 600, 480)
                        contentBounds = RectF(60f, 80f, 540f, 400f)
                        fit()
                    }
                    vm.selectTool(tool)
                    engine.post {}.join()
                    assertEquals(whole, engine.floating!!.wholeImage)
                    check(view, engine, whole)
                    engine.cancelFloating().join()
                }
            } finally {
                store.clear()
            }
        }
    }

    @Test fun perspectiveMovesOnlyDraggedCornerOnLayerAndImage() = checkBothTargets(Tool.PERSPECTIVE) { view, engine, whole ->
        view.gesture(0f, 0f, 36f, 0f)
        val floating = engine.floating!!
        assertArrayEquals(floatArrayOf(36f, 0f, 120f, 0f, 120f, 80f, 0f, 80f), floating.corners(), 0.01f)

        val preview = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Compositor.draw(Canvas(preview), engine.stack, floating = floating)
        val expectedCorner = if (whole) Color.TRANSPARENT else Color.BLUE
        assertEquals(expectedCorner, preview.getPixel(6, 6))
        engine.commitFloating(Matrix(floating.matrix), "Perspective").join()
        assertEquals(expectedCorner, engine.flattened().getPixel(6, 6))
        assertEquals(Color.RED, engine.flattened().getPixel(70, 40))

        engine.undo().join()
        assertEquals(2, engine.stack.layers.size)
        assertEquals(Color.BLUE, engine.stack.layers.first().pixels.toBitmap().getPixel(6, 6))
        assertEquals(Color.RED, engine.stack.layers.last().pixels.toBitmap().getPixel(6, 6))
    }

    @Test fun twoHandlesKeepTheFirstAnchorFixedOnLayerAndImage() = checkBothTargets(Tool.HANDLE) { view, engine, _ ->
        view.gesture(30f, 30f)
        view.gesture(90f, 30f)
        view.gesture(90f, 30f, 90f, 50f)
        val anchors = floatArrayOf(30f, 30f, 90f, 30f)
        engine.floating!!.matrix.mapPoints(anchors)
        assertArrayEquals(floatArrayOf(30f, 30f, 90f, 50f), anchors, 0.01f)
    }
}
