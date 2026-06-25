package com.madvulcan.gpsagentbridge.data

import kotlinx.serialization.Serializable

/**
 * All user-configurable settings for the app.
 *
 * Defaults are tuned per the requirement doc + the desktop repo's recommendation
 * of 5-10 minute intervals for the max-interval fallback.
 *
 * - [distanceThresholdMeters]: how far the user must move before a transmission fires.
 *   Default 500 m. Range 50..5000 m.
 * - [maxIntervalMinutes]: even if the user hasn't moved, fire at most this often.
 *   Default 10 min (lowered from the doc's 30 min after reviewing the desktop repo —
 *   the desktop reads from gpsd every 30s, and walking 100m to a neighbor would
 *   otherwise leave the agent stale for up to 30 min). Range 5..120 min.
 * - [minAccuracyMeters]: ignore fixes whose accuracy is worse than this. Default 20 m.
 *   Range 1..200 m. Acts as a *gate* on incoming fixes (see TransmissionEngine).
 * - [dryRun]: if true, run the engine as normal (UI updates, distance tracking, etc.)
 *   but don't actually transmit. Useful for first-time users to validate their
 *   distance/interval settings before pointing at a real gpsd.
 * - [detailedNotification]: if true, foreground service notification shows live
 *   coordinates + last tx time. If false, shows a minimal "running" notice.
 *   (The notification itself is non-negotiable — required by foreground service law.)
 * - [autoStartOnBoot]: if true, the boot-completed receiver starts the streaming
 *   service automatically on device reboot.
 */
@Serializable
data class Settings(
    val targets: List<ServerTarget> = listOf(
        ServerTarget(host = "100.92.209.24", port = 2948, label = "")
    ),
    val distanceThresholdMeters: Int = 500,
    val maxIntervalMinutes: Int = 10,
    val minAccuracyMeters: Int = 20,
    val dryRun: Boolean = false,
    val detailedNotification: Boolean = true,
    val autoStartOnBoot: Boolean = false,
) {
    /**
     * Coerce all numeric fields into their valid ranges. Called by [SettingsRepository]
     * when reading from DataStore, so that stale values from an older app version
     * (or hand-edited DataStore) don't crash the app.
     *
     * Note: we don't use `require()` here because that would throw on bad persisted
     * state — instead we silently clamp. The setters in [SettingsRepository] enforce
     * bounds on writes.
     */
    fun sanitized(): Settings = copy(
        distanceThresholdMeters = distanceThresholdMeters.coerceIn(DISTANCE_MIN, DISTANCE_MAX),
        maxIntervalMinutes = maxIntervalMinutes.coerceIn(INTERVAL_MIN, INTERVAL_MAX),
        minAccuracyMeters = minAccuracyMeters.coerceIn(ACCURACY_MIN, ACCURACY_MAX),
    )

    /** Convenience: max interval as a millis value, for use with timers. */
    val maxIntervalMillis: Long get() = maxIntervalMinutes * 60_000L

    companion object {
        const val DISTANCE_MIN = 50
        const val DISTANCE_MAX = 5000
        const val INTERVAL_MIN = 5
        const val INTERVAL_MAX = 120
        const val ACCURACY_MIN = 1
        const val ACCURACY_MAX = 200
    }
}
