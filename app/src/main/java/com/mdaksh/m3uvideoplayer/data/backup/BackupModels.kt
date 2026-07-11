package com.mdaksh.m3uvideoplayer.data.backup

import com.mdaksh.m3uvideoplayer.data.local.entity.ChannelEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.GroupEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.HistoryEntity
import com.mdaksh.m3uvideoplayer.data.local.entity.PlaylistEntity

/**
 * The full "Application Data" snapshot written to (and read from) a backup JSON file.
 *
 * This is a plain Gson-serializable model. The Room entities are reused directly as the field
 * types below (Gson only looks at their properties, not their Room annotations), so the JSON
 * shape mirrors the database as-is.
 *
 * [schemaVersion] lets a future release detect and migrate an older backup file instead of
 * failing to parse it or silently dropping fields.
 */
data class BackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    val channels: List<ChannelEntity> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
    val preferences: BackupPreferences = BackupPreferences()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Snapshot of the app-wide DataStore settings (view modes, sort orders) covered by
 * [com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository]. Stored as raw enum
 * *names* — same forward/backward-compatible scheme the DataStore itself uses — so an unknown
 * value on restore just falls back to that setting's default instead of crashing the parse.
 */
data class BackupPreferences(
    val channelViewMode: String? = null,
    val folderViewMode: String? = null,
    val folderSortOrder: String? = null,
    val channelSortOrder: String? = null
)
