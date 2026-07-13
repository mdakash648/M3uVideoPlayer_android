package com.mdaksh.m3uvideoplayer.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.repository.PlaylistRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Background worker that re-syncs a single playlist from its source (URL or the local `file://`
 * copy of an attached file), driven by the user's chosen [com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency].
 *
 * It's a plain [CoroutineWorker] instantiated by WorkManager's default factory; dependencies are
 * pulled from Hilt's [SingletonComponent] via an [EntryPoint] instead of `@HiltWorker`, so no extra
 * `androidx.hilt:hilt-work` integration is required.
 */
class PlaylistSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun playlistRepository(): PlaylistRepository
        fun trustedTimeSource(): TrustedTimeSource
    }

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, INVALID_ID)
        if (playlistId == INVALID_ID) return Result.success()

        val entryPoint = EntryPointAccessors
            .fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val repository = entryPoint.playlistRepository()
        val trustedTimeSource = entryPoint.trustedTimeSource()

        // Keep the trusted-time anchor fresh on every periodic wake. Never throws (offline just
        // keeps the cached anchor), so this can't fail the sync.
        trustedTimeSource.refresh()

        val playlist = repository.getPlaylistById(playlistId) ?: return Result.success()

        // Due-gate: WorkManager wakes us roughly every period, but a wrong device clock used to make
        // the app mis-decide whether a refresh was actually due. Gate on the trusted *elapsed*
        // duration since the last successful sync instead, so a too-early duplicate wake is skipped
        // and a genuinely-due one always runs — independent of the wall clock.
        val frequency = UpdateFrequency.fromName(playlist.updateFrequency)
        val repeatMillis = frequency.repeatMillis
        if (repeatMillis != null && playlist.lastUpdated > 0L) {
            val trusted = trustedTimeSource.now()
            val elapsedSinceSync = trusted.epochMs - playlist.lastUpdated
            // Only skip when we're confident (verified time) and clearly not yet due. A small margin
            // absorbs scheduling jitter so we never perpetually miss a cycle by a few seconds.
            if (trusted.verified && elapsedSinceSync in 0 until (repeatMillis - DUE_MARGIN_MILLIS)) {
                Log.d(
                    TAG,
                    "Playlist $playlistId not due yet " +
                        "(${elapsedSinceSync}ms of ${repeatMillis}ms elapsed); skipping."
                )
                return Result.success()
            }
        }

        return try {
            repository.syncPlaylist(playlist)
            Result.success()
        } catch (e: Exception) {
            // Transient failures (network down, server hiccup) — let WorkManager back off and retry.
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlistId"
        private const val INVALID_ID = -1L
        private const val TAG = "PlaylistSyncWorker"

        /**
         * Grace window subtracted from the interval before the due-gate skips a run. WorkManager's
         * periodic fire time drifts by a few seconds/minutes; without a margin an early-by-a-hair
         * wake would skip and then wait a whole extra period. 5 minutes comfortably covers the jitter
         * while staying tiny next to the shortest (6h) interval.
         */
        private const val DUE_MARGIN_MILLIS = 5L * 60 * 1000
    }
}
