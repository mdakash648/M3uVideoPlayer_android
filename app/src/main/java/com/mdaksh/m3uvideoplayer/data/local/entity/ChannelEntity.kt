package com.mdaksh.m3uvideoplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.ContentType

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val logo: String?,
    val groupName: String,
    val url: String,
    val epgId: String? = null,
    val isFavorite: Boolean = false,
    val contentType: String = ContentType.LIVE,
    /** Static Live/VOD hint parsed from `#EXTINF:`; see [Channel.isLive]. */
    val isLive: Boolean = true,
    val externalId: String? = null,
    /** Raw playlist-sequence rank; see [Channel.position]. */
    val position: Int = 0,
    /** Step 11.4 — Catch-up / Archive metadata; see [Channel.catchupType]. */
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    /** promt2 — `#EXTVLCOPT` HTTP headers; see [Channel.httpReferrer]. */
    val httpReferrer: String? = null,
    val httpUserAgent: String? = null
) {
    fun toDomain(): Channel {
        return Channel(
            id = id,
            playlistId = playlistId,
            name = name,
            logo = logo,
            group = groupName,
            url = url,
            epgId = epgId,
            isFavorite = isFavorite,
            contentType = contentType,
            isLive = isLive,
            externalId = externalId,
            position = position,
            catchupType = catchupType,
            catchupDays = catchupDays,
            catchupSource = catchupSource,
            httpReferrer = httpReferrer,
            httpUserAgent = httpUserAgent
        )
    }

    companion object {
        fun fromDomain(channel: Channel): ChannelEntity {
            return ChannelEntity(
                id = channel.id,
                playlistId = channel.playlistId,
                name = channel.name,
                logo = channel.logo,
                groupName = channel.group,
                url = channel.url,
                epgId = channel.epgId,
                isFavorite = channel.isFavorite,
                contentType = channel.contentType,
                isLive = channel.isLive,
                externalId = channel.externalId,
                position = channel.position,
                catchupType = channel.catchupType,
                catchupDays = channel.catchupDays,
                catchupSource = channel.catchupSource,
                httpReferrer = channel.httpReferrer,
                httpUserAgent = channel.httpUserAgent
            )
        }
    }
}
