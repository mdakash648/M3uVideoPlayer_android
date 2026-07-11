package com.mdaksh.m3uvideoplayer.domain.usecase

import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetChannelsForPlaylistUseCase @Inject constructor(
    private val repository: ChannelRepository
) {
    operator fun invoke(playlistId: Long, groupName: String? = null): Flow<List<Channel>> =
        if (groupName.isNullOrBlank()) {
            repository.getChannelsForPlaylist(playlistId)
        } else {
            repository.getChannelsByGroup(playlistId, groupName)
        }
}

class GetFavoriteChannelsUseCase @Inject constructor(
    private val repository: ChannelRepository
) {
    operator fun invoke(): Flow<List<Channel>> = repository.getFavoriteChannels()
}

class ToggleFavoriteChannelUseCase @Inject constructor(
    private val repository: ChannelRepository
) {
    suspend operator fun invoke(channel: Channel) {
        repository.updateChannel(channel.copy(isFavorite = !channel.isFavorite))
    }
}
