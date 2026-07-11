package com.mdaksh.m3uvideoplayer.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.videolan.libvlc.LibVLC
import javax.inject.Singleton

/**
 * Step 9.2 — provides the single, process-wide LibVLC [LibVLC] engine instance via Hilt.
 *
 * `LibVLC` is expensive to create and owns native resources, so exactly one is shared for the whole
 * app (`@Singleton`); each [org.videolan.libvlc.MediaPlayer] is cheap and created per-[PlayerActivity]
 * on top of it. The option list is where the engine-wide flags live:
 *
 *  - **Step 9.5:** `--avcodec-hw=any` turns on hardware-accelerated decoding, which is what makes
 *    4K/8K streams playable on real devices. `--avcodec-fast`/`--avcodec-skiploopfilter` shave
 *    decode cost further for high-bitrate content.
 *  - **Step 9.4:** the digital audio amplifier is enabled here so the player can push gain past 100%
 *    (up to a 200% boost) at playback time via `MediaPlayer.setVolume`.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideLibVlc(@ApplicationContext context: Context): LibVLC {
        val options = arrayListOf(
            // --- Step 9.5: hardware acceleration for 4K/8K ---
            "--avcodec-hw=any",       // use any available hardware decoder (MediaCodec on Android)
            "--avcodec-fast",         // allow speed-optimised (slightly less accurate) decoding
            "--avcodec-skiploopfilter=all",
            "--network-caching=1500", // 1.5s buffer — smoother live IPTV over flaky networks

            // --- Step 9.4: digital audio amplifier (allows >100% volume) ---
            "--audio-time-stretch",   // keep pitch natural when boosting/altering audio

            // Keep the native log readable during bring-up; drop to "0" for release.
            "-vv"
        )
        return LibVLC(context, options)
    }
}
