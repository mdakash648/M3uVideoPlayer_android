package com.mdaksh.m3uvideoplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mdaksh.m3uvideoplayer.data.local.entity.ResumePointEntity

/** promt5 — read/write access to per-playlist resume points (see [ResumePointEntity]). */
@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_points WHERE playlistId = :playlistId AND url = :url LIMIT 1")
    suspend fun getResumePoint(playlistId: Long, url: String): ResumePointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(point: ResumePointEntity)

    @Query("DELETE FROM resume_points WHERE playlistId = :playlistId AND url = :url")
    suspend fun delete(playlistId: Long, url: String)
}
