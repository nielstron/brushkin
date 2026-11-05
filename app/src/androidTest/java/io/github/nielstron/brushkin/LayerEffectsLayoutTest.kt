package io.github.nielstron.brushkin
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.nielstron.brushkin.engine.LayerEffect
import io.github.nielstron.brushkin.ui.LayerEffects
import org.junit.Rule
import org.junit.Test
class LayerEffectsLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun mergeStaysVisibleAndLastEffectCanBeReached() {
        compose.setContent {
            Box(Modifier.width(320.dp).background(Color(0xff242424))) {
                LayerEffects(List(12) { LayerEffect("Long effect $it",emptyList()) }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Merge").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
        val range=compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.runOnIdle { org.junit.Assert.assertTrue("Finger swipe must scroll the chips",range.value() > 0) }
        compose.onNodeWithText("Long effect 11").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Merge").assertIsDisplayed()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"effect-strip.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
