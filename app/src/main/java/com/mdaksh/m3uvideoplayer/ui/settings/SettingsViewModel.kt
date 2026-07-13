package com.mdaksh.m3uvideoplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository.Companion.COLUMN_COUNT_AUTO
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository.Companion.COLUMN_COUNT_MAX
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository.Companion.COLUMN_COUNT_MIN
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.mdaksh.m3uvideoplayer.domain.usecase.DirectLinksHistory
import com.mdaksh.m3uvideoplayer.domain.usecase.ExportHistoryUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.GetPlaylistsUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.ImportHistoryUseCase
import com.mdaksh.m3uvideoplayer.domain.usecase.SyncPlaylistUseCase
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Backs the Settings screen's Display section — the two column-count steppers (Grid & Poster).
 *
 * Both counts live in [UserPreferencesRepository] where the Folder / Folder Content screens already
 * collect them, so a step here re-renders those grids live. The stepper walks a single ordered ladder:
 * [COLUMN_COUNT_AUTO] (responsive default) → [COLUMN_COUNT_MIN] → … → [COLUMN_COUNT_MAX]. Stepping
 * below MIN drops back to Auto; stepping above MAX is clamped.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val exportHistoryUseCase: ExportHistoryUseCase,
    private val importHistoryUseCase: ImportHistoryUseCase,
    private val getPlaylistsUseCase: GetPlaylistsUseCase,
    private val syncPlaylistUseCase: SyncPlaylistUseCase,
    private val timeZoneManager: com.mdaksh.m3uvideoplayer.data.time.TimeZoneManager,
    private val trustedTimeSource: com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource
) : ViewModel() {

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * True while "Update Playlists" is re-syncing every saved playlist. The Settings screen observes
     * this to disable the button for the duration and flip it back on automatically when done.
     */
    private val _isUpdatingPlaylists = MutableStateFlow(false)
    val isUpdatingPlaylists: StateFlow<Boolean> = _isUpdatingPlaylists.asStateFlow()

    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event.asStateFlow()

    fun consumeEvent() {
        _event.value = null
    }

    /** Current Grid-view column count (Auto sentinel or a fixed value in [MIN, MAX]). */
    val gridColumnCount: StateFlow<Int> = userPreferencesRepository.gridColumnCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), COLUMN_COUNT_AUTO)

    /** Current Poster-view column count, independent of the Grid count. */
    val posterColumnCount: StateFlow<Int> = userPreferencesRepository.posterColumnCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), COLUMN_COUNT_AUTO)

    /** Current player controller auto-hide timeout duration (in milliseconds). */
    val controlsTimeoutMs: StateFlow<Int> = userPreferencesRepository.controlsTimeoutMsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.DEFAULT_CONTROLS_TIMEOUT_MS)

    /** Current player swipe seek sensitivity (in percentage). */
    val swipeSensitivityPercent: StateFlow<Int> = userPreferencesRepository.swipeSensitivityPercentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferencesRepository.DEFAULT_SWIPE_SENSITIVITY_PERCENT)

    /** Current time zone preference. */
    val timeZonePreference: StateFlow<com.mdaksh.m3uvideoplayer.data.preferences.TimeZonePreference> = userPreferencesRepository.timeZonePreferenceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 
            com.mdaksh.m3uvideoplayer.data.preferences.TimeZonePreference(com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode.AUTO, null))

    /** The resolved timezone (Auto = IP-detected, Manual = user pick). */
    val resolvedTimeZone: StateFlow<com.mdaksh.m3uvideoplayer.data.time.ResolvedTimeZone> = timeZoneManager.resolvedTimeZone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.mdaksh.m3uvideoplayer.data.time.ResolvedTimeZone(com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode.AUTO, java.time.ZoneId.systemDefault()))

    fun setTimeZoneAuto() {
        viewModelScope.launch { userPreferencesRepository.setTimeZoneAuto() }
    }

    fun setTimeZoneManual(zoneId: String) {
        viewModelScope.launch { userPreferencesRepository.setTimeZoneManual(zoneId) }
    }

    fun incrementGridColumns() = persistGrid(step(gridColumnCount.value, up = true))
    fun decrementGridColumns() = persistGrid(step(gridColumnCount.value, up = false))

    fun incrementPosterColumns() = persistPoster(step(posterColumnCount.value, up = true))
    fun decrementPosterColumns() = persistPoster(step(posterColumnCount.value, up = false))

    fun incrementControlsTimeout() {
        val next = (controlsTimeoutMs.value + UserPreferencesRepository.CONTROLS_TIMEOUT_STEP)
            .coerceAtMost(UserPreferencesRepository.CONTROLS_TIMEOUT_MAX)
        viewModelScope.launch { userPreferencesRepository.setControlsTimeoutMs(next) }
    }

    fun decrementControlsTimeout() {
        val prev = (controlsTimeoutMs.value - UserPreferencesRepository.CONTROLS_TIMEOUT_STEP)
            .coerceAtLeast(UserPreferencesRepository.CONTROLS_TIMEOUT_MIN)
        viewModelScope.launch { userPreferencesRepository.setControlsTimeoutMs(prev) }
    }

    fun incrementSwipeSensitivity() {
        val next = (swipeSensitivityPercent.value + UserPreferencesRepository.SWIPE_SENSITIVITY_STEP)
            .coerceAtMost(UserPreferencesRepository.SWIPE_SENSITIVITY_MAX)
        viewModelScope.launch { userPreferencesRepository.setSwipeSensitivityPercent(next) }
    }

    fun decrementSwipeSensitivity() {
        val prev = (swipeSensitivityPercent.value - UserPreferencesRepository.SWIPE_SENSITIVITY_STEP)
            .coerceAtLeast(UserPreferencesRepository.SWIPE_SENSITIVITY_MIN)
        viewModelScope.launch { userPreferencesRepository.setSwipeSensitivityPercent(prev) }
    }

    private fun persistGrid(count: Int) {
        viewModelScope.launch { userPreferencesRepository.setGridColumnCount(count) }
    }

    private fun persistPoster(count: Int) {
        viewModelScope.launch { userPreferencesRepository.setPosterColumnCount(count) }
    }

    companion object {
        /** True when [count] is already at the ladder's low end (Auto) — the minus button disables here. */
        fun isAtMin(count: Int): Boolean = count == COLUMN_COUNT_AUTO

        /** True when [count] is already at the ladder's high end — the plus button disables here. */
        fun isAtMax(count: Int): Boolean = count >= COLUMN_COUNT_MAX

        fun isAtControlsTimeoutMin(value: Int): Boolean = value <= UserPreferencesRepository.CONTROLS_TIMEOUT_MIN
        fun isAtControlsTimeoutMax(value: Int): Boolean = value >= UserPreferencesRepository.CONTROLS_TIMEOUT_MAX

        fun isAtSwipeSensitivityMin(value: Int): Boolean = value <= UserPreferencesRepository.SWIPE_SENSITIVITY_MIN
        fun isAtSwipeSensitivityMax(value: Int): Boolean = value >= UserPreferencesRepository.SWIPE_SENSITIVITY_MAX

        /**
         * One step along AUTO → MIN → … → MAX. Going up from AUTO lands on MIN; going down from MIN
         * returns to AUTO. Both ends are clamped so repeated taps can't overshoot.
         */
        private fun step(current: Int, up: Boolean): Int = when {
            up && current == COLUMN_COUNT_AUTO -> COLUMN_COUNT_MIN
            up -> (current + 1).coerceAtMost(COLUMN_COUNT_MAX)
            current <= COLUMN_COUNT_MIN -> COLUMN_COUNT_AUTO
            else -> current - 1
        }
    }

    /**
     * Re-syncs every saved playlist from its source (M3U URL / Xtream server), one after another.
     * Guarded so a second tap while running is ignored; [isUpdatingPlaylists] stays true for the whole
     * run and drops back to false in `finally`, so the button re-enables even if a sync throws. A per
     * playlist failure is counted but does not abort the rest. The auto-created "Direct Links History"
     * playlist is skipped — it has no real source URL to refresh.
     */
    fun updateAllPlaylists() {
        if (_isUpdatingPlaylists.value) return
        _isUpdatingPlaylists.value = true
        viewModelScope.launch {
            var succeeded = 0
            var failed = 0
            try {
                // Refresh trusted time anchor before syncing so the SSL trust manager can
                // validate certificates using correct network time (device clock may be wrong).
                trustedTimeSource.refresh()

                val syncable = getPlaylistsUseCase().first()
                    .filter { it.url != DirectLinksHistory.URL_MARKER }
                for (playlist in syncable) {
                    try {
                        syncPlaylistUseCase(playlist)
                        succeeded++
                    } catch (e: Exception) {
                        failed++
                    }
                }
                _event.value = when {
                    syncable.isEmpty() -> "No playlists to update"
                    failed == 0 -> "All playlists updated ($succeeded)"
                    else -> "Updated $succeeded, $failed failed"
                }
            } catch (e: Exception) {
                _event.value = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't update playlists."
            } finally {
                _isUpdatingPlaylists.value = false
            }
        }
    }

    fun exportHistoryTo(contentResolver: ContentResolver, destination: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val m3uData = exportHistoryUseCase()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        output.write(m3uData.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Couldn't open the destination file")
                }
                _event.value = "History exported successfully"
            } catch (e: Exception) {
                _event.value = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't export history."
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importHistoryFrom(contentResolver: ContentResolver, source: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)?.use { input ->
                        importHistoryUseCase(input)
                    } ?: throw IllegalStateException("Couldn't open the selected file")
                }
                _event.value = "History imported successfully"
            } catch (e: Exception) {
                _event.value = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't import history."
            } finally {
                _isBusy.value = false
            }
        }
    }
}
