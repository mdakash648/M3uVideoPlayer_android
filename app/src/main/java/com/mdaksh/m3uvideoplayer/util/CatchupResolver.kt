package com.mdaksh.m3uvideoplayer.util

import com.mdaksh.m3uvideoplayer.domain.model.Channel
import java.util.concurrent.TimeUnit

/**
 * Step 11.4 — turns a channel's live stream URL + its catch-up metadata into an *archive* playback
 * URL for a given point in the past.
 *
 * IPTV providers expose catch-up in a handful of conventions; this covers the common M3U ones:
 *  - **default / append**: append the provider's `catchup-source` template, substituting the
 *    `${start}`/`${utc}` (segment start, epoch seconds), `${end}`/`${now}` and `${offset}` tokens.
 *    When no template is supplied we fall back to appending `?utc=<start>&lutc=<now>`.
 *  - **shift / timeshift**: append `?utc=<start>` (a.k.a. Stalker-style timeshift).
 *  - **flussonic**: rewrite an `.../index.m3u8` (or `/mono.m3u8`) live path into
 *    `.../index-<start>-<duration>.m3u8`.
 *
 * This is intentionally a *basic* mechanism (as the plan's Step 11.4 asks) — it builds a plausible
 * URL from the tags rather than negotiating EPG programme boundaries.
 */
object CatchupResolver {

    /**
     * Builds a playback URL that starts the archive at [startEpochSeconds] and runs for
     * [durationSeconds]. Returns null when the channel has no usable catch-up info.
     */
    fun resolve(
        channel: Channel,
        startEpochSeconds: Long,
        durationSeconds: Long,
        nowEpochSeconds: Long
    ): String? {
        if (!channel.hasCatchup) return null
        val liveUrl = channel.url.ifBlank { return null }

        val template = channel.catchupSource
        if (!template.isNullOrBlank()) {
            return fillTemplate(template, startEpochSeconds, durationSeconds, nowEpochSeconds)
        }

        return when (channel.catchupType?.lowercase()) {
            "flussonic", "fs" -> flussonic(liveUrl, startEpochSeconds, durationSeconds)
            "shift", "timeshift" -> appendQuery(liveUrl, "utc=$startEpochSeconds")
            // "default", "append", or unknown-but-flagged → standard utc/lutc append.
            else -> appendQuery(liveUrl, "utc=$startEpochSeconds&lutc=$nowEpochSeconds")
        }
    }

    /** Convenience: rewind [minutesAgo] minutes back from [nowEpochSeconds] for [durationSeconds]. */
    fun resolveMinutesAgo(
        channel: Channel,
        minutesAgo: Long,
        durationSeconds: Long,
        nowEpochSeconds: Long
    ): String? {
        val start = nowEpochSeconds - TimeUnit.MINUTES.toSeconds(minutesAgo)
        return resolve(channel, start, durationSeconds, nowEpochSeconds)
    }

    private fun fillTemplate(
        template: String,
        start: Long,
        duration: Long,
        now: Long
    ): String {
        val end = start + duration
        return template
            .replace("\${start}", start.toString())
            .replace("\${utc}", start.toString())
            .replace("\${timestamp}", start.toString())
            .replace("\${end}", end.toString())
            .replace("\${now}", now.toString())
            .replace("\${lutc}", now.toString())
            .replace("\${offset}", (now - start).toString())
            .replace("\${duration}", duration.toString())
            // {start} (single-brace) variants some providers use.
            .replace("{start}", start.toString())
            .replace("{utc}", start.toString())
            .replace("{now}", now.toString())
            .replace("{offset}", (now - start).toString())
            .replace("{duration}", duration.toString())
    }

    private fun flussonic(liveUrl: String, start: Long, duration: Long): String {
        // Turn ".../mono.m3u8" or ".../index.m3u8" into ".../index-<start>-<duration>.m3u8".
        val archiveSegment = "index-$start-$duration.m3u8"
        return when {
            liveUrl.contains("/mono.m3u8") -> liveUrl.replace("/mono.m3u8", "/$archiveSegment")
            liveUrl.contains("/index.m3u8") -> liveUrl.replace("/index.m3u8", "/$archiveSegment")
            liveUrl.endsWith(".m3u8") -> liveUrl.substringBeforeLast('/') + "/$archiveSegment"
            else -> appendQuery(liveUrl, "utc=$start&duration=$duration")
        }
    }

    private fun appendQuery(url: String, query: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$query"
    }
}
