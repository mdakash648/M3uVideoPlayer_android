package com.mdaksh.m3uvideoplayer.domain.repository

import com.mdaksh.m3uvideoplayer.domain.model.Channel
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannelsForPlaylist(playlistId: Long): Flow<List<Channel>>
    fun getChannelsByGroup(playlistId: Long, groupName: String): Flow<List<Channel>>
    fun getFavoriteChannels(): Flow<List<Channel>>
    suspend fun updateChannel(channel: Channel)
}
