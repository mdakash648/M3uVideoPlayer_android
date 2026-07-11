package com.mdaksh.m3uvideoplayer.di

import com.mdaksh.m3uvideoplayer.data.repository.ChannelRepositoryImpl
import com.mdaksh.m3uvideoplayer.data.repository.PlaylistRepositoryImpl
import com.mdaksh.m3uvideoplayer.data.repository.PlaylistResumeRepositoryImpl
import com.mdaksh.m3uvideoplayer.domain.parser.M3UParser
import com.mdaksh.m3uvideoplayer.domain.repository.ChannelRepository
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistRepository
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistResumeRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(
        playlistRepositoryImpl: PlaylistRepositoryImpl
    ): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindChannelRepository(
        channelRepositoryImpl: ChannelRepositoryImpl
    ): ChannelRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistResumeRepository(
        playlistResumeRepositoryImpl: PlaylistResumeRepositoryImpl
    ): PlaylistResumeRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ParserModule {
    @Provides
    @Singleton
    fun provideM3UParser(): M3UParser {
        return M3UParser()
    }
}
