package com.mdaksh.m3uvideoplayer.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mdaksh.m3uvideoplayer.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Step 10.5 — "Play as Audio" / background playback.
 *
 * A foreground service that keeps the stream playing (audio-only) after the user leaves
 * [PlayerActivity]. Media3 migration — it owns its own [ExoPlayer] with the video track disabled,
 * so only the audio track is decoded — and posts a media-style notification with a play/pause and
 * a stop action so playback is controllable from the shade.
 */
@UnstableApi
@AndroidEntryPoint
class AudioPlaybackService : Service() {

    /** Media3 migration — ExoPlayer HTTP stack + buffering policy, shared from PlayerModule. */
    @Inject
    lateinit var mediaDataSourceFactory: DataSource.Factory

    @Inject
    lateinit var loadControl: LoadControl

    private var player: ExoPlayer? = null
    private var title: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> start(intent)
        }
        return START_NOT_STICKY
    }

    /** Builds the audio-only player, seeks to the handed-off position, and goes foreground. */
    private fun start(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startPosition = intent.getLongExtra(EXTRA_POSITION, 0L)

        // Media3 migration — ExoPlayer with the video track disabled (audio-only background playback).
        // The injected LoadControl gives it the same generous live buffering as the foreground path.
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaDataSourceFactory))
            .build()
            .also { exo ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
                exo.setMediaItem(MediaItem.fromUri(url))
                if (startPosition > 0L) exo.seekTo(startPosition)
                exo.playWhenReady = true
                exo.prepare()
            }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) p.pause() else p.play()
        }
        notifyManager().notify(NOTIFICATION_ID, buildNotification())
    }

    // --- Notification -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val playing = player?.isPlaying == true
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio_mode)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(R.string.player_audio_playing))
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .addAction(playPauseIcon, getString(R.string.player_play_pause), action(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_back), action(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun action(name: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).setAction(name)
        return PendingIntent.getService(this, name.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_audio_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            notifyManager().createNotificationChannel(channel)
        }
    }

    private fun notifyManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 42

        const val ACTION_PLAY_PAUSE = "com.mdaksh.m3uvideoplayer.action.PLAY_PAUSE"
        const val ACTION_STOP = "com.mdaksh.m3uvideoplayer.action.STOP"

        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_POSITION = "extra_position"
        // promt2 — Live/VOD hint so background playback picks the same buffering profile as the UI.
        private const val EXTRA_IS_LIVE = "extra_is_live"

        /** Intent that starts audio-only background playback of [url] from [positionMs]. */
        fun startIntent(
            context: Context,
            url: String,
            title: String,
            positionMs: Long,
            isLive: Boolean = true
        ): Intent =
            Intent(context, AudioPlaybackService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSITION, positionMs)
                .putExtra(EXTRA_IS_LIVE, isLive)

        fun stopIntent(context: Context): Intent =
            Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP)
    }
}
