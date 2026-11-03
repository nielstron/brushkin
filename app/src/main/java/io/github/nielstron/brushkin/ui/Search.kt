package io.github.nielstron.brushkin.ui

import kotlin.math.ln

/**
 * BM25 ranking over command labels plus plain-language descriptions. Tiny, instant and
 * model-free; light stemming lets "brighter" match "brightness" and "brighten".
 */
class CommandSearch(private val docs: List<Cmd>) {
    private val k1 = 1.2
    private val b = 0.75
    private val terms: List<Map<String, Int>>
    private val lengths: IntArray
    private val avgLen: Double
    private val df = HashMap<String, Int>()

    init {
        terms = docs.map { c ->
            val tf = HashMap<String, Int>()
            // The label counts triple: a hit there matters most.
            repeat(3) { for (t in tokens(c.label)) tf[t] = (tf[t] ?: 0) + 1 }
            for (t in tokens("${c.section} ${c.hint} ${KEYWORDS[c.label] ?: ""}")) tf[t] = (tf[t] ?: 0) + 1
            tf
        }
        lengths = IntArray(docs.size) { terms[it].values.sum() }
        avgLen = lengths.average().coerceAtLeast(1.0)
        for (tf in terms) for (t in tf.keys) df[t] = (df[t] ?: 0) + 1
    }

    private fun idf(t: String): Double {
        val n = df[t] ?: return 0.0
        return ln(1 + (docs.size - n + 0.5) / (n + 0.5))
    }

    /** Commands ranked by relevance; the last word is prefix-matched since it may be half typed. */
    fun search(query: String): List<Cmd> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        val lastRaw = query.trim().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).lastOrNull().orEmpty()
        val scores = DoubleArray(docs.size)
        for (i in docs.indices) {
            val tf = terms[i]
            val norm = k1 * (1 - b + b * lengths[i] / avgLen)
            for ((qi, t) in q.withIndex()) {
                var f = tf[t]?.toDouble() ?: 0.0
                var w = idf(t)
                if (f == 0.0 && qi == q.lastIndex && lastRaw.length >= 2) {
                    // Prefix match for the word being typed.
                    val hit = tf.keys.firstOrNull { it.startsWith(lastRaw) || it.startsWith(t) }
                    if (hit != null) { f = tf[hit]!!.toDouble(); w = idf(hit) * 0.8 }
                }
                if (f > 0) scores[i] += w * f * (k1 + 1) / (f + norm)
            }
        }
        return docs.indices.filter { scores[it] > 0 }.sortedByDescending { scores[it] }.map { docs[it] }
    }

    companion object {
        private val STOP = setOf(
            "a", "an", "the", "it", "its", "to", "my", "me", "i", "of", "and", "or", "with", "for", "in", "on", "this",
            "that", "make", "do", "how", "can", "want", "please", "is", "be", "some", "more", "less", "from", "into",
        )

        fun tokens(s: String): List<String> =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in STOP }.map { stem(it) }

        /** A very small English stemmer: enough to merge brighter/brighten/brightness. */
        fun stem(w: String): String {
            if (w.length <= 3) return w
            for (suf in listOf("nesses", "ness", "ening", "ened", "ing", "ies", "ers", "est", "en", "er", "ed", "ly", "es", "s")) {
                if (w.endsWith(suf) && w.length - suf.length >= 3) {
                    val base = w.dropLast(suf.length)
                    return if (suf == "ies") base + "y" else base
                }
            }
            return w
        }
    }
}
