package io.hammerhead.pacepilot.util

/**
 * 7-zone Coggan power model (% FTP):
 * Z1 Active Recovery  < 55%
 * Z2 Endurance        55–75%
 * Z3 Tempo            76–90%
 * Z4 Threshold        91–105%
 * Z5 VO2max           106–120%
 * Z6 Anaerobic        121–150%
 * Z7 Neuromuscular    > 150%
 */
object ZoneCalculator {

    // ------------------------------------------------------------------
    // Power zones (Coggan 7-zone)
    // ------------------------------------------------------------------

    private val POWER_ZONE_UPPER_PCT = listOf(54, 75, 90, 105, 120, 150, Int.MAX_VALUE)

    fun powerZone(watts: Int, ftp: Int): Int {
        if (ftp <= 0 || watts <= 0) return 0
        val pct = watts * 100 / ftp
        return POWER_ZONE_UPPER_PCT.indexOfFirst { pct <= it } + 1
    }

    fun powerZoneLowerWatts(zone: Int, ftp: Int): Int = when (zone) {
        1 -> 0
        2 -> ftp * 55 / 100
        3 -> ftp * 76 / 100
        4 -> ftp * 91 / 100
        5 -> ftp * 106 / 100
        6 -> ftp * 121 / 100
        7 -> ftp * 151 / 100
        else -> 0
    }

    fun powerZoneUpperWatts(zone: Int, ftp: Int): Int = when (zone) {
        1 -> ftp * 54 / 100
        2 -> ftp * 75 / 100
        3 -> ftp * 90 / 100
        4 -> ftp * 105 / 100
        5 -> ftp * 120 / 100
        6 -> ftp * 150 / 100
        7 -> Int.MAX_VALUE
        else -> Int.MAX_VALUE
    }

    // ------------------------------------------------------------------
    // HR zones (5-zone model using % max HR)
    // ------------------------------------------------------------------

    private val HR_ZONE_UPPER_PCT = listOf(60, 70, 80, 90, Int.MAX_VALUE)

    fun hrZone(bpm: Int, maxHr: Int): Int {
        if (maxHr <= 0 || bpm <= 0) return 0
        val pct = bpm * 100 / maxHr
        return HR_ZONE_UPPER_PCT.indexOfFirst { pct <= it } + 1
    }

    fun hrZoneLowerBpm(zone: Int, maxHr: Int): Int = when (zone) {
        1 -> 0
        2 -> maxHr * 61 / 100
        3 -> maxHr * 71 / 100
        4 -> maxHr * 81 / 100
        5 -> maxHr * 91 / 100
        else -> 0
    }

    fun hrZoneUpperBpm(zone: Int, maxHr: Int): Int = when (zone) {
        1 -> maxHr * 60 / 100
        2 -> maxHr * 70 / 100
        3 -> maxHr * 80 / 100
        4 -> maxHr * 90 / 100
        5 -> maxHr
        else -> maxHr
    }

    // ------------------------------------------------------------------
    // Normalised Power (30-sec rolling average, then RMS)
    // NP = 4th root of the mean of (30s avg power)^4
    // ------------------------------------------------------------------

    fun normalizedPower(thirtySecAvgPowers: List<Int>): Int {
        if (thirtySecAvgPowers.isEmpty()) return 0
        val mean4th = thirtySecAvgPowers.map { it.toDouble().let { p -> p * p * p * p } }.average()
        return Math.pow(mean4th, 0.25).toInt()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** True if watts is within [low*(1-tolerance), high*(1+tolerance)] */
    fun isInTargetRange(watts: Int, targetLow: Int?, targetHigh: Int?, tolerancePct: Float = 0f): Boolean {
        val low = targetLow ?: return true
        val high = targetHigh ?: low
        return watts >= (low * (1f - tolerancePct)).toInt() &&
            watts <= (high * (1f + tolerancePct)).toInt()
    }

    /** How many watts over the target ceiling (positive = over, negative = under) */
    fun wattsOverCeiling(watts: Int, targetHigh: Int?): Int {
        if (targetHigh == null) return 0
        return watts - targetHigh
    }

    /** How many watts under the target floor (positive = under, negative = over) */
    fun wattsBelowFloor(watts: Int, targetLow: Int?): Int {
        if (targetLow == null) return 0
        return targetLow - watts
    }
}
