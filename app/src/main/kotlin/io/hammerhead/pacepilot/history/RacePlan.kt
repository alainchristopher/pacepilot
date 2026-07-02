package io.hammerhead.pacepilot.history

import kotlinx.serialization.Serializable

/** Persisted race-day pacing + fueling plan (e.g. Ironman 70.3 bike leg). */
@Serializable
data class RacePlan(
    val enabled: Boolean = false,
    /** Intensity factor vs FTP when [targetWatts] is 0 */
    val targetIf: Float = 0.82f,
    /** Explicit target watts; 0 = derive from IF × FTP at ride time */
    val targetWatts: Int = 0,
    /** Planned bike leg duration (minutes) */
    val durationMin: Int = 150,
    /** Planned bike distance (km) — 70.3 ≈ 90 */
    val distanceKm: Float = 90f,
    /** Race-day carb target g/h */
    val carbGramsPerHour: Int = 75,
    /** Optional course label */
    val eventName: String = "Ironman 70.3 Kraków",
    val courseNotes: String = "",
) {
    fun resolveTargetWatts(ftp: Int): Int {
        if (targetWatts > 0) return targetWatts
        if (ftp <= 0) return 0
        return (ftp * targetIf).toInt()
    }

    /** First segment for negative-split pacing (km) */
    val negativeSplitThroughKm: Float = 40f

    /** Watts to hold in opening segment (slightly under target) */
    fun openingTargetWatts(ftp: Int): Int {
        val base = resolveTargetWatts(ftp)
        return (base * 0.97f).toInt().coerceAtLeast(0)
    }
}
