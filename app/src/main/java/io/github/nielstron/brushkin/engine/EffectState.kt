package io.github.nielstron.brushkin.engine

/**
 * A [LayerEffect] as saved state: an operation and its named parameters, independent of filter
 * titles and slider order. [type] is a GEGL operation ("gegl:gaussian-blur") or a Brushkin one.
 */
internal class EffectState(val type: String, val params: List<Pair<String, String>>, val visible: Boolean) {
    fun toEffect(): LayerEffect? {
        val named = params.toMap()
        fun number(key: String) = named[key]?.toFloatOrNull() ?: 0f
        return when (type) {
            COLORIZE -> LayerEffect("Colorize", listOf(number("hue"), number("saturation"), number("lightness")), visible = visible)
            COLOR_BALANCE -> LayerEffect("Color Balance", listOf(number("cyan-red"), number("magenta-green"), number("yellow-blue")), visible = visible)
            COLOR_TO_ALPHA -> LayerEffect("Color to Alpha", emptyList(), bg = color(named["color"]), visible = visible)
            POSITIONAL -> LayerEffect(
                named["effect"] ?: return null,
                generateSequence(0) { it + 1 }.map { named[it.toString()]?.toFloatOrNull() }.takeWhile { it != null }.filterNotNull().toList(),
                color(named["fg"]), color(named["bg"]), visible, named["version"]?.toIntOrNull() ?: 2,
            )
            else -> {
                val title = GeglOperations.title(type) ?: return null
                LayerEffect(title, GeglOperations.properties(type).map { p ->
                    named[p.getString("key")]?.toFloatOrNull() ?: p.getDouble("default").toFloat()
                }, visible = visible, colors = GeglOperations.colorProperties(type).map { it.getString("key") }.filter { it in named }.associateWith { color(named[it]) })
            }
        }
    }

    companion object {
        private const val COLORIZE = "brushkin:colorize"
        private const val COLOR_BALANCE = "brushkin:color-balance"
        private const val COLOR_TO_ALPHA = "brushkin:color-to-alpha"
        /** Effects with no named form: older files' Brushkin parameters and anything unrecognized. */
        private const val POSITIONAL = "brushkin:positional"

        private fun hex(color: Int) = "#%08X".format(color)
        private fun color(text: String?) = text?.removePrefix("#")?.toLongOrNull(16)?.toInt() ?: 0
        private fun numbers(keys: List<String>, values: List<Float>) = keys.zip(values).map { (k, v) -> k to v.toString() }

        fun of(e: LayerEffect): EffectState {
            val operation = if (e.paramsVersion >= 2) GeglOperations.operation(e.name) else null
            return when {
                operation != null -> EffectState(operation, numbers(GeglOperations.properties(operation).map { it.getString("key") }, e.params) + e.colors.map { (k, v) -> k to hex(v) }, e.visible)
                e.paramsVersion >= 2 && e.name == "Colorize" -> EffectState(COLORIZE, numbers(listOf("hue", "saturation", "lightness"), e.params), e.visible)
                e.paramsVersion >= 2 && e.name == "Color Balance" -> EffectState(COLOR_BALANCE, numbers(listOf("cyan-red", "magenta-green", "yellow-blue"), e.params), e.visible)
                e.paramsVersion >= 2 && e.name == "Color to Alpha" -> EffectState(COLOR_TO_ALPHA, listOf("color" to hex(e.bg)), e.visible)
                else -> EffectState(POSITIONAL, listOf("effect" to e.name, "version" to e.paramsVersion.toString(), "fg" to hex(e.fg), "bg" to hex(e.bg)) +
                    e.params.mapIndexed { i, v -> i.toString() to v.toString() }, e.visible)
            }
        }
    }
}
