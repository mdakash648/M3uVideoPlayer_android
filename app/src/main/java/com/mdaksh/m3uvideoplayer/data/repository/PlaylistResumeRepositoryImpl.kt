package com.mdaksh.m3uvideoplayer.data.repository

import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistResumeDao
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistResumePointEntity
import com.mdaksh.m3uvideoplayer.domain.model.ContentType
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistResumeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PlaylistResumeRepositoryImpl @Inject constructor(
    private val dao: PlaylistResumeDao,
    private val channelDao: ChannelDao
) : PlaylistResumeRepository {

    override suspend fun savePlaylistResumePoint(
        playlistId: Long,
        url: String,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    ) {
        // isLive now tracks the playlist-declared channels.contentType (looked up by the shared
        // playlistId+url), NOT the caller's runtime player.length check — per spec: MOVIE -> false,
        // LIVE -> true. SERIES has no rule from the spec, and an unmatched URL (e.g. row written
        // before the channel sync finished) can't be classified either; both fall back to the
        // caller's own runtime isLive so a resume row is never silently misclassified.
        val contentType = channelDao.getContentTypeByUrl(playlistId, url)
        val resolvedIsLive = when (contentType) {
            ContentType.MOVIE -> false
            ContentType.LIVE -> true
            else -> isLive
        }

        // RULE B / LOGIC_1 — VOD Completion: reached (or passed) its own duration.
        val completed = !resolvedIsLive && durationMs > 0L && positionMs >= durationMs
        dao.replace(
            PlaylistResumePointEntity(
                playlistId = playlistId,
                url = url,
                // RULE B / LOGIC_2 — Live TV never stores a timestamp; the FAB always relaunches at
                // the live edge, bypassing positionMs entirely.
                positionMs = if (resolvedIsLive) 0L else positionMs,
                durationMs = if (resolvedIsLive) 0L else durationMs,
                isLive = resolvedIsLive,
                completed = completed,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override fun observeResumeTargets(): Flow<Map<Long, PlaylistResumeTarget>> =
        dao.observeAllResumeTargets().map { rows ->
            rows.associate { row ->
                row.channel.playlistId to PlaylistResumeTarget(
                    playlistId = row.channel.playlistId,
                    channel = row.channel.toDomain(),
                    positionMs = row.resumePositionMs,
                    isLive = row.resumeIsLive,
                    completed = row.resumeCompleted
                )
            }
        }

    override suspend fun clearForPlaylist(playlistId: Long) {
        dao.deleteByPlaylist(playlistId)
    }
}
