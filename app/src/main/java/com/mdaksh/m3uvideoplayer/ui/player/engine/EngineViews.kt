package com.mdaksh.m3uvideoplayer.ui.player.engine

import androidx.media3.ui.PlayerView
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Media3 migration — the two render surfaces from `activity_player.xml`, handed to whichever
 * [PlaybackEngine] is active. Each engine shows its own surface and hides the other on [bind], so
 * an ExoPlayer→VLC fallback swaps what the user sees without the activity touching view visibility.
 */
data class EngineViews(
    /** Media3 primary render surface. */
    val playerView: PlayerView,
    /** LibVLC fallback render surface. */
    val vlcLayout: VLCVideoLayout
)
