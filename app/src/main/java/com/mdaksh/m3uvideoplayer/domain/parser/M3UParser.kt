package com.mdaksh.m3uvideoplayer.domain.parser

import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.ContentType
import java.io.InputStream

class M3UParser {
    fun parse(inputStream: InputStream, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = ""
        var currentEpgId: String? = null
        // Static Live/VOD hint from the #EXTINF: numeric duration value; see [Channel.isLive].
        var currentIsLive = true
        // promt2 — #EXTVLCOPT headers that sit between an #EXTINF and its URL line.
        var currentReferrer: String? = null
        var currentUserAgent: String? = null

        inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXTINF:")) {
                    currentName = extractName(trimmed)
                    currentLogo = extractAttribute(trimmed, "tvg-logo").takeIf { it.isNotEmpty() }
                    currentGroup = extractAttribute(trimmed, "group-title")
                    currentEpgId = extractAttribute(trimmed, "tvg-id").takeIf { it.isNotEmpty() }
                    currentIsLive = extractIsLive(trimmed)
                    currentReferrer = null
                    currentUserAgent = null
                } else if (trimmed.startsWith("#EXTVLCOPT:")) {
                    // e.g. "#EXTVLCOPT:http-referrer=https://..." or ":http-user-agent=Mozilla/..."
                    val opt = trimmed.substringAfter("#EXTVLCOPT:").trim()
                    val key = opt.substringBefore('=', "").trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-referrer", "http-referer" -> currentReferrer = value
                            "http-user-agent" -> currentUserAgent = value
                        }
                    }
                } else if (!trimmed.startsWith("#")) {
                    // It's a URL line
                    val contentType = inferContentType(currentGroup, currentName)
                    channels.add(
                        Channel(
                            playlistId = playlistId,
                            name = currentName,
                            logo = currentLogo,
                            group = currentGroup,
                            url = trimmed,
                            epgId = currentEpgId,
                            contentType = contentType,
                            isLive = currentIsLive,
                            httpReferrer = currentReferrer,
                            httpUserAgent = currentUserAgent
                        )
                    )
                    // Reset for next entry
                    currentName = ""
                    currentLogo = null
                    currentGroup = ""
                    currentEpgId = null
                    currentIsLive = true
                    currentReferrer = null
                    currentUserAgent = null
                }
            }
        }
        return channels
    }

    /**
     * Infers whether a channel is LIVE, a MOVIE, or a SERIES based on its group-title and name.
     * M3U providers often use keywords like "Movies", "Cinema", "Series", "VOD" to distinguish.
     *
     * SERIES detection has absolute priority over MOVIE detection — an episode entry like
     * "Loki S02E05 1080p BluRay" must never end up in the All Movies folder just because it
     * contains a quality tag (1080p/bluray) that would otherwise trigger MOVIE detection.
     *
     * The SxxExx regex (e.g. S01E01, S2E5, s12e099) catches any season/episode numbering pattern
     * without requiring the provider to spell out "series" or "episode" in the group title.
     */
    private val episodePattern = Regex("""s\d{1,4}e\d{1,4}""", RegexOption.IGNORE_CASE)

    private fun inferContentType(groupName: String, channelName: String): String {
        val groupLower = groupName.lowercase()
        val nameLower  = channelName.lowercase()

        // --- SERIES (checked FIRST — must win over any quality keywords below) ---
        val isSeries =
            // SxxExx pattern in the channel name (e.g. S01E01, S2E5, s12e099)
            episodePattern.containsMatchIn(nameLower) ||
            // Explicit series keywords in group or name
            groupLower.contains("series")   || nameLower.contains("series")   ||
            groupLower.contains("season")   || nameLower.contains("season")   ||
            groupLower.contains("episode")  || nameLower.contains("episode")  ||
            groupLower.contains("tvshow")   || nameLower.contains("tvshow")   ||
            groupLower.contains("tv show")  || nameLower.contains("tv show")

        if (isSeries) return ContentType.SERIES

        // --- MOVIE (only reached when no SERIES signal was found) ---
        val isMovie =
            groupLower.contains("movie")    || nameLower.contains("movie")    ||
            groupLower.contains("cinema")   || nameLower.contains("cinema")   ||
            groupLower.contains("film")     || nameLower.contains("film")     ||
            groupLower.contains("vod")      || nameLower.contains("vod")      ||
            // Quality/format tags are only reliable MOVIE signals when there is no episode pattern
            nameLower.contains("2160p")     || nameLower.contains("4k")       ||
            nameLower.contains("1080p")     || nameLower.contains("720p")     ||
            nameLower.contains("bluray")    || nameLower.contains("blu-ray")  ||
            nameLower.contains("bdrip")     || nameLower.contains("webrip")   ||
            nameLower.contains("hdcam")     || nameLower.contains("dvdrip")

        if (isMovie) return ContentType.MOVIE

        // Default to LIVE
        return ContentType.LIVE
    }

    /**
     * Resume Points Engine spec, §2 (M3U Parsing Engine — Live TV vs VOD Detection):
     * reads the numeric value immediately after `#EXTINF:` (before the first space or comma).
     *  - CASE A: `0` or any positive integer (e.g. `#EXTINF:1`, `#EXTINF:3600`) -> VOD -> false.
     *  - CASE B: `-1` or any negative integer (e.g. `#EXTINF:-1`)              -> Live -> true.
     * Unparseable/missing values default to `true` (Live), matching the historical "M3U entries
     * are always LIVE" assumption so existing playlists aren't reclassified without an explicit tag.
     */
    private fun extractIsLive(line: String): Boolean {
        // Trim first so a stray "#EXTINF: -1,Name" (space right after the colon) doesn't make the
        // space-cut return an empty token before the real duration digits are reached.
        val afterPrefix = line.removePrefix("#EXTINF:").trim()
        val durationToken = afterPrefix.substringBefore(' ').substringBefore(',').trim()
        val duration = durationToken.toDoubleOrNull() ?: return true
        return duration < 0
    }

    private fun extractName(line: String): String {
        val commaIndex = line.lastIndexOf(",")
        return if (commaIndex != -1 && commaIndex < line.length - 1) {
            line.substring(commaIndex + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attribute: String): String {
        val regex = "$attribute=\"([^\"]*)\"".toRegex()
        val matchResult = regex.find(line)
        return matchResult?.groupValues?.get(1) ?: ""
    }
}
