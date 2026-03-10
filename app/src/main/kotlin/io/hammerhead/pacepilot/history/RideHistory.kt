package io.hammerhead.pacepilot.history

import kotlinx.serialization.Serializable

/**
 * Summary stats from a single completed ride, persisted to disk.
 */
@Serializable
data class RideSummary(
    val timestamp: Long,                   // epoch ms
    val durationSec: Long,
    val distanceKm: Float,
    val elevationGainM: Float,

    val avgPowerWatts: Int,
    val normalizedPower: Int,
    val maxPowerWatts: Int,
    val ftpAtTime: Int,
    val avgHrBpm: Int,
    val maxHrBpm: Int,

    // Zone time distribution (% of ride time in each zone, index 0 = Z1)
    val powerZoneTimePct: List<Float> = emptyList(),  // 7 zones
    val hrZoneTimePct: List<Float> = emptyList(),      // 5 zones

    // Fatigue / quality signals
    val powerFadingDetected: Boolean = false,
    val hrDecouplingPct: Float = 0f,           // avg HR:power decoupling
    val avgHrRecoveryRateBpmPerSec: Float = 0f, // how fast HR dropped in recoveries

    // Workout compliance
    val wasStructuredWorkout: Boolean = false,
    val avgIntervalComplianceScore: Float = 1f, // 0-1

    // Effort sets (avg watts per effort interval, in order)
    val effortSetAvgPowers: List<Int> = emptyList(),
)

/**
 * Rolling window of recent ride summaries, plus derived stats.
 * Max [MAX_RIDES] rides are kept; oldest are dropped.
 */
@Serializable
data class RideHistory(
    val rides: List<RideSummary> = emptyList(),
) {
    companion object {
        const val MAX_RIDES = 30
    }

    fun withNewRide(summary: RideSummary): RideHistory {
        val updated = (rides + summary).takeLast(MAX_RIDES)
        return copy(rides = updated)
    }

    // ----------------------------------------------------------------
    // Derived stats used to personalise LLM prompts
    // ----------------------------------------------------------------

    val recentRideCount: Int get() = rides.size

    /** Avg NP across all rides with a workout, as proxy for fitness baseline */
    val avgNormalizedPower: Int get() =
        rides.filter { it.normalizedPower > 0 }
            .map { it.normalizedPower }
            .average()
            .let { if (it.isNaN()) 0 else it.toInt() }

    /** Typical HR recovery rate (bpm/sec) after hard efforts */
    val typicalHrRecoveryRate: Float get() =
        rides.filter { it.avgHrRecoveryRateBpmPerSec > 0 }
            .map { it.avgHrRecoveryRateBpmPerSec }
            .average()
            .let { if (it.isNaN()) 0f else it.toFloat() }

    /** Average HR decoupling % — higher = more fatigue over long rides */
    val avgHrDecoupling: Float get() =
        rides.filter { it.hrDecouplingPct > 0 }
            .map { it.hrDecouplingPct }
            .average()
            .let { if (it.isNaN()) 0f else it.toFloat() }

    /** How often intervals were completed within target (0-1) */
    val avgComplianceScore: Float get() =
        rides.filter { it.wasStructuredWorkout }
            .map { it.avgIntervalComplianceScore }
            .average()
            .let { if (it.isNaN()) 1f else it.toFloat() }

    /** True if power fading was detected in >40% of recent structured workouts */
    val frequentPowerFading: Boolean get() {
        val workouts = rides.filter { it.wasStructuredWorkout }
        if (workouts.size < 3) return false
        return workouts.count { it.powerFadingDetected }.toFloat() / workouts.size > 0.4f
    }

    /** Dominant zone by time (1-7) over recent rides */
    val dominantPowerZone: Int get() {
        if (rides.isEmpty()) return 2
        val perZone = (0 until 7).map { z ->
            val values = rides.mapNotNull { r -> r.powerZoneTimePct.getOrNull(z) }
            if (values.isEmpty()) 0.0 else values.average()
        }
        return (perZone.indexOfMax() + 1).coerceIn(1, 7)
    }

    /** Days since last ride (rough — based on timestamp delta) */
    val daysSinceLastRide: Int get() {
        val last = rides.maxByOrNull { it.timestamp } ?: return 99
        return ((System.currentTimeMillis() - last.timestamp) / 86_400_000L).toInt()
    }
}

private fun List<Double>.indexOfMax(): Int {
    var maxIdx = 0
    var maxVal = Double.MIN_VALUE
    forEachIndexed { i, v -> if (v > maxVal) { maxVal = v; maxIdx = i } }
    return maxIdx
}
