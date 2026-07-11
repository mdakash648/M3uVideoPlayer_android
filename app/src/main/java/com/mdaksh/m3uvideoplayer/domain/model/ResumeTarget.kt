package com.mdaksh.m3uvideoplayer.domain.model

/**
 * promt5 — the "continue watching" target for a folder/playlist: the most recently played, not-yet-
 * finished [channel] together with the [positionMs] it was left off at. Backs the floating resume FAB.
 */
data class ResumeTarget(
    val channel: Channel,
    val positionMs: Long,
    /** promt5 — Live TV / IPTV: launch at the real-time edge, ignoring [positionMs]. */
    val isLive: Boolean
)
