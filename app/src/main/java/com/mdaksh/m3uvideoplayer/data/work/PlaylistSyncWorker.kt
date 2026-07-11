package com.mdaksh.m3uvideoplayer.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
    }

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, INVALID_ID)
        if (playlistId == INVALID_ID) return Result.success()

        val repository = EntryPointAccessors
            .fromApplication(applicationContext, WorkerEntryPoint::class.java)
            .playlistRepository()

        val playlist = repository.getPlaylistById(playlistId) ?: return Result.success()

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
    }
}
