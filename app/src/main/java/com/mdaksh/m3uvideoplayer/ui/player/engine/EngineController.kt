package com.mdaksh.m3uvideoplayer.ui.player.engine

import androidx.media3.common.util.UnstableApi

/**
 * Media3 migration — owns both engines and presents ONE [PlaybackEngine] face to the activity,
 * transparently switching ExoPlayer → VLC when ExoPlayer fails on a stream.
 *
 * Flow: every [load] starts on the [exo] engine. If ExoPlayer raises a fatal error BEFORE it ever
 * became ready for that stream, the controller silently reloads the same URL on the [vlc] engine
 * (once per load — a VLC error after that is a real error the activity should show). If ExoPlayer
 * had already played the stream and errors later, that's surfaced normally rather than thrashing
 * engines mid-playback.
 *
 * The activity sees a stable object: [active] always points at whichever engine is live, and the
 * controller forwards the [PlaybackEngine.Listener] callbacks through, injecting the fallback in
 * between. UI code never learns which engine is running.
 */
@UnstableApi
class EngineController(
    private val exo: PlaybackEngine,
    private val vlc: PlaybackEngine
) {
    /** The engine currently responsible for playback. */
    var active: PlaybackEngine = exo
        private set

    val isFallbackActive: Boolean get() = active === vlc

    private lateinit var views: EngineViews
    private var outerListener: PlaybackEngine.Listener? = null

    // Remembered so a fallback can replay the exact same load on the VLC engine.
    private var lastUrl: String? = null
    private var lastReferrer: String? = null
    private var lastUserAgent: String? = null
    private var lastStartMs: Long = 0L

    /** True once the active engine reached ready for the current stream (blocks late fallback). */
    private var becameReady = false
    /** True once we've already fallen back for the current stream (blocks fallback loops). */
    private var fellBackForCurrent = false

    fun bind(views: EngineViews, listener: PlaybackEngine.Listener) {
        this.views = views
        this.outerListener = listener
        exo.bind(views, exoListener)
    }

    fun load(
        url: String,
        referrer: String?,
        userAgent: String?,
        startMs: Long,
        forceSoftwareDecode: Boolean
    ) {
        lastUrl = url
        lastReferrer = referrer
        lastUserAgent = userAgent
        lastStartMs = startMs
        becameReady = false
        fellBackForCurrent = false

        // Every new stream starts optimistically on ExoPlayer.
        switchTo(exo)
        active.load(url, referrer, userAgent, startMs, forceSoftwareDecode)
    }

    /** Listener wrapper installed on the ExoPlayer engine — intercepts errors for fallback. */
    private val exoListener = object : PlaybackEngine.Listener {
        override fun onBuffering() { outerListener?.onBuffering() }
        override fun onReady() { becameReady = true; outerListener?.onReady() }
        override fun onPlayingChanged(isPlaying: Boolean) { outerListener?.onPlayingChanged(isPlaying) }
        override fun onEnded() { outerListener?.onEnded() }
        override fun onVideoSurfaceLost() { outerListener?.onVideoSurfaceLost() }
        override fun onError() {
            // Fall back to VLC only for an early failure that hasn't already been retried.
            if (!fellBackForCurrent && !becameReady && lastUrl != null) {
                fellBackForCurrent = true
                fallbackToVlc()
            } else {
                outerListener?.onError()
            }
        }
    }

    /** Listener wrapper on the VLC engine — a VLC error is terminal (no further fallback). */
    private val vlcListener = object : PlaybackEngine.Listener {
        override fun onBuffering() { outerListener?.onBuffering() }
        override fun onReady() { becameReady = true; outerListener?.onReady() }
        override fun onPlayingChanged(isPlaying: Boolean) { outerListener?.onPlayingChanged(isPlaying) }
        override fun onEnded() { outerListener?.onEnded() }
        override fun onVideoSurfaceLost() { outerListener?.onVideoSurfaceLost() }
        override fun onError() { outerListener?.onError() }
    }

    private fun fallbackToVlc() {
        val url = lastUrl ?: return
        switchTo(vlc)
        vlc.load(url, lastReferrer, lastUserAgent, lastStartMs, forceSoftwareDecode = false)
    }

    /** Release the outgoing engine, bind the incoming one, and make it [active]. */
    private fun switchTo(target: PlaybackEngine) {
        if (active === target && (target === exo)) {
            // Same engine, fresh load — its own load() releases the previous media.
            return
        }
        // Release whichever engine we're leaving so two players never hold the surface at once.
        if (active !== target) active.release()
        target.bind(views, if (target === vlc) vlcListener else exoListener)
        active = target
    }

    // --- Straight delegation to the active engine ---------------------------------------------

    fun play() = active.play()
    fun pause() = active.pause()
    val isPlaying: Boolean get() = active.isPlaying
    val positionMs: Long get() = active.positionMs
    val durationMs: Long get() = active.durationMs
    fun seekTo(ms: Long) = active.seekTo(ms)
    var speed: Float
        get() = active.speed
        set(value) { active.speed = value }
    fun setSoftwareVolume(percent: Int) = active.setSoftwareVolume(percent)
    val isLive: Boolean get() = active.isLive
    fun audioTracks() = active.audioTracks()
    fun subtitleTracks() = active.subtitleTracks()
    fun selectAudioTrack(id: String) = active.selectAudioTrack(id)
    fun selectSubtitleTrack(id: String) = active.selectSubtitleTrack(id)
    fun setFillMode(fill: Boolean) = active.setFillMode(fill)
    val audioSessionId: Int get() = active.audioSessionId

    fun release() {
        exo.release()
        vlc.release()
    }
}
