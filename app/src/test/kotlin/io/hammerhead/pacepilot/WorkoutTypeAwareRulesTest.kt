package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.WorkoutCoachingRules
import io.hammerhead.pacepilot.coaching.WorkoutTypePolicy
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutState
import io.hammerhead.pacepilot.model.WorkoutType
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies per-type rule behavior and confirms no-regression for UNKNOWN type.
 */
class WorkoutTypeAwareRulesTest {

    // ------------------------------------------------------------------
    // UNKNOWN — must match old hardcoded behavior exactly
    // ------------------------------------------------------------------

    @Test
    fun `UNKNOWN - powerAboveTarget fires at 10pct over ceiling`() {
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 276, targetHigh = 250)
        assertNotNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerAboveTarget does NOT fire at 9pct over ceiling`() {
        // 9% over 250 = 272.5W → 272 rounds down, should not fire
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 272, targetHigh = 250)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerBelowTarget fires after 30s settle`() {
        // 10% below 230 floor = 207W. Power at 195W.
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 195, targetLow = 230, elapsedSec = 60)
        assertNotNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerBelowTarget does NOT fire before 30s`() {
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 195, targetLow = 230, elapsedSec = 20)
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerOnTarget fires in 55-95s window`() {
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 240, targetLow = 230, targetHigh = 250, elapsedSec = 70)
        assertNotNull(WorkoutCoachingRules.powerOnTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerOnTarget does NOT fire before 55s`() {
        val ctx = effortContextTyped(WorkoutType.UNKNOWN, power30sAvg = 240, targetLow = 230, targetHigh = 250, elapsedSec = 45)
        assertNull(WorkoutCoachingRules.powerOnTarget(ctx))
    }

    @Test
    fun `UNKNOWN - powerFadingTrend needs 3 sets`() {
        val wsOk = WorkoutState(isActive = true, powerFadingTrend = true, effortAvgPowers = listOf(250, 242, 235))
        assertNotNull(WorkoutCoachingRules.powerFadingTrend(baseContext().copy(workout = wsOk)))

        val wsShort = WorkoutState(isActive = true, powerFadingTrend = true, effortAvgPowers = listOf(250, 242))
        assertNull(WorkoutCoachingRules.powerFadingTrend(baseContext().copy(workout = wsShort)))
    }

    // ------------------------------------------------------------------
    // THRESHOLD — strict ceiling (5%), settle at 30s
    // ------------------------------------------------------------------

    @Test
    fun `THRESHOLD - powerAboveTarget fires at 5pct over ceiling`() {
        // 5% over 250 = 262.5W → threshold = 262W. Power at 263W should fire.
        val ctx = effortContextTyped(WorkoutType.THRESHOLD, power30sAvg = 263, targetHigh = 250)
        val event = WorkoutCoachingRules.powerAboveTarget(ctx)
        assertNotNull(event)
        assertTrue(event!!.message.contains("ceiling", ignoreCase = true) || event.message.contains("back off", ignoreCase = true))
    }

    @Test
    fun `THRESHOLD - powerAboveTarget does NOT fire at 4pct over ceiling`() {
        // 4% over 250 = 260W, threshold = 262W → should not fire
        val ctx = effortContextTyped(WorkoutType.THRESHOLD, power30sAvg = 260, targetHigh = 250)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    @Test
    fun `THRESHOLD - powerFadingTrend needs only 2 sets`() {
        val ws = WorkoutState(
            isActive = true,
            workoutType = WorkoutType.THRESHOLD,
            powerFadingTrend = true,
            effortAvgPowers = listOf(260, 252),
        )
        assertNotNull(WorkoutCoachingRules.powerFadingTrend(baseContext().copy(workout = ws)))
    }

    // ------------------------------------------------------------------
    // VO2_MAX — wider tolerance (15%), longer settle (45s)
    // ------------------------------------------------------------------

    @Test
    fun `VO2_MAX - powerAboveTarget requires 15pct over ceiling to fire`() {
        // 14% over 250 = 285W, should not fire
        val ctx14 = effortContextTyped(WorkoutType.VO2_MAX, power30sAvg = 285, targetHigh = 250)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx14))

        // 16% over 250 = 290W, threshold = 287W → should fire
        val ctx16 = effortContextTyped(WorkoutType.VO2_MAX, power30sAvg = 290, targetHigh = 250)
        val event = WorkoutCoachingRules.powerAboveTarget(ctx16)
        assertNotNull(event)
        assertTrue(event!!.message.contains("Sustainable", ignoreCase = true) || event.message.contains("Too high", ignoreCase = true))
    }

    @Test
    fun `VO2_MAX - powerBelowTarget does NOT fire before 45s settle`() {
        // VO2_MAX settle is 45s
        val ctx = effortContextTyped(WorkoutType.VO2_MAX, power30sAvg = 195, targetLow = 230, elapsedSec = 40)
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `VO2_MAX - powerBelowTarget fires after 45s settle`() {
        val ctx = effortContextTyped(WorkoutType.VO2_MAX, power30sAvg = 195, targetLow = 230, elapsedSec = 50)
        assertNotNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    // ------------------------------------------------------------------
    // RECOVERY_RIDE — strictest ceiling (3%), never fires below target
    // ------------------------------------------------------------------

    @Test
    fun `RECOVERY_RIDE - powerAboveTarget fires at 3pct over ceiling`() {
        // 3% over 150 = 154W threshold. Power at 155 should fire.
        val ctx = effortContextTyped(WorkoutType.RECOVERY_RIDE, power30sAvg = 155, targetHigh = 150)
        val event = WorkoutCoachingRules.powerAboveTarget(ctx)
        assertNotNull(event)
        assertTrue(event!!.message.contains("Z1", ignoreCase = true) || event.message.contains("easy", ignoreCase = true))
    }

    @Test
    fun `RECOVERY_RIDE - powerBelowTarget never fires`() {
        // Even way below floor, recovery ride never punishes going easy
        val ctx = effortContextTyped(WorkoutType.RECOVERY_RIDE, power30sAvg = 50, targetLow = 130, elapsedSec = 120)
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `RECOVERY_RIDE - preIntervalFueling skips recovery rides`() {
        val ctx = recoveryContext().copy(
            workout = recoveryContext().workout.copy(
                workoutType = WorkoutType.RECOVERY_RIDE,
                currentPhase = IntervalPhase.WARMUP,
                nextPhase = IntervalPhase.EFFORT,
                intervalRemainingSec = 60,
            ),
            carbDeficitGrams = 30,
        )
        assertNull(WorkoutCoachingRules.preIntervalFueling(ctx))
    }

    // ------------------------------------------------------------------
    // SWEET_SPOT — earlier fading signal (2 sets), tighter ceiling (7%)
    // ------------------------------------------------------------------

    @Test
    fun `SWEET_SPOT - powerFadingTrend needs only 2 sets`() {
        val ws = WorkoutState(
            isActive = true,
            workoutType = WorkoutType.SWEET_SPOT,
            powerFadingTrend = true,
            effortAvgPowers = listOf(255, 246),
        )
        assertNotNull(WorkoutCoachingRules.powerFadingTrend(baseContext().copy(workout = ws)))
    }

    @Test
    fun `SWEET_SPOT - powerAboveTarget fires at 7pct over ceiling`() {
        // 7% over 250 = 267.5W → threshold = 267W. Power at 268 fires.
        val ctx = effortContextTyped(WorkoutType.SWEET_SPOT, power30sAvg = 268, targetHigh = 250)
        assertNotNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    @Test
    fun `SWEET_SPOT - powerAboveTarget does NOT fire at 6pct over ceiling`() {
        val ctx = effortContextTyped(WorkoutType.SWEET_SPOT, power30sAvg = 265, targetHigh = 250)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    // ------------------------------------------------------------------
    // OVER_UNDER — suppress generic on-target; under-phase is intentional
    // ------------------------------------------------------------------

    @Test
    fun `OVER_UNDER - powerBelowTarget suppressed during intentional under phase`() {
        // targetLow=230, targetHigh=270, midpoint=250. Power at 220 = below floor but above 207 (90%)
        // This is the "under" sub-phase — should be suppressed
        val ctx = effortContextTyped(
            WorkoutType.OVER_UNDER,
            power30sAvg = 220,
            targetLow = 230,
            targetHigh = 270,
            elapsedSec = 60,
        )
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `OVER_UNDER - powerBelowTarget fires when way too far below floor`() {
        // 8% below 230 floor = 211W threshold. Power at 200 fires.
        val ctx = effortContextTyped(
            WorkoutType.OVER_UNDER,
            power30sAvg = 200,
            targetLow = 230,
            targetHigh = 270,
            elapsedSec = 60,
        )
        assertNotNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    // ------------------------------------------------------------------
    // Interval countdown message varies by type
    // ------------------------------------------------------------------

    @Test
    fun `intervalCountdown message differs for VO2_MAX vs RECOVERY_RIDE`() {
        val ctxVo2 = effortContextTyped(WorkoutType.VO2_MAX, remainingSec = 30)
        val ctxRec = effortContextTyped(WorkoutType.RECOVERY_RIDE, remainingSec = 30)

        val msgVo2 = WorkoutCoachingRules.intervalCountdown(ctxVo2)?.message
        val msgRec = WorkoutCoachingRules.intervalCountdown(ctxRec)?.message

        assertNotNull(msgVo2)
        assertNotNull(msgRec)
        assertNotEquals(msgVo2, msgRec)
    }

    // ------------------------------------------------------------------
    // sessionComplete message contains workout type context
    // ------------------------------------------------------------------

    @Test
    fun `sessionComplete VO2_MAX message mentions reps`() {
        val ws = WorkoutState(
            isActive = true,
            workoutType = WorkoutType.VO2_MAX,
            currentPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 5,
            completedEffortCount = 5,
        )
        val event = WorkoutCoachingRules.sessionComplete(baseContext().copy(workout = ws))
        assertNotNull(event)
        assertTrue(event!!.message.contains("rep", ignoreCase = true) || event.message.contains("VO2", ignoreCase = true))
    }

    @Test
    fun `lastIntervalMotivation VO2_MAX urges all-in`() {
        val ws = WorkoutState(
            isActive = true,
            workoutType = WorkoutType.VO2_MAX,
            currentPhase = IntervalPhase.EFFORT,
            nextPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 5,
        )
        val event = WorkoutCoachingRules.lastIntervalMotivation(baseContext().copy(workout = ws))
        assertNotNull(event)
        assertTrue(event!!.message.contains("all in", ignoreCase = true) || event.message.contains("Last rep", ignoreCase = true))
    }
}

// ------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------

private fun effortContextTyped(
    workoutType: WorkoutType,
    power30sAvg: Int = 240,
    targetLow: Int = 230,
    targetHigh: Int = 250,
    elapsedSec: Int = 120,
    remainingSec: Int = 300,
): io.hammerhead.pacepilot.model.RideContext = baseContext().copy(
    power30sAvg = power30sAvg,
    powerWatts = power30sAvg,
    workout = WorkoutState(
        isActive = true,
        workoutType = workoutType,
        currentPhase = IntervalPhase.EFFORT,
        currentStep = 1,
        totalSteps = 7,
        intervalElapsedSec = elapsedSec,
        intervalRemainingSec = remainingSec,
        targetType = TargetType.POWER,
        targetLow = targetLow,
        targetHigh = targetHigh,
        nextPhase = IntervalPhase.RECOVERY,
    ),
)
