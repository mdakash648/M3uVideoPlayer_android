package com.mdaksh.m3uvideoplayer.ui.channel

/**
 * Step 4.3 — the four channel-list presentation styles the user will be able to pick from.
 *
 * This enum is the shared vocabulary between:
 *  - [ChannelAdapter] (which layout/ViewHolder to inflate per item)
 *  - the future DataStore-backed preference (Step 4.4, not implemented yet)
 *  - the future switcher button in the toolbar (Step 4.5, not implemented yet)
 *
 * Ordinal order matters: it's used directly as the RecyclerView item view type in
 * [ChannelAdapter.getItemViewType], so don't reorder existing entries — only append.
 */
enum class ChannelViewMode {
    /** Compact horizontal rows: small logo, name + group, favorite star. Densest useful view. */
    LIST,

    /** Multi-column grid: square-ish thumbnail on top, name below. Classic grid browsing. */
    GRID,

    /** Text-only rows, no logos — fastest to scroll through very large playlists. */
    TITLE_ONLY,

    /** Multi-column grid with a 16:9 poster thumbnail and the name overlaid at the bottom. */
    POSTER;

    companion object {
        /** Current default until Step 4.4 wires up a persisted user preference. */
        val DEFAULT = POSTER
    }
}
