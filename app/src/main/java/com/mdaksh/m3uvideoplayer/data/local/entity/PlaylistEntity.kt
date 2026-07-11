package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val type: String,
    val lastUpdated: Long,
    val updateFrequency: String = UpdateFrequency.NEVER.name
) {
    fun toDomain(): Playlist {
        return Playlist(
            id = id,
            name = name,
            url = url,
            type = type,
            lastUpdated = lastUpdated,
            updateFrequency = updateFrequency
        )
    }

    companion object {
        fun fromDomain(playlist: Playlist): PlaylistEntity {
            return PlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                url = playlist.url,
                type = playlist.type,
                lastUpdated = playlist.lastUpdated,
                updateFrequency = playlist.updateFrequency
            )
        }
    }
}
