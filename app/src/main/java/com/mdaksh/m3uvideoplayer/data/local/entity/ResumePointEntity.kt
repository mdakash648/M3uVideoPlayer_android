package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity

/**
 * promt5 — persistent, per-playlist "resume where you left off" state.
 *
 * Composite primary key **(playlistId, url)** so watch history is strictly isolated per playlist:
 * the same stream URL appearing in Playlist A and Playlist B keeps two independent rows, and the
 * resume FAB on B never surfaces what was watched on A. The URL is used as the video identity (not
 * the channel id, which is `autoGenerate` and gets reassigned on every playlist re-import).
 *
 * [isLive] distinguishes Live TV / IPTV from VOD: for live rows we deliberately keep [positionMs]
 * at 0 and never restore a timestamp — the FAB just relaunches the channel at the real-time edge.
 */
@Entity(tableName = "resume_points", primaryKeys = ["playlistId", "url"])
data class ResumePointEntity(
    /** The playlist this watch record belongs to (isolation key). */
    val playlistId: Long,
    /** Stream URL — the video identity within the playlist. */
    val url: String,
    /** Last saved playback position, in milliseconds. Always 0 for live streams. */
    val positionMs: Long,
    /** Total media length in milliseconds when the position was saved (0 if unknown / live). */
    val durationMs: Long,
    /** True for Live TV / IPTV: no timestamp is stored or restored (see class doc). */
    val isLive: Boolean = false,
    /** True once a VOD crossed the 95%-watched completion threshold; suppresses the resume prompt. */
    val completed: Boolean = false,
    /** Wall-clock time (epoch ms) of the last save — drives "most recently played" ordering. */
    val updatedAt: Long
)
