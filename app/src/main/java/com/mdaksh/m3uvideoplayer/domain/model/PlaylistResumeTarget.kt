package com.mdaksh.m3uvideoplayer.domain.model

/**
 * Floating Resume Button engine — the single "continue watching" target for a playlist, backed by
 * the `playlist_resume_points` table. Powers the resume icon shown per-item on the Playlist list
 * screen (see the Isolation Rule: this is separate from the in-player Start Over/Continue modal).
 */
data class PlaylistResumeTarget(
    val playlistId: Long,
    val channel: Channel,
    val positionMs: Long,
    /** Live TV / IPTV: launch at the real-time edge, ignoring [positionMs] (RULE B / LOGIC_2). */
    val isLive: Boolean,
    /** VOD only — a completed video still keeps its row, but the UI hides the resume icon for it. */
    val completed: Boolean
)
