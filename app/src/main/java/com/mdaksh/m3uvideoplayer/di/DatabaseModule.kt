package com.mdaksh.m3uvideoplayer.di

import android.content.Context
import androidx.room.Room
import com.mdaksh.m3uvideoplayer.data.local.AppDatabase
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.HistoryDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistResumeDao
import com.mdaksh.m3uvideoplayer.data.local.dao.ResumeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "m3u_player.db"
        )
            // No real migrations written yet (app isn't released) — safe to rebuild the DB
            // on schema bumps like the one in Step 5 (new Channel columns) instead of crashing.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePlaylistDao(database: AppDatabase): PlaylistDao {
        return database.playlistDao()
    }

    @Provides
    fun provideChannelDao(database: AppDatabase): ChannelDao {
        return database.channelDao()
    }

    @Provides
    fun provideHistoryDao(database: AppDatabase): HistoryDao {
        return database.historyDao()
    }

    @Provides
    fun provideGroupDao(database: AppDatabase): GroupDao {
        return database.groupDao()
    }

    @Provides
    fun provideResumeDao(database: AppDatabase): ResumeDao {
        return database.resumeDao()
    }

    @Provides
    fun providePlaylistResumeDao(database: AppDatabase): PlaylistResumeDao {
        return database.playlistResumeDao()
    }
}
