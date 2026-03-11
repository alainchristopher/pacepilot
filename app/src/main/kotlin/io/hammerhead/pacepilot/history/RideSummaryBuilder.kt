package io.hammerhead.pacepilot.history

import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.telemetry.HrAnalyzer
import io.hammerhead.pacepilot.telemetry.PowerAnalyzer
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * Builds a RideSummary from the final RideContext snapshot + analyzers at ride end.
 */
object RideSummaryBuilder {

    fun build(
        ctx: RideContext,
        powerAnalyzer: PowerAnalyzer,
        hrAnalyzer: HrAnalyzer,
        // Zone time tracking (seconds per zone, accumulated during ride)
        powerZoneTimeSec: IntArray,  // size 7
        hrZoneTimeSec: IntArray,     // size 5
        peakHrBpm: Int,
        peakPowerWatts: Int,
    ): RideSummary {
        val totalSec = ctx.rideElapsedSec.toInt().coerceAtLeast(1)

        val powerZonePct = powerZoneTimeSec.map { it.toFloat() / totalSec }
        val hrZonePct = hrZoneTimeSec.map { it.toFloat() / totalSec }

        val effortAvgs = powerAnalyzer.effortSetAverages()
        val complianceScore = powerAnalyzer.complianceScore()

        return RideSummary(
            timestamp = System.currentTimeMillis(),
            durationSec = ctx.rideElapsedSec,
            distanceKm = ctx.distanceKm,
            elevationGainM = ctx.elevationGainM,
            avgPowerWatts = powerAnalyzer.rideAvgPower,
            normalizedPower = ctx.normalizedPower,
            maxPowerWatts = peakPowerWatts,
            ftpAtTime = ctx.ftp,
            avgHrBpm = hrAnalyzer.rideAvgHr,
            maxHrBpm = peakHrBpm,
            powerZoneTimePct = powerZonePct,
            hrZoneTimePct = hrZonePct,
            powerFadingDetected = powerAnalyzer.isPowerFading(),
            hrDecouplingPct = ctx.hrDecouplingPct,
            avgHrRecoveryRateBpmPerSec = hrAnalyzer.avgRecoveryRate(),
            wasStructuredWorkout = ctx.workout.isActive || ctx.workout.completedEffortCount > 0,
            avgIntervalComplianceScore = complianceScore,
            effortSetAvgPowers = effortAvgs,
        )
    }
}
