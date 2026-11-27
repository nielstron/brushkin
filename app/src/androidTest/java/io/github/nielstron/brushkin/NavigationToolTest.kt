package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationToolTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun startInHandAndResumePendingEditsFromDock() = runBlocking {
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading && vm.canvas?.view != null }
        vm.tabs.newTab(200, 200, Color.BLUE).join()
        val tab = vm.tabs.active
        try {
            fun hand(label: String) = ui.onNodeWithContentDescription(label)
            fun matrix() = ui.runOnIdle { FloatArray(9).also { vm.canvas!!.view!!.viewMatrix.getValues(it) } }

            hand("Return to Paintbrush").assertIsOn()
            val before = matrix()
            val version = vm.engine.info.value.version
            ui.onRoot().performTouchInput {
                down(center)
                moveBy(Offset(32f, 20f))
                up()
            }
            val after = matrix()
            assertEquals(before[Matrix.MTRANS_X] + 32f, after[Matrix.MTRANS_X], 0.1f)
            assertEquals(before[Matrix.MTRANS_Y] + 20f, after[Matrix.MTRANS_Y], 0.1f)
            assertEquals(version, vm.engine.info.value.version)

            hand("Return to Paintbrush").performClick()
            hand("Navigate canvas").assertIsOff()
            val crop = RectF(4f, 8f, 100f, 120f)
            ui.runOnIdle { vm.pickTool(Tool.CROP); vm.cropRect = RectF(crop) }
            hand("Navigate canvas").performClick()
            hand("Return to Crop").assertIsOn().performClick()
            ui.runOnIdle {
                assertEquals(Tool.CROP, vm.tool)
                assertEquals(crop, vm.cropRect)
            }
        } finally {
            vm.tabs.close(tab).join()
        }
    }
}
