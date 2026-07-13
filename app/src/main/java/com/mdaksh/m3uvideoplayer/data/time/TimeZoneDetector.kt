package com.mdaksh.m3uvideoplayer.data.time

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the user's actual timezone from their IP address, independent of the device's
 * system timezone setting. This is the "Auto" behaviour — cheap Android TV / Firestick boxes
 * often ship with a wrong system timezone, so we query a geolocation API to determine the
 * real timezone based on the network the device is connected to.
 *
 * Falls back to [ZoneId.systemDefault] when every endpoint fails (offline, etc.).
 */
@Singleton
class TimeZoneDetector @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val mutex = Mutex()

    private val _detectedZone = MutableStateFlow(ZoneId.systemDefault())

    /** The most recently detected timezone. Starts with the device default, updated after [detect]. */
    val detectedZone: StateFlow<ZoneId> = _detectedZone.asStateFlow()

    /**
     * Query geolocation APIs to determine the timezone from the device's public IP.
     * Thread-safe, de-duplicated. Never throws — returns the best available zone.
     */
    suspend fun detect(): ZoneId = mutex.withLock {
        withContext(Dispatchers.IO) {
            for ((url, parser) in ENDPOINTS) {
                val zone = tryEndpoint(url, parser)
                if (zone != null) {
                    _detectedZone.value = zone
                    Log.i(TAG, "Auto timezone detected: ${zone.id}")
                    return@withContext zone
                }
            }
            Log.w(TAG, "All timezone detection endpoints failed; using device default: ${ZoneId.systemDefault()}")
            val fallback = ZoneId.systemDefault()
            _detectedZone.value = fallback
            fallback
        }
    }

    private fun tryEndpoint(url: String, parser: (String) -> String?): ZoneId? = try {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            val tzId = parser(body) ?: return@use null
            runCatching { ZoneId.of(tzId) }.getOrNull()
        }
    } catch (e: Exception) {
        Log.d(TAG, "Timezone detection from $url failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "TimeZoneDetector"

        /**
         * Endpoints tried in order. Each pair is (URL, body-parser-returning-IANA-zone-id).
         * We use multiple providers for resilience; all are free, no-auth, no-key APIs.
         */
        private val ENDPOINTS: List<Pair<String, (String) -> String?>> = listOf(
            // worldtimeapi.org — returns JSON with a "timezone" field like "Asia/Dhaka"
            "http://worldtimeapi.org/api/ip" to { body ->
                runCatching { JSONObject(body).optString("timezone").takeIf { it.isNotBlank() } }.getOrNull()
            },
            // ip-api.com — returns JSON with a "timezone" field like "Asia/Dhaka"
            "http://ip-api.com/json/?fields=timezone" to { body ->
                runCatching { JSONObject(body).optString("timezone").takeIf { it.isNotBlank() } }.getOrNull()
            }
        )
    }
}
