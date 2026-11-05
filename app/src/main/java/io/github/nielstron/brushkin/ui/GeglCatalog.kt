package io.github.nielstron.brushkin.ui

import io.github.nielstron.brushkin.engine.GeglFilter
import io.github.nielstron.brushkin.engine.NativeGegl
import org.json.JSONArray
import kotlin.math.roundToInt

/** Only single-input image filters: generators, file operations and aux-input graphs are excluded. */
internal val GEGL_ACTIONS = linkedMapOf(
    "Median Blur" to "median-blur", "Selective Gaussian Blur" to "gaussian-blur-selective",
    "Circular Motion Blur" to "motion-blur-circular", "Zoom Motion Blur" to "motion-blur-zoom",
    "Lens Blur" to "lens-blur", "Mean Curvature Blur" to "mean-curvature-blur",
    "High Pass" to "high-pass", "Red-eye Removal" to "red-eye-removal", "Deinterlace" to "deinterlace",
    "Emboss" to "emboss", "Mosaic" to "mosaic", "Newsprint" to "newsprint",
    "Polar Coordinates" to "polar-coordinates", "Waves" to "waves",
    "Bloom" to "bloom", "Lens Flare" to "lens-flare", "Soft Glow" to "softglow",
    "HSV Noise" to "noise-hsv", "LCH Noise" to "noise-cie-lch", "Hurl Noise" to "noise-hurl",
    "Pick Noise" to "noise-pick", "Slur Noise" to "noise-slur", "Spread Noise" to "noise-spread",
    "Sobel" to "edge-sobel", "Laplace" to "edge-laplace", "Distance Map" to "distance-transform",
    "Normal Map" to "normal-map", "Cartoon" to "cartoon", "Oilify" to "oilify",
    "Cubism" to "cubism", "Canvas Texture" to "texturize-canvas", "Photocopy" to "photocopy",
    "HSV Stretch Contrast" to "stretch-contrast-hsv", "Color Enhance" to "color-enhance",
    "Value Invert" to "value-invert", "Dither" to "dither", "RGB Clip" to "rgb-clip",
)

internal val geglSpecs: Map<String, FilterSpec> by lazy {
    GEGL_ACTIONS.mapValues { (title, id) ->
        val operation = "gegl:$id"
        val props = JSONArray(NativeGegl.describe(operation))
        val keys = List(props.length()) { props.getJSONObject(it).getString("key") }
        val distances = (0 until props.length()).filter { props.getJSONObject(it).getString("unit") == "pixel-distance" }.map { keys[it] }.toSet()
        val parameters = List(props.length()) { i ->
            val p = props.getJSONObject(i)
            val kind = p.getString("kind")
            val options = p.optJSONArray("choices")?.let { choices ->
                List(choices.length()) { j -> choices.getJSONObject(j).let { it.getDouble("value").toFloat() to it.getString("label") } }
            } ?: if (kind == "boolean") listOf(0f to "Off", 1f to "On") else emptyList()
            val default = p.getDouble("default").toFloat()
            // Unbounded native numeric fields are edited as numbers, never as enormous sliders.
            Param(p.getString("label"), p.getDouble("min").toFloat()..p.getDouble("max").toFloat(), default,
                choices = options, integral = kind == "int",
                allowedRange = representableLower(p.getDouble("allowedMin"))..representableUpper(p.getDouble("allowedMax")),
                gamma = p.getDouble("gamma").toFloat()) { if (kind == "int") "${it.roundToInt()}" else "%.3f".format(it) }
        }
        FilterSpec(parameters, defaultIsIdentity = false) { values ->
            GeglFilter(title, operation, keys.indices.associate { keys[it] to values[it].toDouble() }, distances)
        }
    }
}

private fun representableUpper(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() > n) Math.nextDown(it) else it }
private fun representableLower(n: Double): Float = n.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat().let { if (it.toDouble() < n) Math.nextUp(it) else it }
