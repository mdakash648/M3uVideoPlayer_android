package com.mdaksh.m3uvideoplayer.ui.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.data.local.dao.ChannelDao
import com.mdaksh.m3uvideoplayer.data.local.dao.ResumeDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ResumePointEntity
import com.mdaksh.m3uvideoplayer.databinding.ActivityPlayerBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.usecase.SavePlaylistResumePointUseCase
import com.mdaksh.m3uvideoplayer.util.DeviceUtils
import com.mdaksh.m3uvideoplayer.util.QualityUrlParser
import com.mdaksh.m3uvideoplayer.util.StreamUrl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.LoadControl
import com.mdaksh.m3uvideoplayer.ui.player.engine.EngineController
import com.mdaksh.m3uvideoplayer.ui.player.engine.EngineViews
import com.mdaksh.m3uvideoplayer.ui.player.engine.ExoPlaybackEngine
import com.mdaksh.m3uvideoplayer.ui.player.engine.PlaybackEngine
import com.mdaksh.m3uvideoplayer.ui.player.engine.VlcPlaybackEngine
import org.videolan.libvlc.LibVLC
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository

/**
 * Step 9 + Step 10 + Step 11 — the video player screen, styled after VLC's OSD.
 *
 * Step 9 (engine): Media3 migration — playback runs through [EngineController] (ExoPlayer primary,
 * LibVLC fallback) behind the [PlaybackEngine] contract; loading/error states, HW decode. ExoPlayer
 * buffers HLS/TS segments natively (forward buffer + rolling eviction + ABR), so the old hand-rolled
 * HLS proxy is gone.
 *
 * Step 10 (advanced UI): auto-hiding controller (10.1), swipe brightness/volume (10.2), double-tap
 * ±10s seek (10.3), lock (10.4), audio-only hand-off (10.5).
 *
 * Step 11 + prompt reworks:
 *  - **11.1:** Picture-in-Picture (auto on [onUserLeaveHint]).
 *  - **11.2:** playback-speed selector dialog (0.5x–2.0x).
 *  - **11.3:** OSD dialogs for audio-track and subtitle selection.
 *  - **Gesture rework:** right-side vertical swipe drives LibVLC's 0–200% software gain incrementally;
 *    left-side drives brightness, auto-disabled on Smart TVs (see [isTv]).
 *  - **D-pad / keyboard (TV remotes):** arrows = volume, left/right single/double = seek 10s/30s,
 *    center = play/pause (long-press = temporary 2x turbo).
 */
@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    /** Media3 migration — LibVLC is now the FALLBACK engine only; qualified `@Named("vlc")`. */
    @Inject
    @Named("vlc")
    lateinit var libVlc: LibVLC

    /** Media3 — ExoPlayer HTTP stack (shared OkHttp) and buffering policy, from PlayerModule. */
    @Inject
    lateinit var mediaDataSourceFactory: DataSource.Factory

    @Inject
    lateinit var loadControl: LoadControl

    /** promt4 — persistent resume-point store (save/read per-video playback position). */
    @Inject
    lateinit var resumeDao: ResumeDao

    /** Player Favorite — ChannelDao for toggling favourite status from the player. */
    @Inject
    lateinit var channelDao: ChannelDao

    /**
     * Floating Resume Button engine — writes the single active `playlist_resume_points` pointer.
     * Kept fully separate from [resumeDao] (see the Isolation Rule).
     */
    @Inject
    lateinit var savePlaylistResumePointUseCase: SavePlaylistResumePointUseCase

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    private var controlsTimeoutMs = UserPreferencesRepository.DEFAULT_CONTROLS_TIMEOUT_MS.toLong()
    private var swipeSensitivityPercent = UserPreferencesRepository.DEFAULT_SWIPE_SENSITIVITY_PERCENT

    /**
     * Media3 migration — the single engine-agnostic playback controller (ExoPlayer primary, LibVLC
     * fallback). All former `mediaPlayer.*` calls now go through this. Built in [onCreate], released
     * in [onDestroy]. The old hand-rolled HLS proxy is gone — ExoPlayer buffers segments natively.
     */
    private var engine: EngineController? = null

    /**
     * promt4 — activity-scoped background scope for resume-point *reads* (the initial resume lookup).
     * Cancelled in [onDestroy] so a pending read can't touch a torn-down activity.
     */
    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** promt4 — guards the one-time resume prompt so it only appears for the initially opened video. */
    private var resumeChecked = false

    /**
     * [FEATURE UPDATE] The Next/Previous queue — the exact filtered/sorted channel order from the
     * screen that launched playback (e.g. the Folder Content Screen's active [sort_by]), so
     * ⏭️/⏮️ always honors that order rather than any independent sequence.
     */
    private var playbackQueue: List<QueueItem> = emptyList()
    private var currentIndex: Int = 0

    private val currentQueueItem: QueueItem?
        get() = playbackQueue.getOrNull(currentIndex)

    /** promt2 — optional #EXTVLCOPT HTTP headers for protected streams (mirrors [currentQueueItem]). */
    private val httpReferrer: String?
        get() = currentQueueItem?.httpReferrer
    private val httpUserAgent: String?
        get() = currentQueueItem?.httpUserAgent

    // --- promt2: Virtual Quality Manager ------------------------------------------------------
    //
    // Items B/C/D. Some providers host a title's qualities as SEPARATE physical URLs that differ
    // only by a resolution token (…_1080p.mp4 / …_720p.mp4 / …_480p.mp4). Neither ExoPlayer nor
    // LibVLC can adapt across distinct URLs — only inside one master playlist — so this layer derives
    // the alternates with [QualityUrlParser], offers a manual picker, falls back down the ladder on
    // an early load failure, and emulates "Auto" by watching buffering/handshake timing.
    //
    // EVERYTHING here is inert for a single-variant stream ([qualityVariants].size <= 1): the button
    // stays GONE, no fallback fires, no auto-mode runs — behaviour is byte-for-byte the old path.

    /** The generated quality ladder for the current item's base URL (high→low), or one Original entry. */
    private var qualityVariants: List<QualityUrlParser.Variant> = emptyList()

    /** Index into [qualityVariants] currently being played. */
    private var currentQualityIndex: Int = 0
    private var attemptedQualityIndices = mutableSetOf<Int>()

    /** The item URL [qualityVariants] was generated from, so a replay (recovery/resume) never regenerates. */
    private var qualityBaseUrl: String? = null

    /** The inline `|Header=…` suffix of the item URL, re-appended to every swapped variant URL. */
    private var qualityHeaderSuffix: String = ""

    /** Item D — true while "Auto" is selected; the observer may then swap quality on network signals. */
    /* private var autoQualityEnabled: Boolean = true */

    /** True only for a URL that yielded a real ladder (more than the sole Original entry). */
    private val hasQualityOptions: Boolean get() = qualityVariants.size > 1

    /** Item D — SystemClock stamp when the current variant's load began, for the handshake timer. */
    /* private var qualityLoadStartedAt: Long = 0L */

    /** Item D — true until the current variant renders its first frame (onReady), gating the handshake check. */
    /* private var awaitingFirstFrame: Boolean = false */

    /** Item D — rolling count of buffering stalls seen since the last render, for the repeat-stall downgrade. */
    /* private var recentBufferingStalls: Int = 0 */

    /** Item D — SystemClock stamp of the last uninterrupted-playback upgrade, to rate-limit upgrades. */
    /* private var lastAutoUpgradeAt: Long = 0L */

    /** Item D — SystemClock stamp when the current variant last entered a smooth (non-buffering) window. */
    /* private var smoothPlaybackSince: Long = 0L */

    /** Item B — while true, an [onError]/[onReady] belongs to a quality/fallback swap, not the user's stream. */
    /* private var qualitySwapInFlight: Boolean = false */

    /** Item D — resets the stall counter after a clean window, so old stalls don't trigger a late downgrade. */
    /* private val clearBufferingStallsRunnable = Runnable { recentBufferingStalls = 0 } */

    /** Item D — fires if the current variant's handshake exceeds the budget before the first frame. */
    /* private val handshakeTimeoutRunnable = Runnable {
        if (awaitingFirstFrame && autoQualityEnabled && hasQualityOptions) {
            onSlowHandshake()
        }
    } */

    /** Step 9.4 / gesture rework — LibVLC software gain, 0..200 (%). */
    private var appVolume = VOLUME_NORMAL

    /**
     * [FEATURE UPDATE] Hybrid volume — 0-100% drives this stream's hardware volume directly;
     * 101-200% locks hardware at max and drives LibVLC's software gain instead.
     */
    private lateinit var audioManager: AudioManager
    private var systemMaxVolume = 0

    /** Step 11.2 — current playback rate; restored after a turbo hold. */
    private var playbackSpeed = 1.0f

    /** D-pad center long-press turbo (2x) state. */
    private var turboActive = false
    private var speedBeforeTurbo = 1.0f

    /** [FEATURE UPDATE] Left-zone touch long-press turbo (2x) state — separate flag from the
     * D-pad's [centerLongPressFired] so a touch hold and a remote OK hold never interfere with
     * each other; both funnel into the same [startTurbo]/[stopTurbo]. */
    private var leftTouchTurboActive = false

    /**
     * [FEATURE UPDATE] Manually-timed replacement for [GestureDetector]'s built-in onLongPress:
     * scheduled ourselves on ACTION_DOWN inside the left 30% zone and cancelled on ACTION_UP/
     * CANCEL/real-drag, so ordinary hand tremor while holding still can't silently cancel it the
     * way GestureDetector's own touch-slop-based cancellation does.
     */
    private val leftLongPressTurboRunnable = Runnable {
        if (!locked && !draggingBrightness && !draggingVolume && !draggingSeek) {
            handler.removeCallbacks(tapTimeoutRunnable)
            tapCount = 0
            pendingTapZone = null
            leftTouchTurboActive = true
            startTurbo()
        }
    }

    /**
     * promt1 — OK/center is fully Activity-managed (see [dispatchKeyEvent]/[handleCenterKey]), so
     * we detect its long-press ourselves rather than via [onKeyLongPress] (the focused button would
     * otherwise consume the key first). [centerLongPressFired] flips true once turbo kicks in, so
     * the matching key-up releases turbo instead of performing a click.
     */
    private var centerLongPressFired = false
    private val centerLongPressRunnable = Runnable {
        centerLongPressFired = true
        startTurbo()
    }

    /**
     * promt1 §1(1) — set when a *brand new* video is loaded via [play]; the next engine `onReady`
     * drops the D-pad focus onto Play/Pause. Distinguishes a fresh load from a plain resume (which
     * calls `play()` directly and must not steal focus back).
     */
    private var pendingFocusOnPlay = false

    /**
     * [BUGFIX] Some remotes/emulators deliver a genuine physical single click as TWO complete
     * ACTION_DOWN/ACTION_UP pairs fired milliseconds apart. Without a guard, the OK action would
     * run twice back-to-back — e.g. pause immediately followed by resume — so the click visibly
     * does nothing. This timestamp lets [handleCenterKey] ignore the duplicate ACTION_UP.
     */
    private var lastCenterKeyUpTime = 0L

    /** Player Favorite — tracks whether the currently playing channel is a favorite. */
    private var currentChannelFavorite = false

    /** Step 10.4 — while true, all touch + key input is swallowed except unlocking. */
    private var locked = false

    /**
     * [BUG FIX] Live TV Playback Safety — RULE A: true while a live channel has been intentionally
     * "paused" by the user, i.e. fully network-stopped rather than natively paused (see
     * [toggleLivePlayPause]). Reset to false at the top of every [play] call (including the
     * reconnect-on-resume itself), so a channel switch or the surface-recovery watchdog never
     * mistakes this for a fresh stream still being "paused".
     */
    private var liveStreamStoppedByUser = false

    /**
     * [BUG FIX] Live TV Playback Safety — SURFACE RESET FALLBACK: debounce timestamp so a flaky
     * `Vout(voutCount=0)` burst from a real network fluctuation can't fire more than one
     * reconnect within [LIVE_RECOVERY_COOLDOWN_MS].
     */
    private var lastLiveRecoveryAtMs = 0L

    /** True while the user is dragging the seekbar, so the ticker doesn't fight the thumb. */
    private var userSeeking = false

    /**
     * [FEATURE UPDATE] VLC-style horizontal drag-to-seek: true for the duration of a left/right
     * swipe. [seekDragStartPositionMs] anchors the preview to the position when the drag began;
     * [seekPreviewTargetMs] is the position that will be committed on release.
     */
    private var draggingSeek = false
    private var seekDragStartPositionMs = 0L
    private var seekPreviewTargetMs = 0L
    private var seekDragControlsWereVisible = false
    private var lastSeekTime = 0L
    private var seekControlsWereVisible = false
    private var draggingBrightness = false
    private var draggingVolume = false
    private var lastSwipeEndTime = 0L
    private var volumeAccumulator = 0f

    /** Gesture rework — brightness control is meaningless on a TV panel, so disable it there. */
    private val isTv: Boolean by lazy { DeviceUtils.isTv(this) }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector

    /** Pending single-vs-double D-pad seek direction (true = forward), resolved after a short window. */
    private var pendingDpadForward: Boolean? = null

    /**
     * [BUG FIX promt7] D-pad seek commit guard. LibVLC's `player.time` setter is asynchronous — for
     * a short window after a seek the getter keeps returning the OLD position. Without this guard the
     * [progressTicker] reads that stale value and writes it straight back onto the seekbar, making a
     * D-pad seek visibly "snap back" to where it started. While [dpadSeeking] is true the ticker's
     * write-back is suspended (STEP_1) and [dpadSeekTargetMs] holds the absolute committed target
     * captured from the D-pad navigation (STEP_2); tracking resumes only once `player.time` actually
     * lands near the target (STEP_3), or the [dpadSeekSettleTimeout] safety valve fires.
     */
    private var dpadSeeking = false
    private var dpadSeekTargetMs = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    /** promt4 §1 — persist the current playback position every 5s while playing. */
    private val saveResumeTicker = object : Runnable {
        override fun run() {
            saveResumePoint()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideIndicatorRunnable = Runnable { binding.gestureIndicator.visibility = View.GONE }
    private val hideLeftDoubleTapRunnable = Runnable {
        binding.leftDoubleTapLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.leftDoubleTapLayout.visibility = View.GONE }
            .start()
    }
    private val hideRightDoubleTapRunnable = Runnable {
        binding.rightDoubleTapLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.rightDoubleTapLayout.visibility = View.GONE }
            .start()
    }
    private val hideSeekTimeIndicatorRunnable = Runnable { binding.seekTimeIndicator.visibility = View.GONE }
    
    private val commitDpadBarSeekRunnable = Runnable {
        if (!userSeeking) return@Runnable
        userSeeking = false
        if (blockIfLiveSeek()) { scheduleHideControls(); return@Runnable }
        // [BUG FIX] STEP_2 & STEP_3 — commit the explicit seek target accumulated from D-pad interaction
        // on the SeekBar, and resume background updates.
        seekControlsWereVisible = true
        lastSeekTime = System.currentTimeMillis()
        engine?.seekTo(binding.seekBar.progress.toLong())
        scheduleHideControls()
    }

    private val dpadSeekRunnable = Runnable {
        pendingDpadForward?.let { seekSilently(if (it) SEEK_STEP_MS else -SEEK_STEP_MS) }
        pendingDpadForward = null
    }

    /**
     * [BUG FIX promt7] Safety valve: force-resume normal progress tracking if the native seek's
     * landing is never observed (e.g. an exact catch-up tick is missed or the seek fails), so the
     * ticker can't be stranded/suspended forever.
     */
    private val dpadSeekSettleTimeout = Runnable { dpadSeeking = false }

    /**
     * [BUG FIX] Zank Remote / External Volume Sync: Keeps track of the system hardware volume that we
     * intentionally set, so we can ignore our own changes. If the system volume changes externally
     * (e.g. via the Zank remote app), we apply that change to our internal 0-200% appVolume instead.
     */
    private var expectedSystemVolume = -1

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val newVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val oldVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    if (newVolume != -1 && oldVolume != -1 && newVolume != expectedSystemVolume) {
                        val direction = if (newVolume > oldVolume) 1 else if (newVolume < oldVolume) -1 else 0
                        if (direction != 0) {
                            val nextVolume = (appVolume + (direction * VOLUME_KEY_STEP)).coerceIn(0, VOLUME_BOOSTED)
                            if (nextVolume != appVolume) {
                                setVolume(nextVolume)
                            } else {
                                applyVolume()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        playbackQueue = parseQueue(intent)
        currentIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, 0).coerceIn(0, (playbackQueue.size - 1).coerceAtLeast(0))
        val startItem = currentQueueItem
        if (startItem == null || startItem.url.isBlank()) {
            showError(getString(R.string.player_error_no_url))
            return
        }

        binding.textTitle.text = startItem.title

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        systemMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        appVolume = currentSystemVolumePercent()

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }

        // Media3 migration — build the dual engine (ExoPlayer primary + LibVLC fallback) and bind it
        // to both render surfaces. The controller shows/hides the right surface per active engine.
        engine = EngineController(
            exo = ExoPlaybackEngine(this, mediaDataSourceFactory, loadControl),
            vlc = VlcPlaybackEngine(libVlc)
        ).also { controller ->
            controller.bind(
                EngineViews(binding.playerView, binding.videoLayout),
                engineListener
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferencesRepository.controlsTimeoutMsFlow.collect { timeoutMs ->
                    controlsTimeoutMs = timeoutMs.toLong()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferencesRepository.swipeSensitivityPercentFlow.collect { percent ->
                    swipeSensitivityPercent = percent
                }
            }
        }

        setupControls()
        setupGestures()
        updateQueueButtonsVisibility()

        // promt4 §2 — before the initial video begins, check for a saved resume point and, if there
        // is one, prompt Continue / Start Over. Playback starts (at 0 or the saved offset) only once
        // that choice is made. The DB read is off-main; showLoading() covers the brief gap.
        showLoading()
        startInitialPlayback(startItem.url)
        scheduleHideControls()
    }

    /**
     * promt4 §2/§3 — resolves the resume point for the first video, then either prompts the user or
     * plays straight through. A completed video (95%+ watched) or one under the 15s minimum never
     * prompts — it simply starts over.
     */
    private fun startInitialPlayback(url: String) {
        // promt2 — derive the quality ladder for this item before its first load, so the picker
        // button and any fallback are ready the moment playback starts.
        prepareQualityVariants()
        // promt5 — a forced-resume launch (from the floating resume FAB) skips the DB check and the
        // Continue/Start-Over prompt entirely: it plays straight from the caller-supplied offset.
        val forcedResumeMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, -1L)
        if (forcedResumeMs >= 0L) {
            resumeChecked = true
            play(url, forcedResumeMs)
            return
        }
        // Live TV (contentType LIVE / isLive hint) has no rewindable timeline — never look up a
        // resume point and never show the Continue / Start Over modal for it. Start at the live edge.
        if (currentQueueItem?.isLive != false) {
            resumeChecked = true
            play(url, 0L)
            return
        }
        val playlistId = currentQueueItem?.playlistId ?: 0L
        resumeScope.launch {
            val saved = runCatching { resumeDao.getResumePoint(playlistId, url) }.getOrNull()
            // Double-check: if the saved resume point itself was recorded as Live, or has no
            // duration, skip the prompt — the M3U parser may have mislabelled the channel.
            if (saved != null && (saved.isLive || saved.durationMs <= 0L)) {
                withContext(Dispatchers.Main) {
                    resumeChecked = true
                    if (!isFinishing && !isDestroyed) play(url, 0L)
                }
                return@launch
            }
            val resumeMs = saved?.takeIf { isResumable(it) }?.positionMs ?: 0L
            withContext(Dispatchers.Main) {
                resumeChecked = true
                if (isFinishing || isDestroyed) return@withContext
                if (resumeMs >= MIN_WATCHED_MS) {
                    showResumeDialog(resumeMs) { startMs -> play(url, startMs) }
                } else {
                    play(url, 0L)
                }
            }
        }
    }

    /**
     * promt4/promt5 — a resume point is offer-worthy only for VOD that's far enough in and not
     * finished. Live rows never prompt (they carry no timestamp; the FAB relaunches them directly).
     */
    private fun isResumable(point: ResumePointEntity): Boolean =
        !point.isLive && !point.completed && point.positionMs >= MIN_WATCHED_MS &&
            (point.durationMs <= 0 || point.positionMs < point.durationMs * COMPLETION_FRACTION)

    /**
     * promt4 §2/§3 — the dark, TV-friendly center modal. Default focus lands on [Continue] (the
     * positive button) so a single OK/click resumes; [Start Over] resets to 0. It's non-cancelable
     * so a stray Back press can't dismiss it without an explicit choice.
     */
    private fun showResumeDialog(resumeMs: Long, onChoice: (startMs: Long) -> Unit) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_resume_title)
            .setMessage(getString(R.string.player_resume_message, formatTime(resumeMs)))
            .setCancelable(false)
            .setNegativeButton(R.string.player_resume_start_over) { d, _ ->
                d.dismiss()
                onChoice(0L)
            }
            .setPositiveButton(R.string.player_resume_continue) { d, _ ->
                d.dismiss()
                onChoice(resumeMs)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }

    /** [FEATURE UPDATE] Rebuilds the parallel-array queue extras back into [QueueItem]s. */
    private fun parseQueue(intent: Intent): List<QueueItem> {
        val memoryQueue = queueMemoryHolder
        if (memoryQueue != null) {
            queueMemoryHolder = null // free memory immediately
            return memoryQueue
        }

        val urls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
        val referrers = intent.getStringArrayListExtra(EXTRA_QUEUE_REFERRERS).orEmpty()
        val userAgents = intent.getStringArrayListExtra(EXTRA_QUEUE_USER_AGENTS).orEmpty()
        val playlistIds = intent.getLongArrayExtra(EXTRA_QUEUE_PLAYLIST_IDS) ?: LongArray(0)
        val isLiveFlags = intent.getBooleanArrayExtra(EXTRA_QUEUE_IS_LIVE) ?: BooleanArray(0)
        return urls.indices.map { i ->
            QueueItem(
                url = urls[i],
                title = titles.getOrElse(i) { "" },
                httpReferrer = referrers.getOrElse(i) { "" }.takeIf { it.isNotBlank() },
                httpUserAgent = userAgents.getOrElse(i) { "" }.takeIf { it.isNotBlank() },
                playlistId = playlistIds.getOrElse(i) { 0L },
                // promt2 — default to Live (the common IPTV case) when the hint is absent.
                isLive = isLiveFlags.getOrElse(i) { true }
            )
        }
    }

    // --- Playback (Step 9) --------------------------------------------------------------------

    /**
     * @param startMs promt4 — when > 0, LibVLC begins decoding at this offset via `:start-time`
     *   (whole seconds), so "Continue" resumes cleanly at the saved position rather than seeking
     *   after the fact.
     * @param forceSoftwareDecode ITEM C EXTRA KNOB — when true this load disables hardware decoding
     *   entirely and pins VLC to software decode. Used by the live surface-recovery watchdog so a
     *   black-screen/audio-only caused by a flaky HW decoder is re-opened on the reliable SW path
     *   instead of dropping the video track again.
     */
    private fun play(url: String, startMs: Long = 0L, forceSoftwareDecode: Boolean = false) {
        showLoading()
        // [BUG FIX] Live TV Playback Safety — any fresh load (channel switch, manual live resume,
        // or the surface-recovery watchdog) clears the "user stopped a live channel" flag so it
        // never leaks into the next stream.
        liveStreamStoppedByUser = false
        // promt1 §1(1) — a brand-new load; the upcoming ready event snaps focus to Play/Pause.
        pendingFocusOnPlay = true

        // promt1 — a stream URL may carry its required headers inline as `url|Referer=…&User-Agent=…`
        // (Kodi/VLC pipe form). Split them off here so the player opens the bare URL and the server
        // gets the Referer/User-Agent it needs (otherwise it 403s). Inline headers win; fall back to
        // the channel's #EXTVLCOPT headers when the URL has none.
        val parsed = StreamUrl.parse(url)

        // Media3 migration — hand the stream to the engine controller. ExoPlayer buffers segments,
        // does adaptive bitrate and rolling eviction natively (no more HLS proxy); if it can't open
        // the stream it silently falls back to LibVLC. Headers + resume offset are passed through.
        engine?.load(
            url = parsed.url,
            referrer = parsed.referrer ?: httpReferrer,
            userAgent = parsed.userAgent ?: httpUserAgent,
            startMs = startMs,
            forceSoftwareDecode = forceSoftwareDecode
        )

        // promt2 Item D — (re)arm the handshake stopwatch for this variant load. No-op for a
        // single-variant stream or when Auto is off, so ordinary playback is untouched.
    }

    // --- promt2: Virtual Quality Manager (Items B/C/D) ----------------------------------------

    /**
     * Item A hook — derive the quality ladder for the CURRENT item's base URL and refresh the
     * picker button. Called once per item (initial open / Next / Previous), NOT on the internal
     * replays ([play] re-invocations for live-resume, surface recovery, or a quality swap) so a
     * replay never regenerates the ladder or resets the selected quality under us.
     *
     * The item URL may carry an inline `|Header=…` suffix; that is stripped for [QualityUrlParser]
     * (which needs a clean URL) and stashed in [qualityHeaderSuffix] to be re-appended to every
     * swapped variant so protected streams keep their headers.
     */
    private fun prepareQualityVariants() {
        val rawUrl = currentQueueItem?.url.orEmpty()
        if (rawUrl == qualityBaseUrl) return  // same item being replayed — keep existing ladder/state
        qualityBaseUrl = rawUrl

        val pipe = rawUrl.indexOf('|')
        val cleanUrl = if (pipe >= 0) rawUrl.substring(0, pipe) else rawUrl
        qualityHeaderSuffix = if (pipe >= 0) rawUrl.substring(pipe) else ""

        qualityVariants = QualityUrlParser.variants(cleanUrl)
        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)

        // A fresh item resets attempted indices.
        attemptedQualityIndices.clear()
        attemptedQualityIndices.add(currentQualityIndex)

        updateQualityButtonState()
    }

    /** Item C — show/hide the picker button; the menu only appears when a real ladder exists. */
    private fun updateQualityButtonState() {
        binding.btnQuality.visibility = if (hasQualityOptions) View.VISIBLE else View.GONE
    }

    /**
     * Item C — the manual quality menu. Entry 0 is Auto (Item D); the rest are the ladder rungs
     * (1080p / 720p / 480p) as separate physical URLs. Selecting a rung pins that quality and
     * disables Auto; selecting Auto re-enables the network-aware observer.
     */
    private fun showQualityDialog() {
        if (!hasQualityOptions) return
        handler.removeCallbacks(hideControlsRunnable)

        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checked = currentQualityIndex

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectQualityManually(which)
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }

    /** Item C — user pinned a specific rung: leave Auto and swap to it (seamless, position-preserving). */
    private fun selectQualityManually(index: Int) {
        val label = qualityVariants.getOrNull(index)?.label ?: return
        if (index == currentQualityIndex) {
            showIndicator(getString(R.string.player_osd_quality_selected, label))
            scheduleHideControls()
            return
        }
        switchQuality(index, getString(R.string.player_osd_quality_selected, label))
    }

    /** Item D — user chose Auto: re-enable the observer and re-baseline its timers from now. */
    private fun enableAutoQuality() {
    }

    /**
     * Items B/C/D — the shared swap primitive. Saves the current position, points at the new
     * variant's URL (re-appending [qualityHeaderSuffix]), and replays via [play] so the engine
     * re-opens at that offset. A live stream carries no rewindable timeline, so it restarts at the
     * live edge (startMs = 0).
     */
    private fun switchQuality(targetIndex: Int, announce: String?) {
        val variant = qualityVariants.getOrNull(targetIndex) ?: return
        val resumeMs = if (isLiveStream()) 0L else (engine?.positionMs ?: 0L)
        currentQualityIndex = targetIndex
        attemptedQualityIndices.add(targetIndex)
        announce?.let { showIndicator(it) }
        play(variant.url + qualityHeaderSuffix, startMs = resumeMs)
        scheduleHideControls()
    }

    /**
     * Item B — sequential fallback. A fatal error on the active variant drops one rung DOWN the
     * ladder (720p → 480p) and retries at the same position, until the lowest rung is reached — at
     * which point the error is real and surfaced. Returns true if a fallback was started.
     */
    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = (0 until qualityVariants.size).firstOrNull { it !in attemptedQualityIndices }
            ?: return false
        val from = qualityVariants[currentQualityIndex].label
        val lower = qualityVariants[next]
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }

    /**
     * Item D — arm the handshake stopwatch and reset the first-frame gate for a variant load. The
     * matching disarm is in the engine's onReady; if the budget elapses first, [onSlowHandshake]
     * downgrades before playback ever starts.
     */
    private fun armQualityLoadTimers() {
    }

    /**
     * Item D — the current variant's network handshake blew past [QUALITY_HANDSHAKE_BUDGET_MS]
     * before rendering a frame: step down one rung immediately so playback can start on a lighter
     * stream. Guarded by the caller ([handshakeTimeoutRunnable]) to Auto + multi-variant.
     */
    private fun onSlowHandshake() {
    }

    /**
     * Item D — a repeated-stall or slow-handshake signal after playback started: jump straight to
     * the LOWEST rung (weak network — stop laddering down one at a time).
     */
    private fun downgradeToLowestQuality() {
    }

    /**
     * Item D — high-bandwidth recovery. Called from the progress ticker: after a sustained smooth
     * (stall-free) window at a reduced quality, step UP one rung, rate-limited so a marginal link
     * can't oscillate up/down. Does nothing at the top rung, when Auto is off, or while a swap or
     * the first-frame handshake is still in flight.
     */
    private fun maybeUpgradeQuality() {
    }

    // --- promt4: persistent resume state ------------------------------------------------------

    /**
     * promt4 §1 — snapshot the active video's position to the DB.
     *
     * Rules:
     *  - Skips live/unknown-length streams (nothing to resume to).
     *  - Only stores once the user has watched at least [MIN_WATCHED_MS] (15s).
     *  - Once past [COMPLETION_FRACTION] (95%) the row is flagged `completed`, which suppresses the
     *    resume prompt on the next open.
     *
     * The DB write is always off the main thread (fire-and-forget on [resumeScope]) so it can never
     * stall the UI thread — the exit save in [onStop] relies on that scope outliving this activity.
     */
    private fun saveResumePoint() {
        val player = engine ?: return
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        val duration = player.durationMs

        // promt5 §2 — classify by the stream's real duration, NOT the channel's contentType. M3U
        // imports label every entry LIVE, so a movie in an M3U playlist would otherwise be treated
        // as live and never store its timestamp. A finite duration = VOD/movie; none = Live TV/IPTV.
        if (duration <= 0L) {
            // §2B — Live TV / IPTV: record only that this channel was last played in this playlist
            // (so the FAB can relaunch it), with NO timestamp — resume opens it at the live edge.
            upsertResumePoint(
                ResumePointEntity(
                    playlistId = item.playlistId,
                    url = url,
                    positionMs = 0L,
                    durationMs = 0L,
                    isLive = true,
                    completed = false,
                    updatedAt = now
                )
            )
            // Floating Resume Button engine — RULE A trigger; independent of the 15s MIN_WATCHED_MS
            // gate below (that gate only applies to the in-player Continue modal for VOD).
            savePlaylistResumePoint(item.playlistId, url, positionMs = 0L, durationMs = 0L, isLive = true)
            return
        }

        // §2A — VOD / movies / local files: store the exact millisecond position (e.g. Thor @ 55:00).
        val position = player.positionMs
        // Floating Resume Button engine — RULE A trigger (~3-5s into playback via SAVE_INTERVAL_MS),
        // independent of the 15s MIN_WATCHED_MS gate below (that gate is specific to the in-player
        // Continue modal on `resume_points`, per the Isolation Rule).
        savePlaylistResumePoint(item.playlistId, url, position, duration, isLive = false)
        if (position < MIN_WATCHED_MS) return      // under the 15s threshold, no session stored
        val completed = position >= duration * COMPLETION_FRACTION
        upsertResumePoint(
            ResumePointEntity(
                playlistId = item.playlistId,
                url = url,
                positionMs = position,
                durationMs = duration,
                isLive = false,
                completed = completed,
                updatedAt = now
            )
        )
    }

    private fun upsertResumePoint(point: ResumePointEntity) {
        // Writes go on a process-lifetime scope (not [resumeScope]) so the exit save launched from
        // onStop() still completes even after onDestroy() tears this activity down. Always async —
        // never blocks the UI thread, so it can't cause an ANR.
        val dao = resumeDao
        resumeWriteScope.launch { runCatching { dao.upsert(point) } }
    }

    /**
     * Floating Resume Button engine — RULE A: fire-and-forget write of the single active
     * `playlist_resume_points` pointer for [playlistId]. Always async, same process-lifetime scope
     * as [upsertResumePoint], so it survives the exit save launched from onStop()/onDestroy().
     */
    private fun savePlaylistResumePoint(
        playlistId: Long,
        url: String,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    ) {
        val useCase = savePlaylistResumePointUseCase
        resumeWriteScope.launch {
            runCatching { useCase(playlistId, url, positionMs, durationMs, isLive) }
        }
    }

    /** promt4 §1 — flag the current VOD fully watched so re-opening it starts over silently. */
    private fun markCurrentCompleted() {
        val player = engine ?: return
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        val duration = player.durationMs
        if (duration <= 0L) return                 // live (no duration) never "completes"
        upsertResumePoint(
            ResumePointEntity(
                playlistId = item.playlistId,
                url = url,
                positionMs = duration,
                durationMs = duration,
                isLive = false,
                completed = true,
                updatedAt = System.currentTimeMillis()
            )
        )
        // Floating Resume Button engine — RULE B / LOGIC_1: positionMs >= durationMs marks it
        // completed there too, so the Playlist-list resume icon hides for this finished VOD.
        savePlaylistResumePoint(item.playlistId, url, duration, duration, isLive = false)
    }

    /**
     * Media3 migration — engine-agnostic playback callbacks, replacing the old `MediaPlayer.Event`
     * switch. [EngineController] forwards these from whichever engine (ExoPlayer/VLC) is live, and
     * turns an ExoPlayer failure into the automatic VLC fallback before it ever reaches [onError].
     */
    private val engineListener = object : PlaybackEngine.Listener {

        override fun onBuffering() {
            showLoading()
        }

        override fun onReady() {
            showVideo()
            // Re-apply user preferences the fresh load reset.
            applyVolume()
            engine?.speed = if (turboActive) TURBO_RATE else playbackSpeed
            updatePlayPauseIcon()
            handler.removeCallbacks(progressTicker)
            handler.post(progressTicker)
            // promt4 §1 — (re)arm the 5s resume-point autosave for the active video.
            handler.removeCallbacks(saveResumeTicker)
            handler.postDelayed(saveResumeTicker, SAVE_INTERVAL_MS)
            // [FEATURE UPDATE] (re)arm auto-hide now that playback has genuinely started.
            // [FEATURE UPDATE] (re)arm auto-hide now that playback has genuinely started.
            val isRecentSeek = System.currentTimeMillis() - lastSeekTime < 2000L
            if (isRecentSeek && !seekControlsWereVisible) {
                handler.removeCallbacks(hideControlsRunnable)
                setControlsVisible(false)
            } else {
                scheduleHideControls()
            }
            // promt1 §1(1) — brand-new video: force default D-pad focus onto Play/Pause.
            if (pendingFocusOnPlay) {
                pendingFocusOnPlay = false
                focusPlayPause()
            }
        }

        override fun onPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) {
                val isRecentSeek = System.currentTimeMillis() - lastSeekTime < 2000L
                if (isRecentSeek && !seekControlsWereVisible) {
                    handler.removeCallbacks(hideControlsRunnable)
                    setControlsVisible(false)
                } else {
                    scheduleHideControls()
                }
            } else {
                // [FEATURE UPDATE] Paused = no auto-hide; keep the panel up until the user resumes.
                handler.removeCallbacks(hideControlsRunnable)
                setControlsVisible(true)
                // promt4 — pausing is a natural "leaving off" point; snapshot it immediately.
                handler.removeCallbacks(saveResumeTicker)
                saveResumePoint()
            }
        }

        override fun onVideoSurfaceLost() {
            // [BUG FIX] Live TV Playback Safety — the decoder dropped its video track/render surface
            // (audio-only / black screen) while a live channel should be playing. Force a reconnect.
            if (isLiveStream() && !liveStreamStoppedByUser) attemptLiveSurfaceRecovery()
            else showVideo()
        }

        override fun onError() {
            // promt2 Item B — sequential fallback: before surfacing the error, try stepping DOWN the
            // quality ladder (a dead 1080p mirror may still have a live 720p/480p). Only when the
            // lowest rung also fails is the error real and shown. Inert for single-link streams.
            if (tryQualityFallback()) return
            showError(getString(R.string.player_error_network))
        }

        override fun onEnded() {
            // promt4 §1 — a fully played video is marked completed so it won't prompt to resume.
            handler.removeCallbacks(saveResumeTicker)
            markCurrentCompleted()
            showError(getString(R.string.player_error_ended))
        }
    }

    // --- Step 10.1 / 11: controller wiring ----------------------------------------------------

    private fun setupControls() = with(binding) {
        btnBack.setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnFullscreen.setOnClickListener { toggleAspectRatio() }

        // Step 11.
        btnSpeed.setOnClickListener { showSpeedDialog() }
        // promt2 Item C — Virtual Quality Manager picker (button itself stays GONE for single-link).
        btnQuality.setOnClickListener { showQualityDialog() }
        btnAudioTrack.setOnClickListener { showTrackDialog(audio = true) }
        btnSubtitles.setOnClickListener { showTrackDialog(audio = false) }
        btnPip.setOnClickListener { enterPipMode() }

        // Player Favorite — toggle the channel's favorite flag and update the heart icon.
        btnFavorite.setOnClickListener { toggleFavorite() }
        loadFavoriteState()
        if (!supportsPip()) btnPip.visibility = View.GONE

        // [FEATURE UPDATE] Screen rotate + filter-aware, looping Next/Previous.
        btnRotate.setOnClickListener { toggleScreenOrientation() }
        btnNext.setOnClickListener { playNext() }
        btnPrevious.setOnClickListener { playPrevious() }
        binding.btnRewind10.setOnClickListener {
            if (blockIfLiveSeek()) return@setOnClickListener
            engine?.let { player ->
                val pos = (player.positionMs - 10000).coerceAtLeast(0)
                player.seekTo(pos)
                handler.removeCallbacks(hideControlsRunnable)
                scheduleHideControls()
            }
        }
        binding.btnForward10.setOnClickListener {
            if (blockIfLiveSeek()) return@setOnClickListener
            engine?.let { player ->
                val duration = player.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                val pos = (player.positionMs + 10000).coerceAtMost(duration)
                player.seekTo(pos)
                handler.removeCallbacks(hideControlsRunnable)
                scheduleHideControls()
            }
        }

        // promt1 rework — the on-screen lock button is pointless with a D-pad remote, so hide it on TV.
        if (isTv) {
            btnLock.visibility = View.GONE
        } else {
            btnLock.setOnClickListener { toggleLock() }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    textElapsed.text = formatTime(progress.toLong())
                    // STEP_1: Suspend timeline update when user manually drags or D-pad seeks
                    if (!userSeeking) {
                        userSeeking = true
                        handler.removeCallbacks(hideControlsRunnable)
                    }
                    // Fallback commit for D-pad if key listener misses UP event
                    handler.removeCallbacks(commitDpadBarSeekRunnable)
                    handler.postDelayed(commitDpadBarSeekRunnable, 1000)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                // RULE B — a live stream's seekbar has no real timeline (updateProgress() never
                // populates it either); refuse to even start tracking a drag on it.
                if (isLiveStream()) return
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
                handler.removeCallbacks(commitDpadBarSeekRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                handler.removeCallbacks(commitDpadBarSeekRunnable)
                commitDpadBarSeekRunnable.run()
            }
        })

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // ITEM A — ABSOLUTE LOCK: a live stream's seekbar must never scrub. Fully CONSUME the
                // key (return true) so the thumb can't move, and surface the toast once on key-down
                // instead of routing anything toward player.time.
                if (isLiveStream()) {
                    if (event.action == KeyEvent.ACTION_DOWN) blockIfLiveSeek()
                    return@setOnKeyListener true
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (!userSeeking) {
                        userSeeking = true
                        handler.removeCallbacks(hideControlsRunnable)
                    }
                    handler.removeCallbacks(commitDpadBarSeekRunnable)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    // STEP_3: Commit seek quickly on D-pad key release
                    handler.removeCallbacks(commitDpadBarSeekRunnable)
                    handler.postDelayed(commitDpadBarSeekRunnable, 250) // short debounce
                }
                false
            } else {
                false
            }
        }
    }

    // --- Player Favorite -----------------------------------------------------------------------

    /**
     * Reads the current channel's favorite state from the database and updates the heart icon.
     * Called once in [setupControls] for the initial channel, and again whenever the user
     * navigates to a different channel via Next/Previous.
     */
    private fun loadFavoriteState() {
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val isFav = channelDao.isFavoriteByUrl(item.playlistId, url) ?: false
            withContext(Dispatchers.Main) {
                currentChannelFavorite = isFav
                updateFavoriteIcon()
            }
        }
    }

    /**
     * Toggles the current channel's [isFavorite] flag in the database and updates the icon.
     * Works for any content type — Live TV, Movie, or Series.
     */
    private fun toggleFavorite() {
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        val newState = !currentChannelFavorite
        currentChannelFavorite = newState
        updateFavoriteIcon()
        val message = if (newState) R.string.player_added_to_favorites else R.string.player_removed_from_favorites
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            channelDao.setFavoriteByUrl(item.playlistId, url, newState)
        }
    }

    /** Swaps the heart icon between filled (favorite) and outline (not favorite). */
    private fun updateFavoriteIcon() {
        binding.btnFavorite.setImageResource(
            if (currentChannelFavorite) R.drawable.ic_favorite_filled
            else R.drawable.ic_favorite_border
        )
    }

    private fun togglePlayPause() {
        val player = engine ?: return
        // [BUG FIX] Live TV Playback Safety — RULE A: on the LibVLC fallback a native pause() left the
        // HLS/TS decoder holding stale frame references (permanent black screen on resume). Live
        // channels keep the separate stop-and-reconnect flow; see [toggleLivePlayPause]. ExoPlayer
        // doesn't have that defect, but the reconnect-from-live-edge behaviour is still what users
        // want for a paused live channel, so we keep the same flow for both engines.
        if (isLiveStream()) {
            toggleLivePlayPause()
            return
        }
        Log.d(DPAD_DEBUG_TAG, "togglePlayPause() called, isPlaying=${player.isPlaying} -> will ${if (player.isPlaying) "pause" else "play"}")
        if (player.isPlaying) player.pause() else player.play()
        updatePlayPauseIcon()
        scheduleHideControls()
    }

    /**
     * [BUG FIX] Live TV Playback Safety — RULE A: replaces the native pause/resume pair for Live TV.
     *  - "Pause" ➔ [MediaPlayer.stop] tears the network connection and decoder down completely
     *    (no stale frame references left to crash on), and a loading spinner covers the frozen
     *    surface via [showLoading] until the user resumes.
     *  - "Resume" ➔ a brand-new [play] call re-opens the M3U8 URL from scratch and starts at the
     *    real-time live edge (no `:start-time`, exactly like a fresh channel switch).
     */
    private fun toggleLivePlayPause() {
        val player = engine ?: return
        val item = currentQueueItem ?: return
        if (liveStreamStoppedByUser) {
            play(item.url)
        } else {
            liveStreamStoppedByUser = true
            // Media3 migration — the engine has no hard "stop"; pausing releases the decoder pressure
            // and the resume path re-loads from the live edge via [play], matching the old behaviour.
            player.pause()
            showLoading()
        }
        updatePlayPauseIcon()
        scheduleHideControls()
    }

    /**
     * [BUG FIX] Live TV Playback Safety — SURFACE RESET FALLBACK: background auto-recovery, called
     * off a `Vout(voutCount=0)` signal or a video-track `ESDeleted` (audio-only) while a live
     * channel is supposed to be playing (see [onPlayerEvent]). Debounced by [LIVE_RECOVERY_COOLDOWN_MS]
     * so a burst of flaky events during one real network blip can't spam reconnects, and skipped
     * entirely once the user has intentionally stopped the channel via [toggleLivePlayPause].
     *
     * ITEM C — a plain media reload on the same, now-dead IVLCVout often won't rebuild the render
     * pipeline (video stays black while audio runs). So we tear the hardware surface down and
     * rebuild it before reconnecting:
     *   1. stop the player and detach the VLC video surface view,
     *   2. re-attach a clean surface layout,
     *   3. re-load the stream URL forcing SOFTWARE decoding (frame-drop fallback per the EXTRA KNOB).
     */
    private fun attemptLiveSurfaceRecovery() {
        val item = currentQueueItem ?: return
        val now = System.currentTimeMillis()
        if (now - lastLiveRecoveryAtMs < LIVE_RECOVERY_COOLDOWN_MS) return
        lastLiveRecoveryAtMs = now

        // Media3 migration — a fresh [play] rebuilds the whole engine + surface from the live edge,
        // which is the robust recovery for both engines (ExoPlayer usually self-heals; the VLC
        // fallback engine's load() detaches/re-attaches its surface internally). Software decode is
        // forced so a flaky HW decoder that dropped the video track isn't re-selected.
        showLoading()
        play(item.url, forceSoftwareDecode = true)
    }

    private fun updatePlayPauseIcon() {
        val playing = engine?.isPlaying == true
        val resId = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(resId)
    }

    /**
     * [BUG FIX] Live TV Playback Safety — a stream is "live" when LibVLC reports no known length,
     * the same real-duration test already used app-wide (see [saveResumePoint]/[updateProgress])
     * rather than the M3U-declared `contentType`, since providers mislabel entries often.
     */
    private fun isLiveStream(): Boolean = engine?.isLive ?: true

    /**
     * [BUG FIX] Live TV Playback Safety — RULE B: silently blocks any seek entry point for a live
     * channel and surfaces a short toast once, instead of letting `player.time`/`seekTo` scrub a
     * stream that has no rewindable buffer. Returns true when the caller should abort.
     */
    private fun blockIfLiveSeek(): Boolean {
        if (!isLiveStream()) return false
        Toast.makeText(this, R.string.player_live_seek_disabled, Toast.LENGTH_SHORT).show()
        return true
    }

    /** Step 10.1 fullscreen toggle — cycles the engine's aspect handling (best-fit ↔ fill). */
    private var fillMode = false
    private fun toggleAspectRatio() {
        val player = engine ?: return
        fillMode = !fillMode
        player.setFillMode(fillMode)
        binding.btnFullscreen.setImageResource(
            if (fillMode) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        scheduleHideControls()
    }

    // --- [FEATURE UPDATE] Filter-aware, looping Next/Previous ---------------------------------

    /** CASE_1/CASE_2 — advances to the next item in the queue that launched this screen. */
    private fun playNext() {
        if (playbackQueue.size <= 1) return
        // INFINITE_LOOP_PLAYBACK_MECHANISM — wraps past the last item back to the first.
        playAt((currentIndex + 1) % playbackQueue.size)
    }

    /** CASE_1/CASE_2 — steps back to the previous item in the queue that launched this screen. */
    private fun playPrevious() {
        if (playbackQueue.size <= 1) return
        // INFINITE_LOOP_PLAYBACK_MECHANISM — wraps past the first item back to the last.
        playAt((currentIndex - 1 + playbackQueue.size) % playbackQueue.size)
    }

    private fun playAt(index: Int) {
        val item = playbackQueue.getOrNull(index) ?: return
        if (item.url.isBlank()) {
            Toast.makeText(this, R.string.player_error_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        // promt4 — save where we left off on the outgoing item before the index changes under us.
        handler.removeCallbacks(saveResumeTicker)
        saveResumePoint()
        currentIndex = index
        binding.textTitle.text = item.title
        binding.seekBar.progress = 0
        binding.textElapsed.text = formatTime(0L)
        binding.textDuration.text = formatTime(0L)
        // promt2 — regenerate the quality ladder for the newly selected item before it loads.
        prepareQualityVariants()
        // Player Favorite — refresh the heart icon for the newly selected channel.
        loadFavoriteState()
        play(item.url)
        scheduleHideControls()
    }

    /** Only meaningful with more than one item to navigate between. */
    private fun updateQueueButtonsVisibility() {
        val visible = if (playbackQueue.size > 1) View.VISIBLE else View.GONE
        binding.btnNext.visibility = visible
        binding.btnPrevious.visibility = visible
    }

    // --- [FEATURE UPDATE] Manual screen rotation -----------------------------------------------

    /** Forces the activity between portrait and landscape, independent of the device's auto-rotate. */
    private fun toggleScreenOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        scheduleHideControls()
    }

    private fun updateProgress() {
        val player = engine ?: return
        // promt2 Item D — high-bandwidth recovery probe on the 500ms tick (checked before the live
        // early-return so it also applies to live multi-quality streams). No-op unless Auto is on,
        // the stream has a real ladder, and we're below the top rung.
        maybeUpgradeQuality()
        val length = player.durationMs
        if (length <= 0) return // live stream with no known duration
        binding.textDuration.text = formatTime(length)

        // [BUG FIX promt7] STEP_1/STEP_3 — while a D-pad seek is committing, keep the bar pinned to
        // the captured target and refuse to write the stale (pre-seek) position back onto it.
        // Resume normal tracking only once the seek has actually landed near the target.
        if (dpadSeeking) {
            if (kotlin.math.abs(player.positionMs - dpadSeekTargetMs) <= DPAD_SEEK_SETTLE_TOLERANCE_MS) {
                dpadSeeking = false
                handler.removeCallbacks(dpadSeekSettleTimeout)
            } else {
                return
            }
        }

        if (!userSeeking) {
            binding.seekBar.max = length.toInt()
            binding.seekBar.progress = player.positionMs.toInt()
            binding.textElapsed.text = formatTime(player.positionMs)
        }
    }

    /**
     * [YOUTUBE-STYLE] Smooth fade in/out for the control panel.
     * - Show: fade in 200 ms (instant feel, never laggy)
     * - Hide: fade out 300 ms (gradual, feels natural like YouTube)
     * The overlay stays VISIBLE (alpha=0) during the hide animation so touch/d-pad events
     * still work until it's fully gone; GONE is only set once the fade is complete.
     */
    private var _controlsVisible = false

    private fun setControlsVisible(visible: Boolean) {
        if (visible == _controlsVisible) return
        _controlsVisible = visible
        val overlay = binding.controlsOverlay
        val lockBtn = binding.btnLock

        if (visible) {
            // --- SHOW: snap visible then fade in ---
            overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(null)
                .start()
            if (!locked && !isTv && !isInPip()) {
                lockBtn.visibility = View.VISIBLE
                lockBtn.animate().alpha(1f).setDuration(200).start()
            }
            // promt1 §1(2) — drop focus onto Play/Pause when panel appears on TV.
            if (isTv && !isInPip()) focusPlayPause()
        } else {
            // --- HIDE: fade out then set GONE ---
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    if (!_controlsVisible) overlay.visibility = View.GONE
                }
                .start()
            if (!locked && !isTv && !isInPip()) {
                lockBtn.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { if (!_controlsVisible) lockBtn.visibility = View.GONE }
                    .start()
            }
        }
    }

    /**
     * promt1 §1 — force the D-pad focus pointer onto the Play/Pause button. Posted so it runs after
     * the overlay has been laid out (it may have just flipped from GONE), otherwise requestFocus()
     * can no-op on a not-yet-measured view.
     */
    private fun focusPlayPause() {
        if (!isTv) return
        binding.btnPlayPause.post {
            if (binding.controlsOverlay.visibility == View.VISIBLE) {
                binding.btnPlayPause.requestFocus()
            }
        }
    }

    /** promt1 — CASE A (hidden) vs CASE B (visible) hinges entirely on this live panel state. */
    private val controlsShowing: Boolean
        get() = _controlsVisible

    private fun isInPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    /**
     * [FEATURE UPDATE] Auto-hide is only ever armed while the video is genuinely playing — paused
     * playback (including the D-pad center-button preview) keeps the panel up indefinitely.
     */
    private fun scheduleHideControls() {
        if (locked) return
        setControlsVisible(true)
        handler.removeCallbacks(hideControlsRunnable)
        if (engine?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, controlsTimeoutMs)
        }
    }

    private fun toggleControls() {
        if (_controlsVisible) {
            handler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
        } else {
            scheduleHideControls()
        }
    }

    // --- Step 9.4 / gesture rework / [FEATURE UPDATE]: hybrid 0–200% volume -------------------

    /**
     * [FEATURE UPDATE] Hybrid volume entry point — clamps to 0..200 and re-applies the
     * split (RANGE_1 hardware / RANGE_2 software boost) via [applyVolume].
     */
    private fun setVolume(percent: Int) {
        appVolume = percent.coerceIn(0, VOLUME_BOOSTED)
        applyVolume()
    }

    /**
     * [FEATURE UPDATE] Hybrid volume management.
     *  - RANGE_1 (0-100%): scales the device's actual STREAM_MUSIC hardware volume; the LibVLC
     *    core is pinned at 100% (its own normal, unboosted level) so it never attenuates on top
     *    of the hardware scaling.
     *  - RANGE_2 (101-200%): hardware volume is locked at its max, and LibVLC's internal
     *    audio-track gain is driven from 100% up to a 200% software boost instead.
     */
    private fun applyVolume() {
        if (appVolume <= VOLUME_NORMAL) {
            if (systemMaxVolume > 0) {
                val target = ((appVolume / 100f) * systemMaxVolume)
                    .roundToInt()
                    .coerceIn(0, systemMaxVolume)
                expectedSystemVolume = target
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            // 0-100% is driven by hardware volume; keep the engine at its unboosted normal level.
            engine?.setSoftwareVolume(VOLUME_NORMAL)
        } else {
            if (systemMaxVolume > 0) {
                expectedSystemVolume = systemMaxVolume
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemMaxVolume, 0)
            }
            // 101-200% locks hardware at max and drives the engine's software gain boost.
            engine?.setSoftwareVolume(appVolume)
        }
    }

    /** [FEATURE UPDATE] Seeds the slider's starting position from the device's current hardware volume. */
    private fun currentSystemVolumePercent(): Int {
        if (systemMaxVolume <= 0) return VOLUME_NORMAL
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((current.toFloat() / systemMaxVolume) * 100)
            .roundToInt()
            .coerceIn(0, 100)
    }

    // --- Step 11.2: playback speed ------------------------------------------------------------

    private fun showSpeedDialog() {
        handler.removeCallbacks(hideControlsRunnable)
        val labels = SPEEDS.map {
            if (it == 1.0f) getString(R.string.player_speed_normal) else "${it}x"
        }.toTypedArray()
        val checked = SPEEDS.indexOfFirst { abs(it - playbackSpeed) < 0.001f }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_speed)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setPlaybackSpeed(SPEEDS[which])
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        if (!turboActive) engine?.speed = speed
        showIndicator(getString(R.string.player_speed_current, "${speed}x"))
    }

    // --- Step 11.3: audio / subtitle track OSD ------------------------------------------------

    private fun showTrackDialog(audio: Boolean) {
        val player = engine ?: return
        handler.removeCallbacks(hideControlsRunnable)
        val tracks = if (audio) player.audioTracks() else player.subtitleTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(
                this,
                if (audio) R.string.player_no_audio_tracks else R.string.player_no_subtitle_tracks,
                Toast.LENGTH_SHORT
            ).show()
            scheduleHideControls()
            return
        }

        val names = if (audio) {
            tracks.map { it.label }.toTypedArray()
        } else {
            (listOf(getString(R.string.player_track_none)) + tracks.map { it.label }).toTypedArray()
        }
        
        val checked = if (audio) {
            tracks.indexOfFirst { it.selected }
        } else {
            val idx = tracks.indexOfFirst { it.selected }
            if (idx == -1) 0 else idx + 1 // 0 is "None"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) R.string.player_audio_track else R.string.player_subtitle_track)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                if (audio) {
                    val id = tracks[which].id
                    player.selectAudioTrack(id)
                    showIndicator(getString(R.string.player_osd_audio_selected, tracks[which].label))
                } else {
                    if (which == 0) {
                        player.clearSubtitleTrack()
                        showIndicator(getString(R.string.player_osd_subtitle_selected, getString(R.string.player_track_none)))
                    } else {
                        val id = tracks[which - 1].id
                        player.selectSubtitleTrack(id)
                        showIndicator(getString(R.string.player_osd_subtitle_selected, tracks[which - 1].label))
                    }
                }
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }

    // --- Step 11.1: Picture-in-Picture --------------------------------------------------------

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPipMode() {
        if (!supportsPip() || locked) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            @Suppress("DEPRECATION")
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-PiP when the user navigates away mid-playback (Step 11.1).
        if (engine?.isPlaying == true && !locked) enterPipMode()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            handler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
            binding.btnLock.visibility = View.GONE
        } else {
            // [FEATURE UPDATE] Don't resurrect the lock button if Lock Mode is still active —
            // it stays hidden until a swipe-down unlock, same as the rest of the overlay.
            binding.btnLock.visibility = if (locked) View.GONE else View.VISIBLE
        }
    }

    // --- Step 10.4: lock ----------------------------------------------------------------------

    private fun toggleLock() {
        locked = !locked
        binding.btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        binding.btnLock.contentDescription =
            getString(if (locked) R.string.player_unlock else R.string.player_lock)
        // [FEATURE UPDATE] True immersive lock — hide the lock button itself too, for a
        // completely clutter-free "fresh screen". A swipe-down gesture is now the only way
        // to unlock (see handleLockedTouch).
        binding.btnLock.visibility = if (locked) View.GONE else View.VISIBLE
        if (locked) {
            handler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
        } else {
            scheduleHideControls()
        }
        showIndicator(getString(if (locked) R.string.player_locked else R.string.player_unlocked))
    }

    // --- Step 10.2/10.3: touch gestures -------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, GestureListener())
        binding.playerRoot.setOnTouchListener { _, event ->
            if (locked) {
                handleLockedTouch(event)
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownX = event.x
                    tapDownY = event.y
                    tapDownTime = event.eventTime
                    // [FEATURE UPDATE] Arm the left-zone 2x turbo hold. Cancelled on UP/CANCEL
                    // below, or from onScroll the moment a real drag is recognized.
                    val width = binding.playerRoot.width
                    if (!locked && width > 0 && event.x < width * SEEK_ZONE_FRACTION) {
                        handler.removeCallbacks(leftLongPressTurboRunnable)
                        handler.postDelayed(
                            leftLongPressTurboRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                }

                MotionEvent.ACTION_UP -> {
                    // [FEATURE UPDATE] Left-zone long-press turbo — release restores normal speed.
                    // Guarded first so the long-press-then-lift never also gets read as a tap.
                    handler.removeCallbacks(leftLongPressTurboRunnable)
                    if (leftTouchTurboActive) {
                        leftTouchTurboActive = false
                        stopTurbo()
                    }
                    // A "tap" is a touch-up close to where it went down, quickly, and not the
                    // tail end of a drag (seek/brightness/volume) that was already handled live.
                    val wasDragging = draggingSeek || draggingBrightness || draggingVolume
                    val isTap = !wasDragging &&
                        abs(event.x - tapDownX) < TAP_SLOP_PX &&
                        abs(event.y - tapDownY) < TAP_SLOP_PX &&
                        (event.eventTime - tapDownTime) < TAP_MAX_DURATION_MS
                    commitSeekDrag()
                    if (wasDragging) {
                        lastSwipeEndTime = System.currentTimeMillis()
                        draggingBrightness = false
                        draggingVolume = false
                    }
                    if (isTap) {
                        // Skip if inside 1.5s (1500ms) debounce window
                        if (System.currentTimeMillis() - lastSwipeEndTime > 1500L) {
                            handleTap(event.x)
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(leftLongPressTurboRunnable)
                    if (leftTouchTurboActive) {
                        leftTouchTurboActive = false
                        stopTurbo()
                    }
                    val wasDragging = draggingSeek || draggingBrightness || draggingVolume
                    commitSeekDrag()
                    if (wasDragging) {
                        lastSwipeEndTime = System.currentTimeMillis()
                        draggingBrightness = false
                        draggingVolume = false
                    }
                }
            }
            true
        }
    }

    /** Raw touch-down anchor used to distinguish a genuine tap from a drag (see [setupGestures]). */
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L

    /** [FEATURE UPDATE] Left/right 30% edge zones vs. the neutral middle used for tap gestures. */
    private enum class TapZone { LEFT, MIDDLE, RIGHT }

    /**
     * [FEATURE UPDATE] Verification window variables for single-tap vs. double-tap handling.
     * Incorporates an 800ms window where a second click turns the gesture into a double-click.
     */
    private var pendingTapZone: TapZone? = null
    private var tapCount = 0
    private var controlsWereVisible = false
    private val tapTimeoutRunnable = Runnable {
        val zone = pendingTapZone
        if (zone != null) {
            if (tapCount == 1 && controlsWereVisible) {
                // If they were visible when we tapped, and we only tapped once, hide them now
                setControlsVisible(false)
            }
        }
        tapCount = 0
        pendingTapZone = null
    }

    /**
     * [FEATURE UPDATE] Dispatches a raw tap ([x] in view coordinates) to the left/right
     * accumulative-seek zones, or treats a middle tap as play/pause.
     * Incorporates a 0.8s (800ms) double-tap/click verification window:
     *  - Single Tap (only one tap detected, after 800ms timeout): Toggles the controls (shows/hides controlsOverlay).
     *    To prevent lag, if controls are hidden, they are shown INSTANTLY on the first tap.
     *  - Double Tap (two or more taps within 800ms):
     *      - Left/Right zones: Skips +/-10s per tap.
     *      - Middle zone: Toggles play/pause instantly.
     */
    private fun handleTap(x: Float) {
        val width = binding.playerRoot.width
        val zone = when {
            x < width * SEEK_ZONE_FRACTION -> TapZone.LEFT
            x > width * (1f - SEEK_ZONE_FRACTION) -> TapZone.RIGHT
            else -> TapZone.MIDDLE
        }

        if (zone != pendingTapZone) {
            // Cancel previous pending single tap and execute it immediately to avoid delays
            handler.removeCallbacks(tapTimeoutRunnable)
            if (pendingTapZone != null && tapCount == 1 && controlsWereVisible) {
                setControlsVisible(false)
            }
            tapCount = 1
            pendingTapZone = zone
            controlsWereVisible = _controlsVisible

            if (!_controlsVisible) {
                // Instantly show controls if they were hidden — no 800ms delay to make it feel responsive
                scheduleHideControls()
            }
            handler.postDelayed(tapTimeoutRunnable, TOUCH_DOUBLE_TAP_TIMEOUT_MS)
        } else {
            handler.removeCallbacks(tapTimeoutRunnable)
            tapCount++
            if (tapCount >= 2) {
                when (zone) {
                    TapZone.LEFT, TapZone.RIGHT -> {
                        val seconds = (tapCount - 1) * 10
                        showDoubleTapSeekFeedback(zone, seconds)
                        seekBy(
                            if (zone == TapZone.RIGHT) SEEK_STEP_MS else -SEEK_STEP_MS,
                            showControls = false,
                            showCenterIndicator = false
                        )
                        // Post timeout to reset the multi-tap streak if they stop tapping,
                        // but since tapCount >= 2 it won't toggle controls.
                        handler.postDelayed(tapTimeoutRunnable, TOUCH_DOUBLE_TAP_TIMEOUT_MS)
                    }
                    TapZone.MIDDLE -> {
                        togglePlayPause()
                        // Reset middle tap streak immediately so next click doesn't instantly play/pause
                        tapCount = 0
                        pendingTapZone = null
                    }
                }
            } else {
                if (!_controlsVisible) {
                    scheduleHideControls()
                }
                handler.postDelayed(tapTimeoutRunnable, TOUCH_DOUBLE_TAP_TIMEOUT_MS)
            }
        }
    }

    /**
     * [FEATURE UPDATE] Shows a premium YouTube-style double-tap overlay feedback on the left or
     * right side of the screen with a curved background, arrow shifting animation, and accumulated seconds.
     */
    private fun showDoubleTapSeekFeedback(zone: TapZone, seconds: Int) {
        val layout = if (zone == TapZone.LEFT) binding.leftDoubleTapLayout else binding.rightDoubleTapLayout
        val secondsText = if (zone == TapZone.LEFT) binding.textLeftDoubleTapSeconds else binding.textRightDoubleTapSeconds
        val arrowsText = if (zone == TapZone.LEFT) binding.textLeftDoubleTapArrows else binding.textRightDoubleTapArrows
        val hideRunnable = if (zone == TapZone.LEFT) hideLeftDoubleTapRunnable else hideRightDoubleTapRunnable
        
        secondsText.text = "$seconds seconds"
        
        handler.removeCallbacks(hideRunnable)
        layout.animate().cancel()
        
        if (layout.visibility != View.VISIBLE) {
            layout.alpha = 0f
            layout.visibility = View.VISIBLE
        }
        
        layout.animate()
            .alpha(1f)
            .setDuration(100)
            .start()
            
        handler.postDelayed(hideRunnable, 800)
            
        // Quick arrow shifting animation (YouTube style)
        arrowsText.animate().cancel()
        arrowsText.translationX = if (zone == TapZone.LEFT) 20f else -20f
        arrowsText.animate()
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.CycleInterpolator(2f))
            .start()
    }

    /**
     * [FEATURE UPDATE] With the lock button itself hidden ("fresh screen"), a top-to-bottom
     * swipe anywhere on the locked surface is the only recognized gesture — it exits Lock Mode
     * and restores every control panel. Tracked manually (rather than via [gestureDetector])
     * since that detector is never fed events while locked.
     */
    private var lockSwipeStartY: Float? = null

    private fun handleLockedTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lockSwipeStartY = event.y

            MotionEvent.ACTION_UP -> {
                val startY = lockSwipeStartY
                lockSwipeStartY = null
                if (startY != null && event.y - startY >= SWIPE_UNLOCK_THRESHOLD_PX) {
                    toggleLock()
                }
            }

            MotionEvent.ACTION_CANCEL -> lockSwipeStartY = null
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            draggingBrightness = false
            draggingVolume = false
            draggingSeek = false
            volumeAccumulator = 0f
            return true
        }

        /**
         * [FEATURE UPDATE] NOTE: the left-zone 2x turbo long-press is now scheduled manually via
         * [leftLongPressTurboRunnable] in [setupGestures] instead of relying on this callback.
         * GestureDetector cancels its own LONG_PRESS message the moment it sees ANY movement past
         * its internal touch-slop (a few px) — ordinary hand tremor over ~500ms of holding still
         * was enough to silently cancel it, so the gesture never fired in practice.
         */

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            if (e1 == null) return false
            // [FEATURE UPDATE] A real drag means this is a swipe, not a still hold — cancel the
            // pending/active left-zone turbo so it doesn't fire mid-swipe or fight the gesture.
            if (leftTouchTurboActive) {
                leftTouchTurboActive = false
                stopTurbo()
            }
            handler.removeCallbacks(leftLongPressTurboRunnable)
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (!draggingBrightness && !draggingVolume && !draggingSeek) {
                if (abs(dx) < GESTURE_THRESHOLD_PX && abs(dy) < GESTURE_THRESHOLD_PX) return false
                if (abs(dx) > abs(dy)) {
                    // RULE B — a live channel has no rewindable buffer; never arm the drag-seek
                    // preview for it (swallow the gesture silently rather than falling through to
                    // brightness/volume, which would misread an intended horizontal seek swipe).
                    if (isLiveStream()) return true
                    draggingSeek = true
                    seekDragStartPositionMs = engine?.positionMs ?: 0L
                    seekPreviewTargetMs = seekDragStartPositionMs
                    seekDragControlsWereVisible = _controlsVisible
                } else {
                    val leftHalf = e1.x < binding.playerRoot.width / 2f
                    // Gesture rework — brightness (left) is disabled on TVs; there the whole surface
                    // is a volume slider instead.
                    if (leftHalf && !isTv) draggingBrightness = true else draggingVolume = true
                }
            }
            if (draggingSeek) {
                updateSeekPreviewIncremental(distanceX)
                return true
            }
            // promt1 fix — GestureDetector's distanceY is (previousY - currentY), i.e. POSITIVE when
            // the finger moves up. So a positive fraction here correctly means "drag up = increase"
            // for both brightness (left) and volume (right); dragging down decreases.
            val fraction = distanceY / binding.playerRoot.height
            if (draggingBrightness) adjustBrightness(fraction) else adjustVolume(fraction)
            return true
        }
    }

    /**
     * [FEATURE UPDATE] YouTube-style dynamic swipe-to-seek:
     * Accumulates incremental deltas (-[distanceX]) multiplied by a dynamic sensitivity factor
     * that scales with the video's total duration. Applies non-linear acceleration if the swipe
     * speed is high.
     */
    private fun updateSeekPreviewIncremental(distanceX: Float) {
        val player = engine ?: return
        val length = player.durationMs
        val maxTime = if (length > 0) length else Long.MAX_VALUE
        
        // Base sensitivity scales with duration (e.g. duration / 2000 ms per pixel).
        // Capped to stay controllable: min 50ms/px (short video), max 3000ms/px (long movie).
        // Multiplied by user-defined sensitivity percent (e.g. 50% -> 0.5x, 200% -> 2.0x).
        val multiplier = swipeSensitivityPercent.toFloat() / 100f
        val msPerPx = if (length > 0) {
            ((length.toFloat() / 2000f) * multiplier).coerceIn(5f, 10000f)
        } else {
            500f * multiplier
        }
        
        val speed = abs(distanceX)
        val acceleration = if (speed > 5f) {
            1f + (speed - 5f) * 0.2f
        } else {
            1f
        }
        
        val deltaMs = (-distanceX * msPerPx * acceleration).toLong()
        seekPreviewTargetMs = (seekPreviewTargetMs + deltaMs).coerceIn(0L, maxTime)
        
        val actualDeltaMs = seekPreviewTargetMs - seekDragStartPositionMs
        val targetLabel = formatTime(seekPreviewTargetMs)
        val icon = if (actualDeltaMs >= 0) "▶▶ " else "◀◀ "
        showIndicator(icon + targetLabel)
    }

    /** [FEATURE UPDATE] Applies the previewed target position once the drag gesture ends. */
    private fun commitSeekDrag() {
        if (!draggingSeek) return
        draggingSeek = false
        // RULE B — defense in depth: even if a drag was already in flight when the stream flipped
        // live (e.g. mid-buffering), never commit the seek.
        if (isLiveStream()) {
            if (seekDragControlsWereVisible) {
                scheduleHideControls()
            }
            return
        }
        
        seekControlsWereVisible = seekDragControlsWereVisible
        lastSeekTime = System.currentTimeMillis()
        engine?.seekTo(seekPreviewTargetMs)
        
        if (seekDragControlsWereVisible) {
            scheduleHideControls()
        } else {
            // If controls were hidden when the swipe started, keep them hidden.
            // The center gesture indicator will fade out automatically.
            handler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
        }
    }

    /**
     * Step 10.3 — clamp-safe relative seek with a transient indicator.
     * [showControls] — double-tap seek (touch) should show only the +10s/-10s indicator, not the
     * full title/seekbar panel; D-pad seek uses [seekSilently] instead, which never shows the panel.
     */
    private fun seekBy(deltaMs: Long, showControls: Boolean = true, showCenterIndicator: Boolean = true) {
        val player = engine ?: return
        // RULE B — touch-based edge-zone seek is blocked for live the same as D-pad seek.
        if (blockIfLiveSeek()) return
        val length = player.durationMs
        val target = (player.positionMs + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        seekControlsWereVisible = showControls && _controlsVisible
        lastSeekTime = System.currentTimeMillis()
        player.seekTo(target)
        if (showCenterIndicator) {
            val seconds = (abs(deltaMs) / 1000).toInt()
            showIndicator(
                getString(
                    if (deltaMs >= 0) R.string.player_gesture_seek_forward
                    else R.string.player_gesture_seek_backward,
                    seconds
                )
            )
        }
        if (showControls) {
            scheduleHideControls()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
        }
    }

    /**
     * [FEATURE UPDATE] Silent D-pad Left/Right seek: adjusts position, keeps the control panel
     * hidden no matter its prior state, and shows only the mini elapsed/duration readout
     * (see [showSeekTimeIndicator]) rather than the "+Ns" pill or full overlay.
     */
    private fun seekSilently(deltaMs: Long) {
        val player = engine ?: return
        // RULE B — the primary named trigger: D-pad Left/Right 10s/30s seek is fully blocked live.
        if (blockIfLiveSeek()) return
        val length = player.durationMs
        // [BUG FIX promt7] STEP_2 — accumulate from the last captured target rather than the (possibly
        // stale) live position, so rapid consecutive presses add up correctly instead of collapsing
        // back onto an old, not-yet-committed position.
        val base = if (dpadSeeking) dpadSeekTargetMs else player.positionMs
        val target = (base + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        // STEP_1 — suspend the ticker's write-back so the async seek can't be overridden mid-flight.
        dpadSeeking = true
        dpadSeekTargetMs = target
        // STEP_3 — commit the explicit player seek.
        seekControlsWereVisible = false
        lastSeekTime = System.currentTimeMillis()
        player.seekTo(target)
        // Reflect the committed target on the UI immediately so it never flashes the old position.
        if (length > 0) {
            binding.seekBar.max = length.toInt()
            binding.seekBar.progress = target.toInt()
            binding.textElapsed.text = formatTime(target)
        }
        // Arm the safety valve in case the native landing is never observed by the ticker.
        handler.removeCallbacks(dpadSeekSettleTimeout)
        handler.postDelayed(dpadSeekSettleTimeout, DPAD_SEEK_SETTLE_TIMEOUT_MS)
        handler.removeCallbacks(hideControlsRunnable)
        setControlsVisible(false)
        showSeekTimeIndicator(target, length)
    }

    /** [FEATURE UPDATE] Minimal bottom-center "hh:mm:ss / hh:mm:ss" readout, auto-fades. */
    private fun showSeekTimeIndicator(position: Long, length: Long) {
        binding.seekTimeIndicator.text = if (length > 0) {
            getString(R.string.player_seek_time_with_duration, formatTime(position), formatTime(length))
        } else {
            formatTime(position)
        }
        binding.seekTimeIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekTimeIndicatorRunnable)
        handler.postDelayed(hideSeekTimeIndicatorRunnable, SEEK_TIME_INDICATOR_TIMEOUT_MS)
    }

    /** Step 10.2 — screen brightness via the window attributes (0f..1f). */
    private fun adjustBrightness(fraction: Float) {
        val attrs = window.attributes
        val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
        val next = (current + fraction).coerceIn(0.01f, 1f)
        attrs.screenBrightness = next
        window.attributes = attrs
        showIndicator(getString(R.string.player_gesture_brightness, (next * 100).toInt()))
    }

    /**
     * Gesture rework — right-side vertical swipe drives LibVLC's 0–200% software gain incrementally
     * (101%, 102%, …, 200%). No dedicated button; handled purely via gesture.
     */
    private fun adjustVolume(fraction: Float) {
        // [FIX] Accumulate the fractional changes at 40% (0.4f) multiplier to keep swipe seeking buttery smooth
        volumeAccumulator += fraction * VOLUME_BOOSTED * 0.4f
        val change = volumeAccumulator.toInt()
        if (change != 0) {
            val next = (appVolume + change).coerceIn(0, VOLUME_BOOSTED)
            setVolume(next)
            volumeAccumulator -= change
        }
        showIndicator(getString(R.string.player_gesture_volume, appVolume))
    }

    private fun showIndicator(text: String) {
        binding.gestureIndicator.text = text
        binding.gestureIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, INDICATOR_TIMEOUT_MS)
    }

    // --- Step 3 (prompt): D-pad / keyboard controls (TV remotes) ------------------------------

    /**
     * promt1 — the OK/center key is intercepted here, *before* the normal dispatch reaches the
     * focused button. That matters for two reasons:
     *  1. CASE-aware behavior: OK must do different things depending on whether the panel is
     *     hidden (reveal only) or visible (activate the focused control) — see [onCenterClick].
     *  2. Double-fire immunity: if we let a focused Play/Pause button receive OK natively it would
     *     auto-performClick(), and some remotes emit a duplicate OK per physical press — the old
     *     "pause then instantly resume" bug. Owning the key end-to-end (with the [lastCenterKeyUpTime]
     *     debounce in [handleCenterKey]) keeps a single physical press = a single action.
     *
     * Everything else is left to normal dispatch, so the focused button still gets the ARROW keys
     * for standard spatial navigation (CASE B). We only re-arm the auto-hide timer here (promt1 §3:
     * "any remote button" resets the timeout) while the panel is already showing.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isCenterKey(event.keyCode)) {
            return handleCenterKey(event)
        }
        if (!locked && controlsShowing && event.action == KeyEvent.ACTION_DOWN) {
            if (isRemoteNavKey(event.keyCode)) {
                scheduleHideControls()
            }
            // promt3 ACTION_2 / ACTION_3 — global D-pad bridge for the player OSD.
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (bridgeFocusUpToHeader()) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (bridgeFocusDownToContent()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * ACTION_2 — UP from the center transport / seekbar moves focus into the top bar (header).
     */
    private fun bridgeFocusUpToHeader(): Boolean {
        if (!isTv) return false
        val focused = currentFocus ?: return false
        if (isDescendantOf(focused, binding.topBar)) return false
        // Only bridge if there's nothing else focusable above us within the controls.
        val above = android.view.FocusFinder.getInstance().findNextFocus(binding.controlsOverlay, focused, View.FOCUS_UP)
        if (above != null && isDescendantOf(above, binding.controlsOverlay) && !isDescendantOf(above, binding.topBar)) return false
        return binding.btnBack.requestFocus()
    }

    /**
     * ACTION_3 — DOWN from any header control drops focus back onto Play/Pause.
     */
    private fun bridgeFocusDownToContent(): Boolean {
        if (!isTv) return false
        val focused = currentFocus ?: return false
        if (!isDescendantOf(focused, binding.topBar)) return false
        return binding.btnPlayPause.requestFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // OK/center is fully handled in dispatchKeyEvent — it never reaches here.
        if (locked) {
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                setVolume(appVolume + VOLUME_KEY_STEP)
                showIndicator(getString(R.string.player_gesture_volume, appVolume))
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                setVolume(appVolume - VOLUME_KEY_STEP)
                showIndicator(getString(R.string.player_gesture_volume, appVolume))
                return true
            }

            // A dedicated media Play/Pause remote key always toggles directly — it is not the
            // context-aware D-pad "OK", so it bypasses the CASE A reveal-first step.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
        }

        // promt1 CASE B — while the panel is visible on a TV, the arrows are owned by standard
        // spatial navigation (the focused button consumes most of them before we get here). The
        // global background shortcuts (silent seek, Next/Prev) are DISABLED. This branch just
        // stops those shortcuts from firing on edge buttons where focus can't travel further.
        if (isTv && controlsShowing) {
            return super.onKeyDown(keyCode, event)
        }

        // promt1 CASE A — panel hidden: the arrows are global background shortcuts.
        when (keyCode) {
            // Up/Down → filter-aware Next/Previous (honoring the active sort order).
            KeyEvent.KEYCODE_DPAD_UP -> {
                playNext()
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playPrevious()
                return true
            }

            // Left/Right → silent ±10s / ±30s seek without opening the full UI.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDpadSeek(forward = false)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadSeek(forward = true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * promt1 — full ownership of the OK/center key (down/long-press/up), replacing the old
     * onKeyDown/onKeyLongPress/onKeyUp trio. Long-press turbo is timed with our own runnable since
     * the focused button would otherwise swallow the native long-press.
     */
    private fun handleCenterKey(event: KeyEvent): Boolean {
        if (locked) {
            // Lock-state protection — OK can never reach play/pause; nudge toward the unlock gesture.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showLockedHint()
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerLongPressFired = false
                    handler.removeCallbacks(centerLongPressRunnable)
                    handler.postDelayed(
                        centerLongPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(centerLongPressRunnable)
                // [BUGFIX] Swallow the duplicate ACTION_UP some remotes fire for one physical press.
                val now = event.eventTime
                if (now - lastCenterKeyUpTime < CENTER_KEY_DEBOUNCE_MS) return true
                lastCenterKeyUpTime = now
                if (centerLongPressFired) {
                    stopTurbo()
                } else {
                    onCenterClick()
                }
            }
        }
        return true
    }

    /**
     * promt1 §2 — the context-aware OK/center action.
     *  - CASE A (panel hidden): DO NOT play/pause. Only reveal the panel; [setControlsVisible]'s
     *    hidden→visible transition drops default focus onto Play/Pause.
     *  - CASE B (panel visible, TV): activate whatever control currently holds focus — Play/Pause
     *    by default, so a second OK toggles playback.
     * On non-TV (phone/keyboard) there's no visible focus, so OK keeps its original one-press
     * reveal-and-toggle behavior.
     */
    private fun onCenterClick() {
        if (!isTv) {
            setControlsVisible(true)
            togglePlayPause()
            return
        }
        if (controlsShowing) {
            val focused = currentFocus
            if (userSeeking && focused == binding.seekBar) {
                handler.removeCallbacks(commitDpadBarSeekRunnable)
                commitDpadBarSeekRunnable.run()
                return
            }
            if (focused != null && focused.isFocusable) focused.performClick() else togglePlayPause()
            scheduleHideControls()
        } else {
            // Reveal only — focus lands on Play/Pause via the visibility transition; no toggle.
            scheduleHideControls()
        }
    }

    /** [FEATURE UPDATE] Brief centered OSD nudging the user toward the real unlock gesture. */
    private fun showLockedHint() {
        showIndicator(getString(R.string.player_locked_hint))
    }

    /**
     * promt1 — the D-pad "OK" keys only. [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE] is intentionally NOT
     * here: a dedicated media play/pause remote button toggles directly (handled in [onKeyDown])
     * rather than going through the context-aware reveal-first OK flow.
     */
    private fun isCenterKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** promt1 §3 — D-pad direction keys; any press keeps a visible panel alive (resets auto-hide). */
    private fun isRemoteNavKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /**
     * Single press seeks ±10s; a second press within the window upgrades it to ±30s.
     * [FEATURE UPDATE] D-pad Left/Right is now fully silent — it never reveals the control
     * panel, only the fading mini timestamp from [showSeekTimeIndicator].
     */
    private fun handleDpadSeek(forward: Boolean) {
        // RULE B — block immediately, before even arming the single/double-tap window.
        if (blockIfLiveSeek()) return
        if (pendingDpadForward == forward) {
            handler.removeCallbacks(dpadSeekRunnable)
            seekSilently(if (forward) SEEK_STEP_DOUBLE_MS else -SEEK_STEP_DOUBLE_MS)
            pendingDpadForward = null
        } else {
            handler.removeCallbacks(dpadSeekRunnable)
            pendingDpadForward = forward
            handler.postDelayed(dpadSeekRunnable, DOUBLE_CLICK_WINDOW_MS)
        }
    }

    private fun startTurbo() {
        if (turboActive) return
        turboActive = true
        speedBeforeTurbo = playbackSpeed
        engine?.speed = TURBO_RATE
        showIndicator(getString(R.string.player_speed_holding))
    }

    private fun stopTurbo() {
        if (!turboActive) return
        turboActive = false
        engine?.speed = speedBeforeTurbo
        showIndicator(getString(R.string.player_speed_current, "${speedBeforeTurbo}x"))
    }

    // --- Immersive / system status bar --------------------------------------------------------

    /**
     * Hide the system status + navigation bars while a video plays (user request). Uses the
     * "sticky immersive" behaviour so a swipe from the edge shows them transiently, then they
     * auto-hide again.
     */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide after a dialog / PiP / focus change temporarily brings the bars back.
        if (hasFocus) enterImmersiveMode()
    }

    // --- UI states (Step 9.3) -----------------------------------------------------------------

    private fun showLoading() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textError.visibility = View.GONE
    }

    private fun showVideo() {
        binding.progressLoading.visibility = View.GONE
        binding.textError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.progressLoading.visibility = View.GONE
        // Cancel any in-progress animation and force-hide; bypass smooth fade for errors.
        binding.controlsOverlay.animate().cancel()
        binding.controlsOverlay.alpha = 0f
        binding.controlsOverlay.visibility = View.GONE
        _controlsVisible = false
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        // promt4 §1 — exit flush (async, on the process-lifetime write scope so it survives teardown).
        handler.removeCallbacks(saveResumeTicker)
        saveResumePoint()
        // Keep playing while in PiP; only pause when the activity is genuinely backgrounded.
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            engine?.pause()
            handler.removeCallbacks(progressTicker)
            // [BUG FIX promt7] Don't leave a D-pad seek's settle guard armed across a background stop.
            handler.removeCallbacks(dpadSeekSettleTimeout)
            dpadSeeking = false
        } else {
            engine?.pause()
            handler.removeCallbacks(progressTicker)
            handler.removeCallbacks(dpadSeekSettleTimeout)
            dpadSeeking = false
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        resumeScope.cancel()
        handler.removeCallbacksAndMessages(null)
        // Media3 migration — release both engines (ExoPlayer + VLC fallback) and their surfaces.
        engine?.release()
        engine = null
        // The shared LibVLC engine is a Hilt singleton — do NOT release it here.
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    /** [FEATURE UPDATE] One playable entry in the Next/Previous queue. */
    private data class QueueItem(
        val url: String,
        val title: String,
        val httpReferrer: String?,
        val httpUserAgent: String?,
        /** promt5 — owning playlist; the resume-isolation key used when saving/reading positions. */
        val playlistId: Long,
        /**
         * promt2 — playlist-declared Live/VOD hint (from `#EXTINF:`), used at load time to pick the
         * buffering profile before the real stream length is known. The runtime [isLiveStream] check
         * still governs everything after playback starts; this only steers the initial cache sizing.
         */
        val isLive: Boolean
    )

    companion object {
        /**
         * In-memory hand-off for the Next/Previous queue. A large "All Channels" list (thousands of
         * movie/VOD entries) can't ride in the launch Intent — it blows past Binder's ~1 MB limit and
         * crashes with TransactionTooLargeException. So [newIntent] stashes the full queue here and
         * [parseQueue] consumes it (clearing it immediately). The Intent still carries the single start
         * item as a fallback for the process-death case, where this static is gone.
         */
        private var queueMemoryHolder: List<QueueItem>? = null
        /**
         * Process-lifetime scope for resume-point DB writes. Deliberately NOT tied to the activity:
         * the exit save fired from [onStop] must finish even after [onDestroy] cancels the
         * activity's own [resumeScope]. Writes are cheap single-row upserts, so this never leaks.
         */
        private val resumeWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val EXTRA_QUEUE_URLS = "extra_queue_urls"
        private const val EXTRA_QUEUE_TITLES = "extra_queue_titles"
        private const val EXTRA_QUEUE_REFERRERS = "extra_queue_referrers"
        private const val EXTRA_QUEUE_USER_AGENTS = "extra_queue_user_agents"
        private const val EXTRA_QUEUE_PLAYLIST_IDS = "extra_queue_playlist_ids"
        // promt2 — per-item Live/VOD hint, parallel to the URL/title arrays, drives cache sizing.
        private const val EXTRA_QUEUE_IS_LIVE = "extra_queue_is_live"
        private const val EXTRA_QUEUE_INDEX = "extra_queue_index"

        /** promt5 — when > 0, resume the opened video directly at this offset, skipping the prompt. */
        private const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"

        private const val VOLUME_NORMAL = 100
        private const val VOLUME_BOOSTED = 200
        private const val VOLUME_KEY_STEP = 5
        private const val TURBO_RATE = 2.0f

        /** Step 11.2 — selectable speeds (0.5x–2.0x). */
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        private const val INDICATOR_TIMEOUT_MS = 800L

        /** promt4 §1 — autosave cadence, the minimum watch time before a session is stored, and the
         *  fraction of the runtime past which a video counts as fully watched. */
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val MIN_WATCHED_MS = 15_000L
        private const val COMPLETION_FRACTION = 0.95

        /** [FEATURE UPDATE] How long the silent D-pad seek's mini timestamp stays on screen. */
        private const val SEEK_TIME_INDICATOR_TIMEOUT_MS = 1500L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_STEP_DOUBLE_MS = 30_000L

        /**
         * [BUG FIX promt7] D-pad seek settle window. LibVLC seeks asynchronously; the ticker treats
         * the seek as landed once player.time is within this tolerance of the captured target, then
         * resumes normal tracking. The timeout is a hard cap that force-resumes tracking even if the
         * exact landing tick is never observed.
         */
        private const val DPAD_SEEK_SETTLE_TOLERANCE_MS = 1_500L
        private const val DPAD_SEEK_SETTLE_TIMEOUT_MS = 3_000L

        /** [FEATURE UPDATE] Timeline span (ms) that a full-screen-width horizontal drag covers. */
        private const val SEEK_DRAG_RANGE_MS = 120_000L
        private const val DOUBLE_CLICK_WINDOW_MS = 350L
        private const val CENTER_KEY_DEBOUNCE_MS = 250L
        private const val DPAD_DEBUG_TAG = "DpadCenterDebug"
        private const val GESTURE_THRESHOLD_PX = 40f

        /** [BUG FIX] Live TV Playback Safety — minimum gap between two auto surface-recovery reconnects. */
        private const val LIVE_RECOVERY_COOLDOWN_MS = 8_000L

        // --- promt2 Item D: Auto-mode tuning ---
        /** Handshake budget: if the first frame doesn't render within this, downgrade one rung (Item D). */
        private const val QUALITY_HANDSHAKE_BUDGET_MS = 4_000L
        /** Mid-play buffering stalls counted within this rolling window trigger a downgrade. */
        private const val QUALITY_STALL_WINDOW_MS = 12_000L
        /** Number of stalls within [QUALITY_STALL_WINDOW_MS] that forces a drop to the lowest rung. */
        private const val QUALITY_STALL_DOWNGRADE_COUNT = 3
        /** Uninterrupted smooth playback required before Auto attempts a one-rung upgrade. */
        private const val QUALITY_UPGRADE_STABLE_MS = 25_000L
        /** Minimum gap between two successive Auto upgrades, so a marginal link can't oscillate. */
        private const val QUALITY_UPGRADE_COOLDOWN_MS = 30_000L

        /** [FEATURE UPDATE] Movement/time bounds for a raw touch-up to still count as a tap. */
        private const val TAP_SLOP_PX = 24f
        private const val TAP_MAX_DURATION_MS = 250L

        /** [FEATURE UPDATE] Left/right fraction of the screen width that triggers seek taps. */
        private const val SEEK_ZONE_FRACTION = 0.3f

        /** [FEATURE UPDATE] Silence window that ends a double-tap / multi-tap verification. */
        private const val TOUCH_DOUBLE_TAP_TIMEOUT_MS = 800L

        /** [FEATURE UPDATE] Minimum top-to-bottom drag distance to unlock while in Lock Mode. */
        private const val SWIPE_UNLOCK_THRESHOLD_PX = 120f

        /**
         * [FEATURE UPDATE] Builds the intent that launches the player for [startIndex] within
         * [channels] — [channels] must already be in the exact order Next/Previous should honor
         * (e.g. the Folder Content Screen's active `sort_by`, per CASE_1/CASE_2).
         */
        fun newIntent(
            context: Context,
            channels: List<Channel>,
            startIndex: Int,
            resumePositionMs: Long = -1L
        ): Intent {
            // Option 1: In-memory hand-off to prevent TransactionTooLargeException
            queueMemoryHolder = channels.map {
                QueueItem(
                    url = it.url,
                    title = it.name,
                    httpReferrer = it.httpReferrer.orEmpty().takeIf { ref -> ref.isNotBlank() },
                    httpUserAgent = it.httpUserAgent.orEmpty().takeIf { ua -> ua.isNotBlank() },
                    playlistId = it.playlistId,
                    isLive = it.isLive
                )
            }

            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_QUEUE_INDEX, startIndex)
                // promt5 — forced resume offset; -1 means "no forced resume" (normal prompt flow).
                .putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs)
                
            val fallback = channels.getOrNull(startIndex)
            if (fallback != null) {
                intent.putStringArrayListExtra(EXTRA_QUEUE_URLS, arrayListOf(fallback.url))
                intent.putStringArrayListExtra(EXTRA_QUEUE_TITLES, arrayListOf(fallback.name))
                intent.putStringArrayListExtra(EXTRA_QUEUE_REFERRERS, arrayListOf(fallback.httpReferrer.orEmpty()))
                intent.putStringArrayListExtra(EXTRA_QUEUE_USER_AGENTS, arrayListOf(fallback.httpUserAgent.orEmpty()))
                intent.putExtra(EXTRA_QUEUE_PLAYLIST_IDS, longArrayOf(fallback.playlistId))
                intent.putExtra(EXTRA_QUEUE_IS_LIVE, booleanArrayOf(fallback.isLive))
            }
            return intent
        }

        /** Convenience overload for launching a single channel with no Next/Previous queue. */
        fun newIntent(context: Context, channel: Channel, resumePositionMs: Long = -1L): Intent =
            newIntent(context, listOf(channel), 0, resumePositionMs)
    }
}
