package com.mdaksh.m3uvideoplayer.ui.channel

/**
 * How the channels on [ChannelListFragment] (the Folder Content screen) are ordered.
 *
 * Deliberately separate from [com.mdaksh.m3uvideoplayer.ui.group.FolderSortOrder]: the two screens
 * sort different things (folder tiles vs. the channels inside one folder) and are persisted under
 * distinct keys, so changing one never affects the other.
 *
 * Persisted globally by *name* (not ordinal) via
 * [com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository], so entries can be added
 * without silently repointing an old saved value — append rather than reorder.
 */
enum class ChannelSortOrder {
    /** Channel names A→Z (case-insensitive). */
    ASCENDING,

    /** Channel names Z→A (case-insensitive). */
    DESCENDING,

    /** Exact raw M3U sequential order ([com.mdaksh.m3uvideoplayer.domain.model.Channel.position]). */
    PLAYLIST;

    companion object {
        val DEFAULT = PLAYLIST
    }
}
