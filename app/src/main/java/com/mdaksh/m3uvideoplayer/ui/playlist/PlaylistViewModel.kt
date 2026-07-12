package com.mdaksh.m3uvideoplayer.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.work.PlaylistUpdateScheduler
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.domain.usecase.ClearPlaylistResumePointUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.DeletePlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.GetChannelsForPlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.GetPlaylistsUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.ObservePlaylistResumeTargetsUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.SyncPlaylistUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    getPlaylistsUseCase: GetPlaylistsUseCase,
    observePlaylistResumeTargetsUseCase: ObservePlaylistResumeTargetsUseCase,
    private val getChannelsForPlaylistUseCase: GetChannelsForPlaylistUseCase,
    private val deletePlaylistUseCase: DeletePlaylistUseCase,
    private val syncPlaylistUseCase: SyncPlaylistUseCase,
    private val clearPlaylistResumePointUseCase: ClearPlaylistResumePointUseCase,
    private val updateScheduler: PlaylistUpdateScheduler
) : ViewModel() {

    private val allPlaylists: StateFlow<List<Playlist>> = getPlaylistsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Floating Resume Button engine — playlistId -> active resume target, for the list-item icon. */
    val resumeTargets: StateFlow<Map<Long, PlaylistResumeTarget>> = observePlaylistResumeTargetsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * [FIX] Resume Queue Builder — same as GroupListViewModel.buildResumeQueue().
     * Fetches all channels in the same group (folder) as the resume target so the player
     * launches with a full Next/Previous queue instead of a single isolated item.
     */
    suspend fun buildResumeQueue(
        target: PlaylistResumeTarget
    ): Pair<List<com.mdaksh.m3uvideoplayer.domain.model.Channel>, Int> {
        val groupName = target.channel.group.ifBlank { null }
        val groupChannels = getChannelsForPlaylistUseCase(target.playlistId, groupName)
            .first()
            .sortedBy { it.position }
        val startIndex = groupChannels.indexOfFirst { it.id == target.channel.id }.coerceAtLeast(0)
        return Pair(groupChannels.ifEmpty { listOf(target.channel) }, startIndex)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Playlists filtered by the live search query, matching on name or source URL. */
    val playlists: StateFlow<List<Playlist>> =
        combine(allPlaylists, _searchQuery) { list, query ->
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) {
                list
            } else {
                list.filter {
                    it.name.lowercase().contains(needle) || it.url.lowercase().contains(needle)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncErrors = MutableSharedFlow<String>()
    val syncErrors: SharedFlow<String> = _syncErrors

    fun refresh(playlist: Playlist) {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                syncPlaylistUseCase(playlist)
            } catch (e: Exception) {
                _syncErrors.emit("${playlist.name}: ${e.message ?: "Sync failed"}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun delete(playlist: Playlist) {
        viewModelScope.launch {
            deletePlaylistUseCase(playlist)
            clearPlaylistResumePointUseCase(playlist.id)
            // Stop any pending/periodic auto-refresh for the now-removed playlist.
            updateScheduler.cancel(playlist.id)
        }
    }
}
