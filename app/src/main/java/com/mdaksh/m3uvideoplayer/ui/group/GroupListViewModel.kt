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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getChannelsForPlaylistUseCase: GetChannelsForPlaylistUseCase,
    observePlaylistResumeTargetsUseCase: ObservePlaylistResumeTargetsUseCase,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    /**
     * All Series sub-screen flag. When true this screen shows only per-series folders (SERIES
     * channels grouped by group-title) with no pinned tiles and no sort — see [buildGroupItems].
     */
    val seriesOnly: Boolean = savedStateHandle.get<Boolean>("seriesOnly") ?: false

    /**
     * Shared source for both the folder tiles and the search index — collected once. In the All
     * Series sub-screen this is pre-filtered to SERIES content so every derived view (folders +
     * search) is scoped to series only.
     */
    private val channels = getChannelsForPlaylistUseCase(playlistId)
        .map { list ->
            if (seriesOnly) {
                list.filter {
                    it.contentType == com.mdaksh.m3uvideoplayer.domain.model.ContentType.SERIES
                }
            } else {
                list
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Resume Queue Builder — fetches the channels for the same folder (group) as the resume
     * target in one shot, so the player can be launched with a full Next/Previous queue instead
     * of a single isolated item.
     *
     * @return Pair of (orderedQueue, startIndex) where startIndex points to [target.channel].
     *         Falls back to a single-item list if the group cannot be resolved.
     */
    suspend fun buildResumeQueue(
        target: com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
    ): Pair<List<com.mdaksh.m3uvideoplayer.domain.model.Channel>, Int> {
        val groupName = target.channel.group.ifBlank { null }
        val groupChannels = getChannelsForPlaylistUseCase(playlistId, groupName)
            .first()
            .sortedBy { it.position }
        val startIndex = groupChannels.indexOfFirst { it.id == target.channel.id }.coerceAtLeast(0)
        return Pair(groupChannels.ifEmpty { listOf(target.channel) }, startIndex)
    }

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

        // All Series sub-screen: show only per-series folders (no pinned tiles, but sorted).
        if (seriesOnly) {
            val folders = list.toGroupItems()
            return when (order) {
                FolderSortOrder.ASCENDING -> folders.sortedBy { it.name.lowercase() }
                FolderSortOrder.DESCENDING -> folders.sortedByDescending { it.name.lowercase() }
                FolderSortOrder.PLAYLIST -> folders
            }
        }

        // groupBy preserves first-encounter key order, i.e. raw playlist order (channels arrive
        // sorted by position from the DAO), which is exactly FolderSortOrder.PLAYLIST.
        val folders = list.toGroupItems()
        val sorted = when (order) {
            FolderSortOrder.ASCENDING -> folders.sortedBy { it.name.lowercase() }
            FolderSortOrder.DESCENDING -> folders.sortedByDescending { it.name.lowercase() }
            FolderSortOrder.PLAYLIST -> folders
        }
        val favorites = GroupItem(
            name = "",
            channelCount = list.count { it.isFavorite },
            isFavorites = true
        )

        return if (isMovieDominant(list)) {
            // Movie/Series playlist: replace "All Channels" with two pinned tiles:
            //   Position 1 = All Movies  (flat MOVIE list)
            //   Position 2 = All Series  (series-folder sub-screen)
            //   Position 3 = Favorite    (as before)
            val allMovies = GroupItem(
                name = "",
                channelCount = list.count {
                    it.contentType == com.mdaksh.m3uvideoplayer.domain.model.ContentType.MOVIE
                },
                isAllMovies = true
            )
            val allSeries = GroupItem(
                name = "",
                channelCount = list.count {
                    it.contentType == com.mdaksh.m3uvideoplayer.domain.model.ContentType.SERIES
                },
                isAllSeries = true
            )
            listOf(allMovies, allSeries, favorites) + sorted
        } else {
            // Regular playlist: keep original layout (All Channels, Favorite, folders).
            val allChannels = GroupItem(
                name = "",
                channelCount = list.size,
                isAllChannels = true
            )
            listOf(allChannels, favorites) + sorted
        }
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

    /**
     * True when at least [MOVIE_DOMINANT_FRACTION] (60%) of this playlist's channels are MOVIE or
     * SERIES content — the signal that flips the pinned all-channels tile to read "All Movies".
     */
    private fun isMovieDominant(
        list: List<com.mdaksh.m3uvideoplayer.domain.model.Channel>
    ): Boolean {
        if (list.isEmpty()) return false
        val movieOrSeries = list.count {
            it.contentType == com.mdaksh.m3uvideoplayer.domain.model.ContentType.MOVIE ||
                it.contentType == com.mdaksh.m3uvideoplayer.domain.model.ContentType.SERIES
        }
        return movieOrSeries.toFloat() / list.size >= MOVIE_DOMINANT_FRACTION
    }

    /** Real (non-synthetic) folder tiles in raw playlist (first-appearance) order. Callers apply sorting. */
    private fun List<com.mdaksh.m3uvideoplayer.domain.model.Channel>.toGroupItems(): List<GroupItem> =
        groupBy { it.group.ifBlank { "Uncategorized" } }
            .map { (name, channelsInGroup) -> GroupItem(name, channelsInGroup.size) }

    private companion object {
        /** ≥60% MOVIE/SERIES content relabels the all-channels tile as "All Movies". */
        const val MOVIE_DOMINANT_FRACTION = 0.6f
    }
}
