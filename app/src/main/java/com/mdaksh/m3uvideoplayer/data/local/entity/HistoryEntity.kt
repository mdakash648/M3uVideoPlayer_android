package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mdaksh.m3uvideoplayer.domain.model.PlaybackHistory

@Entity(tableName = "playback_history")
data class HistoryEntity(
    @PrimaryKey
    val channelId: Long,
    val lastWatchedTime: Long,
    val duration: Long = 0
) {
    fun toDomain(): PlaybackHistory {
        return PlaybackHistory(
            channelId = channelId,
            lastWatchedTime = lastWatchedTime,
            duration = duration
        )
    }

    companion object {
        fun fromDomain(history: PlaybackHistory): HistoryEntity {
            return HistoryEntity(
                channelId = history.channelId,
                lastWatchedTime = history.lastWatchedTime,
                duration = history.duration
            )
        }
    }
}
