package io.github.nielstron.brushkin.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * A non-destructive filter on a layer: a filter by name with its parameters and colors.
 * [paramsVersion] 1 is Brushkin's own parameter list, from before the filter's parameters came from GEGL.
 */
data class LayerEffect(val name: String, val params: List<Float>, val fg: Int = 0, val bg: Int = 0, val visible: Boolean = true, val paramsVersion: Int = 2, val colors: Map<String, Int> = emptyMap()) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("params", JSONArray(params.map { it.toDouble() })).put("fg", fg).put("bg", bg).put("visible", visible).put("paramsVersion", paramsVersion).put("colors", JSONObject(colors.toMap()))

    companion object {
        fun fromJson(o: JSONObject) = LayerEffect(
            o.getString("name"), o.getJSONArray("params").let { a -> List(a.length()) { a.getDouble(it).toFloat() } },
            o.optInt("fg"), o.optInt("bg"), o.optBoolean("visible", true), o.optInt("paramsVersion", 1),
            o.optJSONObject("colors")?.let { c -> c.keys().asSequence().associateWith { c.getInt(it) } } ?: emptyMap(),
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
    /** Told once per effect when its settings can't be rendered, for example from a damaged file. */
    @Volatile var onSkipped: ((String) -> Unit)? = null
    private val skipped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The layer still shows every other effect. */
    internal fun reportSkipped(name: String, e: Exception) {
        android.util.Log.e("Brushkin", "skipped effect $name", e)
        if (skipped.add(name)) onSkipped?.invoke("Skipped the $name effect: its settings can't be used")
    }

    private class Entry(val key: Long, val effects: List<LayerEffect>, val result: TiledBitmap)
    // Weak keys: results go away with the layer versions (undo history) they belong to.
    private val cache: MutableMap<TiledBitmap, Entry> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    private val scheduling = Any()
    private val pending = mutableSetOf<TiledBitmap>()
    private val _computing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val computing: kotlinx.coroutines.flow.StateFlow<Boolean> = _computing

    private fun key(t: TiledBitmap): Long {
        var k = 1125899906842597L
        for (i in t.tiles.indices) k = 31 * k + (System.identityHashCode(t.tiles[i]).toLong() shl 20) + t.versions.get(i)
        return k
    }

    private fun active(l: Layer) = l.effects.filter { it.visible }

    private class Pipeline(val effects: List<LayerEffect>, val stages: List<Pair<Filter?, IncrementalFilter?>>) {
        fun close() = stages.forEach { it.second?.close() }
        fun render(source: TiledBitmap): TiledBitmap {
            var result=source
            for ((filter, incremental) in stages) if (filter != null) {
                result=try { incremental?.render(result) ?: applyFilterBanded(result,filter,null) }
                    catch (e: Exception) { reportSkipped(filter.name, e); result }
            }
            return result
        }
    }
    // Native graphs need explicit disposal. Keep only the two most recently rendered layers;
    // evicting a pipeline leaves the displayed bitmap cache intact. UI never takes this lock.
    private val pipelines=LinkedHashMap<TiledBitmap,Pipeline>(4,0.75f,true)
    private fun compute(t: TiledBitmap, effects: List<LayerEffect>): TiledBitmap = synchronized(pipelines) {
        if (effects.isEmpty()) {
            pipelines.remove(t)?.close()
            return@synchronized t
        }
        var pipeline=pipelines[t]
        if (pipeline == null || pipeline.effects != effects) {
            pipelines.remove(t)
            // Adding an effect must not rerun the unchanged prefix (e.g. blur → blur + exposure).
            val prefix=pipeline?.effects?.zip(effects)?.takeWhile { (a,b) -> a == b }?.size ?: 0
            val stages=pipeline?.stages?.take(prefix)?.toMutableList() ?: mutableListOf()
            pipeline?.stages?.drop(prefix)?.forEach { it.second?.close() }
            while (pipelines.size >= 2) {
                val oldest=pipelines.entries.iterator()
                oldest.next().value.close(); oldest.remove()
            }
            try {
                for(e in effects.drop(prefix)) {
                    stages.add(try {
                        val filter=factory?.invoke(e)
                        filter to filter?.incremental(t.width,t.height)
                    } catch (failure: Exception) {
                        reportSkipped(e.name, failure); null to null
                    })
                }
            } catch (e: Throwable) {
                stages.forEach { it.second?.close() }; throw e
            }
            pipeline=Pipeline(effects,stages)
            pipelines[t]=pipeline
        }
        pipeline.render(t)
    }

    /** The layer as displayed, computed now if needed (export, merging, flattening). */
    fun pixelsSync(l: Layer): TiledBitmap {
        val fx = active(l)
        if (fx.isEmpty()) {
            cache.remove(l.pixels)
            return compute(l.pixels, fx)
        }
        val k = key(l.pixels)
        cache[l.pixels]?.let { if (it.key == k && it.effects == fx) return it.result }
        return compute(l.pixels, fx).also { cache[l.pixels] = Entry(k, fx, it) }
    }

    /**
     * The layer for on-screen drawing: the cached result, recomputed in the background when stale
     * ([onReady] is called then). Meanwhile the previous result is shown so nothing flickers.
     */
    fun pixelsAsync(l: Layer, launch: (() -> Unit) -> Unit, onReady: () -> Unit): TiledBitmap {
        val fx = active(l)
        val t = l.pixels
        if (fx.isEmpty() && cache[t] == null && synchronized(scheduling) { t !in pending }) return t
        val k = key(t)
        val e = cache[t]
        if (e != null && e.key == k && e.effects == fx) return e.result
        val start = synchronized(scheduling) {
            pending.add(t).also { _computing.value = pending.isNotEmpty() }
        }
        if (start) launch {
            try {
                val k2 = key(t)
                val r = compute(t, fx)
                if (fx.isEmpty()) cache.remove(t) else cache[t] = Entry(k2, fx, r)
            } finally {
                synchronized(scheduling) {
                    pending.remove(t)
                    _computing.value = pending.isNotEmpty()
                }
                // A fresh frame requests the latest pixels/parameters if they changed meanwhile.
                onReady()
            }
        }
        return if (fx.isEmpty()) t else e?.result ?: t
    }
}
