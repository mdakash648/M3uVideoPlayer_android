package com.mdaksh.m3uvideoplayer.ui.editplaylist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.remote.XtreamCredentialsParser
import com.mdaksh.m3uvideoplayer.data.work.PlaylistUpdateScheduler
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.usecase.DeletePlaylistUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.GetPlaylistByIdUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.PlaylistType
import com.mdaksh.m3uvideoplayer.domain.usecase.UpdatePlaylistUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Xtream URL/username/password split back out of a stored playlist for prefilling the form. */
data class EditableSource(
    val url: String,
    val username: String? = null,
    val password: String? = null
)

sealed class EditPlaylistEvent {
    object Saved : EditPlaylistEvent()
    object Removed : EditPlaylistEvent()
    data class Error(val message: String) : EditPlaylistEvent()
}

@HiltViewModel
class EditPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPlaylistByIdUseCase: GetPlaylistByIdUseCase,
    private val updatePlaylistUseCase: UpdatePlaylistUseCase,
    private val deletePlaylistUseCase: DeletePlaylistUseCase,
    private val updateScheduler: PlaylistUpdateScheduler
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _events = MutableSharedFlow<EditPlaylistEvent>()
    val events: SharedFlow<EditPlaylistEvent> = _events

    init {
        viewModelScope.launch {
            val loaded = getPlaylistByIdUseCase(playlistId)
            _playlist.value = loaded
            if (loaded == null) {
                _events.emit(EditPlaylistEvent.Error("This playlist no longer exists"))
            }
        }
    }

    /** Splits an Xtream playlist's stored url back into server url + username + password. */
    fun editableSource(playlist: Playlist): EditableSource {
        if (playlist.type != PlaylistType.XTREAM) return EditableSource(url = playlist.url)
        val creds = XtreamCredentialsParser.parse(playlist.url) ?: return EditableSource(url = playlist.url)
        return EditableSource(url = creds.baseUrl, username = creds.username, password = creds.password)
    }

    fun save(name: String, url: String, username: String?, password: String?, frequency: UpdateFrequency) {
        val current = _playlist.value ?: return

        if (name.isBlank()) {
            emitError("error_name_required")
            return
        }
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            emitError("error_url_required")
            return
        }

        val finalUrl = if (current.type == PlaylistType.XTREAM) {
            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                emitError("error_credentials_required")
                return
            }
            buildXtreamUrl(url, username, password)
        } else {
            url.trim()
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val updated = current.copy(
                    name = name.trim(),
                    url = finalUrl,
                    updateFrequency = frequency.name
                )
                updatePlaylistUseCase(updated)
                updateScheduler.apply(updated.id, frequency)
                _playlist.value = updated
                _events.emit(EditPlaylistEvent.Saved)
            } catch (e: Exception) {
                _events.emit(EditPlaylistEvent.Error(e.message ?: "Failed to save playlist"))
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun remove() {
        val current = _playlist.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                deletePlaylistUseCase(current)
                updateScheduler.cancel(current.id)
                _events.emit(EditPlaylistEvent.Removed)
            } catch (e: Exception) {
                _events.emit(EditPlaylistEvent.Error(e.message ?: "Failed to remove playlist"))
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
        viewModelScope.launch { _events.emit(EditPlaylistEvent.Error(resKey)) }
    }
}
