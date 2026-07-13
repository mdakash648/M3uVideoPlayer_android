package com.mdaksh.m3uvideoplayer.util

/**
 * promt1 — splits the "inline pipe" stream-URL convention used by many IPTV playlists and shared
 * direct links, where the HTTP headers a protected stream needs are appended to the URL itself:
 *
 * ```
 * http://host/live.m3u8|Referer=https://site.com/&User-Agent=Mozilla/5.0
 * ```
 *
 * Without this split the whole string is handed to the player as the URI, so the server never sees
 * the `Referer`/`User-Agent` it requires and answers **403 Forbidden**. Kodi/VLC-style playlists put
 * the real URL before the first `|` and one-or-more `Key=Value` header pairs after it, separated by
 * `&` (occasionally `|`).
 *
 * Scope (per the promt1 decision): only `Referer`/`User-Agent` are recognised — the two headers the
 * app already threads through both engines — so no storage or engine-signature changes are needed.
 * Any other keys in the suffix are ignored. This is applied at the single playback choke point, so
 * every source (M3U, Xtream, pasted direct links, already-imported rows) is covered without touching
 * the stored URL.
 */
object StreamUrl {

    /** A raw stream string decomposed into the URL the player should open plus its optional headers. */
    data class Parsed(
        val url: String,
        val referrer: String?,
        val userAgent: String?
    )

    /**
     * Split [raw] into its real URL and any inline `Referer`/`User-Agent` headers. When [raw] has no
     * `|` suffix (the common case) the URL is returned unchanged with null headers.
     *
     * Header values are split on the FIRST `=` only, so referer URLs that themselves contain `=`
     * (query params) survive. Keys are matched case-insensitively; `Referer` and the common
     * misspelling `Referrer` are both accepted.
     */
    fun parse(raw: String): Parsed {
        val pipe = raw.indexOf('|')
        if (pipe < 0) return Parsed(raw, null, null)

        val url = raw.substring(0, pipe).trim()
        val suffix = raw.substring(pipe + 1)

        var referrer: String? = null
        var userAgent: String? = null
        // Header pairs are separated by '&' (and, rarely, '|' when a playlist chains them).
        for (pair in suffix.split('&', '|')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).trim().lowercase()
            val value = pair.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "referer", "referrer", "http-referrer", "http-referer" -> referrer = value
                "user-agent", "http-user-agent" -> userAgent = value
            }
        }
        return Parsed(url, referrer, userAgent)
    }
}
