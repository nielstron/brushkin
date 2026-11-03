package io.github.nielstron.brushkin.engine

import org.json.JSONArray
import org.json.JSONObject

/** A non-destructive filter on a layer: a filter by name with its parameters and colors. */
data class LayerEffect(val name: String, val params: List<Float>, val fg: Int = 0, val bg: Int = 0, val visible: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("params", JSONArray(params.map { it.toDouble() })).put("fg", fg).put("bg", bg).put("visible", visible)

    companion object {
        fun fromJson(o: JSONObject) = LayerEffect(
            o.getString("name"), o.getJSONArray("params").let { a -> List(a.length()) { a.getDouble(it).toFloat() } },
            o.optInt("fg"), o.optInt("bg"), o.optBoolean("visible", true),
        )
        fun listToJson(l: List<LayerEffect>) = JSONArray(l.map { it.toJson() }).toString()
        fun listFromJson(s: String) = JSONArray(s).let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } }
    }
}

/**
 * Renders layers with their effects. Results are cached per layer and keyed by the identity and
 * version of every tile plus the effect list, so any edit (or undo) invalidates them.
 */
object Effects {
    /** Builds the filter for an effect (set by the UI, which owns the filter definitions). */
    @Volatile var factory: ((LayerEffect) -> Filter?)? = null

    private class Entry(val key: Long, val effects: List<LayerEffect>, val result: TiledBitmap)
    // Weak keys: results go away with the layer versions (undo history) they belong to.
    private val cache: MutableMap<TiledBitmap, Entry> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    private val pending: MutableMap<TiledBitmap, Long> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private fun key(t: TiledBitmap): Long {
        var k = 1125899906842597L
        for (i in t.tiles.indices) k = 31 * k + (System.identityHashCode(t.tiles[i]).toLong() shl 20) + t.versions.get(i)
        return k
    }

    private fun active(l: Layer) = l.effects.filter { it.visible }

    private fun compute(t: TiledBitmap, effects: List<LayerEffect>): TiledBitmap {
        var cur = t
        for (e in effects) { val f = factory?.invoke(e) ?: continue; cur = applyFilterBanded(cur, f, null) }
        return cur
    }

    /** The layer as displayed, computed now if needed (export, merging, flattening). */
    fun pixelsSync(l: Layer): TiledBitmap {
        val fx = active(l); if (fx.isEmpty()) return l.pixels
        val k = key(l.pixels)
        cache[l.pixels]?.let { if (it.key == k && it.effects == fx) return it.result }
        return compute(l.pixels, fx).also { cache[l.pixels] = Entry(k, fx, it) }
    }

    /**
     * The layer for on-screen drawing: the cached result, recomputed in the background when stale
     * ([onReady] is called then). Meanwhile the previous result is shown so nothing flickers.
     */
    fun pixelsAsync(l: Layer, launch: (() -> Unit) -> Unit, onReady: () -> Unit): TiledBitmap {
        val fx = active(l); if (fx.isEmpty()) return l.pixels
        val t = l.pixels
        val k = key(t)
        val e = cache[t]
        if (e != null && e.key == k && e.effects == fx) return e.result
        if (pending.put(t, k) != k) launch {
            try {
                val k2 = key(t)
                val r = compute(t, fx)
                cache[t] = Entry(k2, fx, r)
            } finally { pending.remove(t) }
            onReady()
        }
        return e?.result ?: t
    }
}
