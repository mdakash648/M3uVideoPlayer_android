package com.mdaksh.m3uvideoplayer.domain.usecase

import android.net.Uri
import android.content.Context
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.GroupEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistEntity
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.ContentType
import com.mdaksh.m3uvideoplayer.domain.model.GroupType
import com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.parser.M3UParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class OpenExternalM3UUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val m3uParser: M3UParser,
    private val trustedTimeSource: TrustedTimeSource
) {
    suspend operator fun invoke(uri: Uri): List<Channel> {
        val historyPlaylistName = "Direct Links History"
        val playlists = playlistDao.getAllPlaylistsOnce()
        val historyPlaylist = playlists.find { it.name == historyPlaylistName && it.type == PlaylistType.M3U }

        val playlistId = if (historyPlaylist == null) {
            val newPlaylist = PlaylistEntity(
                name = historyPlaylistName,
                url = DirectLinksHistory.URL_MARKER,
                type = PlaylistType.M3U,
                lastUpdated = trustedTimeSource.now().epochMs,
                updateFrequency = UpdateFrequency.NEVER.name
            )
            playlistDao.insertPlaylist(newPlaylist)
        } else {
            historyPlaylist.id
        }

        // Parse the M3U file
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open the file")
            
        val newChannels = inputStream.use { stream ->
            m3uParser.parse(stream, playlistId)
        }
        
        if (newChannels.isEmpty()) {
            throw IllegalArgumentException("The imported file contains no valid channels.")
        }

        // Fetch existing channels and groups to merge
        val existingChannels = channelDao.getChannelsForPlaylist(playlistId).first()
        val existingGroups = groupDao.getGroupsByPlaylist(playlistId).first().map { it.id }.toSet()

        // Extract groups from the newly parsed channels
        val newGroupEntities = newChannels
            .groupBy { it.group.ifBlank { "Uncategorized" } }
            .filterKeys { !existingGroups.contains(it) } // Only insert groups that don't already exist
            .map { (groupName, groupChannels) ->
                val mostCommonType = groupChannels
                    .groupBy { it.contentType }
                    .maxByOrNull { it.value.size }?.key ?: ContentType.LIVE
                
                val groupType = when (mostCommonType) {
                    ContentType.MOVIE -> GroupType.MOVIE
                    ContentType.SERIES -> GroupType.SERIES
                    else -> GroupType.LIVE
                }

                GroupEntity(
                    id = groupName,
                    playlistId = playlistId,
                    name = groupName,
                    type = groupType.name
                )
            }

        // Insert new groups
        if (newGroupEntities.isNotEmpty()) {
            groupDao.insertGroups(newGroupEntities)
        }

        // Filter out channels that already exist (by URL)
        val existingUrls = existingChannels.map { it.url }.toSet()
        val channelsToInsert = newChannels.filter { !existingUrls.contains(it.url) }

        // Insert new channels
        if (channelsToInsert.isNotEmpty()) {
            val startPosition = existingChannels.size
            channelDao.insertChannels(
                channelsToInsert.mapIndexed { index, channel ->
                    ChannelEntity.fromDomain(channel).copy(position = startPosition + index)
                }
            )
        }

        // Return the full list of new channels parsed from this file so we can play them
        return newChannels
    }
}
