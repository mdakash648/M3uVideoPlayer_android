package com.mdaksh.m3uvideoplayer.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode
import com.mdaksh.m3uvideoplayer.ui.channel.ChannelSortOrder
import com.mdaksh.m3uvideoplayer.ui.channel.ChannelViewMode
import com.mdaksh.m3uvideoplayer.ui.group.FolderSortOrder
import com.mdaksh.m3uvideoplayer.ui.group.FolderViewMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 4.4 — a single app-wide (Preferences) DataStore holding the user's UI settings.
 *
 * Right now it only persists the channel [ChannelViewMode], but it's the natural home for
 * future global toggles (sort order, sync interval, etc.). Stored as the enum *name* rather
 * than its ordinal so reordering/removing enum entries later can't silently repoint an old
 * saved value at the wrong mode — an unknown name just falls back to [ChannelViewMode.DEFAULT].
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.userPreferencesDataStore

    /** Emits the currently-saved view mode, and re-emits whenever it changes. */
    val viewModeFlow: Flow<ChannelViewMode> = dataStore.data.map { prefs ->
        prefs[KEY_VIEW_MODE]?.let { saved ->
            runCatching { ChannelViewMode.valueOf(saved) }.getOrNull()
        } ?: ChannelViewMode.DEFAULT
    }

    /** Persists [mode] globally; every active [viewModeFlow] collector sees it immediately. */
    suspend fun setViewMode(mode: ChannelViewMode) {
        dataStore.edit { prefs -> prefs[KEY_VIEW_MODE] = mode.name }
    }

    /** The globally-saved folder (group) layout — List vs Grid. Same name-based scheme as above. */
    val folderViewModeFlow: Flow<FolderViewMode> = dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_VIEW_MODE]?.let { saved ->
            runCatching { FolderViewMode.valueOf(saved) }.getOrNull()
        } ?: FolderViewMode.DEFAULT
    }

    /** Persists [mode] globally; every active [folderViewModeFlow] collector sees it immediately. */
    suspend fun setFolderViewMode(mode: FolderViewMode) {
        dataStore.edit { prefs -> prefs[KEY_FOLDER_VIEW_MODE] = mode.name }
    }

    /** The globally-saved folder tile sort order. Same name-based scheme as the view modes. */
    val folderSortOrderFlow: Flow<FolderSortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_SORT_ORDER]?.let { saved ->
            runCatching { FolderSortOrder.valueOf(saved) }.getOrNull()
        } ?: FolderSortOrder.DEFAULT
    }

    /** Persists [order] globally; every active [folderSortOrderFlow] collector sees it immediately. */
    suspend fun setFolderSortOrder(order: FolderSortOrder) {
        dataStore.edit { prefs -> prefs[KEY_FOLDER_SORT_ORDER] = order.name }
    }

    /**
     * The globally-saved Folder Content (channel list) sort order. Stored under its own key,
     * entirely independent of [KEY_FOLDER_SORT_ORDER] — changing one never touches the other.
     */
    val channelSortOrderFlow: Flow<ChannelSortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_CHANNEL_SORT_ORDER]?.let { saved ->
            runCatching { ChannelSortOrder.valueOf(saved) }.getOrNull()
        } ?: ChannelSortOrder.DEFAULT
    }

    /** Persists [order] globally; every active [channelSortOrderFlow] collector sees it immediately. */
    suspend fun setChannelSortOrder(order: ChannelSortOrder) {
        dataStore.edit { prefs -> prefs[KEY_CHANNEL_SORT_ORDER] = order.name }
    }

    /**
     * User-chosen column count for **Grid** views (the folder grid and the channel grid) on the
     * Folder and Folder Content screens. [COLUMN_COUNT_AUTO] (the default) means "follow the built-in
     * responsive behaviour" (`R.integer.grid_span_count`, i.e. 3 on phones / 4 on large screens);
     * any value in [[COLUMN_COUNT_MIN], [COLUMN_COUNT_MAX]] overrides it with a fixed column count.
     */
    val gridColumnCountFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_GRID_COLUMN_COUNT] ?: COLUMN_COUNT_AUTO
    }

    /** Persists the Grid-view column count; every active [gridColumnCountFlow] collector re-renders. */
    suspend fun setGridColumnCount(count: Int) {
        dataStore.edit { prefs -> prefs[KEY_GRID_COLUMN_COUNT] = count }
    }

    /**
     * User-chosen column count for the **Poster** view on the Folder Content screen. Same
     * [COLUMN_COUNT_AUTO]-means-responsive semantics as [gridColumnCountFlow], stored under its own
     * key so Grid and Poster counts are independent.
     */
    val posterColumnCountFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_POSTER_COLUMN_COUNT] ?: COLUMN_COUNT_AUTO
    }

    /** Persists the Poster-view column count; every active [posterColumnCountFlow] collector re-renders. */
    suspend fun setPosterColumnCount(count: Int) {
        dataStore.edit { prefs -> prefs[KEY_POSTER_COLUMN_COUNT] = count }
    }

    /** The globally-saved player controller auto-hide timeout duration (in milliseconds). */
    val controlsTimeoutMsFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CONTROLS_TIMEOUT_MS] ?: DEFAULT_CONTROLS_TIMEOUT_MS
    }

    /** Persists the controls auto-hide timeout duration (in milliseconds). */
    suspend fun setControlsTimeoutMs(timeoutMs: Int) {
        val clamped = timeoutMs.coerceIn(CONTROLS_TIMEOUT_MIN, CONTROLS_TIMEOUT_MAX)
        dataStore.edit { prefs -> prefs[KEY_CONTROLS_TIMEOUT_MS] = clamped }
    }

    /** The globally-saved player swipe seek sensitivity (in percentage). */
    val swipeSensitivityPercentFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SWIPE_SENSITIVITY_PERCENT] ?: DEFAULT_SWIPE_SENSITIVITY_PERCENT
    }

    /** Persists the player swipe seek sensitivity (in percentage). */
    suspend fun setSwipeSensitivityPercent(percent: Int) {
        val clamped = percent.coerceIn(SWIPE_SENSITIVITY_MIN, SWIPE_SENSITIVITY_MAX)
        dataStore.edit { prefs -> prefs[KEY_SWIPE_SENSITIVITY_PERCENT] = clamped }
    }

    /**
     * The user's time-zone display preference — [TimeZoneMode] plus, when [TimeZoneMode.MANUAL],
     * the explicitly chosen IANA zone id (e.g. "Asia/Dhaka"). In AUTO mode [TimeZonePreference.zoneId]
     * is null and callers should fall back to the device zone for display. Stored name-based, same
     * as the other prefs, so reordering/removing enum entries can't silently repoint an old value.
     */
    val timeZonePreferenceFlow: Flow<TimeZonePreference> = dataStore.data.map { prefs ->
        val mode = TimeZoneMode.fromName(prefs[KEY_TIME_ZONE_MODE])
        val zoneId = prefs[KEY_TIME_ZONE_ID]?.takeIf { it.isNotBlank() }
        TimeZonePreference(mode, zoneId)
    }

    /** Switch to Auto — display follows the device zone; the saved manual zone id is cleared. */
    suspend fun setTimeZoneAuto() {
        dataStore.edit { prefs ->
            prefs[KEY_TIME_ZONE_MODE] = TimeZoneMode.AUTO.name
            prefs.remove(KEY_TIME_ZONE_ID)
        }
    }

    /** Switch to Manual and pin display to [zoneId] (an IANA id like "Asia/Dhaka"). */
    suspend fun setTimeZoneManual(zoneId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TIME_ZONE_MODE] = TimeZoneMode.MANUAL.name
            prefs[KEY_TIME_ZONE_ID] = zoneId
        }
    }

    companion object {
        private val KEY_VIEW_MODE = stringPreferencesKey("channel_view_mode")
        private val KEY_FOLDER_VIEW_MODE = stringPreferencesKey("folder_view_mode")
        private val KEY_FOLDER_SORT_ORDER = stringPreferencesKey("folder_sort_order")
        private val KEY_CHANNEL_SORT_ORDER = stringPreferencesKey("channel_sort_order")
        private val KEY_GRID_COLUMN_COUNT = intPreferencesKey("grid_column_count")
        private val KEY_POSTER_COLUMN_COUNT = intPreferencesKey("poster_column_count")
        private val KEY_CONTROLS_TIMEOUT_MS = intPreferencesKey("controls_timeout_ms")
        private val KEY_SWIPE_SENSITIVITY_PERCENT = intPreferencesKey("swipe_sensitivity_percent")
        private val KEY_TIME_ZONE_MODE = stringPreferencesKey("time_zone_mode")
        private val KEY_TIME_ZONE_ID = stringPreferencesKey("time_zone_id")

        /** Sentinel: use the built-in responsive column count instead of a fixed user value. */
        const val COLUMN_COUNT_AUTO = 0
        /** Smallest fixed column count the user can pick (below this snaps back to Auto). */
        const val COLUMN_COUNT_MIN = 2
        /** Largest fixed column count the user can pick. */
        const val COLUMN_COUNT_MAX = 8

        const val DEFAULT_CONTROLS_TIMEOUT_MS = 3500
        const val CONTROLS_TIMEOUT_MIN = 500      // 0.5s
        const val CONTROLS_TIMEOUT_MAX = 10000    // 10.0s
        const val CONTROLS_TIMEOUT_STEP = 500     // 0.5s step

        const val DEFAULT_SWIPE_SENSITIVITY_PERCENT = 25
        const val SWIPE_SENSITIVITY_MIN = 25
        const val SWIPE_SENSITIVITY_MAX = 200
        const val SWIPE_SENSITIVITY_STEP = 25
    }
}

/**
 * The persisted time-zone display choice. In [TimeZoneMode.AUTO], [zoneId] is null and display
 * should follow the device's current zone; in [TimeZoneMode.MANUAL], [zoneId] is the pinned IANA id.
 */
data class TimeZonePreference(
    val mode: TimeZoneMode,
    val zoneId: String?
)

/**
 * Single process-wide DataStore instance. The delegate must be a top-level property (the
 * library enforces one DataStore per file/name), so [UserPreferencesRepository] reads it via
 * the injected application Context rather than owning it directly.
 */
private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")
