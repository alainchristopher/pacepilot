package io.hammerhead.pacepilot.workout

import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.WorkoutState
import io.hammerhead.pacepilot.model.WorkoutType

/**
 * Classifies the workout type from the observed workout state.
 * Uses interval structure heuristics — target power zones and durations.
 */
object WorkoutTypeClassifier {

    /**
     * Classify from a completed workout state snapshot.
     * Needs FTP to derive zone from target watts.
     */
    fun classify(state: WorkoutState, ftp: Int): WorkoutType {
        if (!state.isActive || state.totalSteps == 0) return WorkoutType.UNKNOWN
        if (ftp <= 0) return WorkoutType.UNKNOWN

        val targetLow = state.targetLow ?: return WorkoutType.UNKNOWN
        val targetHigh = state.targetHigh ?: targetLow

        val avgTargetPct = (targetLow + targetHigh) / 2 * 100 / ftp
        val durationSec = estimateEffortDuration(state)
        val totalSteps = state.totalSteps

        return classifyFromMetrics(
            avgTargetPct = avgTargetPct,
            effortDurationSec = durationSec,
            totalIntervals = totalSteps,
            targetLow = targetLow,
            targetHigh = targetHigh,
            ftp = ftp,
        )
    }

    fun classifyFromMetrics(
        avgTargetPct: Int,
        effortDurationSec: Int,
        totalIntervals: Int,
        targetLow: Int,
        targetHigh: Int,
        ftp: Int,
    ): WorkoutType {
        val rangeWidth = targetHigh - targetLow
        val isNarrowRange = rangeWidth <= ftp * 5 / 100   // < 5% FTP range = precision target
        val isOverUnder = targetLow < ftp * 96 / 100 && targetHigh > ftp * 104 / 100

        return when {
            // Recovery: Z1 only, low power ceiling
            avgTargetPct < 55 -> WorkoutType.RECOVERY_RIDE

            // Over/under: straddles threshold (e.g. 95-115% FTP)
            isOverUnder -> WorkoutType.OVER_UNDER

            // VO2max: Z5+, short intervals 2-5 min
            avgTargetPct > 105 && effortDurationSec in 60..360 -> WorkoutType.VO2_MAX

            // Threshold: Z4, longer intervals 8-20 min
            avgTargetPct in 91..105 && effortDurationSec in 480..1200 -> WorkoutType.THRESHOLD

            // Sweet spot: Z3-Z4, intervals 8+ min
            avgTargetPct in 76..95 && effortDurationSec >= 480 -> WorkoutType.SWEET_SPOT

            // Endurance surges: base Z2 + shorter high efforts
            avgTargetPct in 55..75 && totalIntervals > 6 -> WorkoutType.ENDURANCE_SURGES

            // Default fallback: sweet spot or threshold
            avgTargetPct in 76..105 -> WorkoutType.SWEET_SPOT

            else -> WorkoutType.UNKNOWN
        }
    }

    /** Estimate effort interval duration from remaining time + step position heuristic */
    private fun estimateEffortDuration(state: WorkoutState): Int {
        // During an effort interval, remaining gives us part of the duration
        return when (state.currentPhase) {
            IntervalPhase.EFFORT -> state.intervalRemainingSec + state.intervalElapsedSec
            else -> 0
        }
    }
}
