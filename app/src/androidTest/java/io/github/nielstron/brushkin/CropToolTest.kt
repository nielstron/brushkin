package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import io.github.nielstron.brushkin.engine.SelectMode
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CropToolTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun cropToSelectionHonorsLayerAndImageTargets() = runBlocking {
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading }
        val engine = vm.engine
        vm.tabs.newTab(32, 24, Color.BLUE).join()
        val tab = vm.tabs.active
        try {
            engine.addLayer("Foreground", Color.RED).join()
            val selection = Path().apply { addRect(4f, 5f, 20f, 15f, Path.Direction.CW) }
            engine.select(selection, SelectMode.REPLACE, false, 0f, "Rectangle Select").join()
            ui.runOnUiThread { vm.selectTool(Tool.CROP) }

            fun cropToSelection(target: String) {
                ui.onNodeWithText(target).performClick()
                val version = engine.info.value.version
                ui.onNodeWithText("Crop to selection").performClick()
                ui.waitUntil(10_000) { engine.info.value.version > version }
            }

            cropToSelection("Layer")
            val cropped = engine.snapshot()
            assertEquals(32, cropped.width)
            assertEquals(24, cropped.height)
            assertEquals(Rect(4, 5, 20, 15), cropped.active!!.bounds)
            val foreground = cropped.active!!.pixels.toBitmap()
            assertEquals(Color.RED, foreground.getPixel(4, 5))
            assertEquals(Color.TRANSPARENT, foreground.getPixel(0, 0))
            assertEquals(Color.BLUE, cropped.layers.first().pixels.toBitmap().getPixel(0, 0))

            engine.undo().join()
            val restored = engine.snapshot().active!!
            assertNull(restored.bounds)
            assertEquals(Color.RED, restored.pixels.toBitmap().getPixel(0, 0))

            cropToSelection("Image")
            val image = engine.snapshot()
            assertEquals(16, image.width)
            assertEquals(10, image.height)
            assertEquals(2, image.layers.size)
        } finally {
            vm.tabs.close(tab).join()
        }
    }
}
