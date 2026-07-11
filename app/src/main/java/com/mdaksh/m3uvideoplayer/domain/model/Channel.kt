package com.mdaksh.m3uvideoplayer.domain.model

/** Shared with [Group.type] naming so the two can be cross-referenced/converted easily. */
object ContentType {
    const val LIVE = "LIVE"
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"
}

data class Channel(
    val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val epgId: String? = null,
    val isFavorite: Boolean = false,
    /** [ContentType.LIVE] / [ContentType.MOVIE] / [ContentType.SERIES]. M3U entries are always LIVE. */
    val contentType: String = ContentType.LIVE,
    /**
     * Static Live/VOD hint parsed at import time from the `#EXTINF:` numeric duration value
     * (per the Resume Points Engine spec, CASE A/B):
     *  - `-1` or any negative number -> Live TV/IPTV channel -> `isLive = true`.
     *  - `0` or any positive number  -> VOD/movie file       -> `isLive = false`.
     * This is only a playlist-declared hint; PlayerActivity still re-classifies from the real
     * stream's `player.length` at playback time (M3U providers often mislabel entries), so this
     * field does NOT feed the resume-points engine — it exists for list-time UI/filtering use.
     */
    val isLive: Boolean = true,
    /**
     * Provider-side id (Xtream stream_id / series_id). Kept around for series entries, whose
     * [url] is empty until the episode-resolution screen (get_series_info) is built in a
     * later phase — the player itself isn't built yet either (see Phase 3 in the plan).
     */
    val externalId: String? = null,
    /**
     * Zero-based rank in the raw playlist sequence (M3U file order / Xtream fetch order), assigned
     * at import time. Backs the "According to Playlist" folder sort and the default channel order.
     */
    val position: Int = 0,
    /**
     * Step 11.4 — Catch-up / Archive support parsed from the M3U `catchup`/`catchup-source` tags.
     * [catchupType] is the provider scheme (e.g. "default", "append", "shift", "flussonic"); null
     * means the channel has no archive. [catchupDays] is how many days back the archive reaches.
     * [catchupSource] is the optional URL template (with `${start}`/`${timestamp}`-style placeholders)
     * some providers ship; when absent we fall back to appending standard `utc`/`lutc` query params.
     */
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    /**
     * promt2 — custom HTTP headers parsed from `#EXTVLCOPT` lines that precede the stream URL.
     * Protected streams require these (Referer / User-Agent) or the request 403s and playback
     * fails with a generic network error. Null when the entry has no `#EXTVLCOPT` tags.
     */
    val httpReferrer: String? = null,
    val httpUserAgent: String? = null
) {
    /** Step 11.4 — true when this channel exposes a rewindable archive we can build a URL for. */
    val hasCatchup: Boolean
        get() = catchupType != null || !catchupSource.isNullOrBlank()
}
