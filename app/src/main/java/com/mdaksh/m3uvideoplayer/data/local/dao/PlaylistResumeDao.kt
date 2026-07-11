package com.mdaksh.m3uvideoplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistResumePointEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistResumeTargetEntity
import kotlinx.coroutines.flow.Flow

/**
 * Floating Resume Button engine — read/write access to `playlist_resume_points`
 * (see [PlaylistResumePointEntity]). Kept entirely separate from [ResumeDao] (`resume_points`),
 * which backs the in-player Start Over/Continue modal only.
 */
@Dao
interface PlaylistResumeDao {

    @Insert
    suspend fun insert(point: PlaylistResumePointEntity)

    @Query("DELETE FROM playlist_resume_points WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)

    /**
     * RULE A — SINGLE-ROW PER PLAYLIST. Wipes any existing pointer for this playlist, then inserts
     * the new one, so `playlist_resume_points` never holds more than one row per playlist.
     */
    @Transaction
    suspend fun replace(point: PlaylistResumePointEntity) {
        deleteByPlaylist(point.playlistId)
        insert(point)
    }

    @Query("SELECT * FROM playlist_resume_points WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getByPlaylist(playlistId: Long): PlaylistResumePointEntity?

    /**
     * Every playlist's active resume pointer, joined with its [com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity]
     * for display (name/logo) — one row per playlist that currently has something to continue.
     * Backs the Floating Resume Button on the Playlist list screen.
     */
    @Query(
        """
        SELECT c.*, r.positionMs AS resumePositionMs, r.isLive AS resumeIsLive,
               r.completed AS resumeCompleted, r.updatedAt AS resumeUpdatedAt
        FROM playlist_resume_points r
        INNER JOIN channels c ON c.url = r.url AND c.playlistId = r.playlistId
        """
    )
    fun observeAllResumeTargets(): Flow<List<PlaylistResumeTargetEntity>>
}
