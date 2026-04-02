package io.hammerhead.pacepilot.state

import android.content.Context

data class ActiveRideSnapshot(
    val savedAtEpochSec: Long,
    val rideElapsedSec: Long,
    val modeName: String,
    val silencedUntilSec: Long,
    val peakPowerWatts: Int,
    val peakHrBpm: Int,
    val powerZoneTimesCsv: String,
    val hrZoneTimesCsv: String,
)

/**
 * Lightweight snapshot persistence for active rides.
 *
 * Purpose: survive process death / service restarts without losing
 * suppression windows and high-level ride context.
 */
class ActiveRideStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(snapshot: ActiveRideSnapshot) {
        prefs.edit()
            .putLong(KEY_SAVED_AT, snapshot.savedAtEpochSec)
            .putLong(KEY_ELAPSED, snapshot.rideElapsedSec)
            .putString(KEY_MODE, snapshot.modeName)
            .putLong(KEY_SILENCE_UNTIL, snapshot.silencedUntilSec)
            .putInt(KEY_PEAK_POWER, snapshot.peakPowerWatts)
            .putInt(KEY_PEAK_HR, snapshot.peakHrBpm)
            .putString(KEY_POWER_ZONE_TIMES, snapshot.powerZoneTimesCsv)
            .putString(KEY_HR_ZONE_TIMES, snapshot.hrZoneTimesCsv)
            .putBoolean(KEY_HAS_SNAPSHOT, true)
            .commit()
    }

    fun load(): ActiveRideSnapshot? {
        if (!prefs.getBoolean(KEY_HAS_SNAPSHOT, false)) return null
        return ActiveRideSnapshot(
            savedAtEpochSec = prefs.getLong(KEY_SAVED_AT, 0L),
            rideElapsedSec = prefs.getLong(KEY_ELAPSED, 0L),
            modeName = prefs.getString(KEY_MODE, "ADAPTIVE") ?: "ADAPTIVE",
            silencedUntilSec = prefs.getLong(KEY_SILENCE_UNTIL, 0L),
            peakPowerWatts = prefs.getInt(KEY_PEAK_POWER, 0),
            peakHrBpm = prefs.getInt(KEY_PEAK_HR, 0),
            powerZoneTimesCsv = prefs.getString(KEY_POWER_ZONE_TIMES, "") ?: "",
            hrZoneTimesCsv = prefs.getString(KEY_HR_ZONE_TIMES, "") ?: "",
        )
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "pacepilot_active_ride_state"
        private const val KEY_HAS_SNAPSHOT = "has_snapshot"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_ELAPSED = "elapsed"
        private const val KEY_MODE = "mode"
        private const val KEY_SILENCE_UNTIL = "silence_until"
        private const val KEY_PEAK_POWER = "peak_power"
        private const val KEY_PEAK_HR = "peak_hr"
        private const val KEY_POWER_ZONE_TIMES = "power_zone_times"
        private const val KEY_HR_ZONE_TIMES = "hr_zone_times"
    }
}

