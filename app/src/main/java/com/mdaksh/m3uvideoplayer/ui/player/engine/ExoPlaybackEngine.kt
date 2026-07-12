package com.mdaksh.m3uvideoplayer.ui.player.engine

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Media3 migration — the PRIMARY playback engine (ExoPlayer).
 *
 * ExoPlayer natively does the forward-buffering + rolling eviction + adaptive-bitrate that the app
 * previously tried to hand-build with a local HLS proxy, so this engine needs no proxy: the injected
 * [LoadControl] tunes the buffer and [HlsMediaSource] handles segmented HLS directly.
 *
 * A fatal [PlaybackException] is surfaced through [PlaybackEngine.Listener.onError], which is the
 * signal the activity uses to fall back to [VlcPlaybackEngine] for that stream.
 */
@UnstableApi
class ExoPlaybackEngine(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val loadControl: LoadControl
) : PlaybackEngine {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val trackSelector = DefaultTrackSelector(context)
    private var listener: PlaybackEngine.Listener? = null
    private var loudness: LoudnessEnhancer? = null

    /** Latest known tracks, used to build the audio/subtitle selection lists. */
    private var lastTracks: Tracks = Tracks.EMPTY

    override fun bind(views: EngineViews, listener: PlaybackEngine.Listener) {
        this.listener = listener
        this.playerView = views.playerView
        // Show ExoPlayer's surface, hide the VLC fallback one.
        views.playerView.visibility = View.VISIBLE
        views.vlcLayout.visibility = View.GONE
    }

    override fun load(
        url: String,
        referrer: String?,
        userAgent: String?,
        startMs: Long,
        forceSoftwareDecode: Boolean
    ) {
        release()

        val exo = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(buildSourceFactory(referrer, userAgent))
            .build()
        player = exo

        playerView?.player = exo
        playerView?.keepScreenOn = true

        exo.addListener(playerListener)
        exo.setMediaItem(MediaItem.fromUri(url))
        if (startMs > 0L) exo.seekTo(startMs)
        exo.playWhenReady = true
        exo.prepare()

        // LoudnessEnhancer needs a valid session id, available once the audio pipeline is built.
        attachLoudness(exo.audioSessionId)
    }

    /**
     * Build a media-source factory that injects the per-stream HTTP headers. HLS is routed through
     * [HlsMediaSource] explicitly; every other container is handled by [DefaultMediaSourceFactory]'s
     * extractor pipeline (progressive MP4/MKV/TS, DASH, etc.).
     */
    private fun buildSourceFactory(referrer: String?, userAgent: String?): MediaSource.Factory {
        val headers = buildMap {
            referrer?.let { put("Referer", it) }
            userAgent?.let { put("User-Agent", it) }
        }
        val factory = dataSourceFactory.also {
            // OkHttpDataSource.Factory carries default headers; set them when present.
            if (headers.isNotEmpty() && it is androidx.media3.datasource.okhttp.OkHttpDataSource.Factory) {
                it.setDefaultRequestProperties(headers)
            }
        }
        return DefaultMediaSourceFactory(factory)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> listener?.onBuffering()
                Player.STATE_READY -> listener?.onReady()
                Player.STATE_ENDED -> listener?.onEnded()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onPlayingChanged(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            lastTracks = tracks
        }

        override fun onPlayerError(error: PlaybackException) {
            // The activity turns this into an automatic VLC fallback for the current stream.
            listener?.onError()
        }
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override val isPlaying: Boolean get() = player?.isPlaying == true

    override val positionMs: Long get() = player?.currentPosition ?: 0L
    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

    override fun seekTo(ms: Long) { player?.seekTo(ms) }

    override var speed: Float
        get() = player?.playbackParameters?.speed ?: 1f
        set(value) { player?.setPlaybackSpeed(value) }

    override fun setSoftwareVolume(percent: Int) {
        // 100 = normal. ExoPlayer's own volume is linear 0..1; keep it at 1 and use LoudnessEnhancer
        // for the >100% boost so 200% is a real gain, matching the old VLC behaviour.
        player?.volume = 1f
        val gainMb = ((percent - 100).coerceAtLeast(0)) * BOOST_MB_PER_PERCENT
        runCatching { loudness?.setTargetGain(gainMb) }
    }

    override val isLive: Boolean
        get() = player?.isCurrentMediaItemLive == true || durationMs <= 0L

    override fun audioTracks(): List<PlaybackEngine.TrackInfo> = tracksOfType(C.TRACK_TYPE_AUDIO)
    override fun subtitleTracks(): List<PlaybackEngine.TrackInfo> = tracksOfType(C.TRACK_TYPE_TEXT)

    private fun tracksOfType(type: Int): List<PlaybackEngine.TrackInfo> {
        val result = ArrayList<PlaybackEngine.TrackInfo>()
        for (group in lastTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val id = "${group.mediaTrackGroup.id}:$i"
                val label = format.label ?: format.language ?: "Track ${result.size + 1}"
                result.add(PlaybackEngine.TrackInfo(id, label, group.isTrackSelected(i)))
            }
        }
        return result
    }

    override fun selectAudioTrack(id: String) = selectTrack(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitleTrack(id: String) = selectTrack(id, C.TRACK_TYPE_TEXT)

    private fun selectTrack(id: String, type: Int) {
        val exo = player ?: return
        for (group in lastTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if ("${group.mediaTrackGroup.id}:$i" == id) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setTrackTypeDisabled(type, false)
                        .build()
                    return
                }
            }
        }
    }

    override fun setFillMode(fill: Boolean) {
        playerView?.resizeMode =
            if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    override val audioSessionId: Int get() = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET

    private fun attachLoudness(sessionId: Int) {
        runCatching { loudness?.release() }
        loudness = null
        if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
            loudness = runCatching { LoudnessEnhancer(sessionId).apply { enabled = true } }.getOrNull()
        }
    }

    override fun release() {
        runCatching { loudness?.release() }
        loudness = null
        playerView?.player = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        lastTracks = Tracks.EMPTY
    }

    companion object {
        /** LoudnessEnhancer gain per percent above 100 (millibels). 100→0 mB, 200→~2000 mB. */
        private const val BOOST_MB_PER_PERCENT = 20
    }
}
