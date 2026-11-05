package io.github.nielstron.brushkin

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.nielstron.brushkin.ui.FilterParameterRow
import io.github.nielstron.brushkin.ui.Param
import org.junit.Rule
import org.junit.Test

class FilterParameterTest {
    @get:Rule val compose = createComposeRule()
    @Test fun typedDecimalsAndSliderShareTheSameValue() {
        compose.setContent {
            var value by remember { mutableFloatStateOf(3f) }
            FilterParameterRow(Param("Amount",0f..100f,3f),value) { value=it }
        }
        val field=compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement("12.34")
        field.performImeAction()
        field.assertTextEquals("12.34")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.123456f) }
        field.assertTextEquals("12.35")
    }

    @Test fun sliderUpdatesFocusedFieldAndSurvivesFocusChange() {
        compose.setContent {
            var radius by remember { mutableFloatStateOf(3f) }
            Column {
                FilterParameterRow(Param("Radius",0f..100f,3f,integral=true),radius) { radius=it }
                OutlinedTextField("other", {}, label={ androidx.compose.material3.Text("Other field") })
            }
        }
        val field=compose.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        field.assertTextEquals("70")
        compose.onAllNodes(hasSetTextAction())[1].performClick()
        field.assertTextEquals("70")
    }
}
