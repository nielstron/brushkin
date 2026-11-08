package io.github.nielstron.brushkin.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Which GEGL operation each filter is, and that operation's numeric and choice properties. */
object GeglOperations {
    /** Only single-input image filters: generators, file operations and aux-input graphs are excluded. */
    val menu = linkedMapOf(
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

    /** Filters that already have their own menu or tool entry. */
    val listed = linkedMapOf(
    "Brightness-Contrast" to "brightness-contrast", "Levels" to "levels", "Hue-Saturation" to "hue-chroma",
    "Threshold" to "threshold", "Posterize" to "posterize", "Drop Shadow" to "dropshadow", "Vignette" to "vignette",
    "Channel Mixer" to "channel-mixer", "Gaussian Blur" to "gaussian-blur", "Unsharp Mask" to "unsharp-mask",
    "Pixelize" to "pixelize", "RGB Noise" to "noise-rgb", "Motion Blur" to "motion-blur-linear",
    "Noise Reduction" to "noise-reduction", "Shadows-Highlights" to "shadows-highlights", "Exposure" to "exposure",
    "Ripple" to "ripple", "Whirl and Pinch" to "whirl-pinch", "Lens Distortion" to "lens-distortion",
    )

    val all: Map<String, String> = menu + listed
    private val titles = all.entries.associate { (title, id) -> "gegl:$id" to title }
    private val properties = ConcurrentHashMap<String, List<JSONObject>>()

    fun operation(title: String): String? = all[title]?.let { "gegl:$it" }
    fun title(operation: String): String? = titles[operation]
    /** In the order a [LayerEffect]'s parameters use. */
    fun properties(operation: String): List<JSONObject> = properties.getOrPut(operation) {
        JSONArray(NativeGegl.describe(operation)).let { a -> List(a.length()) { a.getJSONObject(it) } }
    }
}
