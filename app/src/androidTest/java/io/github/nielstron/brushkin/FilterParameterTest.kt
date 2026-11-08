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
    @Test fun choiceRowShowsLabelAndCurrentChoiceAndChanges() {
        val param=Param("Shape",0f..1f,0f,choices=listOf(0f to "Circle",1f to "Square"))
        compose.setContent {
            var value by remember { mutableFloatStateOf(0f) }
            io.github.nielstron.brushkin.ui.FilterChoiceRow(param,value) { value=it }
        }
        compose.onNodeWithText("Shape").assertExists()
        compose.onNodeWithText("Circle").performClick()
        compose.onNodeWithText("Square").performClick()
        compose.onNodeWithText("Square").assertExists()
        compose.onNodeWithText("Circle").assertDoesNotExist()
    }

    @Test fun toggleRowIsACheckbox() {
        var value by mutableFloatStateOf(0f)
        compose.setContent { io.github.nielstron.brushkin.ui.FilterToggleRow(Param("Clip",0f..1f,0f,toggle=true),value) { value=it } }
        compose.onNodeWithText("Clip",useUnmergedTree=true).assertExists()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        org.junit.Assert.assertEquals(1f,value,0f)
    }

    @Test fun rangeRowShowsOneLabelTwoNumbersAndBothHandles() {
        val low=Param("Low",0f..1f,0.2f,key="low",role="range-start"); val high=Param("High",0f..1f,0.8f,key="high",role="range-end")
        var lowValue by mutableFloatStateOf(0.2f); var highValue by mutableFloatStateOf(0.8f)
        compose.setContent { io.github.nielstron.brushkin.ui.FilterRangeRow(low,high,lowValue,highValue,label="Threshold",onLow={ lowValue=it },onHigh={ highValue=it }) }
        compose.onNodeWithText("Threshold").assertExists()
        compose.onNodeWithText("Low").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        val handles=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        handles.assertCountEquals(2)
        handles[0].performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()
        org.junit.Assert.assertEquals(0.5f,lowValue,0.01f)
        org.junit.Assert.assertEquals(0.8f,highValue,0.01f)
    }

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
