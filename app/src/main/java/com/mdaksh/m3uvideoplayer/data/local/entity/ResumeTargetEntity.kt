package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Embedded

/**
 * promt5 — Room projection for the folder resume query: a full [ChannelEntity] joined with the two
 * columns we need from its [ResumePointEntity] (position + recency). Kept as a query POJO rather than
 * a table so the JOIN can run entirely in SQL (avoids passing large URL lists that would blow the
 * SQLite variable limit on big playlists).
 */
data class ResumeTargetEntity(
    @Embedded val channel: ChannelEntity,
    val resumePositionMs: Long,
    val resumeIsLive: Boolean,
    val resumeUpdatedAt: Long
)
