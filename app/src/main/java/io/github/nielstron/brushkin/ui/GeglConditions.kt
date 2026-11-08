package io.github.nielstron.brushkin.ui

/**
 * GEGL's "visible" and "sensitive" property conditions, evaluated as GIMP does:
 * `!`, `&`, `|`, parentheses, the constants 0 and 1, a boolean property's name, an enum property's
 * name with a list of nicks (`mode {a, b}`), and `$key` or `$property.key` references.
 */
internal class ConditionError(message: String) : Exception(message)

private const val MAX_DEPTH = 100

/** Throws [ConditionError] if [expression] isn't valid for these parameters. */
internal fun evaluateCondition(expression: String, key: String, self: Param, params: List<Param>, current: Map<String, Float>, depth: Int = 0): Boolean {
    if (depth > MAX_DEPTH) throw ConditionError("maximal nesting level exceeded")
    if (expression.isBlank()) return true
    return ConditionParser(expression, key, self, params, current, depth).parse()
}

private class ConditionParser(val text: String, val key: String, val self: Param, val params: List<Param>, val current: Map<String, Float>, val depth: Int) {
    private var at = 0

    private fun peek(): Char? { while (at < text.length && text[at].isWhitespace()) at++; return text.getOrNull(at) }
    private fun accept(c: Char): Boolean = (peek() == c).also { if (it) at++ }
    private fun expect(c: Char) { if (!accept(c)) throw ConditionError("expected '$c' in '$text'") }
    private fun name(): String {
        peek()
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-' || text[at] == '_')) at++
        if (at == start) throw ConditionError("invalid expression '$text'")
        return text.substring(start, at)
    }
    private fun property(name: String) = name.replace('_', '-').let { canonical -> params.firstOrNull { it.key == canonical } }
        ?: throw ConditionError("property '$name' not found")

    fun parse(): Boolean {
        val result = or()
        if (peek() != null) throw ConditionError("trailing tokens in '$text'")
        return result
    }
    // Both sides always parse, so "|" and "&" can't use Kotlin's short-circuiting.
    private fun or(): Boolean { var v = and(); while (accept('|')) { val r = and(); v = v || r }; return v }
    private fun and(): Boolean { var v = not(); while (accept('&')) { val r = not(); v = v && r }; return v }
    private fun not(): Boolean = when {
        accept('!') -> !not()
        accept('(') -> or().also { expect(')') }
        else -> simple()
    }
    private fun simple(): Boolean {
        if (accept('$')) return reference()
        val name = name()
        if (name == "0") return false
        if (name == "1") return true
        val p = property(name)
        val value = current[p.key] ?: p.default
        if (p.toggle) return value != 0f
        if (p.choices.isEmpty()) throw ConditionError("invalid type for '$name'")
        expect('{')
        var match = false
        while (!accept('}')) {
            val nick = name()
            val choice = p.choices.firstOrNull { it.second == nick } ?: throw ConditionError("unknown value '$nick' of '$name'")
            if (choice.first == value) match = true
            if (!accept(',') && peek() != '}') throw ConditionError("expected ',' in '$text'")
        }
        return match
    }
    private fun reference(): Boolean {
        val first = name()
        val (target, field) = if (accept('.')) property(first) to name() else self to first
        val expression = when (field) { "visible" -> target.visibleWhen; "sensitive" -> target.enabledWhen; else -> throw ConditionError("key '$field' not found") }
        return evaluateCondition(expression, field, target, params, current, depth + 1)
    }
}

/** A condition that can't be evaluated counts as true, as in GIMP. */
private fun holds(expression: String, key: String, p: Param, params: List<Param>, current: Map<String, Float>) =
    try { evaluateCondition(expression, key, p, params, current) } catch (e: ConditionError) { true }

internal fun Param.isVisible(params: List<Param>, current: Map<String, Float>) = holds(visibleWhen, "visible", this, params, current)
internal fun Param.isEnabled(params: List<Param>, current: Map<String, Float>) = holds(enabledWhen, "sensitive", this, params, current)
