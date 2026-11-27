package io.github.nielstron.brushkin

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.ui.EditorViewModel
import io.github.nielstron.brushkin.ui.Sheet
import io.github.nielstron.brushkin.ui.Tool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class NavigationToolTest {
    @get:Rule(order = 0) val cleanDock: ExternalResource = object : ExternalResource() {
        private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("brushkin-tools", 0)
        private val keys = listOf("dock-tool-0", "dock-tool-1", "active-dock-slot")
        private val previous = prefs.all.filterKeys { it in keys }

        override fun before() {
            prefs.edit().apply { keys.forEach { remove(it) } }.commit()
        }

        override fun after() {
            prefs.edit().apply {
                for (key in keys) when (val value = previous[key]) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    null -> remove(key)
                }
            }.commit()
        }
    }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    private fun slot(number: Int, tool: Tool) =
        ui.onNodeWithContentDescription("Tool slot $number: ${tool.label}")

    @Test fun startInHandAndResumePendingEditsFromDock() = runBlocking {
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading && vm.canvas?.view != null }
        vm.tabs.newTab(200, 200, Color.BLUE).join()
        val tab = vm.tabs.active
        try {
            fun matrix() = ui.runOnIdle { FloatArray(9).also { vm.canvas!!.view!!.viewMatrix.getValues(it) } }

            slot(1, Tool.NAVIGATE).assertIsSelected()
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
            val scale = ui.runOnIdle { vm.canvas!!.view!!.scale }
            ui.onRoot().performTouchInput {
                down(0, center - Offset(20f, 0f))
                down(1, center + Offset(20f, 0f))
                updatePointerTo(0, center - Offset(0f, 40f))
                updatePointerTo(1, center + Offset(0f, 40f))
                move()
                up(1)
                up(0)
            }
            ui.runOnIdle {
                assertEquals(scale * 2, vm.canvas!!.view!!.scale, 0.01f)
                assertEquals(90f, vm.canvas!!.view!!.viewRotation, 0.01f)
            }
            assertEquals(version, vm.engine.info.value.version)

            slot(2, Tool.BRUSH).performClick()
            slot(1, Tool.NAVIGATE).assertIsNotSelected()
            val crop = RectF(4f, 8f, 100f, 120f)
            ui.runOnIdle { vm.pickTool(Tool.CROP); vm.cropRect = RectF(crop) }
            slot(1, Tool.NAVIGATE).performClick().assertIsSelected()
            slot(2, Tool.CROP).performClick().assertIsSelected()
            ui.runOnIdle {
                assertEquals(Tool.CROP, vm.tool)
                assertEquals(crop, vm.cropRect)
            }
        } finally {
            vm.tabs.close(tab).join()
        }
    }

    @Test fun menuAndPickerReplaceTheChosenDockSlot() {
        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading && vm.canvas?.view != null }

        fun pick(tool: Tool) {
            ui.runOnIdle { assertEquals(Sheet.TOOLS, vm.sheet) }
            ui.onAllNodes(hasText(tool.label))[0].performScrollTo().performClick()
            ui.runOnIdle {
                assertEquals(Sheet.NONE, vm.sheet)
                assertEquals(tool, vm.tool)
            }
        }

        ui.onNodeWithContentDescription("Menus and commands").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Rectang")
        ui.onNode(hasText("Tools") and hasText("Rectangle")).performClick()
        slot(1, Tool.RECT_SELECT).assertIsSelected()
        slot(2, Tool.BRUSH).assertIsNotSelected().performClick()
        ui.runOnIdle {
            assertEquals(Tool.BRUSH, vm.tool)
            assertEquals(Sheet.NONE, vm.sheet)
        }

        slot(2, Tool.BRUSH).assertIsSelected().performClick()
        pick(Tool.ELLIPSE_SELECT)
        slot(2, Tool.ELLIPSE_SELECT).assertIsSelected()
        slot(1, Tool.RECT_SELECT).performTouchInput { longClick() }
        pick(Tool.FREE_SELECT)
        slot(1, Tool.FREE_SELECT).assertIsSelected()
        slot(2, Tool.ELLIPSE_SELECT).assertIsNotSelected().performClick()
        slot(2, Tool.ELLIPSE_SELECT).performTouchInput { longClick() }
        pick(Tool.COLOR_SELECT)
        slot(2, Tool.COLOR_SELECT).assertIsSelected()
        slot(1, Tool.FREE_SELECT).assertIsNotSelected()
    }
}
