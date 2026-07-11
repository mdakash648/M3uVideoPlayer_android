package com.mdaksh.m3uvideoplayer.domain.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val url: String,
    val type: String, // e.g., "M3U", "XTREAM"
    val lastUpdated: Long,
    /** Persisted [UpdateFrequency] name driving the background auto-refresh schedule. */
    val updateFrequency: String = UpdateFrequency.NEVER.name
)
