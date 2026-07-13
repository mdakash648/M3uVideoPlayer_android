package com.mdaksh.m3uvideoplayer.data.time

import com.mdaksh.m3uvideoplayer.data.preferences.TimeZonePreference
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the *effective* display time zone from the user's [TimeZonePreference] and formats
 * trusted-time epochs into human-readable strings.
 *
 * Only display is affected — scheduling never touches this class (it's elapsed-duration based via
 * [TrustedTimeSource]). Auto mode resolves to the **IP-detected** timezone from
 * [TimeZoneDetector] (not the device's system timezone, which is often wrong on Android TV boxes).
 * Manual mode uses the pinned IANA id, falling back to the detected zone if that id is invalid.
 */
@Singleton
class TimeZoneManager @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val timeZoneDetector: TimeZoneDetector
) {

    /**
     * The current display preference, resolved to a concrete [ResolvedTimeZone] for the UI.
     * Combines the user's saved preference with the auto-detected zone so that any change to
     * either (e.g. a fresh IP detection completes) re-emits the resolved value.
     */
    val resolvedTimeZone: Flow<ResolvedTimeZone> =
        combine(
            userPreferencesRepository.timeZonePreferenceFlow,
            timeZoneDetector.detectedZone
        ) { pref, autoZone ->
            resolve(pref, autoZone)
        }

    /** Format [epochMs] (from [TrustedTime]) as a localized date+time in the given [zone]. */
    fun formatDateTime(epochMs: Long, zone: ZoneId): String =
        DATE_TIME_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(epochMs))

    private fun resolve(pref: TimeZonePreference, autoZone: ZoneId): ResolvedTimeZone {
        val zone = when (pref.mode) {
            TimeZoneMode.AUTO -> autoZone
            TimeZoneMode.MANUAL -> pref.zoneId
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: autoZone
        }
        return ResolvedTimeZone(mode = pref.mode, zone = zone)
    }

    companion object {
        private val DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
    }
}

/**
 * The concrete zone the app is currently displaying times in, plus how it was chosen.
 *
 * [zone] is always a valid [ZoneId] (Auto/IP-detected or the user's Manual pick); [mode] drives the
 * "(Auto)" / "(Manual)" suffix shown in Settings.
 */
data class ResolvedTimeZone(
    val mode: TimeZoneMode,
    val zone: ZoneId
) {
    /** The IANA id string, e.g. "Asia/Dhaka". */
    val id: String get() = zone.id
}
