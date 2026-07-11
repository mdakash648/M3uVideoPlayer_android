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
import com.mdaksh.m3uvideoplayer.data.local.dao.ResumeDao
import com.mdaksh.m3uvideoplayer.data.local.entity.ResumePointEntity
import com.mdaksh.m3uvideoplayer.databinding.ActivityPlayerBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.usecase.SavePlaylistResumePointUseCase
import com.mdaksh.m3uvideoplayer.util.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Step 9 + Step 10 + Step 11 — the LibVLC-backed video player screen, styled after VLC's OSD.
 *
 * Step 9 (engine): Hilt-injected [LibVLC], per-screen [MediaPlayer], loading/error states, HW decode.
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
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    @Inject
    lateinit var libVlc: LibVLC

    /** promt4 — persistent resume-point store (save/read per-video playback position). */
    @Inject
    lateinit var resumeDao: ResumeDao

    /**
     * Floating Resume Button engine — writes the single active `playlist_resume_points` pointer.
     * Kept fully separate from [resumeDao] (see the Isolation Rule).
     */
    @Inject
    lateinit var savePlaylistResumePointUseCase: SavePlaylistResumePointUseCase

    private var mediaPlayer: MediaPlayer? = null

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
     * promt1 §1(1) — set when a *brand new* video is loaded via [play]; the next [MediaPlayer.Event.Playing]
     * drops the D-pad focus onto Play/Pause. Distinguishes a fresh load from a plain resume (which
     * calls `player.play()` directly and must not steal focus back).
     */
    private var pendingFocusOnPlay = false

    /**
     * [BUGFIX] Some remotes/emulators deliver a genuine physical single click as TWO complete
     * ACTION_DOWN/ACTION_UP pairs fired milliseconds apart. Without a guard, the OK action would
     * run twice back-to-back — e.g. pause immediately followed by resume — so the click visibly
     * does nothing. This timestamp lets [handleCenterKey] ignore the duplicate ACTION_UP.
     */
    private var lastCenterKeyUpTime = 0L

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
    private val hideSeekTimeIndicatorRunnable = Runnable { binding.seekTimeIndicator.visibility = View.GONE }
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

        mediaPlayer = MediaPlayer(libVlc).also { player ->
            // 3rd arg = enableSubtitles: create the subtitle surface so selected SPU tracks
            // actually render (bottom-center overlay). Was false, which silently dropped subtitles.
            player.attachViews(binding.videoLayout, null, true, false)
            player.setEventListener(::onPlayerEvent)
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
        // promt5 — a forced-resume launch (from the floating resume FAB) skips the DB check and the
        // Continue/Start-Over prompt entirely: it plays straight from the caller-supplied offset.
        val forcedResumeMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, -1L)
        if (forcedResumeMs > 0L) {
            resumeChecked = true
            play(url, forcedResumeMs)
            return
        }
        val playlistId = currentQueueItem?.playlistId ?: 0L
        resumeScope.launch {
            val saved = runCatching { resumeDao.getResumePoint(playlistId, url) }.getOrNull()
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
        val urls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
        val referrers = intent.getStringArrayListExtra(EXTRA_QUEUE_REFERRERS).orEmpty()
        val userAgents = intent.getStringArrayListExtra(EXTRA_QUEUE_USER_AGENTS).orEmpty()
        val playlistIds = intent.getLongArrayExtra(EXTRA_QUEUE_PLAYLIST_IDS) ?: LongArray(0)
        return urls.indices.map { i ->
            QueueItem(
                url = urls[i],
                title = titles.getOrElse(i) { "" },
                httpReferrer = referrers.getOrElse(i) { "" }.takeIf { it.isNotBlank() },
                httpUserAgent = userAgents.getOrElse(i) { "" }.takeIf { it.isNotBlank() },
                playlistId = playlistIds.getOrElse(i) { 0L }
            )
        }
    }

    // --- Playback (Step 9) --------------------------------------------------------------------

    /**
     * @param startMs promt4 — when > 0, LibVLC begins decoding at this offset via `:start-time`
     *   (whole seconds), so "Continue" resumes cleanly at the saved position rather than seeking
     *   after the fact.
     */
    private fun play(url: String, startMs: Long = 0L) {
        showLoading()
        // [BUG FIX] Live TV Playback Safety — any fresh load (channel switch, manual live resume,
        // or the surface-recovery watchdog) clears the "user stopped a live channel" flag so it
        // never leaks into the next stream.
        liveStreamStoppedByUser = false
        // promt1 §1(1) — a brand-new load; the upcoming Playing event snaps focus to Play/Pause.
        pendingFocusOnPlay = true
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)   // Step 9.5
            // promt1 — adaptive look-ahead buffering tuned to the stream type (replaces the flat 1.5s cache).
            applyPromt1Buffering(url)
            // promt2 — pass #EXTVLCOPT headers so protected streams authenticate instead of 403-ing.
            httpReferrer?.let { addOption(":http-referrer=$it") }
            httpUserAgent?.let { addOption(":http-user-agent=$it") }
            // promt4 — resume offset (seconds); harmless at 0.
            if (startMs > 0L) addOption(":start-time=${startMs / 1000}")
        }
        mediaPlayer?.media = media
        media.release()
        mediaPlayer?.play()
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
        val player = mediaPlayer ?: return
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        val duration = player.length

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
        val position = player.time
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
        val player = mediaPlayer ?: return
        val item = currentQueueItem ?: return
        val url = item.url.takeIf { it.isNotBlank() } ?: return
        val duration = player.length
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

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Buffering ->
                if (event.buffering < 100f) showLoading() else showVideo()

            MediaPlayer.Event.Playing -> {
                showVideo()
                // Re-apply user preferences the media reset on load.
                applyVolume()
                mediaPlayer?.rate = if (turboActive) TURBO_RATE else playbackSpeed
                updatePlayPauseIcon()
                handler.removeCallbacks(progressTicker)
                handler.post(progressTicker)
                // promt4 §1 — (re)arm the 5s resume-point autosave for the active video.
                handler.removeCallbacks(saveResumeTicker)
                handler.postDelayed(saveResumeTicker, SAVE_INTERVAL_MS)
                // [FEATURE UPDATE] Auto-hide only applies while actually playing — (re)arm the
                // timer now that playback has genuinely started (covers the initial load, where
                // scheduleHideControls() was called too early to know we weren't playing yet).
                scheduleHideControls()
                // promt1 §1(1) — brand-new video: force default D-pad focus onto Play/Pause.
                if (pendingFocusOnPlay) {
                    pendingFocusOnPlay = false
                    focusPlayPause()
                }
            }

            MediaPlayer.Event.Paused -> {
                updatePlayPauseIcon()
                // [FEATURE UPDATE] Paused = no auto-hide; keep the panel (and lock icon) up until
                // the user resumes or interacts again.
                handler.removeCallbacks(hideControlsRunnable)
                setControlsVisible(true)
                // promt4 — pausing is a natural "leaving off" point; snapshot it immediately.
                handler.removeCallbacks(saveResumeTicker)
                saveResumePoint()
            }

            MediaPlayer.Event.Vout -> {
                // [BUG FIX] Live TV Playback Safety — SURFACE RESET FALLBACK: `voutCount == 0` while
                // a live channel is still (meant to be) playing means the decoder dropped its video
                // track/render surface — audio keeps going, video goes permanently black. Force a
                // fast reconnect to restore the render pipeline instead of leaving it dead.
                if (event.voutCount == 0 && isLiveStream() && !liveStreamStoppedByUser) {
                    attemptLiveSurfaceRecovery()
                } else {
                    showVideo()
                }
            }

            MediaPlayer.Event.EncounteredError ->
                showError(getString(R.string.player_error_network))

            MediaPlayer.Event.EndReached -> {
                // promt4 §1 — a fully played video is marked completed so it won't prompt to resume.
                handler.removeCallbacks(saveResumeTicker)
                markCurrentCompleted()
                showError(getString(R.string.player_error_ended))
            }
        }
    }

    // --- Step 10.1 / 11: controller wiring ----------------------------------------------------

    private fun setupControls() = with(binding) {
        btnBack.setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnFullscreen.setOnClickListener { toggleAspectRatio() }

        // Step 11.
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnAudioTrack.setOnClickListener { showTrackDialog(audio = true) }
        btnSubtitles.setOnClickListener { showTrackDialog(audio = false) }
        btnPip.setOnClickListener { enterPipMode() }
        if (!supportsPip()) btnPip.visibility = View.GONE

        // [FEATURE UPDATE] Screen rotate + filter-aware, looping Next/Previous.
        btnRotate.setOnClickListener { toggleScreenOrientation() }
        btnNext.setOnClickListener { playNext() }
        btnPrevious.setOnClickListener { playPrevious() }

        // promt1 rework — the on-screen lock button is pointless with a D-pad remote, so hide it on TV.
        if (isTv) {
            btnLock.visibility = View.GONE
        } else {
            btnLock.setOnClickListener { toggleLock() }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) textElapsed.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                // RULE B — a live stream's seekbar has no real timeline (updateProgress() never
                // populates it either); refuse to even start tracking a drag on it.
                if (isLiveStream()) return
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                if (!userSeeking) return   // never started (see onStartTrackingTouch) — nothing to commit
                userSeeking = false
                if (blockIfLiveSeek()) { scheduleHideControls(); return }
                mediaPlayer?.time = sb.progress.toLong()
                scheduleHideControls()
            }
        })
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        // [BUG FIX] Live TV Playback Safety — RULE A: a native player.pause() leaves the HLS/TS
        // decoder holding stale packet/frame references, which is what causes the permanent black
        // screen (with audio still running) on resume. Live channels get a fully separate
        // stop-and-reconnect flow instead; see [toggleLivePlayPause].
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
        val player = mediaPlayer ?: return
        val item = currentQueueItem ?: return
        if (liveStreamStoppedByUser) {
            play(item.url)
        } else {
            liveStreamStoppedByUser = true
            player.stop()
            showLoading()
        }
        updatePlayPauseIcon()
        scheduleHideControls()
    }

    /**
     * [BUG FIX] Live TV Playback Safety — SURFACE RESET FALLBACK: background auto-recovery, called
     * off a `Vout(voutCount=0)` signal while a live channel is supposed to be playing (see
     * [onPlayerEvent]). Debounced by [LIVE_RECOVERY_COOLDOWN_MS] so a burst of flaky events during
     * one real network blip can't spam reconnects, and skipped entirely once the user has
     * intentionally stopped the channel via [toggleLivePlayPause].
     */
    private fun attemptLiveSurfaceRecovery() {
        val item = currentQueueItem ?: return
        val now = System.currentTimeMillis()
        if (now - lastLiveRecoveryAtMs < LIVE_RECOVERY_COOLDOWN_MS) return
        lastLiveRecoveryAtMs = now
        play(item.url)
    }

    private fun updatePlayPauseIcon() {
        val playing = mediaPlayer?.isPlaying == true
        val resId = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(resId)
    }

    /**
     * [BUG FIX] Live TV Playback Safety — a stream is "live" when LibVLC reports no known length,
     * the same real-duration test already used app-wide (see [saveResumePoint]/[updateProgress])
     * rather than the M3U-declared `contentType`, since providers mislabel entries often.
     */
    private fun isLiveStream(): Boolean = (mediaPlayer?.length ?: 0L) <= 0L

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

    /** Step 10.1 fullscreen toggle — cycles LibVLC's aspect handling (best-fit ↔ fill). */
    private fun toggleAspectRatio() {
        val player = mediaPlayer ?: return
        if (player.videoScale == MediaPlayer.ScaleType.SURFACE_BEST_FIT) {
            player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
            binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            binding.btnFullscreen.setImageResource(R.drawable.ic_fullscreen)
        }
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
        val player = mediaPlayer ?: return
        val length = player.length
        if (length <= 0) return // live stream with no known duration
        binding.textDuration.text = formatTime(length)

        // [BUG FIX promt7] STEP_1/STEP_3 — while a D-pad seek is committing, keep the bar pinned to
        // the captured target and refuse to write the stale (pre-seek) player.time back onto it.
        // Resume normal tracking only once the native seek has actually landed near the target.
        if (dpadSeeking) {
            if (kotlin.math.abs(player.time - dpadSeekTargetMs) <= DPAD_SEEK_SETTLE_TOLERANCE_MS) {
                dpadSeeking = false
                handler.removeCallbacks(dpadSeekSettleTimeout)
            } else {
                return
            }
        }

        if (!userSeeking) {
            binding.seekBar.max = length.toInt()
            binding.seekBar.progress = player.time.toInt()
            binding.textElapsed.text = formatTime(player.time)
        }
    }

    /**
     * [FEATURE UPDATE] The lock icon now fades in/out together with the rest of the panel — the
     * only exceptions are cases where it must stay hidden regardless (TV remotes don't need it,
     * PiP has no room for it, and Lock Mode already manages its own hidden state elsewhere).
     */
    private fun setControlsVisible(visible: Boolean) {
        val wasVisible = binding.controlsOverlay.visibility == View.VISIBLE
        binding.controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (!locked && !isTv && !isInPip()) {
            binding.btnLock.visibility = if (visible) View.VISIBLE else View.GONE
        }
        // promt1 §1(2) — every time the panel goes from HIDDEN to VISIBLE, drop the remote's focus
        // cleanly onto Play/Pause. Guarded to the actual transition so it never yanks focus back
        // while the user is navigating a panel that's already up (CASE B spatial navigation).
        if (visible && !wasVisible && isTv && !isInPip()) {
            focusPlayPause()
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
        get() = binding.controlsOverlay.visibility == View.VISIBLE

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
        if (mediaPlayer?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun toggleControls() {
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
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
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            mediaPlayer?.volume = VOLUME_NORMAL
        } else {
            if (systemMaxVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemMaxVolume, 0)
            }
            mediaPlayer?.volume = appVolume
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
        if (!turboActive) mediaPlayer?.rate = speed
        showIndicator(getString(R.string.player_speed_current, "${speed}x"))
    }

    // --- Step 11.3: audio / subtitle track OSD ------------------------------------------------

    private fun showTrackDialog(audio: Boolean) {
        val player = mediaPlayer ?: return
        handler.removeCallbacks(hideControlsRunnable)
        val tracks = if (audio) player.audioTracks else player.spuTracks
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(
                this,
                if (audio) R.string.player_no_audio_tracks else R.string.player_no_subtitle_tracks,
                Toast.LENGTH_SHORT
            ).show()
            scheduleHideControls()
            return
        }
        val names = tracks.map { it.name }.toTypedArray()
        val currentId = if (audio) player.audioTrack else player.spuTrack
        val checked = tracks.indexOfFirst { it.id == currentId }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) R.string.player_audio_track else R.string.player_subtitle_track)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val id = tracks[which].id
                if (audio) player.audioTrack = id else player.spuTrack = id
                showIndicator(
                    getString(
                        if (audio) R.string.player_osd_audio_selected
                        else R.string.player_osd_subtitle_selected,
                        tracks[which].name
                    )
                )
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
        if (mediaPlayer?.isPlaying == true && !locked) enterPipMode()
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
                }

                MotionEvent.ACTION_UP -> {
                    // A "tap" is a touch-up close to where it went down, quickly, and not the
                    // tail end of a drag (seek/brightness/volume) that was already handled live.
                    val isTap = !draggingSeek &&
                        abs(event.x - tapDownX) < TAP_SLOP_PX &&
                        abs(event.y - tapDownY) < TAP_SLOP_PX &&
                        (event.eventTime - tapDownTime) < TAP_MAX_DURATION_MS
                    commitSeekDrag()
                    if (isTap) handleTap(event.x)
                }

                MotionEvent.ACTION_CANCEL -> commitSeekDrag()
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
     * [FEATURE UPDATE] Accumulative multi-tap seek state: consecutive taps in the same edge zone,
     * within [MULTI_TAP_TIMEOUT_MS] of each other, each add another ±[SEEK_STEP_MS] — so 2 taps
     * = ±10s, 3 taps = ±20s, 4 taps = ±30s, and so on. A tap elsewhere, or the timeout firing,
     * resets the streak.
     */
    private var multiTapZone: TapZone? = null
    private var multiTapCount = 0
    private val multiTapResetRunnable = Runnable {
        multiTapZone = null
        multiTapCount = 0
    }

    /**
     * [FEATURE UPDATE] Dispatches a raw tap ([x] in view coordinates) to the left/right
     * accumulative-seek zones, or treats a middle tap as play/pause (2nd tap) / show-hide
     * controls (1st tap) — mirroring the old single/double-tap behavior but resolved instantly
     * rather than waiting out Android's double-tap confirmation window.
     */
    private fun handleTap(x: Float) {
        val width = binding.playerRoot.width
        val zone = when {
            x < width * SEEK_ZONE_FRACTION -> TapZone.LEFT
            x > width * (1f - SEEK_ZONE_FRACTION) -> TapZone.RIGHT
            else -> TapZone.MIDDLE
        }

        handler.removeCallbacks(multiTapResetRunnable)
        if (zone == multiTapZone) multiTapCount++ else {
            multiTapZone = zone
            multiTapCount = 1
        }

        when (zone) {
            TapZone.LEFT, TapZone.RIGHT -> {
                if (multiTapCount >= 2) {
                    seekBy(if (zone == TapZone.RIGHT) SEEK_STEP_MS else -SEEK_STEP_MS, showControls = false)
                } else {
                    toggleControls()
                }
            }

            TapZone.MIDDLE -> {
                if (multiTapCount >= 2) {
                    togglePlayPause()
                    multiTapCount = 0
                } else {
                    toggleControls()
                }
            }
        }
        handler.postDelayed(multiTapResetRunnable, MULTI_TAP_TIMEOUT_MS)
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

        private var draggingBrightness = false
        private var draggingVolume = false

        override fun onDown(e: MotionEvent): Boolean {
            draggingBrightness = false
            draggingVolume = false
            draggingSeek = false
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            if (e1 == null) return false
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
                    seekDragStartPositionMs = mediaPlayer?.time ?: 0L
                } else {
                    val leftHalf = e1.x < binding.playerRoot.width / 2f
                    // Gesture rework — brightness (left) is disabled on TVs; there the whole surface
                    // is a volume slider instead.
                    if (leftHalf && !isTv) draggingBrightness = true else draggingVolume = true
                }
            }
            if (draggingSeek) {
                updateSeekPreview(dx)
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
     * [FEATURE UPDATE] Live preview for an in-progress horizontal drag: [totalDx] is the
     * cumulative distance from where the finger first touched down (screen-width relative),
     * mapped onto [SEEK_DRAG_RANGE_MS] of timeline per full swipe. Only updates [seekPreviewTargetMs]
     * and the OSD — the actual seek happens once on release, via [commitSeekDrag].
     */
    private fun updateSeekPreview(totalDx: Float) {
        val player = mediaPlayer ?: return
        val length = player.length
        val maxTime = if (length > 0) length else Long.MAX_VALUE
        val fraction = totalDx / binding.playerRoot.width
        val deltaMs = (fraction * SEEK_DRAG_RANGE_MS).toLong()
        seekPreviewTargetMs = (seekDragStartPositionMs + deltaMs).coerceIn(0L, maxTime)
        val actualDeltaMs = seekPreviewTargetMs - seekDragStartPositionMs
        val deltaLabel = formatTime(abs(actualDeltaMs))
        showIndicator(
            getString(
                if (actualDeltaMs >= 0) R.string.player_gesture_seek_drag_forward
                else R.string.player_gesture_seek_drag_backward,
                deltaLabel
            )
        )
    }

    /** [FEATURE UPDATE] Applies the previewed target position once the drag gesture ends. */
    private fun commitSeekDrag() {
        if (!draggingSeek) return
        draggingSeek = false
        // RULE B — defense in depth: even if a drag was already in flight when the stream flipped
        // live (e.g. mid-buffering), never commit the seek.
        if (isLiveStream()) {
            scheduleHideControls()
            return
        }
        mediaPlayer?.time = seekPreviewTargetMs
        scheduleHideControls()
    }

    /**
     * Step 10.3 — clamp-safe relative seek with a transient indicator.
     * [showControls] — double-tap seek (touch) should show only the +10s/-10s indicator, not the
     * full title/seekbar panel; D-pad seek uses [seekSilently] instead, which never shows the panel.
     */
    private fun seekBy(deltaMs: Long, showControls: Boolean = true) {
        val player = mediaPlayer ?: return
        // RULE B — touch-based edge-zone seek is blocked for live the same as D-pad seek.
        if (blockIfLiveSeek()) return
        val length = player.length
        val target = (player.time + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        player.time = target
        val seconds = (abs(deltaMs) / 1000).toInt()
        showIndicator(
            getString(
                if (deltaMs >= 0) R.string.player_gesture_seek_forward
                else R.string.player_gesture_seek_backward,
                seconds
            )
        )
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
        val player = mediaPlayer ?: return
        // RULE B — the primary named trigger: D-pad Left/Right 10s/30s seek is fully blocked live.
        if (blockIfLiveSeek()) return
        val length = player.length
        // [BUG FIX promt7] STEP_2 — accumulate from the last captured target rather than the (possibly
        // stale) live player.time, so rapid consecutive presses add up correctly instead of collapsing
        // back onto an old, not-yet-committed position.
        val base = if (dpadSeeking) dpadSeekTargetMs else player.time
        val target = (base + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        // STEP_1 — suspend the ticker's write-back so the async seek can't be overridden mid-flight.
        dpadSeeking = true
        dpadSeekTargetMs = target
        // STEP_3 — commit the explicit player seek.
        player.time = target
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
        val next = (appVolume + (fraction * VOLUME_BOOSTED)).toInt()
        setVolume(next)
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
        mediaPlayer?.rate = TURBO_RATE
        showIndicator(getString(R.string.player_speed_holding))
    }

    private fun stopTurbo() {
        if (!turboActive) return
        turboActive = false
        mediaPlayer?.rate = speedBeforeTurbo
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
        binding.controlsOverlay.visibility = View.GONE
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
            mediaPlayer?.pause()
            handler.removeCallbacks(progressTicker)
            // [BUG FIX promt7] Don't leave a D-pad seek's settle guard armed across a background stop.
            handler.removeCallbacks(dpadSeekSettleTimeout)
            dpadSeeking = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resumeScope.cancel()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { player ->
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
        }
        mediaPlayer = null
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
        val playlistId: Long
    )

    companion object {
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
        private const val EXTRA_QUEUE_INDEX = "extra_queue_index"

        /** promt5 — when > 0, resume the opened video directly at this offset, skipping the prompt. */
        private const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"

        private const val VOLUME_NORMAL = 100
        private const val VOLUME_BOOSTED = 200
        private const val VOLUME_KEY_STEP = 5
        private const val TURBO_RATE = 2.0f

        /** Step 11.2 — selectable speeds (0.5x–2.0x). */
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        private const val CONTROLS_TIMEOUT_MS = 3500L
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

        /** [FEATURE UPDATE] Movement/time bounds for a raw touch-up to still count as a tap. */
        private const val TAP_SLOP_PX = 24f
        private const val TAP_MAX_DURATION_MS = 250L

        /** [FEATURE UPDATE] Left/right fraction of the screen width that triggers seek taps. */
        private const val SEEK_ZONE_FRACTION = 0.3f

        /** [FEATURE UPDATE] Silence window that ends a multi-tap seek streak. */
        private const val MULTI_TAP_TIMEOUT_MS = 900L

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
            val urls = ArrayList(channels.map { it.url })
            val titles = ArrayList(channels.map { it.name })
            val referrers = ArrayList(channels.map { it.httpReferrer.orEmpty() })
            val userAgents = ArrayList(channels.map { it.httpUserAgent.orEmpty() })
            val playlistIds = channels.map { it.playlistId }.toLongArray()
            return Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_QUEUE_URLS, urls)
                .putStringArrayListExtra(EXTRA_QUEUE_TITLES, titles)
                .putStringArrayListExtra(EXTRA_QUEUE_REFERRERS, referrers)
                .putStringArrayListExtra(EXTRA_QUEUE_USER_AGENTS, userAgents)
                // promt5 — per-item playlist id, the resume-isolation key.
                .putExtra(EXTRA_QUEUE_PLAYLIST_IDS, playlistIds)
                .putExtra(EXTRA_QUEUE_INDEX, startIndex)
                // promt5 — forced resume offset; -1 means "no forced resume" (normal prompt flow).
                .putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs)
        }

        /** Convenience overload for launching a single channel with no Next/Previous queue. */
        fun newIntent(context: Context, channel: Channel, resumePositionMs: Long = -1L): Intent =
            newIntent(context, listOf(channel), 0, resumePositionMs)
    }
}
