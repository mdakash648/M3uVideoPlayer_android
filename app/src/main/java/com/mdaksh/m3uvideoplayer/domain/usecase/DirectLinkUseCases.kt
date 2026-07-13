package com.mdaksh.m3uvideoplayer.domain.usecase

import android.net.Uri
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.HistoryDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.local.dao.ResumeDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.GroupEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistEntity
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.ContentType
import com.mdaksh.m3uvideoplayer.domain.model.GroupType
import com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.parser.M3UParser
import kotlinx.coroutines.flow.first
import java.io.InputStream
import javax.inject.Inject

/**
 * Shared identity for the special auto-created "Direct Links History" playlist. [URL_MARKER] is
 * the placeholder stored in [PlaylistEntity.url] for this playlist (it has no real source URL),
 * and is the authoritative way to recognize it — playlist names are user-editable/duplicable,
 * the marker isn't.
 */
object DirectLinksHistory {
    const val PLAYLIST_NAME = "Direct Links History"
    const val URL_MARKER = "direct_links"
}

class PlayDirectLinkUseCase @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val trustedTimeSource: TrustedTimeSource
) {
    suspend operator fun invoke(url: String): Channel {
        val historyPlaylistName = DirectLinksHistory.PLAYLIST_NAME
        val groupName = "Movie" // Uncategorized

        // 1. Find or create the history playlist
        var playlists = playlistDao.getAllPlaylistsOnce()
        var historyPlaylist = playlists.find { it.name == historyPlaylistName && it.type == PlaylistType.M3U }

        val playlistId = if (historyPlaylist == null) {
            val newPlaylist = PlaylistEntity(
                name = historyPlaylistName,
                url = DirectLinksHistory.URL_MARKER, // placeholder
                type = PlaylistType.M3U,
                lastUpdated = trustedTimeSource.now().epochMs,
                updateFrequency = UpdateFrequency.NEVER.name
            )
            playlistDao.insertPlaylist(newPlaylist)
        } else {
            historyPlaylist.id
        }

        // 2. Ensure the "All Movies" group exists for this playlist
        val existingGroups = groupDao.getGroupsByPlaylist(playlistId).first()
        if (existingGroups.none { it.id == groupName }) {
            groupDao.insertGroups(
                listOf(
                    GroupEntity(
                        id = groupName,
                        playlistId = playlistId,
                        name = groupName,
                        type = GroupType.MOVIE.name
                    )
                )
            )
        }

        // 3. Create and insert the channel (if not already exists)
        val channels = channelDao.getChannelsForPlaylist(playlistId).first()
        var existingChannel = channels.find { it.url == url }
        
        if (existingChannel == null) {
            val channelName = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "Direct Link"
            val newChannel = ChannelEntity(
                playlistId = playlistId,
                name = channelName,
                logo = null,
                groupName = groupName,
                url = url,
                contentType = ContentType.MOVIE,
                isLive = false,
                position = channels.size
            )
            channelDao.insertChannels(listOf(newChannel))
            
            // Re-fetch to get the assigned ID
            val updatedChannels = channelDao.getChannelsForPlaylist(playlistId).first()
            existingChannel = updatedChannels.find { it.url == url }
        }

        return existingChannel?.toDomain() ?: throw IllegalStateException("Failed to insert direct link channel")
    }
}

class ExportHistoryUseCase @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao
) {
    suspend operator fun invoke(): String {
        val historyPlaylistName = DirectLinksHistory.PLAYLIST_NAME
        val playlists = playlistDao.getAllPlaylistsOnce()
        val historyPlaylist = playlists.find { it.name == historyPlaylistName && it.type == PlaylistType.M3U }
            ?: throw IllegalStateException("History playlist not found.")

        val channels = channelDao.getChannelsForPlaylist(historyPlaylist.id).first()

        val sb = java.lang.StringBuilder()
        sb.append("#EXTM3U\n")
        for (channel in channels) {
            // M3U format: #EXTINF:-1 group-title="...", Channel Name
            // URL
            sb.append("#EXTINF:-1 group-title=\"${channel.groupName}\", ${channel.name}\n")
            if (!channel.httpReferrer.isNullOrBlank() || !channel.httpUserAgent.isNullOrBlank()) {
                val opts = mutableListOf<String>()
                if (!channel.httpReferrer.isNullOrBlank()) opts.add("http-referrer=${channel.httpReferrer}")
                if (!channel.httpUserAgent.isNullOrBlank()) opts.add("http-user-agent=${channel.httpUserAgent}")
                sb.append("#EXTVLCOPT:${opts.joinToString(",")}\n")
            }
            sb.append("${channel.url}\n")
        }
        return sb.toString()
    }
}

class ImportHistoryUseCase @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val m3uParser: M3UParser,
    private val trustedTimeSource: TrustedTimeSource
) {
    suspend operator fun invoke(inputStream: InputStream) {
        val historyPlaylistName = DirectLinksHistory.PLAYLIST_NAME
        var playlists = playlistDao.getAllPlaylistsOnce()
        var historyPlaylist = playlists.find { it.name == historyPlaylistName && it.type == PlaylistType.M3U }

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
        val channels = m3uParser.parse(inputStream, playlistId)
        if (channels.isEmpty()) {
            throw IllegalArgumentException("The imported file contains no valid channels.")
        }

        // Extract groups
        val groups = channels
            .groupBy { it.group.ifBlank { "Uncategorized" } }
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

        // Replace existing channels and groups for this playlist
        channelDao.deleteChannelsForPlaylist(playlistId)
        channelDao.insertChannels(
            channels.mapIndexed { index, channel ->
                ChannelEntity.fromDomain(channel).copy(position = index)
            }
        )

        groupDao.deleteGroupsByPlaylist(playlistId)
        groupDao.insertGroups(groups)
    }
}

/**
 * Folder & Video Delete gate — true only for the special auto-created "Direct Links History"
 * playlist (matched by [DirectLinksHistory.URL_MARKER]), so delete affordances never appear on
 * regular M3U/Xtream playlists browsed through the same Group/Channel list screens.
 */
class IsDirectLinksHistoryPlaylistUseCase @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    suspend operator fun invoke(playlistId: Long): Boolean {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return false
        return playlist.url == DirectLinksHistory.URL_MARKER
    }
}

/**
 * Video Delete — removes a single Direct Link entry, plus its per-playlist resume point and
 * playback-history row so nothing orphaned lingers behind.
 */
class DeleteDirectLinkVideoUseCase @Inject constructor(
    private val channelDao: ChannelDao,
    private val resumeDao: ResumeDao,
    private val historyDao: HistoryDao
) {
    suspend operator fun invoke(channel: Channel) {
        resumeDao.delete(channel.playlistId, channel.url)
        historyDao.deleteHistory(channel.id)
        channelDao.deleteChannelById(channel.id)
    }
}

/**
 * Folder Delete — removes a whole group from Direct Links History, taking every video inside it
 * (plus their resume points and history rows) along with it, then removes the now-empty folder
 * itself.
 */
class DeleteDirectLinkFolderUseCase @Inject constructor(
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val resumeDao: ResumeDao,
    private val historyDao: HistoryDao
) {
    suspend operator fun invoke(playlistId: Long, groupName: String) {
        val channelsInGroup = channelDao.getChannelsInGroupOnce(playlistId, groupName)
        channelsInGroup.forEach { channel ->
            resumeDao.delete(playlistId, channel.url)
            historyDao.deleteHistory(channel.id)
        }
        channelDao.deleteChannelsByGroup(playlistId, groupName)
        groupDao.deleteGroup(playlistId, groupName)
    }
}
