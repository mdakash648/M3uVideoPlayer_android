package com.mdaksh.m3uvideoplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mdaksh.m3uvideoplayer.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastWatchedTime DESC")
    fun getPlaybackHistory(): Flow<List<HistoryEntity>>

    /** One-shot (non-Flow) snapshot of the full playback history — used by the Backup export. */
    @Query("SELECT * FROM playback_history")
    suspend fun getAllHistoryOnce(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    /** Bulk insert used by Restore. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryList(history: List<HistoryEntity>)

    @Query("DELETE FROM playback_history WHERE channelId = :channelId")
    suspend fun deleteHistory(channelId: Long)
}
