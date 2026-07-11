package com.mdaksh.m3uvideoplayer.domain.model

/**
 * How often a playlist should re-sync from its source.
 *
 * [repeatMillis] is the period for a WorkManager `PeriodicWorkRequest`; `null` means the option has
 * no periodic schedule ([ON_START] runs once per app launch, [NEVER] never runs). The declaration
 * order matches the on-screen dropdown (`R.array.update_frequency_options`) so the enum ordinal maps
 * 1:1 to the selected index.
 */
enum class UpdateFrequency(val repeatMillis: Long?) {
    ON_START(null),
    EVERY_6_HOURS(6L * 60 * 60 * 1000),
    EVERY_12_HOURS(12L * 60 * 60 * 1000),
    EVERY_3_DAYS(3L * 24 * 60 * 60 * 1000),
    EVERY_WEEK(7L * 24 * 60 * 60 * 1000),
    NEVER(null);

    /** True for the fixed-interval options that back a periodic worker. */
    val isPeriodic: Boolean get() = repeatMillis != null

    companion object {
        val DEFAULT = NEVER

        /** Safe parse from a persisted name, falling back to [DEFAULT] for unknown/legacy values. */
        fun fromName(name: String?): UpdateFrequency =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
