package com.mdaksh.m3uvideoplayer.ui.player.engine

/**
 * Media3 migration — the engine-agnostic playback contract.
 *
 * [com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity] talks ONLY to this interface, never to
 * ExoPlayer or LibVLC directly, so the automatic ExoPlayer→VLC fallback is a single swap of the
 * active [PlaybackEngine] with zero UI-code changes. Two implementations exist:
 *  • [ExoPlaybackEngine] — primary (Media3 / ExoPlayer).
 *  • [VlcPlaybackEngine] — fallback (LibVLC), used when ExoPlayer reports a fatal error on a stream.
 *
 * Scope note: per the migration decision, the fallback covers **core playback** — load, play/pause,
 * seek, position/duration, speed, audio/subtitle track selection, and software volume. Advanced
 * extras (PiP, 200% boost via LoudnessEnhancer, live surface watchdog) are wired on the ExoPlayer
 * path in the activity and are best-effort when the VLC fallback is active.
 */
interface PlaybackEngine {

    /** One selectable audio or subtitle track, normalised across both engines. */
    data class TrackInfo(
        /** Engine-local stable identifier used to (re)select this track. */
        val id: String,
        /** Human-readable label shown in the selection dialog. */
        val label: String,
        val selected: Boolean
    )

    /** Playback lifecycle callbacks, mapped from each engine's native event stream. */
    interface Listener {
        /** Buffering / connecting — show the spinner. */
        fun onBuffering()
        /** Ready & rendering — hide spinner, show video, (re)apply user prefs. */
        fun onReady()
        /** Playing/paused state changed (update the play/pause icon). */
        fun onPlayingChanged(isPlaying: Boolean)
        /** End of a finite (VOD) stream reached. */
        fun onEnded()
        /** Fatal playback error. On the ExoPlayer engine this is what triggers the VLC fallback. */
        fun onError()
        /** The video render surface was lost while a live stream should be playing (recovery hook). */
        fun onVideoSurfaceLost()
    }

    /** Attach the engine's output to its render surface and start receiving [Listener] callbacks. */
    fun bind(views: EngineViews, listener: Listener)

    /**
     * Load and start [url]. [referrer]/[userAgent] are the optional `#EXTVLCOPT` headers; [startMs]
     * is a resume offset (0 = from the start). [forceSoftwareDecode] pins software decoding.
     */
    fun load(
        url: String,
        referrer: String?,
        userAgent: String?,
        startMs: Long,
        forceSoftwareDecode: Boolean
    )

    fun play()
    fun pause()
    val isPlaying: Boolean

    /** Current position / total duration in ms. [durationMs] <= 0 means live/unknown. */
    val positionMs: Long
    val durationMs: Long
    fun seekTo(ms: Long)

    /** Playback speed multiplier (1.0 = normal). */
    var speed: Float

    /**
     * Software output level, 100..200 where 100 = normal (no boost). Values above 100 apply a gain
     * boost (ExoPlayer: LoudnessEnhancer; VLC: internal audio gain). The 0-100 hardware-volume range
     * is handled by the activity via AudioManager, exactly as before.
     */
    fun setSoftwareVolume(percent: Int)

    /** True when the current media has no finite duration (live IPTV/TS). */
    val isLive: Boolean

    fun audioTracks(): List<TrackInfo>
    fun subtitleTracks(): List<TrackInfo>
    fun selectAudioTrack(id: String)
    fun selectSubtitleTrack(id: String)

    /** Toggle between best-fit (letterbox) and fill (crop) video scaling. */
    fun setFillMode(fill: Boolean)

    /** Android audio session id for effects (LoudnessEnhancer); 0 if unavailable. */
    val audioSessionId: Int

    /** Stop playback and release native resources. The instance is not reused after this. */
    fun release()
}
