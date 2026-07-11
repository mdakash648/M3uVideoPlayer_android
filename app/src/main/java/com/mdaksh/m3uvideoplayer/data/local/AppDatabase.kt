package com.mdaksh.m3uvideoplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.HistoryDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistResumeDao
import com.mdaksh.m3uvideoplayer.data.local.dao.ResumeDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.GroupEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.HistoryEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistResumePointEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.ResumePointEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        GroupEntity::class,
        HistoryEntity::class,
        ResumePointEntity::class,
        // playlist_resume_points — Floating Resume Button engine (see PlaylistResumeDao doc).
        PlaylistResumePointEntity::class
    ],
    version = 10, // v9->v10: added ChannelEntity.isLive (static EXTINF duration-value hint).
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun groupDao(): GroupDao
    abstract fun historyDao(): HistoryDao
    abstract fun resumeDao(): ResumeDao
    abstract fun playlistResumeDao(): PlaylistResumeDao
}
