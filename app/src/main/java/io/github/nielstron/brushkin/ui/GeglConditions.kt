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
            val index = p.choices.indices.firstOrNull { (p.nicks.getOrNull(it) ?: p.choices[it].second) == nick } ?: throw ConditionError("unknown value '$nick' of '$name'")
            if (p.choices[index].first == value) match = true
            if (!accept(',') && peek() != '}') throw ConditionError("expected ',' in '$text'")
        }
        return match
    }
    /** A quoted string, a key holding the text, `$key`, or `[condition : value, ...]`. Null if no condition held. */
    fun parseString(): String? {
        val result = string()
        if (peek() != null) throw ConditionError("trailing tokens in '$text'")
        return result
    }
    private fun string(): String? = when (peek()) {
        '\'' -> quoted()
        '[' -> selection()
        '$' -> { at++; val first = name(); val (target, field) = if (accept('.')) property(first) to name() else self to first
            target.meta[field]?.let { ConditionParser(it, field, target, params, current, depth + 1).parseStringChecked() } }
        else -> name().let { self.meta[it] ?: throw ConditionError("key '$it' not found") }
    }
    private fun parseStringChecked(): String? { if (depth > MAX_DEPTH) throw ConditionError("maximal nesting level exceeded"); return parseString() }
    private fun quoted(): String {
        expect('\'')
        val out = StringBuilder()
        while (true) {
            val c = text.getOrNull(at++) ?: throw ConditionError("unterminated string in '$text'")
            if (c == '\'') return out.toString()
            out.append(if (c == '\\') text.getOrNull(at++) ?: throw ConditionError("unterminated string in '$text'") else c)
        }
    }
    private fun selection(): String? {
        expect('[')
        var result: String? = null
        var found = false
        while (!accept(']')) {
            val holds = or()
            expect(':')
            val value = string()
            if (holds && !found) { result = value; found = true }
            if (!accept(',') && peek() != ']') throw ConditionError("expected ',' in '$text'")
        }
        return result
    }

    private fun reference(): Boolean {
        val first = name()
        val (target, field) = if (accept('.')) property(first) to name() else self to first
        val expression = when (field) { "visible" -> target.visibleWhen; "sensitive" -> target.enabledWhen; else -> target.meta[field] ?: throw ConditionError("key '$field' not found") }
        return evaluateCondition(expression, field, target, params, current, depth + 1)
    }
}

/** Throws [ConditionError] if [expression] isn't valid; null if no selection branch held. */
internal fun evaluateString(expression: String, key: String, self: Param, params: List<Param>, current: Map<String, Float>): String? =
    ConditionParser(expression, key, self, params, current, 0).parseString()

/** A condition that can't be evaluated counts as true, as in GIMP. */
private fun holds(expression: String, key: String, p: Param, params: List<Param>, current: Map<String, Float>) =
    try { evaluateCondition(expression, key, p, params, current) } catch (e: ConditionError) { true }

internal fun Param.isVisible(params: List<Param>, current: Map<String, Float>) = holds(visibleWhen, "visible", this, params, current)
internal fun Param.isEnabled(params: List<Param>, current: Map<String, Float>) = holds(enabledWhen, "sensitive", this, params, current)
private fun Param.text(key: String, fallback: String, params: List<Param>, current: Map<String, Float>) =
    meta[key]?.let { try { evaluateString(it, key, this, params, current) } catch (e: ConditionError) { null } } ?: fallback
internal fun Param.displayLabel(params: List<Param>, current: Map<String, Float>) = text("label", label, params, current)
/** A label or description that depends on other settings can only be checked as it's shown. */
internal val Param.hasDynamicText get() = "label" in meta || "description" in meta

/** Blank when GEGL's description only repeats the label; fixed text was already checked on loading. */
internal fun Param.displayDescription(params: List<Param>, current: Map<String, Float>): String =
    if (!hasDynamicText) description
    else text("description", description, params, current).takeIf { addsInformation(displayLabel(params, current), it) }.orEmpty()

private val FILLER_WORDS = ("a an the of to for in on at by be is are as and or if it its this that with from should use used using value values amount " +
    "set sets adjust adjusts adjustment apply applies applied control controls specify specifies define defines determine determines number when will which into " +
    "per create creates effect image buffer filter operation result make makes change changes input output").split(' ').toSet()

private fun meaningfulWords(text: String) = Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.filter { it !in FILLER_WORDS }.toList()
private fun sameWord(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && a.take(4) == b.take(4))

/** False when at least 70% of [text]'s meaningful words are already in [label]. */
internal fun addsInformation(label: String, text: String): Boolean {
    val words = meaningfulWords(text)
    if (words.isEmpty()) return false
    val named = meaningfulWords(label)
    val joined = named.joinToString("")
    return words.count { w -> named.any { sameWord(w, it) } || (w.length > 4 && w in joined) } < words.size * 0.7
}
