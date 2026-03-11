package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutType
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * All workout coaching rules as pure functions, now workout-type-aware.
 * Each function derives a [WorkoutTypePolicy] from ctx.workout.workoutType
 * and uses its parameters for thresholds, settle times, and suppression windows.
 *
 * When workoutType is UNKNOWN, WorkoutTypePolicy.DEFAULT is used — identical
 * to previous hardcoded behavior. No regression.
 */
object WorkoutCoachingRules {

    // ------------------------------------------------------------------
    // Pre-interval rules (not type-dependent)
    // ------------------------------------------------------------------

    /**
     * 1. pre_interval_alert — effort starts in 60-90 seconds.
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
        if (remainingSec !in 55..95) return null

        val isFirst = ctx.inFirstIntervalOfSession
        val message = if (isFirst) {
            when (ws.workoutType) {
                WorkoutType.VO2_MAX -> "First rep. Go hard — VO2max."
                WorkoutType.THRESHOLD -> "First block. Don't overcook early."
                WorkoutType.RECOVERY_RIDE -> "Easy effort. Stay Z1."
                else -> "First block. Settle in, don't overcook."
            }
        } else {
            "Effort in ${remainingSec}s. Get ready."
        }

        return CoachingEvent(
            ruleId = if (isFirst) RuleId.FIRST_INTERVAL else RuleId.PRE_INTERVAL_ALERT,
            message = message,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * 2. pre_interval_fueling — effort approaching + carb deficit exists.
     */
    fun preIntervalFueling(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (ws.workoutType == WorkoutType.RECOVERY_RIDE) return null // no urgency on recovery

        val inTransitionPhase = ws.currentPhase == IntervalPhase.RECOVERY ||
            ws.currentPhase == IntervalPhase.WARMUP
        if (!inTransitionPhase) return null

        val nextPhase = ws.nextPhase ?: return null
        if (nextPhase != IntervalPhase.EFFORT) return null

        if (ws.intervalRemainingSec > 90) return null

        val deficit = ctx.carbDeficitGrams
        if (deficit < 10) return null

        val sinceLastEat = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec else ctx.rideElapsedSec
        if (sinceLastEat < 1200 && deficit < 20) return null

        return CoachingEvent(
            ruleId = RuleId.PRE_INTERVAL_FUELING,
            message = "Fuel now. ${deficit}g deficit. Hard effort coming.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 300,
        )
    }

    // ------------------------------------------------------------------
    // During effort rules — type-aware
    // ------------------------------------------------------------------

    /**
     * 3. power_above_target — 30s avg > target ceiling + tolerance%.
     * Tolerance is type-dependent: tighter for THRESHOLD/SWEET_SPOT, wider for VO2_MAX.
     */
    fun powerAboveTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        val ceiling = ws.targetHigh ?: return null
        if (ceiling <= 0) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        val threshold = ceiling + (ceiling * policy.overTargetTolerancePct / 100)

        val avg30s = ctx.power30sAvg
        if (avg30s <= threshold) return null

        val overBy = avg30s - ceiling
        val message = when (ws.workoutType) {
            WorkoutType.THRESHOLD -> "Back off. ${overBy}W over — precision matters."
            WorkoutType.SWEET_SPOT -> "Ease back. ${overBy}W above sweet spot."
            WorkoutType.VO2_MAX -> "Too high. Drop ${overBy}W."
            WorkoutType.OVER_UNDER -> "Over phase done. Drop ${overBy}W."
            WorkoutType.RECOVERY_RIDE -> "Too hard. ${overBy}W over Z1. Ease off."
            else -> "Ease back. ${overBy}W above target."
        }

        return CoachingEvent(
            ruleId = RuleId.POWER_ABOVE_TARGET,
            message = message,
            priority = CoachingPriority.CRITICAL,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * 4. power_below_target — 30s avg < target floor - tolerance%.
     * Settle time is type-dependent: longer for VO2_MAX (short ramp), shorter for THRESHOLD.
     */
    fun powerBelowTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        // Never penalise going easy on recovery
        if (ws.workoutType == WorkoutType.RECOVERY_RIDE) return null

        val floor = ws.targetLow ?: return null
        if (floor <= 0) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        val threshold = floor - (floor * policy.underTargetTolerancePct / 100)

        val avg30s = ctx.power30sAvg
        if (avg30s >= threshold) return null

        if (ws.intervalElapsedSec < policy.settleTimeSec) return null

        val belowBy = floor - avg30s
        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "Push harder. ${belowBy}W below. Dig in."
            WorkoutType.THRESHOLD -> "More power. ${belowBy}W below target."
            WorkoutType.SWEET_SPOT -> "Lift it. ${belowBy}W below sweet spot."
            WorkoutType.ENDURANCE_SURGES -> "Surge — ${belowBy}W below. Go."
            WorkoutType.OVER_UNDER -> "Under phase — hold ${belowBy}W down."
            else -> "Push harder. ${belowBy}W below target."
        }

        // OVER_UNDER: being below floor in the "under" sub-phase is intentional.
        // Suppress the alert entirely if target range spans threshold (rider is doing the under phase).
        if (ws.workoutType == WorkoutType.OVER_UNDER) {
            val ceiling = ws.targetHigh ?: return null
            val midpoint = (floor + ceiling) / 2
            // If current power is between floor-10% and midpoint, it's the intended under phase
            if (avg30s >= floor * 90 / 100 && avg30s < midpoint) return null
        }

        return CoachingEvent(
            ruleId = RuleId.POWER_BELOW_TARGET,
            message = message,
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * 5. power_on_target — positive reinforcement when in range.
     * Suppression window varies by type: shorter for sweet spot (reward discipline),
     * longer for VO2max/over-under (quieter, less chatter during hard efforts).
     */
    fun powerOnTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.POWER) return null

        val floor = ws.targetLow ?: return null
        val ceiling = ws.targetHigh ?: floor

        val avg30s = ctx.power30sAvg
        if (!ZoneCalculator.isInTargetRange(avg30s, floor, ceiling)) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        // Positive reinforcement window: [positiveWindowStartSec .. +40]
        val windowEnd = policy.positiveWindowStartSec + 40
        if (ws.intervalElapsedSec !in policy.positiveWindowStartSec..windowEnd) return null

        val message = when (ws.workoutType) {
            WorkoutType.SWEET_SPOT -> "Dialed in. Hold ${avg30s}W steady."
            WorkoutType.THRESHOLD -> "On threshold. Don't let it drift up."
            WorkoutType.VO2_MAX -> "Good pace. Stay there."
            WorkoutType.OVER_UNDER -> "Over phase — hold ${avg30s}W, over the top."
            WorkoutType.ENDURANCE_SURGES -> "Surge on target. Hold it."
            else -> "Good. Hold ${avg30s}W."
        }

        return CoachingEvent(
            ruleId = RuleId.POWER_ON_TARGET,
            message = message,
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = policy.onTargetSuppressionSec,
        )
    }

    /**
     * 6. interval_countdown — 30 seconds remaining.
     * Message adapts to workout type for final-push intent.
     */
    fun intervalCountdown(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null

        val remaining = ws.intervalRemainingSec
        if (remaining !in 25..35) return null

        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "30 sec. Give everything."
            WorkoutType.THRESHOLD -> "30 sec left. Don't let up."
            WorkoutType.SWEET_SPOT -> "30 sec. Keep it smooth."
            WorkoutType.RECOVERY_RIDE -> "30 sec. Stay easy."
            else -> "30 sec left. Hold."
        }

        return CoachingEvent(
            ruleId = RuleId.INTERVAL_COUNTDOWN,
            message = message,
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 60,
        )
    }

    /**
     * Bonus: cadence_dropping — cadence below minimum during effort.
     * Minimum cadence is type-dependent (higher for VO2max, lower for recovery).
     */
    fun cadenceDropping(ctx: RideContext, settingsMinCadence: Int = 75): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ctx.cadenceRpm <= 0) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        // Use the higher of settings minimum and type minimum
        val minCadence = maxOf(settingsMinCadence, policy.minCadenceRpm)

        if (ctx.cadenceRpm >= minCadence) return null
        if (ctx.cadenceRpm > minCadence * 80 / 100) return null // must be hard drop

        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "Cadence dropping. Spin — ${minCadence}+ rpm."
            WorkoutType.THRESHOLD -> "Shift lighter. Cadence dropping."
            WorkoutType.SWEET_SPOT -> "Keep cadence up. Shift lighter."
            else -> "Cadence dropping. Shift lighter."
        }

        return CoachingEvent(
            ruleId = RuleId.CADENCE_DROPPING,
            message = message,
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * hr_ceiling_exceeded — HR too high during HR-based workout effort.
     */
    fun hrCeilingExceeded(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null

        val ceiling = ws.targetHigh ?: return null
        if (ctx.heartRateBpm <= ceiling + 5) return null

        val overBy = ctx.heartRateBpm - ceiling
        val message = when (ws.workoutType) {
            WorkoutType.RECOVERY_RIDE -> "Too hard. ${overBy}bpm above Z1. Back off."
            WorkoutType.ENDURANCE_SURGES -> "Post-surge HR high. Settle to base."
            else -> "HR ${overBy}bpm above zone. Ease off."
        }

        return CoachingEvent(
            ruleId = RuleId.HR_CEILING_EXCEEDED,
            message = message,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 90,
        )
    }

    /**
     * hr_below_target — HR too low during HR-based workout effort.
     * Settle time from policy (VO2max needs longer ramp time).
     */
    fun hrBelowTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null
        if (ws.workoutType == WorkoutType.RECOVERY_RIDE) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        if (ws.intervalElapsedSec < policy.settleTimeSec + 60) return null // HR needs extra time

        val floor = ws.targetLow ?: return null
        val margin = floor - (floor * 5 / 100)
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
     */
    fun hrOnTarget(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null
        if (ws.targetType != TargetType.HEART_RATE) return null

        val floor = ws.targetLow ?: return null
        val ceiling = ws.targetHigh ?: floor

        if (ctx.heartRateBpm < floor || ctx.heartRateBpm > ceiling) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        // HR on-target window: positiveWindowStartSec + 30 (HR settles 30s after power window)
        val windowStart = policy.positiveWindowStartSec + 30
        val windowEnd = windowStart + 40
        if (ws.intervalElapsedSec !in windowStart..windowEnd) return null

        return CoachingEvent(
            ruleId = RuleId.HR_ON_TARGET,
            message = "HR locked in at ${ctx.heartRateBpm}bpm. Hold it.",
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = policy.onTargetSuppressionSec,
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

        if (ws.intervalElapsedSec < 30) return null

        if (ctx.ftp <= 0) return null
        val z3Lower = ZoneCalculator.powerZoneLowerWatts(3, ctx.ftp)
        if (ctx.power30sAvg < z3Lower) return null

        // VO2max recovery quality is critical — use sharper message
        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "Recover. Next rep needs you fresh. Z1."
            WorkoutType.THRESHOLD -> "Drop power now. Threshold needs real rest."
            else -> "Actually recover. Drop to Z1."
        }

        return CoachingEvent(
            ruleId = RuleId.RECOVERY_NOT_RECOVERING,
            message = message,
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

        if (ws.intervalElapsedSec < 60) return null
        if (ctx.maxHr <= 0) return null

        val z4Lower = ZoneCalculator.hrZoneLowerBpm(4, ctx.maxHr)
        if (ctx.heartRateBpm < z4Lower) return null

        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "HR still high. Keep spinning easy."
            else -> "HR still high. Keep spinning easy."
        }

        return CoachingEvent(
            ruleId = RuleId.HR_NOT_DROPPING,
            message = message,
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

        if (ws.intervalRemainingSec < 60) return null

        val sinceLastEat = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec else ctx.rideElapsedSec
        if (sinceLastEat < 1200) return null

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
    // Session-level rules — type-aware
    // ------------------------------------------------------------------

    /**
     * 10. power_fading_trend — avg power declining across sets.
     * Minimum sets before firing is type-dependent.
     */
    fun powerFadingTrend(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (!ws.powerFadingTrend) return null

        val policy = WorkoutTypePolicy.forType(ws.workoutType)
        if (ws.effortAvgPowers.size < policy.fadingTrendMinSets) return null

        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "Power fading rep-to-rep. Reassess after this one."
            WorkoutType.THRESHOLD -> "Power fading. Consider stopping."
            WorkoutType.SWEET_SPOT -> "Power fading. Reduce intensity."
            else -> "Power fading. Stop after this rep."
        }

        return CoachingEvent(
            ruleId = RuleId.POWER_FADING_TREND,
            message = message,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 600,
        )
    }

    /**
     * 11. recovery_quality_declining — HR recovery rate slowing set-over-set.
     * More important for VO2max (recovery between reps defines the workout).
     */
    fun recoveryQualityDeclining(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (!ws.recoveryQualityDeclining) return null
        if (ws.recoveryDropRates.size < 2) return null

        val message = when (ws.workoutType) {
            WorkoutType.VO2_MAX -> "Recovery slowing. Quality degrading — stop soon."
            WorkoutType.THRESHOLD -> "Recovery slowing. Cut or reduce 5%."
            else -> "Recovery slowing. Cut session or reduce load."
        }

        return CoachingEvent(
            ruleId = RuleId.RECOVERY_QUALITY_DECLINING,
            message = message,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 600,
        )
    }

    /**
     * 12. session_complete — all intervals done, transition to endurance.
     */
    fun sessionComplete(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive) return null
        if (ws.currentPhase != IntervalPhase.COOLDOWN) return null

        if (ws.intervalElapsedSec > 30) return null

        val completedCount = ws.completedEffortCount
        val msg = when {
            completedCount <= 0 -> "Done. Easy riding from here."
            ws.workoutType == WorkoutType.VO2_MAX ->
                "VO2max done. ${completedCount} reps. Fuel now."
            ws.workoutType == WorkoutType.THRESHOLD ->
                "Threshold done. ${completedCount} sets. Recover well."
            ws.workoutType == WorkoutType.SWEET_SPOT ->
                "Sweet spot done. Fuel within 20 min."
            else -> "Session done. Fuel within 20 min."
        }

        return CoachingEvent(
            ruleId = RuleId.SESSION_COMPLETE,
            message = msg,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 3600,
        )
    }

    /**
     * 13. last_interval_motivation — final effort block encouragement.
     * Message is type-specific.
     */
    fun lastIntervalMotivation(ctx: RideContext): CoachingEvent? {
        val ws = ctx.workout
        if (!ws.isActive || ws.currentPhase != IntervalPhase.EFFORT) return null

        val nextPhase = ws.nextPhase
        val isLastEffort = nextPhase == IntervalPhase.COOLDOWN ||
            nextPhase == null ||
            (ws.currentStep == ws.totalSteps - 2)

        if (!isLastEffort) return null
        if (ws.intervalElapsedSec > 30) return null

        val recoverySlow = ws.recoveryQualityDeclining
        val message = when {
            recoverySlow && ws.workoutType == WorkoutType.VO2_MAX ->
                "Last rep. Recovery was slow — start controlled."
            recoverySlow ->
                "Final block. Recovery slower — start steady."
            ws.workoutType == WorkoutType.VO2_MAX ->
                "Last rep. Go all in."
            ws.workoutType == WorkoutType.THRESHOLD ->
                "Final block. Hold threshold to the end."
            ws.workoutType == WorkoutType.SWEET_SPOT ->
                "Last block. Finish strong and steady."
            else ->
                "Final block. You've got this."
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

    fun evaluateAll(ctx: RideContext, settingsMinCadence: Int = 75): List<CoachingEvent> =
        listOfNotNull(
            preIntervalAlert(ctx),
            preIntervalFueling(ctx),
            powerAboveTarget(ctx),
            powerBelowTarget(ctx),
            powerOnTarget(ctx),
            intervalCountdown(ctx),
            cadenceDropping(ctx, settingsMinCadence),
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
