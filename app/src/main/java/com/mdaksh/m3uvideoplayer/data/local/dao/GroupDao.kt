package com.mdaksh.m3uvideoplayer.data.local.dao

import androidx.room.*
import com.mdaksh.m3uvideoplayer.data.local.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM playlist_groups WHERE playlistId = :playlistId")
    fun getGroupsByPlaylist(playlistId: Long): Flow<List<GroupEntity>>

    /** One-shot (non-Flow) snapshot of every group across every playlist — used by the Backup export. */
    @Query("SELECT * FROM playlist_groups")
    suspend fun getAllGroupsOnce(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Query("DELETE FROM playlist_groups WHERE playlistId = :playlistId")
    suspend fun deleteGroupsByPlaylist(playlistId: Long)

    /** Folder Delete — removes a single group (folder) row. [groupId] is [GroupEntity.id], the group name. */
    @Query("DELETE FROM playlist_groups WHERE playlistId = :playlistId AND id = :groupId")
    suspend fun deleteGroup(playlistId: Long, groupId: String)
}
