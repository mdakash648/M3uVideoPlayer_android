package com.mdaksh.m3uvideoplayer.domain.repository

import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import kotlinx.coroutines.flow.Flow

/**
 * Floating Resume Button engine — single-row-per-playlist "continue watching" pointer, kept
 * entirely separate from the in-player Start Over/Continue modal (see the Isolation Rule).
 */
interface PlaylistResumeRepository {

    /** RULE A — deletes any previous pointer for [playlistId], then stores this one. */
    suspend fun savePlaylistResumePoint(
        playlistId: Long,
        url: String,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    )

    /** Every playlist's active resume target, keyed by playlistId. Powers the Playlist list icons. */
    fun observeResumeTargets(): Flow<Map<Long, PlaylistResumeTarget>>

    suspend fun clearForPlaylist(playlistId: Long)
}
