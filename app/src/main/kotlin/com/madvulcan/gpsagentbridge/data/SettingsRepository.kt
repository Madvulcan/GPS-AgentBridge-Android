package com.madvulcan.gpsagentbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * DataStore-backed persistence for [Settings].
 *
 * Replaces the SharedPreferences approach from the original requirements doc with
 * Preferences DataStore, which has safer concurrent-write semantics and is the
 * official recommended replacement.
 *
 * The [Settings.targets] list is serialized to a JSON string under TARGETS_KEY
 * using kotlinx.serialization. This is simpler than modeling each target as a
 * separate DataStore entry, and the list rarely grows beyond a handful of entries.
 */
class SettingsRepository(context: Context) {

    private val ds: DataStore<Preferences> = context.applicationContext.dataStore

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            targets = parseTargets(p[TARGETS_KEY]),
            distanceThresholdMeters = p[DISTANCE_KEY] ?: Settings().distanceThresholdMeters,
            maxIntervalMinutes = p[INTERVAL_KEY] ?: Settings().maxIntervalMinutes,
            minAccuracyMeters = p[ACCURACY_KEY] ?: Settings().minAccuracyMeters,
            dryRun = p[DRY_RUN_KEY] ?: false,
            detailedNotification = p[DETAILED_NOTIF_KEY] ?: true,
            autoStartOnBoot = p[AUTO_START_KEY] ?: false,
        ).sanitized()
    }

    suspend fun update(transform: (Settings) -> Settings) {
        ds.edit { p ->
            val current = Settings(
                targets = parseTargets(p[TARGETS_KEY]),
                distanceThresholdMeters = p[DISTANCE_KEY] ?: Settings().distanceThresholdMeters,
                maxIntervalMinutes = p[INTERVAL_KEY] ?: Settings().maxIntervalMinutes,
                minAccuracyMeters = p[ACCURACY_KEY] ?: Settings().minAccuracyMeters,
                dryRun = p[DRY_RUN_KEY] ?: false,
                detailedNotification = p[DETAILED_NOTIF_KEY] ?: true,
                autoStartOnBoot = p[AUTO_START_KEY] ?: false,
            )
            val next = transform(current)
            p[TARGETS_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ServerTarget.serializer()),
                next.targets,
            )
            p[DISTANCE_KEY] = next.distanceThresholdMeters
            p[INTERVAL_KEY] = next.maxIntervalMinutes
            p[ACCURACY_KEY] = next.minAccuracyMeters
            p[DRY_RUN_KEY] = next.dryRun
            p[DETAILED_NOTIF_KEY] = next.detailedNotification
            p[AUTO_START_KEY] = next.autoStartOnBoot
        }
    }

    suspend fun setTargets(targets: List<ServerTarget>) = update { it.copy(targets = targets) }
    suspend fun setDistanceThreshold(meters: Int) = update { it.copy(distanceThresholdMeters = meters.coerceIn(Settings.DISTANCE_MIN, Settings.DISTANCE_MAX)) }
    suspend fun setMaxInterval(minutes: Int) = update { it.copy(maxIntervalMinutes = minutes.coerceIn(Settings.INTERVAL_MIN, Settings.INTERVAL_MAX)) }
    suspend fun setMinAccuracy(meters: Int) = update { it.copy(minAccuracyMeters = meters.coerceIn(Settings.ACCURACY_MIN, Settings.ACCURACY_MAX)) }
    suspend fun setDryRun(enabled: Boolean) = update { it.copy(dryRun = enabled) }
    suspend fun setDetailedNotification(enabled: Boolean) = update { it.copy(detailedNotification = enabled) }
    suspend fun setAutoStartOnBoot(enabled: Boolean) = update { it.copy(autoStartOnBoot = enabled) }

    // ------------------------------------------------------------------ helpers

    private fun parseTargets(raw: String?): List<ServerTarget> {
        if (raw.isNullOrBlank()) return Settings().targets
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ServerTarget.serializer()),
                raw,
            )
        } catch (t: Throwable) {
            // Corrupt JSON — fall back to defaults rather than crash.
            Settings().targets
        }
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "gps_agent_bridge_settings"
        )

        private val TARGETS_KEY = stringPreferencesKey("targets_json")
        private val DISTANCE_KEY = intPreferencesKey("distance_threshold_m")
        private val INTERVAL_KEY = intPreferencesKey("max_interval_min")
        private val ACCURACY_KEY = intPreferencesKey("min_accuracy_m")
        private val DRY_RUN_KEY = booleanPreferencesKey("dry_run")
        private val DETAILED_NOTIF_KEY = booleanPreferencesKey("detailed_notif")
        private val AUTO_START_KEY = booleanPreferencesKey("auto_start_boot")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
