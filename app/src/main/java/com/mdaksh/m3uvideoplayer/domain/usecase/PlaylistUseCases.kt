package com.mdaksh.m3uvideoplayer.domain.usecase

import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Playlist type constants shared across the app. */
object PlaylistType {
    const val M3U = "M3U"
    const val XTREAM = "XTREAM"
}

class GetPlaylistsUseCase @Inject constructor(
    private val repository: PlaylistRepository
) {
    operator fun invoke(): Flow<List<Playlist>> = repository.getAllPlaylists()
}

class GetPlaylistByIdUseCase @Inject constructor(
    private val repository: PlaylistRepository
) {
    suspend operator fun invoke(id: Long): Playlist? = repository.getPlaylistById(id)
}

class UpdatePlaylistUseCase @Inject constructor(
    private val repository: PlaylistRepository
) {
    suspend operator fun invoke(playlist: Playlist) = repository.updatePlaylist(playlist)
}

class AddPlaylistUseCase @Inject constructor(
    private val repository: PlaylistRepository,
    private val syncPlaylistUseCase: SyncPlaylistUseCase
) {
    /**
     * Inserts a playlist and immediately triggers a first sync so channels/groups
     * are available right after adding it.
     */
    suspend operator fun invoke(playlist: Playlist): Long {
        val id = repository.insertPlaylist(playlist)
        val saved = repository.getPlaylistById(id)
        if (saved != null) {
            syncPlaylistUseCase(saved)
        }
        return id
    }
}

class DeletePlaylistUseCase @Inject constructor(
    private val repository: PlaylistRepository
) {
    suspend operator fun invoke(playlist: Playlist) = repository.deletePlaylist(playlist)
}

class SyncPlaylistUseCase @Inject constructor(
    private val repository: PlaylistRepository
) {
    suspend operator fun invoke(playlist: Playlist) = repository.syncPlaylist(playlist)
}

class DeleteGroupUseCase @Inject constructor(
    private val channelRepository: com.mdaksh.m3uvideoplayer.domain.repository.ChannelRepository,
    private val groupDao: com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao,
    private val channelDao: com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
) {
    /**
     * Deletes a group and all channels belonging to it.
     * Transactional-like behavior: first wipe channels, then the group header.
     */
    suspend operator fun invoke(playlistId: Long, groupId: String) {
        channelDao.deleteChannelsByGroup(playlistId, groupId)
        groupDao.deleteGroup(playlistId, groupId)
    }
}

class DeleteChannelUseCase @Inject constructor(
    private val channelDao: com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
) {
    suspend operator fun invoke(channelId: Long) {
        channelDao.deleteChannelById(channelId)
    }
}

