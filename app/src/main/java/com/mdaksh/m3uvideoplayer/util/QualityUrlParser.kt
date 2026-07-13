package com.mdaksh.m3uvideoplayer.util

/**
 * promt2 — the "Virtual Quality Manager" URL layer.
 *
 * Some M3U providers host a title's qualities as **separate physical URLs** that differ only by a
 * resolution token, e.g. `.../movie_1080p.mp4`, `.../movie_720p.mp4`, `.../movie_480p.mp4`. ExoPlayer
 * and LibVLC can only adapt bitrate *inside* one master playlist, never across distinct URLs — so the
 * app derives the alternate quality URLs itself by substituting the token, offers them in a picker,
 * and falls back down the ladder when a link fails.
 *
 * Scope note (confirmed for promt2): the quality URLs are assumed to differ ONLY by the token, so a
 * plain string replace is enough. A URL with no recognised token yields a single ["Original"] entry,
 * which the player treats as "no quality menu, no fallback" — unchanged behaviour.
 */
object QualityUrlParser {

    /** One selectable quality: a display [label] and the [url] that plays it. */
    data class Variant(val label: String, val url: String)

    /** Label used when a URL carries no recognised resolution token. */
    const val ORIGINAL_LABEL = "Original"

    /** Quality ladder, highest → lowest. Detection and generation both use this single list. */
    private val LADDER = listOf("1080p", "720p", "480p")

    private val resolutionRegex = Regex("(1080p|720p|480p)", RegexOption.IGNORE_CASE)

    /**
     * Derive the quality variants for [cleanUrl] (which must already have any inline `|Header=…`
     * suffix stripped — see [StreamUrl.parse]). Returns the full [LADDER] as substituted URLs, ordered
     * high→low, when a token is present; otherwise a single [ORIGINAL_LABEL] variant pointing at the
     * URL unchanged.
     */
    fun variants(cleanUrl: String): List<Variant> {
        val match = resolutionRegex.find(cleanUrl) ?: return listOf(Variant(ORIGINAL_LABEL, cleanUrl))
        val token = match.value
        return LADDER.map { quality ->
            Variant(quality, cleanUrl.replace(token, quality, ignoreCase = true))
        }
    }

    /**
     * Index into [variants] of the quality actually present in [cleanUrl] — the one that should start
     * selected. Falls back to 0 (the top of the ladder / the sole "Original") when nothing matches.
     */
    fun detectedIndex(cleanUrl: String, variants: List<Variant>): Int {
        val token = resolutionRegex.find(cleanUrl)?.value ?: return 0
        val idx = variants.indexOfFirst { it.label.equals(token, ignoreCase = true) }
        return if (idx >= 0) idx else 0
    }
}
