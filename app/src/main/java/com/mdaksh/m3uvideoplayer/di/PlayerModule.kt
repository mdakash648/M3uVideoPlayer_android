package com.mdaksh.m3uvideoplayer.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import javax.inject.Named
import javax.inject.Singleton

/**
 * Media3 migration — process-wide playback engine wiring.
 *
 * PRIMARY engine is **Media3 / ExoPlayer**. ExoPlayer itself is cheap and lifecycle-bound, so it is
 * built per-[com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity]; this module provides the expensive
 * / shared pieces it needs:
 *
 *  - **[LoadControl]** — the buffering policy. This REPLACES the old LibVLC `:network-caching` knobs
 *    (the former `PlaybackBuffering.kt`). ExoPlayer's LoadControl is what does the YouTube-style
 *    forward buffering + rolling eviction natively, so the hand-rolled HLS proxy is no longer needed.
 *  - **[DataSource.Factory]** — HTTP stack for media, backed by the app's shared [OkHttpClient] so
 *    per-stream `#EXTVLCOPT` headers (Referer / User-Agent) can be attached the same way as before.
 *
 * FALLBACK engine is **LibVLC**, kept for streams ExoPlayer can't open (exotic codecs / containers).
 * It is expensive to create and owns native resources, so exactly one is shared (`@Singleton`),
 * qualified `@Named("vlc")`. A [org.videolan.libvlc.MediaPlayer] on top of it is built lazily by the
 * activity only if a fallback actually happens.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    // --- Media3 / ExoPlayer building blocks ---------------------------------------------------

    /**
     * Buffering policy for live IPTV + VOD. Generous forward buffer so the decoder never underflows
     * at an HLS/TS segment boundary (the promt2 "chunk-gap lag" problem), while `startPlayback`/
     * `rebuffer` thresholds stay low so channels start fast. This is ExoPlayer's native equivalent of
     * the old `network-caching=8000 / live-caching=6000` values, done at the buffer layer.
     */
    @Provides
    @Singleton
    @UnstableApi
    fun provideLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,      // keep up to 15s buffered ahead
                /* maxBufferMs = */ 60_000,      // allow up to 60s on good networks
                /* bufferForPlaybackMs = */ 2_500,           // ~2.5s buffered before first frame
                /* bufferForPlaybackAfterRebufferMs = */ 5_000 // 5s after a stall before resuming
            )
            .setPrioritizeTimeOverSizeThresholds(true) // favour target duration over a byte cap for live
            .build()

    /**
     * Media HTTP data source backed by the shared OkHttp client. [OkHttpDataSource] lets the player
     * fetch playlists/segments through the same stack (and TLS/proxy config) as the rest of the app;
     * per-request headers are set at load time in the activity.
     */
    @Provides
    @Singleton
    @UnstableApi
    fun provideMediaDataSourceFactory(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): DataSource.Factory {
        val http = OkHttpDataSource.Factory(okHttpClient)
        // DefaultDataSource wraps the HTTP factory so local (file/content) URIs also work — e.g. the
        // audio-only hand-off or any temporary local cache.
        return DefaultDataSource.Factory(context, http)
    }

    // --- LibVLC fallback engine ---------------------------------------------------------------

    /**
     * The single shared LibVLC engine, used ONLY when ExoPlayer fails on a stream. `--avcodec-hw=any`
     * enables hardware decoding; `--audio-time-stretch` keeps pitch natural during speed changes.
     */
    @Provides
    @Singleton
    @Named("vlc")
    fun provideLibVlc(@ApplicationContext context: Context): LibVLC {
        val options = arrayListOf(
            "--avcodec-hw=any",
            "--avcodec-fast",
            "--avcodec-skiploopfilter=all",
            "--audio-time-stretch",
            "-vv"
        )
        return LibVLC(context, options)
    }
}
