package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Embedded

/**
 * Room projection for the Floating Resume Button query: a full [ChannelEntity] joined with the
 * columns we need from its [PlaylistResumePointEntity] row, so the Playlist list screen can show
 * the channel's name/logo without a second query per row.
 */
data class PlaylistResumeTargetEntity(
    @Embedded val channel: ChannelEntity,
    val resumePositionMs: Long,
    val resumeIsLive: Boolean,
    val resumeCompleted: Boolean,
    val resumeUpdatedAt: Long
)
