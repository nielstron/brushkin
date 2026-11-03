package io.github.nielstron.brushkin.engine

import android.graphics.BlendMode
import java.util.concurrent.atomic.AtomicLong

/** GIMP-style layer modes that map onto hardware-accelerated [BlendMode]s. */
enum class LayerMode(val label: String, val blend: BlendMode?, val oraName: String) {
    NORMAL("Normal", null, "svg:src-over"),
    MULTIPLY("Multiply", BlendMode.MULTIPLY, "svg:multiply"),
    SCREEN("Screen", BlendMode.SCREEN, "svg:screen"),
    OVERLAY("Overlay", BlendMode.OVERLAY, "svg:overlay"),
    DARKEN("Darken only", BlendMode.DARKEN, "svg:darken"),
    LIGHTEN("Lighten only", BlendMode.LIGHTEN, "svg:lighten"),
    DODGE("Dodge", BlendMode.COLOR_DODGE, "svg:color-dodge"),
    BURN("Burn", BlendMode.COLOR_BURN, "svg:color-burn"),
    HARD_LIGHT("Hard light", BlendMode.HARD_LIGHT, "svg:hard-light"),
    SOFT_LIGHT("Soft light", BlendMode.SOFT_LIGHT, "svg:soft-light"),
    DIFFERENCE("Difference", BlendMode.DIFFERENCE, "svg:difference"),
    EXCLUSION("Exclusion", BlendMode.EXCLUSION, "svg:exclusion"),
    HUE("HSL Hue", BlendMode.HUE, "svg:hue"),
    SATURATION("HSL Saturation", BlendMode.SATURATION, "svg:saturation"),
    COLOR("HSL Color", BlendMode.COLOR, "svg:color"),
    LUMINOSITY("Luminosity", BlendMode.LUMINOSITY, "svg:luminosity"),
    ADDITION("Addition", BlendMode.PLUS, "svg:plus");

    companion object {
        fun fromOra(s: String?) = entries.firstOrNull { it.oraName == s } ?: NORMAL
    }
}

private val ids = AtomicLong(1)
fun newId() = ids.getAndIncrement()

/**
 * A raster layer. Pixel data lives in [pixels]; the scalar properties are immutable so that a
 * layer can be published to the UI thread safely — property changes create a new [Layer] that
 * shares the same [pixels].
 */
data class Layer(
    val id: Long = newId(),
    val name: String,
    val pixels: TiledBitmap,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val mode: LayerMode = LayerMode.NORMAL,
    val lockAlpha: Boolean = false,
    val lockPixels: Boolean = false,
)

/** Immutable snapshot of the layer stack, bottom-most first. */
data class Stack(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val activeId: Long,
    val selection: Selection? = null,
) {
    val active: Layer? get() = layers.firstOrNull { it.id == activeId } ?: layers.lastOrNull()
    fun indexOf(id: Long) = layers.indexOfFirst { it.id == id }
    fun replace(l: Layer) = copy(layers = layers.map { if (it.id == l.id) l else it })
}
