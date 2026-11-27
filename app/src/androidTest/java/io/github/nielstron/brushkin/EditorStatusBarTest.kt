package io.github.nielstron.brushkin

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import io.github.nielstron.brushkin.ui.EditorViewModel
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class EditorStatusBarTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun editorUsesLightStatusIconsBeforeAndAfterLayers() {
        fun assertLightIcons() = ui.runOnIdle {
            val window = ui.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse("The dark editor needs light status bar icons", controller.isAppearanceLightStatusBars)
        }

        val vm = ui.runOnUiThread { ViewModelProvider(ui.activity)[EditorViewModel::class.java] }
        ui.waitUntil(30_000) { !vm.startupLoading }
        assertLightIcons()
        ui.onNodeWithContentDescription("Layers").performClick()
        ui.onNodeWithText("Layers").assertExists()
        Espresso.pressBack()
        ui.onNodeWithText("Layers").assertDoesNotExist()
        assertLightIcons()
    }
}
