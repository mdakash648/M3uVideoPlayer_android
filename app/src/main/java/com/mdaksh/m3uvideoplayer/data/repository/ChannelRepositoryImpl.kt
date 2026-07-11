package com.mdaksh.m3uvideoplayer.data.repository

import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao
) : ChannelRepository {
    override fun getChannelsForPlaylist(playlistId: Long): Flow<List<Channel>> {
        return channelDao.getChannelsForPlaylist(playlistId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getChannelsByGroup(playlistId: Long, groupName: String): Flow<List<Channel>> {
        return channelDao.getChannelsByGroup(playlistId, groupName).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getFavoriteChannels(): Flow<List<Channel>> {
        return channelDao.getFavoriteChannels().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun updateChannel(channel: Channel) {
        channelDao.updateChannel(ChannelEntity.fromDomain(channel))
    }
}
