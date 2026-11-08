package io.github.nielstron.brushkin.ui

import io.github.nielstron.brushkin.engine.GeglFilter
import io.github.nielstron.brushkin.engine.GeglOperations
import io.github.nielstron.brushkin.engine.NativeGegl
import org.json.JSONArray
import kotlin.math.roundToInt

internal val GEGL_ACTIONS = GeglOperations.menu

/** Pixel lengths GEGL doesn't tag as "pixel-distance"; previews scale them with the image. */
private val UNTAGGED_DISTANCES = mapOf("gegl:ripple" to setOf("amplitude", "period"), "gegl:shadows-highlights" to setOf("radius"))

internal val geglSpecs: Map<String, FilterSpec> by lazy {
    GeglOperations.all.mapValues { (title, id) ->
        val operation = "gegl:$id"
        val props = JSONArray(NativeGegl.describe(operation))
        val keys = List(props.length()) { props.getJSONObject(it).getString("key") }
        val distances = (0 until props.length()).filter { props.getJSONObject(it).getString("unit") == "pixel-distance" }.map { keys[it] }.toSet() +
            UNTAGGED_DISTANCES[operation].orEmpty()
        val parameters = List(props.length()) { i ->
            val p = props.getJSONObject(i)
            val kind = p.getString("kind")
            val options = p.optJSONArray("choices")?.let { choices ->
                List(choices.length()) { j -> choices.getJSONObject(j).let { it.getDouble("value").toFloat() to it.getString("label") } }
            } ?: emptyList()
            val default = p.getDouble("default").toFloat()
            // Unbounded native numeric fields are edited as numbers, never as enormous sliders.
            Param(p.getString("label"), p.getDouble("min").toFloat()..p.getDouble("max").toFloat(), default,
                choices = options, integral = kind == "int" || kind == "uint", toggle = kind == "boolean", key = p.getString("key"), visibleWhen = p.getString("visible"), enabledWhen = p.getString("sensitive"),
                allowedRange = representableLower(p.getDouble("allowedMin"))..representableUpper(p.getDouble("allowedMax")),
                gamma = p.getDouble("gamma").toFloat()) { if (kind == "int" || kind == "uint") "${it.roundToInt()}" else "%.3f".format(it) }
        }
        FilterSpec(parameters) { values ->
            GeglFilter(title, operation, keys.indices.associate { keys[it] to values[it].toDouble() }, distances)
        }
    }
}

private fun representableUpper(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() > n) Math.nextDown(it) else it }
private fun representableLower(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() < n) Math.nextUp(it) else it }
