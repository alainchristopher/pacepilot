package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.coaching.WorkoutCoachingRules
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutState
import org.junit.Assert.*
import org.junit.Test

class WorkoutCoachingRulesTest {

    // ------------------------------------------------------------------
    // preIntervalAlert
    // ------------------------------------------------------------------

    @Test
    fun `preIntervalAlert fires in 60-90s window before effort`() {
        val ctx = recoveryContext(remainingSec = 70, elapsedSec = 110, nextPhase = IntervalPhase.EFFORT)
        val event = WorkoutCoachingRules.preIntervalAlert(ctx)
        assertNotNull(event)
        assertEquals(RuleId.PRE_INTERVAL_ALERT, event!!.ruleId)
        assertTrue(event.message.contains("Effort"))
    }

    @Test
    fun `preIntervalAlert does NOT fire outside window`() {
        val ctx = recoveryContext(remainingSec = 120, elapsedSec = 60, nextPhase = IntervalPhase.EFFORT)
        assertNull(WorkoutCoachingRules.preIntervalAlert(ctx))
    }

    @Test
    fun `preIntervalAlert does NOT fire if next phase is not EFFORT`() {
        val ctx = recoveryContext(remainingSec = 70, elapsedSec = 110, nextPhase = IntervalPhase.COOLDOWN)
        assertNull(WorkoutCoachingRules.preIntervalAlert(ctx))
    }

    @Test
    fun `preIntervalAlert uses FIRST_INTERVAL ruleId for first effort`() {
        val ctx = recoveryContext(remainingSec = 70, elapsedSec = 110, nextPhase = IntervalPhase.EFFORT)
            .copy(inFirstIntervalOfSession = true)
        val event = WorkoutCoachingRules.preIntervalAlert(ctx)
        assertNotNull(event)
        assertEquals(RuleId.FIRST_INTERVAL, event!!.ruleId)
        assertTrue(event.message.contains("First effort block"))
    }

    // ------------------------------------------------------------------
    // powerAboveTarget
    // ------------------------------------------------------------------

    @Test
    fun `powerAboveTarget fires when 30s avg 10pct above ceiling`() {
        // Target: 230-250W. 10% above 250 = 275W
        val ctx = effortContext(power30sAvg = 280, targetHigh = 250)
        val event = WorkoutCoachingRules.powerAboveTarget(ctx)
        assertNotNull(event)
        assertEquals(RuleId.POWER_ABOVE_TARGET, event!!.ruleId)
        assertEquals(CoachingPriority.CRITICAL, event.priority)
        assertTrue(event.message.contains("above target"))
    }

    @Test
    fun `powerAboveTarget does NOT fire when within 10pct`() {
        // 260W is only 4% above 250W ceiling
        val ctx = effortContext(power30sAvg = 260, targetHigh = 250)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    @Test
    fun `powerAboveTarget does NOT fire during recovery`() {
        val ctx = recoveryContext(power30sAvg = 300)
        assertNull(WorkoutCoachingRules.powerAboveTarget(ctx))
    }

    // ------------------------------------------------------------------
    // powerBelowTarget
    // ------------------------------------------------------------------

    @Test
    fun `powerBelowTarget fires when 30s avg 10pct below floor after 30s`() {
        // Floor 230W. 10% below = 207W. Power at 195W.
        val ctx = effortContext(power30sAvg = 195, targetLow = 230, elapsedSec = 60)
        val event = WorkoutCoachingRules.powerBelowTarget(ctx)
        assertNotNull(event)
        assertEquals(RuleId.POWER_BELOW_TARGET, event!!.ruleId)
        assertTrue(event.message.contains("below target"))
    }

    @Test
    fun `powerBelowTarget does NOT fire in first 30s of interval`() {
        val ctx = effortContext(power30sAvg = 190, targetLow = 230, elapsedSec = 20)
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    @Test
    fun `powerBelowTarget does NOT fire when power above threshold`() {
        val ctx = effortContext(power30sAvg = 215, targetLow = 230, elapsedSec = 60)
        assertNull(WorkoutCoachingRules.powerBelowTarget(ctx))
    }

    // ------------------------------------------------------------------
    // powerOnTarget
    // ------------------------------------------------------------------

    @Test
    fun `powerOnTarget fires 60-90s into effort when in range`() {
        val ctx = effortContext(power30sAvg = 240, targetLow = 230, targetHigh = 250, elapsedSec = 70)
        val event = WorkoutCoachingRules.powerOnTarget(ctx)
        assertNotNull(event)
        assertEquals(RuleId.POWER_ON_TARGET, event!!.ruleId)
        assertEquals(CoachingPriority.LOW, event.priority)
    }

    @Test
    fun `powerOnTarget does NOT fire outside 60-90s window`() {
        val ctx = effortContext(power30sAvg = 240, targetLow = 230, targetHigh = 250, elapsedSec = 120)
        assertNull(WorkoutCoachingRules.powerOnTarget(ctx))
    }

    // ------------------------------------------------------------------
    // intervalCountdown
    // ------------------------------------------------------------------

    @Test
    fun `intervalCountdown fires in 25-35s window`() {
        val ctx = effortContext(remainingSec = 30)
        val event = WorkoutCoachingRules.intervalCountdown(ctx)
        assertNotNull(event)
        assertEquals(RuleId.INTERVAL_COUNTDOWN, event!!.ruleId)
        assertTrue(event.message.contains("30 sec"))
    }

    @Test
    fun `intervalCountdown does NOT fire at 60s remaining`() {
        val ctx = effortContext(remainingSec = 60)
        assertNull(WorkoutCoachingRules.intervalCountdown(ctx))
    }

    // ------------------------------------------------------------------
    // recoveryNotRecovering
    // ------------------------------------------------------------------

    @Test
    fun `recoveryNotRecovering fires when power in Z3 during recovery after 30s`() {
        // FTP 250, Z3 lower = 76% * 250 = 190W
        val ctx = recoveryContext(power30sAvg = 200, elapsedSec = 45, ftp = 250)
        val event = WorkoutCoachingRules.recoveryNotRecovering(ctx)
        assertNotNull(event)
        assertEquals(RuleId.RECOVERY_NOT_RECOVERING, event!!.ruleId)
    }

    @Test
    fun `recoveryNotRecovering does NOT fire before 30s`() {
        val ctx = recoveryContext(power30sAvg = 200, elapsedSec = 20, ftp = 250)
        assertNull(WorkoutCoachingRules.recoveryNotRecovering(ctx))
    }

    @Test
    fun `recoveryNotRecovering does NOT fire when power in Z1`() {
        val ctx = recoveryContext(power30sAvg = 100, elapsedSec = 45, ftp = 250)
        assertNull(WorkoutCoachingRules.recoveryNotRecovering(ctx))
    }

    // ------------------------------------------------------------------
    // hrNotDropping
    // ------------------------------------------------------------------

    @Test
    fun `hrNotDropping fires when HR in Z4 at 60s into recovery`() {
        // FTP 250, maxHr 180. Z4 lower = 81% * 180 = 145bpm
        val ctx = recoveryContext(hrBpm = 158, elapsedSec = 65, maxHr = 180)
        val event = WorkoutCoachingRules.hrNotDropping(ctx)
        assertNotNull(event)
        assertEquals(RuleId.HR_NOT_DROPPING, event!!.ruleId)
    }

    @Test
    fun `hrNotDropping does NOT fire before 60s in recovery`() {
        val ctx = recoveryContext(hrBpm = 158, elapsedSec = 30)
        assertNull(WorkoutCoachingRules.hrNotDropping(ctx))
    }

    // ------------------------------------------------------------------
    // recoveryFuelingWindow
    // ------------------------------------------------------------------

    @Test
    fun `recoveryFuelingWindow fires in 30-90s of recovery with enough time remaining`() {
        val ctx = recoveryContext(remainingSec = 120, elapsedSec = 50)
            .copy(timeSinceLastFuelSec = 2000L)
        val event = WorkoutCoachingRules.recoveryFuelingWindow(ctx)
        assertNotNull(event)
        assertEquals(RuleId.RECOVERY_FUELING_WINDOW, event!!.ruleId)
    }

    @Test
    fun `recoveryFuelingWindow does NOT fire if recently fueled`() {
        val ctx = recoveryContext(remainingSec = 120, elapsedSec = 50)
            .copy(timeSinceLastFuelSec = 500L)
        assertNull(WorkoutCoachingRules.recoveryFuelingWindow(ctx))
    }

    // ------------------------------------------------------------------
    // powerFadingTrend
    // ------------------------------------------------------------------

    @Test
    fun `powerFadingTrend fires when trend is true and 3+ efforts done`() {
        val ws = WorkoutState(
            isActive = true,
            powerFadingTrend = true,
            effortAvgPowers = listOf(250, 242, 235),
        )
        val ctx = baseContext().copy(workout = ws)
        val event = WorkoutCoachingRules.powerFadingTrend(ctx)
        assertNotNull(event)
        assertEquals(RuleId.POWER_FADING_TREND, event!!.ruleId)
        assertEquals(CoachingPriority.HIGH, event.priority)
    }

    @Test
    fun `powerFadingTrend does NOT fire with fewer than 3 sets`() {
        val ws = WorkoutState(isActive = true, powerFadingTrend = true, effortAvgPowers = listOf(250, 242))
        val ctx = baseContext().copy(workout = ws)
        assertNull(WorkoutCoachingRules.powerFadingTrend(ctx))
    }

    // ------------------------------------------------------------------
    // recoveryQualityDeclining
    // ------------------------------------------------------------------

    @Test
    fun `recoveryQualityDeclining fires when flag is true and 2+ rates tracked`() {
        val ws = WorkoutState(
            isActive = true,
            recoveryQualityDeclining = true,
            recoveryDropRates = listOf(0.5f, 0.3f),
        )
        val ctx = baseContext().copy(workout = ws)
        val event = WorkoutCoachingRules.recoveryQualityDeclining(ctx)
        assertNotNull(event)
        assertEquals(RuleId.RECOVERY_QUALITY_DECLINING, event!!.ruleId)
    }

    // ------------------------------------------------------------------
    // sessionComplete
    // ------------------------------------------------------------------

    @Test
    fun `sessionComplete fires at start of cooldown`() {
        val ws = WorkoutState(
            isActive = true,
            currentPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 5,
            completedEffortCount = 3,
        )
        val ctx = baseContext().copy(workout = ws)
        val event = WorkoutCoachingRules.sessionComplete(ctx)
        assertNotNull(event)
        assertEquals(RuleId.SESSION_COMPLETE, event!!.ruleId)
        assertTrue(event.message.contains("Session done"))
    }

    @Test
    fun `sessionComplete does NOT fire mid-cooldown`() {
        val ws = WorkoutState(
            isActive = true,
            currentPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 60,
        )
        val ctx = baseContext().copy(workout = ws)
        assertNull(WorkoutCoachingRules.sessionComplete(ctx))
    }

    // ------------------------------------------------------------------
    // lastIntervalMotivation
    // ------------------------------------------------------------------

    @Test
    fun `lastIntervalMotivation fires at start of last effort`() {
        val ws = WorkoutState(
            isActive = true,
            currentPhase = IntervalPhase.EFFORT,
            currentStep = 5,
            totalSteps = 7,
            nextPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 5,
        )
        val ctx = baseContext().copy(workout = ws)
        val event = WorkoutCoachingRules.lastIntervalMotivation(ctx)
        assertNotNull(event)
        assertEquals(RuleId.LAST_INTERVAL_MOTIVATION, event!!.ruleId)
    }

    @Test
    fun `lastIntervalMotivation does NOT fire mid-last-interval`() {
        val ws = WorkoutState(
            isActive = true,
            currentPhase = IntervalPhase.EFFORT,
            currentStep = 5,
            totalSteps = 7,
            nextPhase = IntervalPhase.COOLDOWN,
            intervalElapsedSec = 60,
        )
        val ctx = baseContext().copy(workout = ws)
        assertNull(WorkoutCoachingRules.lastIntervalMotivation(ctx))
    }

    // ------------------------------------------------------------------
    // evaluateAll returns no duplicates for nominal context
    // ------------------------------------------------------------------

    @Test
    fun `evaluateAll returns distinct rule IDs`() {
        val ctx = effortContext(power30sAvg = 240, targetLow = 230, targetHigh = 250, elapsedSec = 70)
        val events = WorkoutCoachingRules.evaluateAll(ctx)
        val ruleIds = events.map { it.ruleId }
        assertEquals(ruleIds.size, ruleIds.distinct().size)
    }
}
