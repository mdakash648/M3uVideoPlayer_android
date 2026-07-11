package com.mdaksh.m3uvideoplayer.domain.model

data class PlaybackHistory(
    val channelId: Long,
    val lastWatchedTime: Long,
    val duration: Long = 0
)
