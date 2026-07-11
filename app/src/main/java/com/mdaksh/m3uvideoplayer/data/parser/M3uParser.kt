package com.mdaksh.m3uvideoplayer.data.parser

import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.Group
import java.io.InputStream

class M3uParser {

    fun parse(inputStream: InputStream, playlistId: Long): Pair<List<Group>, List<Channel>> {
        val groups = mutableMapOf<String, Group>()
        val channels = mutableListOf<Channel>()
        
        val reader = inputStream.bufferedReader()
        var currentLine: String?
        var currentChannelInfo: Map<String, String>? = null
        // promt2 — #EXTVLCOPT headers accumulate between an #EXTINF and its URL line.
        var currentReferrer: String? = null
        var currentUserAgent: String? = null

        while (reader.readLine().also { currentLine = it } != null) {
            val line = currentLine?.trim() ?: continue

            if (line.startsWith("#EXTINF:")) {
                currentChannelInfo = parseExtInf(line)
                currentReferrer = null
                currentUserAgent = null
            } else if (line.startsWith("#EXTVLCOPT:")) {
                // e.g. "#EXTVLCOPT:http-referrer=https://..." / "#EXTVLCOPT:http-user-agent=Mozilla/..."
                val opt = line.substringAfter("#EXTVLCOPT:").trim()
                val key = opt.substringBefore('=', "").trim().lowercase()
                val value = opt.substringAfter('=', "").trim()
                if (value.isNotEmpty()) {
                    when (key) {
                        "http-referrer", "http-referer" -> currentReferrer = value
                        "http-user-agent" -> currentUserAgent = value
                    }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                // This is the URL line
                if (currentChannelInfo != null) {
                    val name = currentChannelInfo["name"] ?: "Unknown"
                    val groupName = currentChannelInfo["group-title"] ?: "Uncategorized"
                    val logo = currentChannelInfo["tvg-logo"]
                    val epgId = currentChannelInfo["tvg-id"]

                    // Step 11.4 — Catch-up / Archive tags. Providers spell the type a few ways
                    // (`catchup`, `catchup-type`, `timeshift`) and ship the day count under
                    // `catchup-days`/`timeshift`/`tvg-rec`; the optional template is `catchup-source`.
                    val catchupType = currentChannelInfo["catchup"]
                        ?: currentChannelInfo["catchup-type"]
                        ?: currentChannelInfo["timeshift"]?.let { "shift" }
                    val catchupDays = (currentChannelInfo["catchup-days"]
                        ?: currentChannelInfo["timeshift"]
                        ?: currentChannelInfo["tvg-rec"])?.toIntOrNull() ?: 0
                    val catchupSource = currentChannelInfo["catchup-source"]

                    if (!groups.containsKey(groupName)) {
                        groups[groupName] = Group(
                            id = groupName,
                            playlistId = playlistId,
                            name = groupName
                        )
                    }

                    channels.add(
                        Channel(
                            playlistId = playlistId,
                            group = groupName,
                            name = name,
                            url = line,
                            logo = logo,
                            epgId = epgId,
                            catchupType = catchupType,
                            catchupDays = catchupDays,
                            catchupSource = catchupSource,
                            httpReferrer = currentReferrer,
                            httpUserAgent = currentUserAgent
                        )
                    )
                    currentChannelInfo = null
                    currentReferrer = null
                    currentUserAgent = null
                }
            }
        }
        
        return Pair(groups.values.toList(), channels)
    }

    private fun parseExtInf(line: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        // Extract name (everything after the last comma)
        val lastCommaIndex = line.lastIndexOf(',')
        if (lastCommaIndex != -1) {
            info["name"] = line.substring(lastCommaIndex + 1).trim()
        }

        // Extract attributes using regex
        val regex = "(\\S+)=\"([^\"]+)\"".toRegex()
        val matches = regex.findAll(line)
        for (match in matches) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            info[key] = value
        }
        
        return info
    }
}
