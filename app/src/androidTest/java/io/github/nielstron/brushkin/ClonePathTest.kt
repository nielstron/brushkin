package io.github.nielstron.brushkin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.BrushSettings
import io.github.nielstron.brushkin.ui.Anchor
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClonePathTest {
    @Test fun pathsUseTheSelectedSourceAndKeepItsAlignment() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "clone-path-test").apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(ctx) }
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("clone-path-test-$name", mode)
        }
        val store = ViewModelStore()
        val size = 96
        val input = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (x >= 44) Color.GRAY else if ((x / 4 + y / 4) % 2 == 0) Color.RED else Color.CYAN
        }
        val image = Bitmap.createBitmap(input, size, size, Bitmap.Config.ARGB_8888)
        withContext(Dispatchers.Main) {
            val vm = EditorViewModel(app).also { store.put("test", it) }
            try {
                for (brush in listOf(Tool.CLONE, Tool.HEAL, Tool.PERSPECTIVE_CLONE)) {
                    vm.engine.openImage(image, "Pattern").join()
                    vm.selectTool(brush)
                    vm.settings = BrushSettings(size = 10f, hardness = 1f)
                    vm.cloneSource = PointF(12f, 32f)
                    vm.cloneOffset = null
                    vm.perspOffset = null
                    vm.perspPlane = floatArrayOf(0f, 0f, 96f, 0f, 96f, 96f, 0f, 96f)
                    vm.selectTool(Tool.PATHS)
                    for (y in listOf(32, 52)) {
                        vm.path.anchors.clear()
                        vm.path.anchors.add(Anchor(PointF(56f, y.toFloat())))
                        vm.path.anchors.add(Anchor(PointF(78f, y.toFloat())))
                        vm.strokePath()
                        val result = vm.engine.snapshot().active!!.pixels.toPixels()
                        assertTrue("No $brush pixels on path at y=$y", (56..78).any { result[y * size + it] != Color.GRAY })
                        if (brush != Tool.HEAL) {
                            assertEquals("$brush sampled the wrong row", input[(y + 1) * size + 18], result[(y + 1) * size + 62])
                        }
                    }
                    if (brush == Tool.PERSPECTIVE_CLONE) assertNotNull(vm.perspOffset)
                    else assertEquals(Point(-44, 0), vm.cloneOffset)
                }
            } finally {
                store.clear()
                dir.deleteRecursively()
            }
        }
    }
}
