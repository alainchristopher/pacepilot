package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * All 13 workout coaching rules as pure functions.
 * Each function: (RideContext) -> CoachingEvent?
 * Returns null if the condition is not met.
 */
object WorkoutCoachingRules {

    // ------------------------------------------------------------------
    // Pre-interval rules
    // ------------------------------------------------------------------

    /**
     * 1. pre_interval_alert — effort starts in 60-90 seconds.
     * Fires when the current phase is RECOVERY or WARMUP and remaining time is 60-90s.
     */
    fun preIntervalAlert(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null

        val inTransitionPhase = ws.currentPhase == IntervalPhase.RECOVERY ||
            ws.currentPhase == IntervalPhase.WARMUP
        if (!inTransitionPhase) return null

        val nextPhase = ws.nextPhase ?: return null
        if (nextPhase != IntervalPhase.EFFORT) return null

        val remainingSec = ws.intervalRemainingSec
        if (remainingSec !in 55..95) return null  // 55-95s window (5s slop for tick timing)

        val isFirst = ctx.inFirstIntervalOfSession
        val message = if (isFirst) "First effort block. Settle in — don't overcook."
        else "Effort in ${remainingSec}s. Get ready."

        return CoachingEvent(
            ruleId = if (isFirst) RuleId.FIRST_INTERVAL else RuleId.PRE_INTERVAL_ALERT,
            message = message,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * 2. pre_interval_fueling — effort approaching + NomRide carb deficit exists.
     */
    fun preIntervalFueling(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null

        val inTransitionPhase = ws.currentPhase == IntervalPhase.RECOVERY ||
            ws.currentPhase == IntervalPhase.WARMUP
        if (!inTransitionPhase) return null

        val nextPhase = ws.nextPhase ?: return null
        if (nextPhase != IntervalPhase.EFFORT) return null

        // Only fire if effort is within 90 seconds
        if (ws.intervalRemainingSec > 90) return null

        val deficit = ctx.carbDeficitGrams
        if (deficit < 10) return null

        val sinceLastEat = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec else ctx.rideElapsedSec
        if (sinceLastEat < 1200 && deficit < 20) return null // recently fueled, low deficit

        return CoachingEvent(
            ruleId = RuleId.PRE_INTERVAL_FUELING,
            message = "Fuel now. ${deficit}g deficit. Hard effort coming.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 300,
        )
    }

    // ------------------------------------------------------------------
    // During effort rules
    // ------------------------------------------------------------------

    /**
     * 3. power_above_target — 30s avg > target ceiling + 10% for 30s.
     */
    fun powerAboveTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        val ceiling = ws.targetHigh ?: return null
        val threshold = ceiling + (ceiling * 10 / 100) // 10% above ceiling

        val avg30s = ctx.power30sAvg
        if (avg30s <= threshold) return null

        val overBy = avg30s - ceiling
        return CoachingEvent(
            ruleId = RuleId.POWER_ABOVE_TARGET,
            message = "Ease back. ${overBy}W above target.",
            priority = CoachingPriority.CRITICAL,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * 4. power_below_target — 30s avg < target floor - 10% for 30s.
     */
    fun powerBelowTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        val floor = ws.targetLow ?: return null
        val threshold = floor - (floor * 10 / 100) // 10% below floor

        val avg30s = ctx.power30sAvg
        if (avg30s >= threshold) return null

        // Don't fire in first 30s of interval (rider may still be ramping up)
        if (ws.intervalElapsedSec < 30) return null

        val belowBy = floor - avg30s
        return CoachingEvent(
            ruleId = RuleId.POWER_BELOW_TARGET,
            message = "Push harder. ${belowBy}W below target.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * 5. power_on_target — positive reinforcement when in range (sparse, 1x per interval).
     */
    fun powerOnTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        val floor = ws.targetLow ?: return null
        val ceiling = ws.targetHigh ?: floor

        val avg30s = ctx.power30sAvg
        if (!ZoneCalculator.isInTargetRange(avg30s, floor, ceiling)) return null

        // Only fire 60-90 seconds into an effort (once settled)
        if (ws.intervalElapsedSec !in 55..95) return null

        return CoachingEvent(
            ruleId = RuleId.POWER_ON_TARGET,
            message = "Good. Hold ${avg30s}W.",
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 300, // once per interval roughly
        )
    }

    /**
     * 6. interval_countdown — 30 seconds remaining in current effort interval.
     */
    fun intervalCountdown(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null

        val remaining = ws.intervalRemainingSec
        if (remaining !in 25..35) return null // ±5s window

        return CoachingEvent(
            ruleId = RuleId.INTERVAL_COUNTDOWN,
            message = "30 sec left. Hold.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * Bonus: cadence_dropping — cadence below minimum during effort.
     */
    fun cadenceDropping(ctx: RideContext, minCadence: Int = 75): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ctx.cadenceRpm <= 0) return null // no cadence sensor
        if (ctx.cadenceRpm >= minCadence) return null
        // Only trigger if below 80% of minimum (hard drop, not just slight)
        if (ctx.cadenceRpm > minCadence * 80 / 100) return null

        return CoachingEvent(
            ruleId = RuleId.CADENCE_DROPPING,
            message = "Cadence dropping. Shift lighter.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * hr_ceiling_exceeded — HR too high during HR-based workout effort.
     * 5bpm grace above ceiling to account for sensor lag.
     */
    fun hrCeilingExceeded(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null

        val ceiling = ws.targetHigh ?: return null
        if (ctx.heartRateBpm <= ceiling + 5) return null

        val overBy = ctx.heartRateBpm - ceiling
        return CoachingEvent(
            ruleId = RuleId.HR_CEILING_EXCEEDED,
            message = "HR ${overBy}bpm above zone. Ease off.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * hr_below_target — HR too low during HR-based workout effort.
     * Rider isn't generating enough effort — needs to push more.
     * Only fires after 90s (HR needs time to climb).
     */
    fun hrBelowTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null
        if (ws.intervalElapsedSec < 90) return null  // HR needs time to rise

        val floor = ws.targetLow ?: return null
        val margin = floor - (floor * 5 / 100)  // 5% below floor before alerting
        if (ctx.heartRateBpm >= margin) return null

        val belowBy = floor - ctx.heartRateBpm
        return CoachingEvent(
            ruleId = RuleId.HR_BELOW_TARGET,
            message = "HR ${belowBy}bpm below zone. Pick it up.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 120,
        )
    }

    /**
     * hr_on_target — positive reinforcement for HR-based workouts.
     * Fires once per effort interval when HR is in the target range.
     */
    fun hrOnTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null

        val floor = ws.targetLow ?: return null
        val ceiling = ws.targetHigh ?: floor

        if (ctx.heartRateBpm < floor || ctx.heartRateBpm > ceiling) return null

        // Only fire 90-120s into effort (HR settled)
        if (ws.intervalElapsedSec !in 85..125) return null

        return CoachingEvent(
            ruleId = RuleId.HR_ON_TARGET,
            message = "HR locked in at ${ctx.heartRateBpm}bpm. Hold it.",
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 300,
        )
    }

    // ------------------------------------------------------------------
    // Recovery interval rules
    // ------------------------------------------------------------------

    /**
     * 7. recovery_not_recovering — power still in Z3+ during recovery interval.
     */
    fun recoveryNotRecovering(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.RECOVERY) return null

        // Only fire after 30s in recovery (rider may take time to back off)
        if (ws.intervalElapsedSec < 30) return null

        val z3Lower = ZoneCalculator.powerZoneLowerWatts(3, ctx.ftp)
        if (ctx.power30sAvg < z3Lower) return null

        return CoachingEvent(
            ruleId = RuleId.RECOVERY_NOT_RECOVERING,
            message = "Actually recover. Drop to Z1.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 120,
        )
    }

    /**
     * 8. hr_not_dropping — HR elevated 60s into recovery interval.
     */
    fun hrNotDropping(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.RECOVERY) return null
        if (ctx.heartRateBpm <= 0) return null

        if (ws.intervalElapsedSec < 60) return null // only after 60s

        // HR should have dropped to at least Z3 or below
        val z4Lower = ZoneCalculator.hrZoneLowerBpm(4, ctx.maxHr)
        if (ctx.heartRateBpm < z4Lower) return null

        return CoachingEvent(
            ruleId = RuleId.HR_NOT_DROPPING,
            message = "HR still high. Keep spinning easy.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 120,
        )
    }

    /**
     * 9. recovery_fueling_window — good time to fuel during recovery interval.
     */
    fun recoveryFuelingWindow(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.RECOVERY) return null

        // Only in meaningful recovery windows (>60s remaining)
        if (ws.intervalRemainingSec < 60) return null

        // Don't prompt if recently fueled (within 20min)
        val sinceLastEat = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec else ctx.rideElapsedSec
        if (sinceLastEat < 1200) return null

        // Fire in the middle of recovery (not at start/end)
        if (ws.intervalElapsedSec !in 30..90) return null

        val deficitMsg = if (ctx.carbDeficitGrams > 10) " ${ctx.carbDeficitGrams}g deficit." else ""
        return CoachingEvent(
            ruleId = RuleId.RECOVERY_FUELING_WINDOW,
            message = "Good time to fuel.${deficitMsg}",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 300,
        )
    }

    // ------------------------------------------------------------------
    // Session-level rules
    // ------------------------------------------------------------------

    /**
     * 10. power_fading_trend — avg power declining across sets.
     */
    fun powerFadingTrend(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (!ws.powerFadingTrend) return null
        if (ws.effortAvgPowers.size < 3) return null // need at least 3 sets

        return CoachingEvent(
            ruleId = RuleId.POWER_FADING_TREND,
            message = "Power fading. Consider stopping after this rep.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 600, // once per session
        )
    }

    /**
     * 11. recovery_quality_declining — HR recovery rate slowing set-over-set.
     */
    fun recoveryQualityDeclining(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (!ws.recoveryQualityDeclining) return null
        if (ws.recoveryDropRates.size < 2) return null

        return CoachingEvent(
            ruleId = RuleId.RECOVERY_QUALITY_DECLINING,
            message = "Recovery slowing. Cut the session or reduce intensity.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 600, // once per session
        )
    }

    /**
     * 12. session_complete — all intervals done, transition to endurance.
     */
    fun sessionComplete(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (ws.currentPhase != IntervalPhase.COOLDOWN) return null

        // Fire once when entering cooldown
        if (ws.intervalElapsedSec > 10) return null // only at start of cooldown

        val completedCount = ws.completedEffortCount
        val msg = if (completedCount > 0)
            "Session done. Nice work. Fuel within 20 min."
        else
            "Intervals done. Easy riding from here."

        return CoachingEvent(
            ruleId = RuleId.SESSION_COMPLETE,
            message = msg,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 3600, // once per ride
        )
    }

    /**
     * 13. last_interval_motivation — final effort block encouragement.
     */
    fun lastIntervalMotivation(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null

        // Is this the last effort? Check if there's no more effort intervals after this
        val nextPhase = ws.nextPhase
        val isLastEffort = nextPhase == IntervalPhase.COOLDOWN ||
            nextPhase == null ||
            (ws.currentStep == ws.totalSteps - 2)

        if (!isLastEffort) return null

        // Only fire at the start of the last interval
        if (ws.intervalElapsedSec > 15) return null

        val recoverySlow = ws.recoveryQualityDeclining
        val message = when {
            recoverySlow -> "Final block. Recovery was slower — steady start."
            else -> "Final block. You have the fitness."
        }

        return CoachingEvent(
            ruleId = RuleId.LAST_INTERVAL_MOTIVATION,
            message = message,
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 3600,
        )
    }

    // ------------------------------------------------------------------
    // Convenience: evaluate all rules for current workout context
    // ------------------------------------------------------------------

    fun evaluateAll(ctx: RideContext, minCadence: Int = 75): List<CoachingEvent> =
        listOfNotNull(
            preIntervalAlert(ctx),
            preIntervalFueling(ctx),
            powerAboveTarget(ctx),
            powerBelowTarget(ctx),
            powerOnTarget(ctx),
            intervalCountdown(ctx),
            cadenceDropping(ctx, minCadence),
            hrCeilingExceeded(ctx),
            hrBelowTarget(ctx),
            hrOnTarget(ctx),
            recoveryNotRecovering(ctx),
            hrNotDropping(ctx),
            recoveryFuelingWindow(ctx),
            powerFadingTrend(ctx),
            recoveryQualityDeclining(ctx),
            sessionComplete(ctx),
            lastIntervalMotivation(ctx),
        )
}
