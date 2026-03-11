package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.WorkoutType

/**
 * Per-workout-type coaching parameters.
 *
 * Coaching intent by type (from spec Section 3.3):
 *   SWEET_SPOT / THRESHOLD — pacing precision. Tighter tolerances, earlier over-target protection.
 *   VO2_MAX              — execution and completion. Wider early tolerance, strong late encouragement.
 *   OVER_UNDER           — phase precision. Suppress generic "on target"; cue over/under phase intent.
 *   ENDURANCE_SURGES     — base discipline. Less aggressive micro-corrections during short surges.
 *   RECOVERY_RIDE        — strict ceiling, minimal motivational pressure.
 *   UNKNOWN              — falls through to current default behavior (no regression).
 */
data class WorkoutTypePolicy(
    /**
     * % above target ceiling before powerAboveTarget fires (e.g. 10 = 10%).
     * Tighter for precision workouts, wider for VO2max where early ramp overshoot is normal.
     */
    val overTargetTolerancePct: Int,

    /**
     * % below target floor before powerBelowTarget fires.
     * Wider for VO2max (let rider find their own pace early), tighter for sweet spot.
     */
    val underTargetTolerancePct: Int,

    /**
     * Seconds into effort before powerBelowTarget/hrBelowTarget fires.
     * Longer settle time for VO2max (HR/power needs ramp time).
     */
    val settleTimeSec: Int,

    /**
     * Suppression window (seconds) for powerOnTarget/hrOnTarget.
     * Shorter = more frequent positive reinforcement (sweet spot).
     * Longer = quieter (VO2max, over-under).
     */
    val onTargetSuppressionSec: Int,

    /**
     * Minimum cadence for cadenceDropping rule.
     * Harder efforts typically need higher cadence.
     */
    val minCadenceRpm: Int,

    /**
     * Number of effort sets before power_fading_trend fires.
     * VO2max: 3 sets (needs data). Threshold: 2 sets (longer efforts = earlier signal).
     */
    val fadingTrendMinSets: Int,

    /**
     * Seconds into effort before powerOnTarget / hrOnTarget fires (positive reinforcement window).
     * Separate from settleTimeSec because power settle and "settled enough to praise" differ.
     * Old code used 55s for powerOnTarget (window 55-95s) and 85s for hrOnTarget (85-125s).
     */
    val positiveWindowStartSec: Int,

    /**
     * Human-readable coaching emphasis for the AI prompt.
     * One concise sentence describing what the LLM should prioritise for this workout type.
     */
    val aiEmphasis: String,
) {
    companion object {
        /**
         * Default when type is UNKNOWN — matches previous hardcoded values exactly.
         * positiveWindowStartSec = 55 → powerOnTarget fires at [55..95], hrOnTarget at [85..125].
         * hrBelowTarget uses settleTimeSec(30) + 60 = 90s, matching old hardcoded value.
         */
        val DEFAULT = WorkoutTypePolicy(
            overTargetTolerancePct = 10,
            underTargetTolerancePct = 10,
            settleTimeSec = 30,
            onTargetSuppressionSec = 300,
            minCadenceRpm = 75,
            fadingTrendMinSets = 3,
            positiveWindowStartSec = 55,
            aiEmphasis = "Coach to interval targets. Balance intensity with sustainable pacing.",
        )

        val SWEET_SPOT = WorkoutTypePolicy(
            overTargetTolerancePct = 7,
            underTargetTolerancePct = 8,
            settleTimeSec = 30,
            onTargetSuppressionSec = 240,
            minCadenceRpm = 80,
            fadingTrendMinSets = 2,
            positiveWindowStartSec = 50,
            aiEmphasis = "Sweet spot workout. Prioritise pacing consistency and steady adherence to target. 'Hold steady. Don't drift up.'",
        )

        val THRESHOLD = WorkoutTypePolicy(
            overTargetTolerancePct = 5,
            underTargetTolerancePct = 8,
            settleTimeSec = 30,
            onTargetSuppressionSec = 240,
            minCadenceRpm = 80,
            fadingTrendMinSets = 2,
            positiveWindowStartSec = 50,
            aiEmphasis = "Threshold workout. Strictly enforce ceiling — early overcooking destroys the set. 'Don't overcook early.'",
        )

        val VO2_MAX = WorkoutTypePolicy(
            overTargetTolerancePct = 15,
            underTargetTolerancePct = 12,
            settleTimeSec = 45,
            onTargetSuppressionSec = 360,
            minCadenceRpm = 85,
            fadingTrendMinSets = 3,
            positiveWindowStartSec = 60,
            aiEmphasis = "VO2max workout. Short all-out efforts. Encourage completion over perfection. Recovery quality between reps is critical.",
        )

        val OVER_UNDER = WorkoutTypePolicy(
            overTargetTolerancePct = 8,
            underTargetTolerancePct = 8,
            settleTimeSec = 25,
            onTargetSuppressionSec = 400,
            minCadenceRpm = 80,
            fadingTrendMinSets = 3,
            positiveWindowStartSec = 40,
            aiEmphasis = "Over-under workout. Precision across threshold. Cue over-phase vs under-phase explicitly — rider must know which side they're on.",
        )

        val ENDURANCE_SURGES = WorkoutTypePolicy(
            overTargetTolerancePct = 12,
            underTargetTolerancePct = 10,
            settleTimeSec = 20,
            onTargetSuppressionSec = 300,
            minCadenceRpm = 75,
            fadingTrendMinSets = 3,
            positiveWindowStartSec = 35,
            aiEmphasis = "Endurance with surges. Base discipline between surges is the main goal. After each spike: 'Back to Z2. Settle.'",
        )

        val RECOVERY_RIDE = WorkoutTypePolicy(
            overTargetTolerancePct = 3,
            underTargetTolerancePct = 50,
            settleTimeSec = 60,
            onTargetSuppressionSec = 600,
            minCadenceRpm = 70,
            fadingTrendMinSets = 10,
            positiveWindowStartSec = 90,
            aiEmphasis = "Recovery ride. Strictly enforce Z1 ceiling. Minimal motivational pressure — the goal is rest, not performance.",
        )

        fun forType(type: WorkoutType): WorkoutTypePolicy = when (type) {
            WorkoutType.SWEET_SPOT -> SWEET_SPOT
            WorkoutType.THRESHOLD -> THRESHOLD
            WorkoutType.VO2_MAX -> VO2_MAX
            WorkoutType.OVER_UNDER -> OVER_UNDER
            WorkoutType.ENDURANCE_SURGES -> ENDURANCE_SURGES
            WorkoutType.RECOVERY_RIDE -> RECOVERY_RIDE
            WorkoutType.UNKNOWN -> DEFAULT
        }
    }
}
