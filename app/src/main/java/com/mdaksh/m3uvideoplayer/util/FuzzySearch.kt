package com.mdaksh.m3uvideoplayer.util

/**
 * Lightweight, dependency-free fuzzy text matching for the folder & channel search screens.
 *
 * The core metric is the Sørensen–Dice coefficient over character bigrams. Unlike a plain
 * `contains()` substring filter, it tolerates the insert / delete / transpose typos users actually
 * make, so heavy misspellings still resolve to the intended title:
 *
 *   "bacalor point", "becelor point", "bachalar point"  ->  "Bachelor Point"
 *
 * A substring hit still short-circuits to the top of the ranking, so typing an exact prefix stays
 * instant, and a per-token pass keeps single-word queries matching inside multi-word titles.
 *
 * Everything is precomputable: build a [FuzzyIndex] once per content list and reuse it across every
 * keystroke — only the (short) query is prepared per search.
 */
object FuzzySearch {

    /** Minimum similarity (0..1) for a candidate to be considered a match. Tuned for typo tolerance. */
    const val DEFAULT_THRESHOLD = 0.45

    /** Score assigned to a clean substring / token-containment hit before the coverage bonus. */
    private const val SUBSTRING_BASE = 0.9

    /**
     * A candidate string with everything the scorer needs precomputed: lowercased & punctuation-
     * stripped form, its spaceless bigrams, and the same broken down per whitespace token.
     */
    class Prepared internal constructor(
        val norm: String,
        private val bigrams: List<String>,
        private val tokens: List<String>,
        private val tokenBigrams: List<List<String>>
    ) {
        val isBlank: Boolean get() = norm.isEmpty()

        internal fun scoreAgainst(target: Prepared): Double {
            if (isBlank || target.isBlank) return 0.0
            // Exact substring is the strongest signal: rank it just under 1.0, higher the more of
            // the target it covers (so "point" ranks a short title above a long one).
            if (target.norm.contains(norm)) {
                val coverage = norm.length.toDouble() / target.norm.length.coerceAtLeast(1)
                return (SUBSTRING_BASE + (1.0 - SUBSTRING_BASE) * coverage).coerceAtMost(1.0)
            }
            val whole = dice(bigrams, target.bigrams)
            val perToken = tokenScore(target)
            return maxOf(whole, perToken)
        }

        /** Average best-token similarity: lets a single mistyped word match inside a longer title. */
        private fun tokenScore(target: Prepared): Double {
            if (tokens.isEmpty() || target.tokens.isEmpty()) return 0.0
            var sum = 0.0
            for (i in tokens.indices) {
                val qt = tokens[i]
                var best = 0.0
                for (j in target.tokens.indices) {
                    val tt = target.tokens[j]
                    val sim = if (tt.contains(qt) || qt.contains(tt)) {
                        SUBSTRING_BASE
                    } else {
                        dice(tokenBigrams[i], target.tokenBigrams[j])
                    }
                    if (sim > best) best = sim
                }
                sum += best
            }
            return sum / tokens.size
        }
    }

    /** Prepare a raw title for matching (as either the query or an index entry). */
    fun prepare(text: String): Prepared {
        val norm = normalize(text)
        val tokens = if (norm.isEmpty()) emptyList() else norm.split(' ')
        return Prepared(
            norm = norm,
            bigrams = bigrams(norm.replace(" ", "")),
            tokens = tokens,
            tokenBigrams = tokens.map { bigrams(it) }
        )
    }

    /** Convenience one-shot similarity for two raw strings (0..1). */
    fun similarity(query: String, target: String): Double =
        prepare(query).scoreAgainst(prepare(target))

    /**
     * Lowercase, trim, collapse runs of whitespace, and reduce any punctuation to a single space so
     * "bachelor-point!" and "Bachelor  Point" normalize alike.
     */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        var pendingSpace = false
        for (raw in input.trim()) {
            val ch = raw.lowercaseChar()
            when {
                ch.isLetterOrDigit() -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    sb.append(ch)
                }
                // whitespace or punctuation both act as a separator
                else -> pendingSpace = true
            }
        }
        return sb.toString()
    }

    private fun bigrams(s: String): List<String> = when {
        s.isEmpty() -> emptyList()
        s.length == 1 -> listOf(s)
        else -> (0 until s.length - 1).map { s.substring(it, it + 2) }
    }

    /** Sørensen–Dice coefficient over two bigram multisets, in 0..1. */
    private fun dice(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val counts = HashMap<String, Int>(a.size * 2)
        for (g in a) counts[g] = (counts[g] ?: 0) + 1
        var overlap = 0
        for (g in b) {
            val remaining = counts[g] ?: 0
            if (remaining > 0) {
                overlap++
                counts[g] = remaining - 1
            }
        }
        return 2.0 * overlap / (a.size + b.size)
    }
}

/** A matched item paired with its similarity [score] (0..1); higher is a closer match. */
data class Scored<T>(val item: T, val score: Double)

/**
 * A precomputed fuzzy index over a content list. Build it once per list (off the main thread) and
 * call [search] on every keystroke — each candidate's expensive normalization/bigram work is done
 * up front, so a query only prepares itself and scans.
 */
class FuzzyIndex<T> private constructor(
    private val entries: List<Pair<T, FuzzySearch.Prepared>>
) {

    /** Matches above [threshold], ranked most-similar first. Blank query yields no results. */
    fun search(
        query: String,
        threshold: Double = FuzzySearch.DEFAULT_THRESHOLD
    ): List<Scored<T>> {
        val prepared = FuzzySearch.prepare(query)
        if (prepared.isBlank) return emptyList()
        val hits = ArrayList<Scored<T>>()
        for ((item, entry) in entries) {
            val score = prepared.scoreAgainst(entry)
            if (score >= threshold) hits.add(Scored(item, score))
        }
        hits.sortByDescending { it.score }
        return hits
    }

    companion object {
        fun <T> build(items: List<T>, name: (T) -> String): FuzzyIndex<T> =
            FuzzyIndex(items.map { it to FuzzySearch.prepare(name(it)) })

        fun <T> empty(): FuzzyIndex<T> = FuzzyIndex(emptyList())
    }
}
