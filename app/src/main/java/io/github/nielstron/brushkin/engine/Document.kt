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
    ADDITION("Addition", BlendMode.PLUS, "svg:plus"),
    /** Groups only: children blend straight into what's below the group (GIMP's Pass through). */
    PASS_THROUGH("Pass through", null, "svg:src-over");

    companion object {
        fun fromOra(s: String?) = entries.firstOrNull { it.oraName == s && it != PASS_THROUGH } ?: NORMAL
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
    /** Grayscale layer mask (white = visible); missing tiles are white. */
    val mask: TiledBitmap? = null,
    val maskEnabled: Boolean = true,
    /** Paint tools and filters act on the mask instead of the pixels. */
    val editMask: Boolean = false,
    /** Non-null for a layer group: its children, bottom-most first. */
    val children: List<Layer>? = null,
    val expanded: Boolean = true,
    /** Non-null for an editable text layer; its pixels are rendered from this. */
    val text: TextSpec? = null,
    /** Content beyond the canvas edges (see [LayerExtent]). */
    val outside: Map<Long, android.graphics.Bitmap> = emptyMap(),
    /** A layer boundary smaller than the canvas: painting is clipped to it. Null = canvas-sized. */
    val bounds: android.graphics.Rect? = null,
    /** GIMP's "lock position": move/transform tools leave the layer alone. */
    val lockPosition: Boolean = false,
    /** Linked layers move and transform together. */
    val linked: Boolean = false,
    /** Non-destructive filters (GIMP 3 layer effects), applied on top of [pixels] when shown. */
    val effects: List<LayerEffect> = emptyList(),
) {
    val isGroup get() = children != null
    /** What paint tools and filters currently modify. */
    val drawable: TiledBitmap get() = if (editMask && mask != null) mask else pixels
    val masked get() = mask != null && maskEnabled
}

const val MASK_WHITE = 0xFFFFFFFF.toInt()
const val MASK_BLACK = 0xFF000000.toInt()
fun newMask(w: Int, h: Int) = TiledBitmap(w, h, MASK_WHITE)

/** Brush colors become gray when painting on a mask. */
fun maskGray(color: Int): Int {
    val l = luma(color)
    return (0xFF shl 24) or (l shl 16) or (l shl 8) or l
}

/** Immutable snapshot of the layer stack, bottom-most first. */
/** A guide line: vertical at x = [pos], or horizontal at y = [pos] (image pixels). */
data class Guide(val vertical: Boolean, val pos: Int)

data class Stack(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,
    val activeId: Long,
    val selection: Selection? = null,
    /** Quick mask: while set, the selection is this grayscale channel and paint tools edit it. */
    val quickMask: TiledBitmap? = null,
    /** Guides (GIMP-style): straight lines across the image that tools snap to. */
    val guides: List<Guide> = emptyList(),
) {
    /** Every layer and group, depth-first, bottom-most first. */
    val all: List<Layer> get() = flatten(layers)
    val active: Layer? get() = find(activeId) ?: all.lastOrNull { !it.isGroup } ?: layers.lastOrNull()
    fun find(id: Long): Layer? = all.firstOrNull { it.id == id }
    fun replace(l: Layer) = copy(layers = mapTree(layers) { if (it.id == l.id) l else it })

    /** Parent group of [id] (null = top level) and the sibling list containing it. */
    fun siblingsOf(id: Long): Pair<Layer?, List<Layer>>? {
        fun search(parent: Layer?, list: List<Layer>): Pair<Layer?, List<Layer>>? {
            if (list.any { it.id == id }) return parent to list
            for (l in list) l.children?.let { c -> search(l, c)?.let { return it } }
            return null
        }
        return search(null, layers)
    }

    /** Insert [l] directly above [anchorId] in the same group (or on top when not found). */
    fun insertAbove(anchorId: Long, l: Layer): Stack {
        val sib = siblingsOf(anchorId) ?: return copy(layers = layers + l)
        val list = sib.second.toMutableList().apply { add(indexOfFirst { it.id == anchorId } + 1, l) }
        return withSiblings(sib.first, list)
    }

    fun remove(id: Long): Stack = copy(layers = removeTree(layers, id))

    /** Replace the child list of [parent] (null = top level). */
    fun withSiblings(parent: Layer?, list: List<Layer>): Stack =
        if (parent == null) copy(layers = list) else replace(find(parent.id)!!.copy(children = list))

    companion object {
        /**
         * Pass-through groups don't composite in isolation: their (visible) children take their
         * place in the parent, with the group's opacity applied to each.
         */
        fun expandPassThrough(list: List<Layer>): List<Layer> = if (list.none { it.mode == LayerMode.PASS_THROUGH && it.isGroup }) list else list.flatMap { l ->
            if (l.isGroup && l.mode == LayerMode.PASS_THROUGH) {
                if (!l.visible) emptyList() else expandPassThrough(l.children!!).map { it.copy(opacity = it.opacity * l.opacity) }
            } else listOf(l)
        }

        fun flatten(list: List<Layer>): List<Layer> = list.flatMap { listOf(it) + flatten(it.children ?: emptyList()) }
        fun mapTree(list: List<Layer>, f: (Layer) -> Layer): List<Layer> =
            list.map { l -> f(l).let { m -> if (m.children != null) m.copy(children = mapTree(m.children, f)) else m } }
        fun removeTree(list: List<Layer>, id: Long): List<Layer> =
            list.filter { it.id != id }.map { if (it.children != null) it.copy(children = removeTree(it.children, id)) else it }
    }
}
