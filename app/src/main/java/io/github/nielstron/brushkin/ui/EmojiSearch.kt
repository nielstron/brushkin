package io.github.nielstron.brushkin.ui

import android.content.Context
import android.graphics.Paint
import java.util.Locale

/**
 * Emoji search on Unicode CLDR annotations (the names and keywords keyboards use): English plus
 * the device language when bundled (assets/emoji/<lang>.tsv, built by tools/cldr_emoji.py).
 * Only emoji the system font can draw are offered.
 */
object EmojiSearch {
    class Entry(val emoji: String, val name: String, val words: List<String>)

    @Volatile private var index: List<Entry>? = null
    @Volatile private var indexLang: String? = null

    private fun split(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Loads the index (call off the UI thread). */
    fun index(ctx: Context): List<Entry> {
        val lang = Locale.getDefault().language
        index?.let { if (indexLang == lang) return it }
        val langs = listOf("en", lang).distinct().filter { l -> runCatching { ctx.assets.open("emoji/$l.tsv").close() }.isSuccess }
        // Merge names/keywords of all loaded languages per emoji, keeping CLDR's order.
        val names = LinkedHashMap<String, String>()
        val words = HashMap<String, MutableSet<String>>()
        for (l in langs) ctx.assets.open("emoji/$l.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                val f = line.split('\t'); if (f.size < 3) continue
                val e = f[0]
                if (l == lang || e !in names) names[e] = f[1]
                words.getOrPut(e) { HashSet() }.addAll(split(f[1] + " " + f[2].replace('|', ' ')))
            }
        }
        val paint = Paint()
        val out = ArrayList<Entry>(names.size)
        for ((e, name) in names) {
            // Single symbols that also have a text form get the emoji presentation selector.
            val shown = if (e.codePointCount(0, e.length) == 1 && e.codePointAt(0) < 0x1F000) e + "️" else e
            if (!paint.hasGlyph(shown)) continue
            out.add(Entry(shown, name, words[e]!!.toList()))
        }
        indexLang = lang
        return out.also { index = it }
    }

    /** Emoji whose name/keywords match every query word (exact > prefix > substring). */
    fun search(ctx: Context, query: String, limit: Int = 200): List<String> {
        val q = split(query)
        if (q.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in index(ctx)) {
            var total = 0
            for (t in q) {
                var best = 0
                for (w in e.words) {
                    val sc = when { w == t -> 3; w.startsWith(t) -> 2; t.length >= 3 && w.contains(t) -> 1; else -> 0 }
                    if (sc > best) best = sc
                }
                if (best == 0) { total = -1; break }
                total += best
            }
            // A match in the emoji's own name ranks above a keyword-only match.
            if (total > 0) scored.add(e to total + if (split(e.name).any { it.startsWith(q[0]) }) 2 else 0)
        }
        return scored.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.words.size }).take(limit).map { it.first.emoji }
    }
}
