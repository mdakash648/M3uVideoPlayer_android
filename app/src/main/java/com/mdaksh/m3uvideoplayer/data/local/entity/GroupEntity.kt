package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.mdaksh.m3uvideoplayer.domain.model.Group
import com.mdaksh.m3uvideoplayer.domain.model.GroupType

@Entity(
    tableName = "playlist_groups",
    primaryKeys = ["id", "playlistId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"])]
)
data class GroupEntity(
    val id: String,
    val playlistId: Long,
    val name: String,
    val type: String
) {
    fun toDomain() = Group(
        id = id,
        playlistId = playlistId,
        name = name,
        type = GroupType.valueOf(type)
    )
}

fun Group.toEntity() = GroupEntity(
    id = id,
    playlistId = playlistId,
    name = name,
    type = type.name
)
