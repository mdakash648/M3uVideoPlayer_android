package com.mdaksh.m3uvideoplayer.ui.player

import android.net.Uri
import org.videolan.libvlc.Media

/**
 * promt1 — "Adaptive Dynamic Buffering & Rolling Chunk Cache Engine".
 *
 * The written spec asks for a YouTube-style engine that pre-fetches individual HLS/DASH *segments*
 * into a sliding `[-4 … +5]` window and permanently purges the ones behind it. That is ExoPlayer/
 * Media3's model (SimpleCache + CacheDataSource + PreloadManager), where app code sees each segment.
 *
 * This app plays through **LibVLC**, whose native engine owns segment download, buffering and demux
 * and exposes no per-segment handle from Kotlin — so the literal chunk window/purge machine cannot be
 * built here (a parallel downloader would just waste bandwidth VLC ignores). Instead we implement the
 * spec's GOAL — §3's smooth, stutter-free, seek-resilient playback with a forward look-ahead buffer —
 * using LibVLC's real, engine-level knobs:
 *
 *  • **network / live caching** — the read-ahead buffer VLC fills ahead of the decoder. This IS the
 *    "N chunks ahead" look-ahead (§1), just expressed in milliseconds of media instead of a segment
 *    count. Bigger buffer ⇒ more forward runway to ride out network drops (§3).
 *  • **prefetch stream filter** — for progressive files (MP4/MKV/TS over HTTP), reads a bounded
 *    memory window ahead of the read head and releases what falls behind it: the rolling forward
 *    cache + automatic purge of §1/§2, done natively.
 *  • **adaptive-logic** — for HLS (`.m3u8`) / DASH (`.mpd`) / Smooth (`.ism`), VLC's own bitrate and
 *    segment scheduler. It fills the look-ahead window, drops stale segments and re-plans on a seek
 *    (§1 CASE_2) and steps bitrate down on bandwidth loss instead of stalling (§3).
 *
 * §2's "prevent memory leaks / keep a bounded rolling window" holds for free: every buffer above is
 * inherently bounded, so VLC never retains the whole stream — only its caching window around the
 * current position.
 *
 * All values are named constants so they stay easy to tune per device/network profile.
 */

/** VOD / generic network look-ahead buffer (ms). Larger than VLC's 1s default for more runway. */
private const val NETWORK_CACHING_MS = 5000

/** Live/IPTV look-ahead buffer (ms). Kept below [NETWORK_CACHING_MS] to bound live latency. */
private const val LIVE_CACHING_MS = 3000

/** Bounded forward memory window (KiB) the prefetch filter keeps ahead for progressive files (~16 MiB). */
private const val PREFETCH_BUFFER_KB = 16384

/**
 * Adaptive bitrate/segment scheduling logic for HLS/DASH.
 * "predictive" biases toward sustained smooth playback (bandwidth-estimating) over raw quality.
 */
private const val ADAPTIVE_LOGIC = "predictive"

private enum class StreamKind { ADAPTIVE, PROGRESSIVE }

/** Classify by container so we hand VLC the buffering strategy that actually applies to it. */
private fun streamKind(url: String): StreamKind {
    val leaf = runCatching { Uri.parse(url).lastPathSegment }
        .getOrNull()
        ?.lowercase()
        .orEmpty()
    return if (leaf.endsWith(".m3u8") || leaf.endsWith(".mpd") || leaf.endsWith(".ism")) {
        StreamKind.ADAPTIVE
    } else {
        StreamKind.PROGRESSIVE
    }
}

/**
 * promt1 — apply the adaptive look-ahead buffering options for [url] to this [Media].
 *
 * Replaces the old flat `:network-caching=1500`. Both caching options are always set (VLC selects the
 * one matching the access type at runtime); the stream-type branch adds the piece that genuinely
 * applies — prefetch for progressive files, adaptive-logic for HLS/DASH.
 */
fun Media.applyPromt1Buffering(url: String) {
    // Shared forward look-ahead buffer for every network stream (§1 "chunks ahead", in ms).
    addOption(":network-caching=$NETWORK_CACHING_MS")
    addOption(":live-caching=$LIVE_CACHING_MS")

    when (streamKind(url)) {
        // HLS/DASH/Smooth: VLC's adaptive demux owns the segment window + bitrate + seek re-plan.
        StreamKind.ADAPTIVE ->
            addOption(":adaptive-logic=$ADAPTIVE_LOGIC")

        // Progressive file over HTTP: bounded rolling forward cache via the prefetch stream filter.
        StreamKind.PROGRESSIVE ->
            addOption(":prefetch-buffer-size=$PREFETCH_BUFFER_KB")
    }
}
