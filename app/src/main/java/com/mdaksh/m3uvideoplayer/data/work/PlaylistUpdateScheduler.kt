package com.mdaksh.m3uvideoplayer.data.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates a playlist's [UpdateFrequency] into WorkManager schedules.
 *
 *  - Periodic options ([UpdateFrequency.isPeriodic]) enqueue a unique [PeriodicWorkRequestBuilder]
 *    keyed by playlist id, so re-applying just replaces the existing schedule.
 *  - `ON_START` / `NEVER` cancel any periodic work; `ON_START` is instead handled once per launch
 *    via [runStartupSyncs].
 */
@Singleton
class PlaylistUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Install (or remove) the periodic schedule for [playlistId] according to [frequency]. */
    fun apply(playlistId: Long, frequency: UpdateFrequency) {
        val repeatMillis = frequency.repeatMillis
        if (repeatMillis == null) {
            // ON_START and NEVER have no periodic component.
            cancel(playlistId)
            return
        }
        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            repeatMillis, TimeUnit.MILLISECONDS
        )
            .setInputData(workDataOf(PlaylistSyncWorker.KEY_PLAYLIST_ID to playlistId))
            .build()
        workManager.enqueueUniquePeriodicWork(
            uniqueName(playlistId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Cancel any scheduled refresh for [playlistId] (e.g. when the playlist is deleted). */
    fun cancel(playlistId: Long) {
        workManager.cancelUniqueWork(uniqueName(playlistId))
    }

    /** Enqueue a one-time sync for every playlist set to [UpdateFrequency.ON_START]. */
    fun runStartupSyncs(playlists: List<Playlist>) {
        playlists
            .filter { UpdateFrequency.fromName(it.updateFrequency) == UpdateFrequency.ON_START }
            .forEach { enqueueOneTime(it.id) }
    }

    private fun enqueueOneTime(playlistId: Long) {
        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setInputData(workDataOf(PlaylistSyncWorker.KEY_PLAYLIST_ID to playlistId))
            .build()
        workManager.enqueue(request)
    }

    private fun uniqueName(playlistId: Long): String = "$UNIQUE_WORK_PREFIX$playlistId"

    private companion object {
        const val UNIQUE_WORK_PREFIX = "sync_playlist_"
    }
}
