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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    /** Current Grid-view column count (Auto sentinel or a fixed value in [MIN, MAX]). */
    val gridColumnCount: StateFlow<Int> = userPreferencesRepository.gridColumnCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), COLUMN_COUNT_AUTO)

    /** Current Poster-view column count, independent of the Grid count. */
    val posterColumnCount: StateFlow<Int> = userPreferencesRepository.posterColumnCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), COLUMN_COUNT_AUTO)

    fun incrementGridColumns() = persistGrid(step(gridColumnCount.value, up = true))
    fun decrementGridColumns() = persistGrid(step(gridColumnCount.value, up = false))

    fun incrementPosterColumns() = persistPoster(step(posterColumnCount.value, up = true))
    fun decrementPosterColumns() = persistPoster(step(posterColumnCount.value, up = false))

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
}
