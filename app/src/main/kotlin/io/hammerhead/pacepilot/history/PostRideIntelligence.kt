package io.hammerhead.pacepilot.history

import kotlinx.serialization.Serializable

@Serializable
data class PostRideInsight(
    val generatedAtEpochMs: Long,
    val rideTimestampMs: Long,
    val summary: String,
    val timeline: List<String>,
    val patterns: List<String>,
)

object PostRideIntelligence {
    fun build(history: RideHistory, latestRide: RideSummary): PostRideInsight {
        val summary = buildString {
            append(
                "Ride done: ${(latestRide.durationSec / 60)} min, " +
                    "${"%.1f".format(latestRide.distanceKm)} km, " +
                    "NP ${latestRide.normalizedPower}W."
            )
            if (latestRide.wasStructuredWorkout) {
                append(" Workout compliance ${(latestRide.avgIntervalComplianceScore * 100).toInt()}%.")
            }
            if (latestRide.hrDecouplingPct > 4f) {
                append(" Cardiac drift ${latestRide.hrDecouplingPct.toInt()}% suggests fatigue.")
            } else {
                append(" Decoupling stayed controlled.")
            }
        }

        val timeline = mutableListOf<String>().apply {
            add("Peak power ${latestRide.maxPowerWatts}W, peak HR ${latestRide.maxHrBpm}bpm.")
            add("Dominant zone across history: Z${history.dominantPowerZone}.")
            if (latestRide.powerFadingDetected) add("Late-ride power fade was detected.")
            if (latestRide.avgHrRecoveryRateBpmPerSec > 0f) {
                add("Recovery rate ${"%.2f".format(latestRide.avgHrRecoveryRateBpmPerSec)} bpm/s.")
            }
            // AI coaching stats
            if (latestRide.alertsFired > 0) {
                val aiRate = if (latestRide.alertsFired > 0) {
                    (latestRide.aiUpgrades * 100 / latestRide.alertsFired)
                } else 0
                add("Coaching: ${latestRide.alertsFired} alerts, $aiRate% AI-upgraded.")
                if (latestRide.aiFailures > 0 && latestRide.aiUpgrades == 0) {
                    add("No AI connection — all alerts were rule-based.")
                } else if (latestRide.aiFailures > latestRide.aiUpgrades) {
                    add("Intermittent AI connectivity — ${latestRide.aiFailures} fallbacks.")
                }
            }
        }

        val patterns = mutableListOf<String>().apply {
            if (history.recentRideCount >= 3 && history.frequentPowerFading) {
                add("Pattern: frequent interval power fading over recent rides.")
            }
            if (history.avgHrDecoupling > 5f) {
                add("Pattern: elevated HR:power decoupling trend (fatigue accumulation).")
            }
            if (history.avgComplianceScore < 0.8f) {
                add("Pattern: interval compliance below target; start efforts more conservatively.")
            }
            if (isEmpty()) {
                add("No negative trend detected yet. Keep consistency.")
            }
        }

        return PostRideInsight(
            generatedAtEpochMs = System.currentTimeMillis(),
            rideTimestampMs = latestRide.timestamp,
            summary = summary,
            timeline = timeline,
            patterns = patterns,
        )
    }
}

