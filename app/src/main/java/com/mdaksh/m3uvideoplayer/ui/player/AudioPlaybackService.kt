package com.mdaksh.m3uvideoplayer.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mdaksh.m3uvideoplayer.R
import dagger.hilt.android.AndroidEntryPoint
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import javax.inject.Inject

/**
 * Step 10.5 — "Play as Audio" / background playback.
 *
 * A foreground service that keeps the stream playing (audio-only) after the user leaves
 * [PlayerActivity]. It owns its own [MediaPlayer] built on the injected app-wide [LibVLC] engine —
 * no video output is attached, so only the audio track is decoded — and posts a media-style
 * notification with a play/pause and a stop action so playback is controllable from the shade.
 */
@AndroidEntryPoint
class AudioPlaybackService : Service() {

    @Inject
    lateinit var libVlc: LibVLC

    private var mediaPlayer: MediaPlayer? = null
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

        mediaPlayer = MediaPlayer(libVlc).also { player ->
            val media = Media(libVlc, Uri.parse(url)).apply {
                // Audio only — tell the decoder not to bother with the video track.
                addOption(":no-video")
                addOption(":network-caching=1500")
            }
            player.media = media
            media.release()
            player.play()
            if (startPosition > 0L) player.time = startPosition
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
        notifyManager().notify(NOTIFICATION_ID, buildNotification())
    }

    // --- Notification -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val playing = mediaPlayer?.isPlaying == true
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
        mediaPlayer?.let { player ->
            player.stop()
            player.release()
        }
        mediaPlayer = null
    }

    companion object {
        private const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 42

        const val ACTION_PLAY_PAUSE = "com.mdaksh.m3uvideoplayer.action.PLAY_PAUSE"
        const val ACTION_STOP = "com.mdaksh.m3uvideoplayer.action.STOP"

        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_POSITION = "extra_position"

        /** Intent that starts audio-only background playback of [url] from [positionMs]. */
        fun startIntent(context: Context, url: String, title: String, positionMs: Long): Intent =
            Intent(context, AudioPlaybackService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSITION, positionMs)

        fun stopIntent(context: Context): Intent =
            Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP)
    }
}
