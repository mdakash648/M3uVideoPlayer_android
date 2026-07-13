package com.mdaksh.m3uvideoplayer.domain.model

/**
 * How the app decides which time zone to *display* times in.
 *
 * Note: this only affects the presentation of times (e.g. "last synced at 10:30 PM"). Playlist
 * update scheduling is based on elapsed real duration from
 * [com.mdaksh.m3uvideoplayer.data.time.TrustedTimeSource], never on local calendar time, so
 * switching zones can never break the refresh schedule.
 */
enum class TimeZoneMode {
    /**
     * Auto-detect timezone from the device's IP address via geolocation APIs. This is the default
     * and is independent of the device's system clock — important for Android TV / Firestick boxes
     * that often ship with a wrong system timezone.
     */
    AUTO,

    /** Use a zone the user explicitly picked — useful for TV boxes with no reliable locale data. */
    MANUAL;

    companion object {
        val DEFAULT = AUTO

        /** Safe parse from a persisted name, falling back to [DEFAULT] for unknown/legacy values. */
        fun fromName(name: String?): TimeZoneMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
