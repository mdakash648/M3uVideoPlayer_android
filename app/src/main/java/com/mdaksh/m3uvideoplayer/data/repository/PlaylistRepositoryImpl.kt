package com.mdaksh.m3uvideoplayer.data.repository

import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.toEntity
import com.mdaksh.m3uvideoplayer.data.remote.XtreamApi
import com.mdaksh.m3uvideoplayer.data.remote.XtreamApiFactory
import com.mdaksh.m3uvideoplayer.data.remote.XtreamCredentials
import com.mdaksh.m3uvideoplayer.data.remote.XtreamCredentialsParser
import com.mdaksh.m3uvideoplayer.data.remote.dto.XtreamCategoryDto
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.ContentType
import com.mdaksh.m3uvideoplayer.domain.model.Group
import com.mdaksh.m3uvideoplayer.domain.model.GroupType
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.parser.M3UParser
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistRepository
import com.mdaksh.m3uvideoplayer.domain.usecase.PlaylistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val m3uParser: M3UParser,
    private val xtreamApiFactory: XtreamApiFactory
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPlaylistById(id: Long): Playlist? {
        return playlistDao.getPlaylistById(id)?.toDomain()
    }

    override suspend fun insertPlaylist(playlist: Playlist): Long {
        return playlistDao.insertPlaylist(PlaylistEntity.fromDomain(playlist))
    }

    override suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.updatePlaylist(PlaylistEntity.fromDomain(playlist))
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        playlistDao.deletePlaylist(PlaylistEntity.fromDomain(playlist))
    }

    override suspend fun syncPlaylist(playlist: Playlist) {
        withContext(Dispatchers.IO) {
            when (playlist.type) {
                PlaylistType.M3U -> syncM3u(playlist)
                PlaylistType.XTREAM -> syncXtream(playlist)
            }
        }
    }

    // ---------------------------------------------------------------------
    // M3U
    // ---------------------------------------------------------------------

    private suspend fun syncM3u(playlist: Playlist) {
        try {
            val inputStream = URL(playlist.url).openStream()
            val channels = m3uParser.parse(inputStream, playlist.id)

            // Step 11.4 — Infer GroupType from the channels it contains (rather than hardcoding LIVE).
            val groups = channels
                .groupBy { it.group.ifBlank { "Uncategorized" } }
                .map { (groupName, groupChannels) ->
                    // Pick the GroupType that matches the most frequent ContentType in the group.
                    val mostCommonType = groupChannels
                        .groupBy { it.contentType }
                        .maxByOrNull { it.value.size }?.key ?: ContentType.LIVE
                    
                    val groupType = when (mostCommonType) {
                        ContentType.MOVIE -> GroupType.MOVIE
                        ContentType.SERIES -> GroupType.SERIES
                        else -> GroupType.LIVE
                    }

                    Group(
                        id = groupName,
                        playlistId = playlist.id,
                        name = groupName,
                        type = groupType
                    )
                }

            channelDao.deleteChannelsForPlaylist(playlist.id)
            // Stamp each channel with its rank in the raw M3U sequence so "According to Playlist"
            // ordering survives regardless of row/insert order.
            channelDao.insertChannels(
                channels.mapIndexed { index, channel ->
                    ChannelEntity.fromDomain(channel).copy(position = index)
                }
            )

            groupDao.deleteGroupsByPlaylist(playlist.id)
            groupDao.insertGroups(groups.map { it.toEntity() })

            markSynced(playlist)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    // ---------------------------------------------------------------------
    // Xtream Codes
    // ---------------------------------------------------------------------

    private suspend fun syncXtream(playlist: Playlist) {
        val credentials = XtreamCredentialsParser.parse(playlist.url)
            ?: throw IllegalArgumentException("Invalid Xtream playlist URL/credentials")

        val api = xtreamApiFactory.create(credentials.baseUrl)

        // Fail fast with a clear message if the login itself is rejected, instead of
        // silently ending up with an empty channel list.
        val auth = try {
            api.authenticate(credentials.username, credentials.password)
        } catch (e: Exception) {
            throw IllegalStateException("Could not reach Xtream server: ${e.message}", e)
        }
        val userInfo = auth.userInfo
        if (userInfo != null && !userInfo.isAuthenticated) {
            throw IllegalStateException(
                userInfo.message ?: userInfo.status ?: "Xtream login rejected by server"
            )
        }

        val allGroups = mutableListOf<Group>()
        val allChannels = mutableListOf<Channel>()

        fetchLive(api, credentials, playlist.id, allGroups, allChannels)
        fetchVod(api, credentials, playlist.id, allGroups, allChannels)
        fetchSeries(api, credentials, playlist.id, allGroups, allChannels)

        if (allChannels.isEmpty() && allGroups.isEmpty()) {
            // Login worked but nothing came back at all (e.g. every category call failed) —
            // treat this as a failure rather than silently wiping existing data.
            throw IllegalStateException("Xtream server returned no live/VOD/series content")
        }

        channelDao.deleteChannelsForPlaylist(playlist.id)
        // Same playlist-order stamping as M3U; here it follows the live/VOD/series fetch order.
        channelDao.insertChannels(
            allChannels.mapIndexed { index, channel ->
                ChannelEntity.fromDomain(channel).copy(position = index)
            }
        )

        groupDao.deleteGroupsByPlaylist(playlist.id)
        groupDao.insertGroups(allGroups.map { it.toEntity() })

        markSynced(playlist)
    }

    private suspend fun fetchLive(
        api: XtreamApi,
        credentials: XtreamCredentials,
        playlistId: Long,
        outGroups: MutableList<Group>,
        outChannels: MutableList<Channel>
    ) {
        try {
            val categories = api.getLiveCategories(credentials.username, credentials.password)
            val categoryNames = categories.toNameMap()
            outGroups += categories.toGroups(playlistId, GroupType.LIVE)

            val streams = api.getLiveStreams(credentials.username, credentials.password)
            streams.forEach { stream ->
                val streamId = stream.streamId ?: return@forEach
                val categoryId = stream.categoryId
                outChannels += Channel(
                    playlistId = playlistId,
                    name = stream.name ?: "Unknown Channel",
                    logo = stream.streamIcon?.takeIf { it.isNotBlank() },
                    group = categoryId?.let { categoryNames[it] } ?: "Live",
                    url = "${credentials.baseUrl}/live/${credentials.username}/${credentials.password}/$streamId.m3u8",
                    epgId = stream.epgChannelId?.takeIf { it.isNotBlank() },
                    contentType = ContentType.LIVE,
                    isLive = true,
                    externalId = streamId
                )
            }
        } catch (e: Exception) {
            // Some providers don't expose live TV (rare) — don't let it block VOD/series.
            e.printStackTrace()
        }
    }

    private suspend fun fetchVod(
        api: XtreamApi,
        credentials: XtreamCredentials,
        playlistId: Long,
        outGroups: MutableList<Group>,
        outChannels: MutableList<Channel>
    ) {
        try {
            val categories = api.getVodCategories(credentials.username, credentials.password)
            val categoryNames = categories.toNameMap()
            outGroups += categories.toGroups(playlistId, GroupType.MOVIE)

            val streams = api.getVodStreams(credentials.username, credentials.password)
            streams.forEach { stream ->
                val streamId = stream.streamId ?: return@forEach
                val categoryId = stream.categoryId
                val extension = stream.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
                outChannels += Channel(
                    playlistId = playlistId,
                    name = stream.name ?: "Unknown Movie",
                    logo = stream.streamIcon?.takeIf { it.isNotBlank() },
                    group = categoryId?.let { categoryNames[it] } ?: "Movies",
                    url = "${credentials.baseUrl}/movie/${credentials.username}/${credentials.password}/$streamId.$extension",
                    contentType = ContentType.MOVIE,
                    isLive = false,
                    externalId = streamId
                )
            }
        } catch (e: Exception) {
            // VOD isn't offered by every provider — skip rather than fail the whole sync.
            e.printStackTrace()
        }
    }

    private suspend fun fetchSeries(
        api: XtreamApi,
        credentials: XtreamCredentials,
        playlistId: Long,
        outGroups: MutableList<Group>,
        outChannels: MutableList<Channel>
    ) {
        try {
            val categories = api.getSeriesCategories(credentials.username, credentials.password)
            val categoryNames = categories.toNameMap()
            outGroups += categories.toGroups(playlistId, GroupType.SERIES)

            val seriesList = api.getSeries(credentials.username, credentials.password)
            seriesList.forEach { series ->
                val seriesId = series.seriesId ?: return@forEach
                val categoryId = series.categoryId
                outChannels += Channel(
                    playlistId = playlistId,
                    name = series.name ?: "Unknown Series",
                    logo = series.cover?.takeIf { it.isNotBlank() },
                    group = categoryId?.let { categoryNames[it] } ?: "Series",
                    // Series don't have one playable file — the actual episode URL needs a
                    // separate get_series_info(series_id) call to list seasons/episodes.
                    // That episode-picker screen isn't built yet (player itself is Phase 3,
                    // not started per the plan), so we keep the series entry with an empty
                    // url and its externalId so that screen can resolve it later.
                    url = "",
                    contentType = ContentType.SERIES,
                    isLive = false,
                    externalId = seriesId
                )
            }
        } catch (e: Exception) {
            // Series isn't offered by every provider — skip rather than fail the whole sync.
            e.printStackTrace()
        }
    }

    private suspend fun markSynced(playlist: Playlist) {
        val updated = playlist.copy(lastUpdated = System.currentTimeMillis())
        playlistDao.updatePlaylist(PlaylistEntity.fromDomain(updated))
    }

    private fun List<XtreamCategoryDto>.toNameMap(): Map<String, String> =
        mapNotNull { dto ->
            val id = dto.categoryId ?: return@mapNotNull null
            val name = dto.categoryName?.takeIf { it.isNotBlank() } ?: id
            id to name
        }.toMap()

    private fun List<XtreamCategoryDto>.toGroups(playlistId: Long, type: GroupType): List<Group> =
        mapNotNull { dto ->
            val id = dto.categoryId ?: return@mapNotNull null
            Group(
                id = id,
                playlistId = playlistId,
                name = dto.categoryName?.takeIf { it.isNotBlank() } ?: id,
                type = type
            )
        }
}
