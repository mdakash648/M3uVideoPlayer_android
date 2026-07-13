package com.mdaksh.m3uvideoplayer.ui.directlink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.usecase.PlayDirectLinkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DirectLinkEvent {
    data class Play(val channel: Channel) : DirectLinkEvent()
    data class Error(val message: String) : DirectLinkEvent()
}

@HiltViewModel
class DirectLinkViewModel @Inject constructor(
    private val playDirectLinkUseCase: PlayDirectLinkUseCase
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _events = MutableSharedFlow<DirectLinkEvent>()
    val events: SharedFlow<DirectLinkEvent> = _events

    fun play(url: String) {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            viewModelScope.launch {
                _events.emit(DirectLinkEvent.Error("error_url_required"))
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val channel = playDirectLinkUseCase(url.trim())
                _events.emit(DirectLinkEvent.Play(channel))
            } catch (e: Exception) {
                _events.emit(DirectLinkEvent.Error(e.message ?: "Failed to process direct link"))
            } finally {
                _isLoading.value = false
            }
        }
    }
}
