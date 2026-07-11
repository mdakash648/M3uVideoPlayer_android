package com.mdaksh.m3uvideoplayer.data.backup

import com.google.gson.Gson
import com.mdaksh.m3uvideoplayer.data.local.AppDatabase
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.GroupDao
import com.mdaksh.m3uvideoplayer.data.local.dao.HistoryDao
import com.mdaksh.m3uvideoplayer.data.local.dao.PlaylistDao
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.ui.channel.ChannelSortOrder
import com.mdaksh.m3uvideoplayer.ui.channel.ChannelViewMode
import com.mdaksh.m3uvideoplayer.ui.group.FolderSortOrder
import com.mdaksh.m3uvideoplayer.ui.group.FolderViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads/writes the entire app state — playlists, groups, channels, playback history, and the
 * DataStore-backed UI preferences — as a single [BackupPayload] JSON document. Backs the
 * "Save Application Data" / "Load Application Data" actions on the Backup & Restore screen.
 *
 * The actual file I/O (native file picker, `content://` Uri streams) lives in
 * [com.mdaksh.m3uvideoplayer.ui.settings.BackupRestoreViewModel] — this class only ever deals in
 * JSON strings, so it stays trivially testable.
 */
@Singleton
class BackupRestoreRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val historyDao: HistoryDao,
    private val appDatabase: AppDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val gson: Gson
) {

    /** Builds the full backup payload and serializes it to a pretty-printed JSON string. */
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            playlists = playlistDao.getAllPlaylistsOnce(),
            groups = groupDao.getAllGroupsOnce(),
            channels = channelDao.getAllChannelsOnce(),
            history = historyDao.getAllHistoryOnce(),
            preferences = BackupPreferences(
                channelViewMode = userPreferencesRepository.viewModeFlow.first().name,
                folderViewMode = userPreferencesRepository.folderViewModeFlow.first().name,
                folderSortOrder = userPreferencesRepository.folderSortOrderFlow.first().name,
                channelSortOrder = userPreferencesRepository.channelSortOrderFlow.first().name
            )
        )
        gson.toJson(payload)
    }

    /**
     * Parses [json] and replaces the entire current app state with it.
     *
     * Every table is cleared first via [AppDatabase.clearAllTables] so a restore never leaves a
     * mix of old and imported rows (e.g. a playlist the user deleted after the backup was taken
     * reappearing alongside the restored ones). Insertion order matters: playlists before groups
     * (groups declare a `CASCADE` foreign key on `playlistId`) and before channels.
     *
     * @throws BackupParseException if [json] isn't a valid/recognized backup file.
     */
    suspend fun importBackupJson(json: String): Unit = withContext(Dispatchers.IO) {
        val payload = try {
            gson.fromJson(json, BackupPayload::class.java)
        } catch (e: Exception) {
            throw BackupParseException("This file isn't a valid backup.", e)
        } ?: throw BackupParseException("This file isn't a valid backup.")

        if (payload.schemaVersion > BackupPayload.CURRENT_SCHEMA_VERSION) {
            throw BackupParseException(
                "This backup was made with a newer version of the app and can't be restored here."
            )
        }

        appDatabase.clearAllTables()

        playlistDao.insertPlaylists(payload.playlists)
        groupDao.insertGroups(payload.groups)
        channelDao.insertChannels(payload.channels)
        historyDao.insertHistoryList(payload.history)

        restorePreferences(payload.preferences)
    }

    private suspend fun restorePreferences(prefs: BackupPreferences) {
        prefs.channelViewMode
            ?.let { runCatching { ChannelViewMode.valueOf(it) }.getOrNull() }
            ?.let { userPreferencesRepository.setViewMode(it) }

        prefs.folderViewMode
            ?.let { runCatching { FolderViewMode.valueOf(it) }.getOrNull() }
            ?.let { userPreferencesRepository.setFolderViewMode(it) }

        prefs.folderSortOrder
            ?.let { runCatching { FolderSortOrder.valueOf(it) }.getOrNull() }
            ?.let { userPreferencesRepository.setFolderSortOrder(it) }

        prefs.channelSortOrder
            ?.let { runCatching { ChannelSortOrder.valueOf(it) }.getOrNull() }
            ?.let { userPreferencesRepository.setChannelSortOrder(it) }
    }
}

/** Thrown for any backup file that can't be parsed or safely applied. */
class BackupParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
