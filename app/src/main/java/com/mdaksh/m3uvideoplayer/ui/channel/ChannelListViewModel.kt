package com.mdaksh.m3uvideoplayer.ui.channel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.usecase.GetChannelsForPlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.ToggleFavoriteChannelUseCase
import com.mdaksh.m3uvideoplayer.util.FuzzyIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getChannelsForPlaylistUseCase: GetChannelsForPlaylistUseCase,
    private val toggleFavoriteChannelUseCase: ToggleFavoriteChannelUseCase,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    /**
     * Step 4.2 — Optional group-title filter passed by [com.mdaksh.m3uvideoplayer.ui.group.GroupListFragment].
     * Null/blank means "no filter" (show every channel in the playlist), matching the nav-graph
     * default set in Step 4.1.
     */
    val groupName: String? = savedStateHandle.get<String>("groupName")?.takeIf { it.isNotBlank() }

    /**
     * Set by [com.mdaksh.m3uvideoplayer.ui.group.GroupListFragment]'s pinned "Favorite" tile.
     * When true, [groupName] is ignored (the fragment always passes null alongside it) and the
     * list shows only [Channel.isFavorite] channels for this playlist.
     */
    val favoritesOnly: Boolean = savedStateHandle.get<Boolean>("favoritesOnly") ?: false

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Channels for this group in playlist order — the source for both the list and the search index. */
    private val allChannels: StateFlow<List<Channel>> =
        getChannelsForPlaylistUseCase(playlistId, groupName)
            .map { list -> if (favoritesOnly) list.filter { it.isFavorite } else list }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Auto-indexed fuzzy directory over the channel names, rebuilt (off the main thread) only when
     * the channel list itself changes — not on every keystroke.
     */
    private val searchIndex: StateFlow<FuzzyIndex<Channel>> =
        allChannels
            .map { list -> FuzzyIndex.build(list) { it.name } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FuzzyIndex.empty())

    /**
     * The globally-persisted Folder Content sort order (Ascending / Descending / According to
     * Playlist). Independent of [com.mdaksh.m3uvideoplayer.ui.group.GroupListViewModel.sortOrder] —
     * this only ever reorders channels within the currently-open folder, never the folder tiles.
     */
    val sortOrder: StateFlow<ChannelSortOrder> = userPreferencesRepository.channelSortOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChannelSortOrder.DEFAULT)

    fun setSortOrder(order: ChannelSortOrder) {
        viewModelScope.launch { userPreferencesRepository.setChannelSortOrder(order) }
    }

    /**
     * Channels shown on screen. With a blank query this is the full list ordered per [sortOrder];
     * once the user types, it becomes the error-tolerant fuzzy matches ranked most-similar first
     * (sort order doesn't apply to search results, same as the Folder screen), so heavy
     * misspellings still find the intended channel.
     */
    val channels: StateFlow<List<Channel>> =
        combine(allChannels, searchIndex, _searchQuery, sortOrder) { list, index, query, order ->
            if (query.isBlank()) list.sortedFor(order) else index.search(query).map { it.item }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Applies [order] to this (already playlist-ordered) list. [ChannelSortOrder.PLAYLIST] sorts
     * explicitly by [Channel.position] rather than trusting incoming order, so it always restores
     * the exact raw M3U sequence regardless of how the list arrived.
     */
    private fun List<Channel>.sortedFor(order: ChannelSortOrder): List<Channel> = when (order) {
        ChannelSortOrder.ASCENDING -> sortedBy { it.name.lowercase() }
        ChannelSortOrder.DESCENDING -> sortedByDescending { it.name.lowercase() }
        ChannelSortOrder.PLAYLIST -> sortedBy { it.position }
    }

    /**
     * Step 4.4/4.5 — the globally-persisted channel presentation style, streamed straight from
     * [UserPreferencesRepository]. The fragment collects this and applies it live, so a change
     * made here (or on any other screen, later) is reflected everywhere and survives restarts.
     * Seeded with [ChannelViewMode.DEFAULT] so the very first frame has a mode before DataStore
     * emits.
     */
    val viewMode: StateFlow<ChannelViewMode> = userPreferencesRepository.viewModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChannelViewMode.DEFAULT)

    /**
     * User-chosen column counts for the Grid and Poster presentations.
     * [UserPreferencesRepository.COLUMN_COUNT_AUTO] means "use the responsive default"; the fragment
     * resolves each against `R.integer.grid_span_count`.
     */
    val gridColumnCount: StateFlow<Int> = userPreferencesRepository.gridColumnCountFlow
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.COLUMN_COUNT_AUTO
        )

    val posterColumnCount: StateFlow<Int> = userPreferencesRepository.posterColumnCountFlow
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.COLUMN_COUNT_AUTO
        )

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { toggleFavoriteChannelUseCase(channel) }
    }

    /** Step 4.5 — persist the user's pick; [viewMode] then re-emits and the UI updates. */
    fun setViewMode(mode: ChannelViewMode) {
        viewModelScope.launch { userPreferencesRepository.setViewMode(mode) }
    }
}
