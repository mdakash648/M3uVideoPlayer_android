package com.mdaksh.m3uvideoplayer.ui.group

/**
 * The two ways the folder (group) grid on [GroupListFragment] can be laid out. Unlike the
 * channel screen's four-way [com.mdaksh.m3uvideoplayer.ui.channel.ChannelViewMode], folders only
 * need List vs Grid.
 *
 * Ordinal order is used as the RecyclerView item view type in [GroupAdapter.getItemViewType],
 * so append rather than reorder. Persisted globally via
 * [com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository].
 */
enum class FolderViewMode {
    /** Vertical rows: folder icon + name + channel count. */
    LIST,

    /** Multi-column grid of folder tiles (the original look). */
    GRID;

    companion object {
        val DEFAULT = GRID
    }
}
