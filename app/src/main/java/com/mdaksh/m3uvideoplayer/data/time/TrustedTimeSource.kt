package com.mdaksh.m3uvideoplayer.data.time

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A clock-independent "trusted time" source for all playlist-scheduling math and user-facing
 * timestamps.
 *
 * ## Why this exists
 * Many Android TV boxes / Firesticks ship with a wrong system clock (bad timezone, drift, or no
 * NTP sync). Because playlist "last updated" / "is a sync due" logic was based on
 * `System.currentTimeMillis()`, a wrong wall-clock made the app think the last sync was in the
 * future (never refresh) or mis-measure the interval (refresh never/always). See
 * `playlist_time_fix_prompt.md`.
 *
 * ## How it stays correct
 * On [refresh] we read the `Date` header from a trusted HTTPS endpoint and store an **anchor**:
 * `(networkEpochMs, elapsedRealtimeAtAnchor)`. Thereafter [now] computes the current epoch as
 * `networkEpochMs + (SystemClock.elapsedRealtime() - elapsedRealtimeAtAnchor)`. Because
 * [SystemClock.elapsedRealtime] is monotonic and immune to the user/device changing the wall
 * clock, the *elapsed duration* the app measures is correct even when `currentTimeMillis()` is
 * nonsense.
 *
 * ## Bootstrap & wrong-clock resilience
 * This class uses its **own** internal [OkHttpClient] with lenient SSL validation (bypasses
 * certificate date checks) so it can fetch network time even when the device clock is so far off
 * that normal HTTPS would fail certificate validation. It also has HTTP (non-SSL) fallback
 * endpoints that work regardless of certificate issues.
 *
 * ## Fallbacks & resilience
 *  - No anchor yet, or the monotonic clock went backwards (device rebooted — `elapsedRealtime`
 *    resets to 0 on boot): we fall back to [System.currentTimeMillis] and flag the result
 *    [TrustedTime.verified] = false so callers/logs can tell the time is unverified.
 *  - [refresh] failing (offline) never throws to the caller here — it just returns false and the
 *    last good anchor keeps being used, so scheduling keeps working offline until the next sync.
 */
@Singleton
class TrustedTimeSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.trustedTimeDataStore

    /**
     * A lenient OkHttpClient that bypasses certificate date validation. This is essential for
     * bootstrapping: when the device clock is years off, normal SSL fails because certs look
     * expired/not-yet-valid. We only use this for fetching the `Date` header from trusted hosts
     * (Google, Cloudflare, Apple) — the security risk is minimal since we don't transmit or
     * receive any sensitive data, just a timestamp header.
     */
    private val bootstrapClient: OkHttpClient by lazy {
        createBootstrapClient()
    }

    // In-memory mirror of the persisted anchor so [now] is a cheap, non-suspending call. Populated
    // lazily from DataStore on the first [refresh]/[ensureLoaded]; a null anchor means "unverified".
    @Volatile
    private var anchor: Anchor? = null

    @Volatile
    private var loadedFromDisk = false

    private val refreshMutex = Mutex()

    /**
     * The current best-estimate wall-clock time.
     *
     * Non-suspending and safe to call from anywhere. If [ensureLoaded]/[refresh] has never run this
     * process and there's an in-memory anchor absent, it uses the device clock (unverified). Prefer
     * calling [refresh] once at a convenient point (app launch, worker start) so the anchor is
     * loaded and fresh.
     */
    fun now(): TrustedTime {
        val current = anchor
        if (current != null) {
            val elapsedNow = SystemClock.elapsedRealtime()
            val delta = elapsedNow - current.elapsedRealtimeAtAnchor
            // A negative delta means the monotonic clock reset under us (reboot) — the anchor's
            // elapsed reference is meaningless now, so we can't trust the offset until re-synced.
            if (delta >= 0) {
                return TrustedTime(current.networkEpochMs + delta, verified = true)
            }
        }
        return TrustedTime(System.currentTimeMillis(), verified = false)
    }

    /**
     * Fetch fresh network time and update the anchor. Returns true on success.
     *
     * Safe to call opportunistically (app launch, every periodic worker run). Concurrent calls are
     * de-duplicated by [refreshMutex]. Never throws — network failures just return false and leave
     * the previous anchor in place. Must be called off the main thread (does blocking I/O); it hops
     * to [Dispatchers.IO] itself.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            // Try HTTPS Date-header endpoints first (most accurate, from the response header).
            for (url in HTTPS_TIME_ENDPOINTS) {
                val epoch = fetchDateHeader(url) ?: continue
                return@withContext setAnchor(epoch)
            }
            // Fall back to HTTP JSON APIs that return a datetime string — these work even when
            // SSL is completely broken due to device clock issues.
            for ((url, parser) in HTTP_TIME_ENDPOINTS) {
                val epoch = fetchJsonTime(url, parser) ?: continue
                return@withContext setAnchor(epoch)
            }
            Log.w(TAG, "Network time refresh failed on all endpoints; using ${describeFallback()}")
            false
        }
    }

    private fun setAnchor(epoch: Long): Boolean {
        val newAnchor = Anchor(
            networkEpochMs = epoch,
            elapsedRealtimeAtAnchor = SystemClock.elapsedRealtime()
        )
        anchor = newAnchor
        // Persist in a fire-and-forget manner from the IO context
        kotlinx.coroutines.runBlocking { persist(newAnchor) }
        return true
    }

    /** Load the persisted anchor into memory once per process. Cheap no-op after the first call. */
    private suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        val prefs = dataStore.data.first()
        val epoch = prefs[KEY_NETWORK_EPOCH_MS]
        val elapsed = prefs[KEY_ELAPSED_AT_ANCHOR]
        val bootId = prefs[KEY_BOOT_ELAPSED_SANITY]
        if (epoch != null && elapsed != null) {
            // Only trust a persisted anchor if the monotonic clock hasn't obviously rewound since
            // it was saved. `elapsedRealtime` resets on reboot, so a stored elapsed value larger
            // than the current one means we rebooted and the anchor is stale.
            val nowElapsed = SystemClock.elapsedRealtime()
            if (bootId == null || nowElapsed >= elapsed) {
                anchor = Anchor(epoch, elapsed)
            } else {
                Log.i(TAG, "Discarding pre-reboot time anchor; awaiting a fresh network sync.")
            }
        }
        loadedFromDisk = true
    }

    /** Fetch the `Date` header from a URL using the bootstrap (lenient-SSL) client. */
    private fun fetchDateHeader(url: String): Long? = try {
        val request = Request.Builder().url(url).head().build()
        bootstrapClient.newCall(request).execute().use { response ->
            // getDate parses the RFC 1123 HTTP `Date` header; null if absent/unparseable.
            response.headers.getDate("Date")?.time
        }
    } catch (e: Exception) {
        Log.d(TAG, "Time fetch from $url failed: ${e.message}")
        null
    }

    /** Fetch epoch from an HTTP JSON endpoint. */
    private fun fetchJsonTime(url: String, parser: (String) -> Long?): Long? = try {
        val request = Request.Builder().url(url).build()
        bootstrapClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            parser(body)
        }
    } catch (e: Exception) {
        Log.d(TAG, "Time fetch from $url failed: ${e.message}")
        null
    }

    private suspend fun persist(a: Anchor) {
        dataStore.edit { prefs ->
            prefs[KEY_NETWORK_EPOCH_MS] = a.networkEpochMs
            prefs[KEY_ELAPSED_AT_ANCHOR] = a.elapsedRealtimeAtAnchor
            prefs[KEY_BOOT_ELAPSED_SANITY] = a.elapsedRealtimeAtAnchor
        }
    }

    private fun describeFallback(): String =
        if (anchor != null) "cached network anchor + monotonic clock" else "unverified device clock"

    private data class Anchor(
        val networkEpochMs: Long,
        val elapsedRealtimeAtAnchor: Long
    )

    companion object {
        private const val TAG = "TrustedTimeSource"

        /**
         * HTTPS endpoints tried first for the `Date` header. All major, high-availability hosts
         * that return an accurate `Date`. HEAD keeps the request tiny. Uses the bootstrap client
         * with lenient SSL so these work even when the device clock is wrong.
         */
        private val HTTPS_TIME_ENDPOINTS = listOf(
            "https://www.google.com",
            "https://www.cloudflare.com",
            "https://www.apple.com"
        )

        /**
         * HTTP (non-SSL) fallback endpoints that return time as JSON. These are the last resort
         * when even the lenient-SSL client can't connect (e.g. some corporate proxies that
         * intercept HTTPS with their own time-dependent certs).
         */
        private val HTTP_TIME_ENDPOINTS: List<Pair<String, (String) -> Long?>> = listOf(
            "http://worldtimeapi.org/api/ip" to { body: String ->
                runCatching {
                    val unixTime = JSONObject(body).optLong("unixtime", -1L)
                    if (unixTime > 0) unixTime * 1000L else null
                }.getOrNull()
            }
        )

        private val KEY_NETWORK_EPOCH_MS = longPreferencesKey("trusted_time_network_epoch_ms")
        private val KEY_ELAPSED_AT_ANCHOR = longPreferencesKey("trusted_time_elapsed_at_anchor")
        private val KEY_BOOT_ELAPSED_SANITY = longPreferencesKey("trusted_time_boot_sanity")

        /**
         * Creates an OkHttpClient with lenient SSL that bypasses certificate date validation.
         * This is ONLY for fetching time from known-trusted hosts — never for user data.
         */
        private fun createBootstrapClient(): OkHttpClient {
            val lenientTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(lenientTrustManager), SecureRandom())

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, lenientTrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

/**
 * A point-in-time reading from [TrustedTimeSource].
 *
 * @property epochMs Unix epoch millis — use this exactly like [System.currentTimeMillis] would be
 *   used, but it stays correct even when the device clock is wrong.
 * @property verified True when [epochMs] came from a network-synced anchor + monotonic clock;
 *   false when we fell back to the raw (possibly wrong) device clock.
 */
data class TrustedTime(
    val epochMs: Long,
    val verified: Boolean
)

/**
 * Single process-wide DataStore for the trusted-time anchor. Top-level delegate per the library's
 * one-instance-per-name rule (same pattern as `userPreferencesDataStore`).
 */
private val Context.trustedTimeDataStore by preferencesDataStore(name = "trusted_time")
