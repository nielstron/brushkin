package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.ui.ConditionError
import io.github.nielstron.brushkin.ui.Param
import io.github.nielstron.brushkin.ui.addsInformation
import io.github.nielstron.brushkin.ui.displayLabel
import io.github.nielstron.brushkin.ui.initialColor
import io.github.nielstron.brushkin.ui.initialValue
import io.github.nielstron.brushkin.ui.roundFilterNumber
import io.github.nielstron.brushkin.ui.evaluateCondition
import org.junit.Assert.*
import org.junit.Test

class GeglConditionTest {
    private val on = Param("On", 0f..1f, 1f, toggle = true, key = "on")
    private val off = Param("Off", 0f..1f, 0f, toggle = true, key = "keep-it")
    private val mode = Param("Mode", 0f..2f, 1f, choices = listOf(0f to "a", 1f to "b", 2f to "c-d"), key = "mode")
    private val amount = Param("Amount", 0f..1f, 0f, key = "amount")
    private val linked = Param("Linked", 0f..1f, 0f, key = "linked", visibleWhen = "mode {a}", enabledWhen = "off-by-name-is-unknown")
    private val all = listOf(on, off, mode, amount, linked)
    private fun eval(e: String, self: Param = amount, current: Map<String, Float> = emptyMap()) = evaluateCondition(e, "visible", self, all, current)

    @Test fun blankIsTrue() = assertTrue(eval("  "))
    @Test fun constants() { assertTrue(eval("1")); assertFalse(eval("0")) }
    @Test fun booleans() { assertTrue(eval("on")); assertFalse(eval(" keep-it")); assertTrue(eval("! keep_it")) }
    @Test fun enumsMatchByNick() {
        assertTrue(eval("mode {b}")); assertFalse(eval("mode {a, c-d}")); assertTrue(eval("!mode {a}"))
        assertTrue(eval("mode {a, c-d}", current = mapOf("mode" to 2f)))
        assertFalse(eval("mode {}")); assertTrue(eval("mode {b,}"))
    }
    @Test fun operatorsAndPrecedence() {
        assertTrue(eval("1 | 0 & 0")); assertFalse(eval("(1 | 0) & 0")); assertTrue(eval("!(on & keep-it)"))
    }
    @Test fun referencesToAnotherPropertysCondition() {
        assertFalse(eval("\$linked.visible")); assertTrue(eval("\$linked.visible", current = mapOf("mode" to 0f)))
        assertTrue(eval("\$visible & on", self = Param("X", 0f..1f, 0f, visibleWhen = "1")))
    }
    @Test fun dynamicLabels() {
        val p = Param("Plain", 0f..1f, 0f, key = "p", meta = mapOf("rgb-label" to "Red", "cmyk-label" to "Cyan",
            "label" to "[mode {a} : rgb-label, mode {b} : cmyk-label]"))
        val params = all + p
        assertEquals("Cyan", p.displayLabel(params, emptyMap()))
        assertEquals("Red", p.displayLabel(params, mapOf("mode" to 0f)))
        assertEquals("Plain", p.displayLabel(params, mapOf("mode" to 2f)))
        val quoted = Param("Q", 0f..1f, 0f, key = "q", meta = mapOf("label" to "[on : 'It\\'s on', 1 : 'off']"))
        assertEquals("It's on", quoted.displayLabel(listOf(on, quoted), emptyMap()))
    }

    @Test fun nicksAreMatchedWhenLabelsDiffer() {
        val styled = Param("S", 0f..1f, 1f, choices = listOf(0f to "Circle", 1f to "Square"), nicks = listOf("circle", "square"), key = "shape")
        assertTrue(evaluateCondition("shape {square}", "visible", styled, listOf(styled), emptyMap()))
        assertThrows(ConditionError::class.java) { evaluateCondition("shape {Square}", "visible", styled, listOf(styled), emptyMap()) }
    }

    @Test fun precisionFollowsDigits() {
        assertEquals(0.123f, roundFilterNumber(0.12345f, 3), 0f)
        assertEquals(12f, roundFilterNumber(12.4f, 0), 0f)
    }

    @Test fun rolesSetWhereASettingStarts() {
        val width = Param("W", 0f..4096f, 16f, key = "width", unit = "pixel-distance", axis = "x", role = "output-extent")
        val x = Param("X", 0f..4096f, 16f, key = "x", unit = "pixel-coordinate", axis = "x", role = "output-extent")
        val plain = Param("P", 0f..4096f, 16f, key = "p", unit = "pixel-distance", axis = "x")
        assertEquals(800f, width.initialValue(800, 600), 0f)
        assertEquals(0f, x.initialValue(800, 600), 0f)
        assertEquals(16f, plain.initialValue(800, 600), 0f)
        assertEquals(7, Param("C", 0f..1f, 0f, role = "color-primary", color = 1).initialColor(7, 9))
        assertEquals(9, Param("C", 0f..1f, 0f, role = "color-secondary", color = 1).initialColor(7, 9))
        assertEquals(1, Param("C", 0f..1f, 0f, color = 1).initialColor(7, 9))
    }

    @Test fun descriptionsThatRepeatTheLabelAreDropped() {
        assertFalse(addsInformation("Clip to the input extent", "Should the output extent be clipped to the input extent"))
        assertFalse(addsInformation("Black level", "Adjust the black level"))
        assertFalse(addsInformation("Mirrors", "Number of mirrors to use"))
        assertFalse(addsInformation("Anything", "   "))
        assertTrue(addsInformation("Size X", "Standard deviation for the horizontal axis"))
        assertTrue(addsInformation("Tile size", "Average diameter of each tile (in pixels)"))
    }

    @Test fun circularReferencesFail() {
        val loop = Param("L", 0f..1f, 0f, key = "l", visibleWhen = "\$l.visible")
        assertThrows(ConditionError::class.java) { evaluateCondition(loop.visibleWhen, "visible", loop, listOf(loop), emptyMap()) }
    }
    @Test fun invalidExpressionsFail() {
        for (e in listOf("missing", "mode", "amount", "mode {nope}", "on || on", "2", "on on", "(on")) assertThrows(e, ConditionError::class.java) { eval(e) }
    }
}
