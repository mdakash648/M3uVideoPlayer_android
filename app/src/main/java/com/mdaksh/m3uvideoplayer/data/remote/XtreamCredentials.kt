package com.mdaksh.m3uvideoplayer.data.remote

import java.net.URI
import java.net.URLDecoder

/**
 * Server base URL + login, extracted back out of the playlist's stored [Playlist.url].
 *
 * [AddPlaylistViewModel.buildXtreamUrl] encodes an Xtream login as
 * "http://host:port?username=U&password=P" when the playlist is first saved (so no schema
 * changes were needed on [PlaylistEntity]). This reverses that encoding so the sync code
 * can rebuild real Xtream API calls from a stored playlist.
 */
data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String
)

object XtreamCredentialsParser {

    fun parse(playlistUrl: String): XtreamCredentials? {
        return try {
            val uri = URI(playlistUrl.trim())
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val baseUrl = if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"

            val params = parseQuery(uri.rawQuery)
            val username = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val password = params["password"]?.takeIf { it.isNotBlank() } ?: return null

            XtreamCredentials(baseUrl, username, password)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx == -1) return@mapNotNull null
            val key = pair.substring(0, idx)
            val value = try {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            } catch (e: Exception) {
                pair.substring(idx + 1)
            }
            key to value
        }.toMap()
    }
}
