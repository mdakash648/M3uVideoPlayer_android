package com.mdaksh.m3uvideoplayer.ui.player.engine

import android.net.Uri
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * Media3 migration — the FALLBACK playback engine (LibVLC).
 *
 * Only used when [ExoPlaybackEngine] reports a fatal error on a stream (exotic codec/container).
 * Wraps the same LibVLC [MediaPlayer] the app used before the migration, exposed through the shared
 * [PlaybackEngine] contract so the activity's UI code is identical for both engines.
 *
 * Per the migration scope this covers core playback (load / play-pause / seek / tracks / speed /
 * software gain / aspect). It carries over the two live-safety behaviours that matter most on the
 * fallback path: the audio-only / surface-lost recovery signal (Vout / ESDeleted) surfaced through
 * [PlaybackEngine.Listener.onVideoSurfaceLost].
 */
class VlcPlaybackEngine(
    private val libVlc: LibVLC
) : PlaybackEngine {

    private var player: MediaPlayer? = null
    private var views: EngineViews? = null
    private var listener: PlaybackEngine.Listener? = null
    private var softwareDecode = false

    override fun bind(views: EngineViews, listener: PlaybackEngine.Listener) {
        this.views = views
        this.listener = listener
        views.playerView.visibility = View.GONE
        views.vlcLayout.visibility = View.VISIBLE
    }

    override fun load(
        url: String,
        referrer: String?,
        userAgent: String?,
        startMs: Long,
        forceSoftwareDecode: Boolean
    ) {
        val layout = views?.vlcLayout ?: return
        release()
        softwareDecode = forceSoftwareDecode

        val mp = MediaPlayer(libVlc).also { p ->
            p.attachViews(layout, null, true, false)
            p.setEventListener(::onVlcEvent)
        }
        player = mp

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(!forceSoftwareDecode, false)
            // Generous live caching so segment boundaries don't underflow on the fallback path.
            addOption(":network-caching=8000")
            addOption(":live-caching=6000")
            addOption(":clock-jitter=5000")
            referrer?.let { addOption(":http-referrer=$it") }
            userAgent?.let { addOption(":http-user-agent=$it") }
            if (startMs > 0L) addOption(":start-time=${startMs / 1000}")
        }
        mp.media = media
        media.release()
        mp.play()
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        val l = listener ?: return
        when (event.type) {
            MediaPlayer.Event.Buffering ->
                if (event.buffering < 100f) l.onBuffering() else l.onReady()
            MediaPlayer.Event.Playing -> {
                l.onReady()
                l.onPlayingChanged(true)
            }
            MediaPlayer.Event.Paused -> l.onPlayingChanged(false)
            MediaPlayer.Event.Vout ->
                if (event.voutCount == 0) l.onVideoSurfaceLost() else l.onReady()
            MediaPlayer.Event.ESDeleted ->
                if (event.esChangedType == IMedia.Track.Type.Video) l.onVideoSurfaceLost()
            MediaPlayer.Event.EncounteredError -> l.onError()
            MediaPlayer.Event.EndReached -> l.onEnded()
        }
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override val isPlaying: Boolean get() = player?.isPlaying == true

    override val positionMs: Long get() = player?.time ?: 0L
    override val durationMs: Long get() = (player?.length ?: 0L).coerceAtLeast(0L)
    override fun seekTo(ms: Long) { player?.time = ms }

    override var speed: Float
        get() = player?.rate ?: 1f
        set(value) { player?.rate = value }

    override fun setSoftwareVolume(percent: Int) {
        // VLC's own volume is 0..200; 100 = normal, up to 200 = boost (the pre-migration behaviour).
        player?.volume = percent.coerceIn(0, 200)
    }

    override val isLive: Boolean get() = durationMs <= 0L

    override fun audioTracks(): List<PlaybackEngine.TrackInfo> =
        trackList(player?.audioTracks, player?.audioTrack)
    override fun subtitleTracks(): List<PlaybackEngine.TrackInfo> =
        trackList(player?.spuTracks, player?.spuTrack)

    private fun trackList(
        tracks: Array<MediaPlayer.TrackDescription>?,
        currentId: Int?
    ): List<PlaybackEngine.TrackInfo> =
        tracks?.map { PlaybackEngine.TrackInfo(it.id.toString(), it.name, it.id == currentId) }
            ?: emptyList()

    override fun selectAudioTrack(id: String) { id.toIntOrNull()?.let { player?.audioTrack = it } }
    override fun selectSubtitleTrack(id: String) { id.toIntOrNull()?.let { player?.spuTrack = it } }

    override fun setFillMode(fill: Boolean) {
        player?.videoScale =
            if (fill) MediaPlayer.ScaleType.SURFACE_FILL
            else MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    /** LibVLC manages its own audio pipeline; no session id is exposed for external effects. */
    override val audioSessionId: Int get() = 0

    override fun release() {
        player?.let { p ->
            p.setEventListener(null)
            p.stop()
            p.detachViews()
            p.release()
        }
        player = null
    }
}
