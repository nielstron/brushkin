package io.github.nielstron.brushkin

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import io.github.nielstron.brushkin.ui.Tool
import io.github.nielstron.brushkin.ui.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ViewportToolSwitchTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun choose(tool: Tool) {
        ui.onNodeWithContentDescription("Menus and commands").performClick()
        ui.onNode(hasSetTextAction()).performTextInput(tool.label)
        ui.onNode(hasText("Tools") and hasText(tool.label)).performClick()
    }

    @Test fun navigationSurvivesSelectingPaintbrushAndDrawing() = runBlocking {
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading && vm.canvas?.view?.contentBounds != null }
        vm.tabs.newTab(240, 360, Color.WHITE).join()
        val tab = vm.tabs.active
        try {
            choose(Tool.NAVIGATE)
            fun matrix() = ui.runOnIdle { FloatArray(9).also { vm.canvas!!.view!!.viewMatrix.getValues(it) } }
            ui.runOnIdle { vm.canvas!!.view!!.fit() }
            val fitted = matrix()

            ui.onRoot().performTouchInput {
                down(center)
                moveBy(Offset(19f, 13f))
                up()
                down(0, center - Offset(20f, 0f))
                down(1, center + Offset(20f, 0f))
                updatePointerTo(0, center - Offset(0f, 32f))
                updatePointerTo(1, center + Offset(0f, 32f))
                move()
                up(1)
                up(0)
            }
            val navigated = matrix()
            ui.runOnIdle { assertEquals(90f, vm.canvas!!.view!!.viewRotation, 0.1f) }
            choose(Tool.BRUSH)
            assertArrayEquals("Selecting an editing tool moved the image", navigated, matrix(), 0.01f)
            val version = vm.engine.info.value.version
            ui.onRoot().performTouchInput {
                down(center)
                moveBy(Offset(16f, 6f))
                up()
            }
            ui.waitUntil(10_000) { vm.engine.info.value.version > version }
            assertArrayEquals("Drawing moved the image", navigated, matrix(), 0.01f)

            choose(Tool.PERSPECTIVE)
            ui.waitUntil(10_000) { vm.engine.info.value.floating }
            ui.onNodeWithText("Apply").assertExists()
            assertArrayEquals("Switching from painting to transform controls moved the image", navigated, matrix(), 0.01f)
            choose(Tool.NAVIGATE)
            assertArrayEquals("Returning to Navigate with a pending transform moved the image", navigated, matrix(), 0.01f)
            ui.onNodeWithText("Reset view").performClick()
            assertArrayEquals("Reset should fit and center the document upright", fitted, matrix(), 0.01f)
            for (tool in listOf(Tool.BRUSH, Tool.TEXT, Tool.CROP, Tool.NAVIGATE)) {
                choose(tool)
                assertArrayEquals("Selecting ${tool.label} moved the fitted view", fitted, matrix(), 0.01f)
            }
        } finally {
            vm.tabs.close(tab).join()
        }
    }
}
