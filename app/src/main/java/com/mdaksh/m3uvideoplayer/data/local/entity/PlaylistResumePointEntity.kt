package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity

/**
 * Floating Resume Button engine — the single "last watched item" pointer for a playlist.
 *
 * Composite primary key **(playlistId, url)** matches the unified schema spec, but RULE A (see
 * [com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistResumeDao.replace]) guarantees only ONE row
 * ever exists per [playlistId]: any previous row for that playlist is deleted before the new one
 * is inserted.
 *
 * ISOLATION RULE — this table is completely separate from `resume_points`
 * ([com.mdaksh.m3uvideoplayer.data.local.entity.ResumePointEntity]), which continues to power only
 * the in-player "Start Over / Continue" modal. This table exclusively backs the Floating Resume
 * Button shown on the Playlist list screen.
 */
@Entity(tableName = "playlist_resume_points", primaryKeys = ["playlistId", "url"])
data class PlaylistResumePointEntity(
    /** The playlist this pointer belongs to. */
    val playlistId: Long,
    /** Stream/video URL — the video identity within the playlist. */
    val url: String,
    /** Last saved playback position, in milliseconds. Always 0 for live streams. */
    val positionMs: Long,
    /** Total media length in milliseconds (0 for live / unknown). */
    val durationMs: Long,
    /** 0 = VOD, 1 = Live Stream. */
    val isLive: Boolean = false,
    /** 0 = Unfinished, 1 = Completed (VOD only — see RULE B / LOGIC_1). */
    val completed: Boolean = false,
    /** System timestamp of the last write — drives "most recently played" ordering. */
    val updatedAt: Long
)
