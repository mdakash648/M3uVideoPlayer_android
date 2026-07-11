package com.mdaksh.m3uvideoplayer.domain.usecase

import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistResumeRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Floating Resume Button engine — RULE A trigger: called a few seconds into playback (and
 * periodically afterwards) from [com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity] to write the
 * single active resume pointer for the current playlist.
 */
class SavePlaylistResumePointUseCase @Inject constructor(
    private val repository: PlaylistResumeRepository
) {
    suspend operator fun invoke(
        playlistId: Long,
        url: String,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    ) = repository.savePlaylistResumePoint(playlistId, url, positionMs, durationMs, isLive)
}

/** Floating Resume Button engine — one entry per playlist that currently has something to continue. */
class ObservePlaylistResumeTargetsUseCase @Inject constructor(
    private val repository: PlaylistResumeRepository
) {
    operator fun invoke(): Flow<Map<Long, PlaylistResumeTarget>> = repository.observeResumeTargets()
}

/** Clears a playlist's resume pointer — called when the playlist itself is deleted. */
class ClearPlaylistResumePointUseCase @Inject constructor(
    private val repository: PlaylistResumeRepository
) {
    suspend operator fun invoke(playlistId: Long) = repository.clearForPlaylist(playlistId)
}
