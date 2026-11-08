package io.github.nielstron.brushkin.ui

import io.github.nielstron.brushkin.engine.GeglFilter
import io.github.nielstron.brushkin.engine.GeglOperations
import io.github.nielstron.brushkin.engine.NativeGegl
import org.json.JSONObject
import kotlin.math.roundToInt

internal val GEGL_ACTIONS = GeglOperations.menu

/** Pixel lengths GEGL doesn't tag as "pixel-distance"; previews scale them with the image. */
private val SCALED_UNITS = setOf("pixel-distance", "pixel-coordinate")

private val UNTAGGED_DISTANCES = mapOf("gegl:ripple" to setOf("amplitude", "period"), "gegl:shadows-highlights" to setOf("radius"))

internal val geglSpecs: Map<String, FilterSpec> by lazy {
    GeglOperations.all.mapValues { (title, id) ->
        val operation = "gegl:$id"
        val props = GeglOperations.properties(operation)
        val keys = props.map { it.getString("key") }
        val distances = props.filter { it.getString("unit") in SCALED_UNITS }.map { it.getString("key") }.toSet() + UNTAGGED_DISTANCES[operation].orEmpty()
        val parameters = props.map { p ->
            val kind = p.getString("kind")
            val options = p.optJSONArray("choices")?.let { choices -> List(choices.length()) { choices.getJSONObject(it) } }.orEmpty()
            // Unbounded native numeric fields are edited as numbers, never as enormous sliders.
            metadata(p, Param(p.getString("label"), p.getDouble("min").toFloat()..p.getDouble("max").toFloat(), p.getDouble("default").toFloat(),
                choices = options.map { it.getDouble("value").toFloat() to it.getString("label") }, nicks = options.map { it.getString("nick") },
                integral = kind == "int" || kind == "uint" || kind == "seed", toggle = kind == "boolean", seed = kind == "seed",
                allowedRange = representableLower(p.getDouble("allowedMin"))..representableUpper(p.getDouble("allowedMax")),
                gamma = p.getDouble("gamma").toFloat(), digits = p.getInt("digits")))
        }
        val colors = GeglOperations.colorProperties(operation).map { p ->
            metadata(p, Param(p.getString("label"), 0f..1f, 0f, color = p.getDouble("default").toLong().toInt()))
        }
        FilterSpec(parameters, colors, GeglOperations.description(operation).takeIf { addsInformation(title, it) }.orEmpty()) { values, picked ->
            GeglFilter(title, operation, keys.indices.associate { keys[it] to values[it].toDouble() } + picked.mapValues { it.value.toDouble() }, distances)
        }
    }
}

private fun metadata(p: JSONObject, param: Param): Param {
    val meta = p.getJSONObject("meta").let { m -> m.keys().asSequence().associateWith { m.getString(it) } }
    val withMeta = param.copy(meta = meta)
    val description = p.getString("description").takeIf { withMeta.hasDynamicText || addsInformation(param.label, it) }.orEmpty()
    return param.copy(key = p.getString("key"), visibleWhen = p.getString("visible"), enabledWhen = p.getString("sensitive"), unit = p.getString("unit"),
        axis = p.getString("axis"), direction = p.getString("direction"), role = p.getString("role"), description = description, meta = meta)
}

private fun representableUpper(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() > n) Math.nextDown(it) else it }
private fun representableLower(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() < n) Math.nextUp(it) else it }
