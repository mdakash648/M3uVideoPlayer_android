package com.mdaksh.m3uvideoplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getChannelsForPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :groupName ORDER BY position ASC")
    fun getChannelsByGroup(playlistId: Long, groupName: String): Flow<List<ChannelEntity>>

    /** One-shot (non-Flow) snapshot of every channel inside a single group — used by folder delete. */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :groupName")
    suspend fun getChannelsInGroupOnce(playlistId: Long, groupName: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE isFavorite = 1")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>

    /** One-shot (non-Flow) snapshot of every channel across every playlist — used by the Backup export. */
    @Query("SELECT * FROM channels")
    suspend fun getAllChannelsOnce(): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsForPlaylist(playlistId: Long)

    /** Folder Delete — removes every channel inside a single group in one shot. */
    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND groupName = :groupName")
    suspend fun deleteChannelsByGroup(playlistId: Long, groupName: String)

    /** Video Delete — removes a single channel row by its id. */
    @Query("DELETE FROM channels WHERE id = :channelId")
    suspend fun deleteChannelById(channelId: Long)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)

    /**
     * Floating Resume Button engine — `playlist_resume_points` and `channels` share the same `url`
     * value per playlist, so the channel's real `contentType` (as imported/parsed) can be looked up
     * to decide `isLive` for a resume row. Scoped by `playlistId` too (matching the composite key
     * on `playlist_resume_points`) so the same stream URL reused across two different playlists
     * doesn't cross-contaminate the lookup.
     */
    @Query("SELECT contentType FROM channels WHERE playlistId = :playlistId AND url = :url LIMIT 1")
    suspend fun getContentTypeByUrl(playlistId: Long, url: String): String?

}
