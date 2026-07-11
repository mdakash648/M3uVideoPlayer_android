package com.mdaksh.m3uvideoplayer.ui.group

/**
 * How the folder (group) tiles on [GroupListFragment] are ordered. The synthetic "All channels"
 * tile is always pinned first regardless of this choice (see [GroupListViewModel]).
 *
 * Persisted globally by *name* (not ordinal) via
 * [com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository], so entries can be added
 * without silently repointing an old saved value — append rather than reorder.
 */
enum class FolderSortOrder {
    /** Folder names A→Z (case-insensitive). */
    ASCENDING,

    /** Folder names Z→A (case-insensitive). */
    DESCENDING,

    /** First-appearance order in the raw playlist sequence (no sorting). */
    PLAYLIST;

    companion object {
        val DEFAULT = PLAYLIST
    }
}
