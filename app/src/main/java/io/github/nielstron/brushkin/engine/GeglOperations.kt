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
        "Box Blur" to "box-blur", "Focus Blur" to "focus-blur", "Bilateral Filter" to "bilateral-filter",
        "Denoise DCT" to "denoise-dct", "Domain Transform" to "domain-transform",
        "Symmetric Nearest Neighbor" to "snn-mean", "Mirrors" to "mirrors", "Antialias" to "antialias",
        "Edge Detection" to "edge", "Neon Edges" to "edge-neon", "Difference of Gaussians" to "difference-of-gaussians",
        "Image Gradient" to "image-gradient", "Local Threshold" to "local-threshold", "Stretch Contrast" to "stretch-contrast",
        "Color Temperature" to "color-temperature", "Vibrance" to "vibrance", "Saturation" to "saturation", "Sepia" to "sepia",
        "Color Rotate" to "color-rotate", "Mono Mixer" to "mono-mixer",
        "Extract Component" to "component-extract", "Absolute" to "absolute", "Clip Alpha" to "alpha-clip",
        "Unpremultiply Alpha" to "unpremultiply", "Alien Map" to "alien-map", "Invert (Linear)" to "invert-linear",
        "Invert (Perceptual)" to "invert-gamma", "Convolution Matrix" to "convolution-matrix",
        "Fattal Tone Mapping" to "fattal02", "Mantiuk Tone Mapping" to "mantiuk06", "Reinhard Tone Mapping" to "reinhard05",
        "Spherize" to "spherize", "Shift" to "shift", "Wind" to "wind", "Engrave" to "engrave",
        "Value Propagate" to "value-propagate", "Video Degradation" to "video-degradation", "Fractal Trace" to "fractal-trace",
        "Illusion" to "illusion", "Apply Lens" to "apply-lens", "Panorama Projection" to "panorama-projection",
        "Little Planet" to "stereographic-projection", "Supernova" to "supernova", "Long Shadow" to "long-shadow",
        "Bevel" to "bevel", "Tile Glass" to "tile-glass", "Paper Tile" to "tile-paper", "Make Seamless" to "tile-seamless",
        "SLIC Segmentation" to "slic", "Waterpixels" to "waterpixels", "Color Assimilation Grid" to "color-assimilation-grid",
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
    private val described = ConcurrentHashMap<String, List<JSONObject>>()
    private val infos = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var menuSections: Map<String, String> = emptyMap()

    private fun described(operation: String) = described.getOrPut(operation) {
        JSONArray(NativeGegl.describe(operation)).let { a -> List(a.length()) { a.getJSONObject(it) } }
    }
    fun operation(title: String): String? = all[title]?.let { "gegl:$it" }
    fun title(operation: String): String? = titles[operation]
    /** Numeric and choice properties, in the order a [LayerEffect]'s parameters use. */
    fun properties(operation: String): List<JSONObject> = described(operation).filter { it.getString("kind") != "color" }
    fun colorProperties(operation: String): List<JSONObject> = described(operation).filter { it.getString("kind") == "color" }
    fun description(operation: String): String = info(operation).getString("description")
    private fun info(operation: String) = infos.getOrPut(operation) { JSONObject(NativeGegl.operationInfo(operation)) }

    /** The menu section for a [menu] filter; plain "Filters" until [loadMenuSections] has run. */
    fun menuSection(title: String): String = menuSections[title] ?: "Filters"

    /** Groups [menu] by GEGL's first category; a category with one filter goes in "More". */
    fun loadMenuSections() {
        val category = menu.mapValues { (_, id) -> info("gegl:$id").getString("categories").substringBefore(':').ifBlank { "more" } }
        val counts = category.values.groupingBy { it }.eachCount()
        menuSections = category.mapValues { (_, c) ->
            val name = when { counts.getValue(c) < 2 -> "More"; c == "tonemapping" -> "Tone mapping"; else -> c.replace('-', ' ').replaceFirstChar { it.uppercase() } }
            "Filters: $name"
        }
    }
}
