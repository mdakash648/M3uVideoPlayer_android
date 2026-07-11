package com.mdaksh.m3uvideoplayer.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.domain.usecase.GetChannelsForPlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.ObservePlaylistResumeTargetsUseCase
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
class GroupListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getChannelsForPlaylistUseCase: GetChannelsForPlaylistUseCase,
    observePlaylistResumeTargetsUseCase: ObservePlaylistResumeTargetsUseCase,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    /** Shared source for both the folder tiles and the search index — collected once. */
    private val channels = getChannelsForPlaylistUseCase(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Folder Resume FAB — this playlist's single "continue watching" pointer, if any, from the
     * playlist_resume_points engine. Null when nothing has been watched yet in this playlist.
     */
    val resumeTarget: StateFlow<PlaylistResumeTarget?> = observePlaylistResumeTargetsUseCase()
        .map { targets -> targets[playlistId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The globally-persisted folder tile sort order (Ascending / Descending / According to Playlist). */
    val sortOrder: StateFlow<FolderSortOrder> = userPreferencesRepository.folderSortOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderSortOrder.DEFAULT)

    fun setSortOrder(order: FolderSortOrder) {
        viewModelScope.launch { userPreferencesRepository.setFolderSortOrder(order) }
    }

    /**
     * Folder tiles for this playlist. Two synthetic tiles are always pinned first — "All channels"
     * then "Favorite" — regardless of [order]; the real folders (derived by grouping channels on
     * their group-title, blanks collapsing into "Uncategorized") follow, ordered per [order].
     * Empty until channels have synced.
     */
    val groups: StateFlow<List<GroupItem>> =
        combine(channels, sortOrder) { list, order -> buildGroupItems(list, order) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The globally-persisted folder layout (List/Grid). */
    val folderViewMode: StateFlow<FolderViewMode> = userPreferencesRepository.folderViewModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderViewMode.DEFAULT)

    fun setFolderViewMode(mode: FolderViewMode) {
        viewModelScope.launch { userPreferencesRepository.setFolderViewMode(mode) }
    }

    /**
     * User-chosen column count for the folder grid. [UserPreferencesRepository.COLUMN_COUNT_AUTO]
     * means "use the responsive default"; the fragment resolves it against `R.integer.grid_span_count`.
     */
    val gridColumnCount: StateFlow<Int> = userPreferencesRepository.gridColumnCountFlow
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            UserPreferencesRepository.COLUMN_COUNT_AUTO
        )

    private fun buildGroupItems(
        list: List<com.mdaksh.m3uvideoplayer.domain.model.Channel>,
        order: FolderSortOrder
    ): List<GroupItem> {
        if (list.isEmpty()) return emptyList()
        // groupBy preserves first-encounter key order, i.e. raw playlist order (channels arrive
        // sorted by position from the DAO), which is exactly FolderSortOrder.PLAYLIST.
        val folders = list.toGroupItems()
        val sorted = when (order) {
            FolderSortOrder.ASCENDING -> folders.sortedBy { it.name.lowercase() }
            FolderSortOrder.DESCENDING -> folders.sortedByDescending { it.name.lowercase() }
            FolderSortOrder.PLAYLIST -> folders
        }
        val allChannels = GroupItem(name = "", channelCount = list.size, isAllChannels = true)
        val favorites = GroupItem(
            name = "",
            channelCount = list.count { it.isFavorite },
            isFavorites = true
        )
        // Position 1: All channels, Position 2: Favorite — both pinned, ignoring the sort filter.
        return listOf(allChannels, favorites) + sorted
    }

    // --- Global search (within this playlist) ------------------------------------------------

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** True while a non-blank query is active — the fragment swaps the folder grid for results. */
    val isSearching: StateFlow<Boolean> = _searchQuery
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Auto-indexed fuzzy directories over folder names and channel names, rebuilt off the main
     * thread only when the underlying channel list changes.
     */
    private val folderIndex: StateFlow<FuzzyIndex<GroupItem>> =
        channels
            .map { list -> FuzzyIndex.build(list.toGroupItems()) { it.name } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FuzzyIndex.empty())

    private val fileIndex: StateFlow<FuzzyIndex<Channel>> =
        channels
            .map { list -> FuzzyIndex.build(list) { it.name } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FuzzyIndex.empty())

    /**
     * Combined search hits, recomputed on every keystroke via the fuzzy indices. Priority is folders
     * first (matching group names), then files (matching channel names) — each ranked most-similar
     * first so heavy misspellings still surface the intended item. Empty while the query is blank.
     */
    val searchResults: StateFlow<List<SearchResult>> =
        combine(folderIndex, fileIndex, _searchQuery) { folders, files, query ->
            if (query.isBlank()) {
                emptyList()
            } else {
                val folderHits = folders.search(query).map { SearchResult.Folder(it.item) }
                val fileHits = files.search(query).map { SearchResult.File(it.item) }
                folderHits + fileHits
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Real (non-synthetic) folder tiles in raw playlist (first-appearance) order. Callers apply sorting. */
    private fun List<com.mdaksh.m3uvideoplayer.domain.model.Channel>.toGroupItems(): List<GroupItem> =
        groupBy { it.group.ifBlank { "Uncategorized" } }
            .map { (name, channelsInGroup) -> GroupItem(name, channelsInGroup.size) }
}
