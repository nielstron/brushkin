package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.ui.ConditionError
import io.github.nielstron.brushkin.ui.Param
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
    @Test fun circularReferencesFail() {
        val loop = Param("L", 0f..1f, 0f, key = "l", visibleWhen = "\$l.visible")
        assertThrows(ConditionError::class.java) { evaluateCondition(loop.visibleWhen, "visible", loop, listOf(loop), emptyMap()) }
    }
    @Test fun invalidExpressionsFail() {
        for (e in listOf("missing", "mode", "amount", "mode {nope}", "on || on", "2", "on on", "(on")) assertThrows(e, ConditionError::class.java) { eval(e) }
    }
}
