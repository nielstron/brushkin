package io.github.nielstron.brushkin

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.ui.EffectChip
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EffectChipTest {
    @get:Rule val compose=createComposeRule()
    @Test fun warningRequiresOptInAndRememberingOnlySuppressesTheWarning() {
        val preferences=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("effect-chip-test",0)
        preferences.edit().clear().commit()
        try {
            compose.setContent {
                var selected by remember { mutableStateOf(false) }
                Column {
                    EffectChip(selected,preferences) { selected=it }
                    Text(if(selected) "Live" else "Baked")
                }
            }
            compose.onNodeWithText("Baked").assertExists()
            compose.onNodeWithText("Effect").performClick()
            compose.onNodeWithText("Keep as a live effect?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Baked").assertExists()
            compose.onNodeWithText("Effect").performClick()
            compose.onNodeWithText("Don’t remind me again").performClick()
            compose.onNodeWithText("Use effect").performClick()
            compose.onNodeWithText("Live").assertExists()
            assertTrue(preferences.getBoolean("hide-live-effect-warning",false))
            compose.onNodeWithText("Effect").performClick()
            compose.onNodeWithText("Baked").assertExists()
            compose.onNodeWithText("Effect").performClick()
            compose.onNodeWithText("Keep as a live effect?").assertDoesNotExist()
            compose.onNodeWithText("Live").assertExists()
        } finally { preferences.edit().clear().commit() }
    }
}
