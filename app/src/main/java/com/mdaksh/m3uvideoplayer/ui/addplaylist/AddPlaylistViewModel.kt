package com.mdaksh.m3uvideoplayer.ui.addplaylist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.local.ImportedFile
import com.mdaksh.m3uvideoplayer.data.local.PlaylistFileStore
import com.mdaksh.m3uvideoplayer.data.work.PlaylistUpdateScheduler
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.usecase.AddPlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.PlaylistType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AddPlaylistEvent {
    object Saved : AddPlaylistEvent()
    data class Error(val message: String) : AddPlaylistEvent()
}

@HiltViewModel
class AddPlaylistViewModel @Inject constructor(
    private val addPlaylistUseCase: AddPlaylistUseCase,
    private val playlistFileStore: PlaylistFileStore,
    private val updateScheduler: PlaylistUpdateScheduler
) : ViewModel() {

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _events = MutableSharedFlow<AddPlaylistEvent>()
    val events: SharedFlow<AddPlaylistEvent> = _events

    /** The file the user attached as the playlist source, or null when using a URL. */
    private val _attachedFile = MutableStateFlow<ImportedFile?>(null)
    val attachedFile: StateFlow<ImportedFile?> = _attachedFile.asStateFlow()

    /** Copy the picked file into app storage immediately so its (transient) Uri permission can lapse. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                _attachedFile.value = playlistFileStore.importToLocal(uri)
            } catch (e: Exception) {
                _events.emit(AddPlaylistEvent.Error(e.message ?: "Could not read the selected file"))
            }
        }
    }

    fun clearAttachedFile() {
        _attachedFile.value = null
    }

    /**
     * Validates and saves a playlist.
     *
     * Source is either the attached file's local `file://` url or the typed [url]. For
     * [PlaylistType.XTREAM], [username]/[password] are appended to the stored url so the sync step
     * can rebuild Xtream API calls from it. After insert, the chosen [frequency] is scheduled.
     */
    fun save(
        type: String,
        name: String,
        url: String,
        username: String?,
        password: String?,
        frequency: UpdateFrequency
    ) {
        if (name.isBlank()) {
            emitError("error_name_required")
            return
        }

        val attached = _attachedFile.value
        val finalUrl: String = when {
            attached != null -> attached.url
            url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://")) -> {
                emitError("error_url_required")
                return
            }
            type == PlaylistType.XTREAM -> {
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    emitError("error_credentials_required")
                    return
                }
                buildXtreamUrl(url, username, password)
            }
            else -> url.trim()
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val id = addPlaylistUseCase(
                    Playlist(
                        name = name.trim(),
                        url = finalUrl,
                        type = type,
                        lastUpdated = 0,
                        updateFrequency = frequency.name
                    )
                )
                updateScheduler.apply(id, frequency)
                _events.emit(AddPlaylistEvent.Saved)
            } catch (e: Exception) {
                _events.emit(AddPlaylistEvent.Error(e.message ?: "Failed to save playlist"))
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun buildXtreamUrl(serverUrl: String, username: String, password: String): String {
        val base = serverUrl.trim().trimEnd('/')
        return "$base?username=$username&password=$password"
    }

    private fun emitError(resKey: String) {
        viewModelScope.launch { _events.emit(AddPlaylistEvent.Error(resKey)) }
    }
}
