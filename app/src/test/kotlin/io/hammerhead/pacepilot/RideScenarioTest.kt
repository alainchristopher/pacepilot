package io.hammerhead.pacepilot

import io.hammerhead.pacepilot.ai.CoachingContextBuilder
import io.hammerhead.pacepilot.ai.RideNarrative
import io.hammerhead.pacepilot.history.RideHistory
import io.hammerhead.pacepilot.history.RideSummary
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario-based integration tests.
 *
 * These test entire ride situations — not individual rule functions —
 * by replaying sequences of RideContext frames and asserting which
 * coaching events fire and when.
 *
 * Also tests that the LLM prompt contains the right context without
 * hitting the actual Gemini API.
 */
class RideScenarioTest {

    // ----------------------------------------------------------------
    // Scenario 1: VO2max workout — overcooking intervals
    // ----------------------------------------------------------------

    @Test
    fun `overcooking a VO2max interval fires CRITICAL power_above_target`() {
        val ftp = 280
        val targetLow = (ftp * 1.06).toInt()  // 106% FTP
        val targetHigh = (ftp * 1.20).toInt() // 120% FTP
        val overTarget = (targetHigh * 1.15).toInt() // 15% over ceiling

        val frames = RideSimulator.workout(ftp = ftp)
            .warmupInterval(600)
            .effortInterval(targetLow, targetHigh, 300) {
                copy(
                    powerWatts = overTarget,
                    power5sAvg = overTarget,
                    power30sAvg = overTarget,
                    power3minAvg = overTarget,
                    powerZone = 6,
                    heartRateBpm = 172,
                    hrZone = 4,
                )
            }
            .build()

        val events = RuleEvaluator.evaluateWorkout(frames)
        val powerAbove = events.filter { it.event.ruleId == "power_above_target" }

        assertTrue("Expected power_above_target to fire", powerAbove.isNotEmpty())
        assertTrue(
            "Power above target should be CRITICAL",
            powerAbove.any { it.event.priority == CoachingPriority.CRITICAL }
        )

        // Should NOT fire in warmup
        val warmupEvents = events.filter { it.frameSec < 600 }
        assertFalse(
            "power_above_target should not fire during warmup",
            warmupEvents.any { it.event.ruleId == "power_above_target" }
        )
    }

    // ----------------------------------------------------------------
    // Scenario 2: Sweet spot workout — power fades across sets
    // ----------------------------------------------------------------

    @Test
    fun `power fading across sets fires power_fading_trend after 3 sets`() {
        val ftp = 280
        val sweetSpotLow = (ftp * 0.88).toInt()
        val sweetSpotHigh = (ftp * 0.94).toInt()

        // 3 effort intervals with progressively declining avg power
        val set1Power = (ftp * 0.92).toInt()
        val set2Power = (ftp * 0.87).toInt() // dropping
        val set3Power = (ftp * 0.80).toInt() // clearly fading

        val effortAvgs = listOf(set1Power, set2Power, set3Power)

        // Build a context where fading trend is already flagged
        val frames = RideSimulator.workout(ftp = ftp)
            .effortInterval(sweetSpotLow, sweetSpotHigh, 300) {
                copy(
                    power30sAvg = set3Power,
                    workout = workout.copy(
                        effortAvgPowers = effortAvgs,
                        powerFadingTrend = true,
                        currentStep = 2,
                        totalSteps = 5,
                    )
                )
            }
            .build()

        val events = RuleEvaluator.evaluateWorkout(frames)
        assertTrue(
            "power_fading_trend should fire when 3 sets show decline",
            events.any { it.event.ruleId == "power_fading_trend" }
        )
    }

    // ----------------------------------------------------------------
    // Scenario 3: Recovery ride — rider keeps pushing into Z2+
    // ----------------------------------------------------------------

    @Test
    fun `recovery ride with Z2 power fires recoveryTooHard`() {
        val ftp = 280
        val z2Power = (ftp * 0.62).toInt() // just into Z2

        val frames = RideSimulator.recovery(ftp = ftp)
            .steadyState(600) {
                copy(
                    power30sAvg = z2Power,
                    powerWatts = z2Power,
                    powerZone = 2,
                    rideElapsedSec = 600L,
                )
            }
            .build()

        val events = RuleEvaluator.evaluateAll(frames)
        assertTrue(
            "adaptive_recovery should fire on Z2+ during recovery ride",
            events.any { it.event.ruleId == "adaptive_recovery" }
        )
    }

    // ----------------------------------------------------------------
    // Scenario 4: Pre-interval alert window fires correctly
    // ----------------------------------------------------------------

    @Test
    fun `pre_interval_alert fires in 55-95s window before effort`() {
        val ftp = 280

        val frames = RideSimulator.workout(ftp = ftp)
            .interval(IntervalPhase.RECOVERY, 120, configure = {
                copy(
                    workout = workout.copy(
                        nextPhase = IntervalPhase.EFFORT,
                        // intervalRemainingSec is set by simulator per-frame
                    )
                )
            })
            .build()

        // Specifically check the 55-95s window within the recovery interval
        val inWindow = frames.filter {
            it.workout.intervalRemainingSec in 55..95 &&
                it.workout.currentPhase == IntervalPhase.RECOVERY
        }
        assertTrue("Should have frames in the pre-interval window", inWindow.isNotEmpty())

        // Check outside the window
        val outsideWindow = frames.filter {
            it.workout.intervalRemainingSec < 50 ||
                it.workout.intervalRemainingSec > 100
        }

        val windowEvents = RuleEvaluator.evaluateWorkout(inWindow)
        assertTrue(
            "pre_interval_alert should fire in 55-95s window",
            windowEvents.any { it.event.ruleId == "pre_interval_alert" }
        )
    }

    // ----------------------------------------------------------------
    // Scenario 5: Last interval detection
    // ----------------------------------------------------------------

    @Test
    fun `last_interval_motivation fires on final effort block`() {
        val ftp = 280
        val targetLow = (ftp * 0.88).toInt()
        val targetHigh = (ftp * 0.94).toInt()

        // Simulate last effort (step 4 of 5, phase=EFFORT, next=COOLDOWN)
        val ctx = TestHelpers.buildContext(
            mode = RideMode.WORKOUT,
            power30sAvg = targetLow + 5,
            workoutActive = true,
        ).copy(
            workout = io.hammerhead.pacepilot.model.WorkoutState(
                isActive = true,
                currentStep = 4,
                totalSteps = 5,
                currentPhase = IntervalPhase.EFFORT,
                nextPhase = IntervalPhase.COOLDOWN,
                intervalElapsedSec = 5,  // early in interval
                intervalRemainingSec = 295,
                targetLow = targetLow,
                targetHigh = targetHigh,
            )
        )

        val event = io.hammerhead.pacepilot.coaching.WorkoutCoachingRules.lastIntervalMotivation(ctx)
        assertNotNull("last_interval_motivation should fire on final block", event)
        assertEquals("last_interval_motivation", event!!.ruleId)
    }

    // ----------------------------------------------------------------
    // Scenario 6: HR recovery declining fires correctly
    // ----------------------------------------------------------------

    @Test
    fun `recovery_quality_declining fires when HR recovery slows`() {
        val ctx = TestHelpers.buildContext(
            mode = RideMode.WORKOUT,
            workoutActive = true,
        ).copy(
            workout = io.hammerhead.pacepilot.model.WorkoutState(
                isActive = true,
                currentStep = 3,
                totalSteps = 8,
                currentPhase = IntervalPhase.EFFORT,
                recoveryDropRates = listOf(0.8f, 0.6f, 0.4f), // declining
                recoveryQualityDeclining = true,
            )
        )

        val event = io.hammerhead.pacepilot.coaching.WorkoutCoachingRules.recoveryQualityDeclining(ctx)
        assertNotNull("recovery_quality_declining should fire", event)
    }

    // ----------------------------------------------------------------
    // Scenario 7: LLM prompt quality tests (no API calls)
    // ----------------------------------------------------------------

    @Test
    fun `stable context includes rider history when available`() {
        val history = RideHistory(
            rides = List(10) { i ->
                RideSummary(
                    timestamp = System.currentTimeMillis() - i * 86_400_000L,
                    durationSec = 3600,
                    distanceKm = 60f,
                    elevationGainM = 800f,
                    avgPowerWatts = 210,
                    normalizedPower = 225,
                    maxPowerWatts = 480,
                    ftpAtTime = 280,
                    avgHrBpm = 148,
                    maxHrBpm = 178,
                    powerZoneTimePct = listOf(0.05f, 0.55f, 0.25f, 0.10f, 0.03f, 0.01f, 0.01f),
                    hrZoneTimePct = listOf(0.05f, 0.50f, 0.30f, 0.13f, 0.02f),
                    powerFadingDetected = i % 3 == 0,
                    hrDecouplingPct = 3.5f,
                    avgHrRecoveryRateBpmPerSec = 0.45f,
                    wasStructuredWorkout = i % 2 == 0,
                    avgIntervalComplianceScore = 0.82f,
                )
            }
        )

        val stable = CoachingContextBuilder.buildStableContext(history)

        assertTrue("Stable context should include ride count", stable.contains("10"))
        assertTrue("Stable context should include NP baseline", stable.contains("225W"))
        assertTrue("Stable context should include HR recovery rate", stable.contains("bpm/sec"))
        assertTrue("Stable context should include compliance score", stable.contains("82%"))
        println("\n=== STABLE CONTEXT SAMPLE ===\n$stable\n=============================\n")
    }

    @Test
    fun `live prompt includes power deviation from target`() {
        val ftp = 280
        val targetHigh = (ftp * 1.20).toInt()
        val overTarget = (targetHigh * 1.12).toInt()

        val ctx = TestHelpers.buildContext(
            mode = RideMode.WORKOUT,
            power30sAvg = overTarget,
            workoutActive = true,
        ).copy(
            ftp = ftp,
            power30sAvg = overTarget,
            workout = io.hammerhead.pacepilot.model.WorkoutState(
                isActive = true,
                currentPhase = IntervalPhase.EFFORT,
                intervalElapsedSec = 90,
                intervalRemainingSec = 210,
                targetType = io.hammerhead.pacepilot.model.TargetType.POWER,
                targetLow = (ftp * 1.06).toInt(),
                targetHigh = targetHigh,
            )
        )

        val event = io.hammerhead.pacepilot.coaching.WorkoutCoachingRules.powerAboveTarget(ctx)!!
        val narrative = RideNarrative()
        val prompt = CoachingContextBuilder.buildLivePrompt(event, ctx, narrative)

        assertTrue("Live prompt should mention the trigger rule", prompt.contains("power_above_target"))
        assertTrue("Live prompt should include power numbers", prompt.contains("W"))
        assertTrue("Live prompt should include target", prompt.contains("above ceiling"))
        println("\n=== LIVE PROMPT SAMPLE ===\n$prompt\n==========================\n")
    }

    @Test
    fun `ride narrative accumulates events correctly`() {
        val narrative = RideNarrative()

        // Simulate 70 minutes of ride
        val baseCtx = TestHelpers.buildContext(RideMode.ENDURANCE, 210)

        // 10 min baseline window
        repeat(600) { sec ->
            narrative.onContext(
                baseCtx.copy(
                    rideElapsedSec = sec.toLong(),
                    power30sAvg = 210,
                    heartRateBpm = 145,
                )
            )
        }

        // 60 min — first hour locks
        repeat(3000) { sec ->
            narrative.onContext(
                baseCtx.copy(rideElapsedSec = (600 + sec).toLong(), power30sAvg = 210)
            )
        }

        // 61+ min — power drops
        val droppedCtx = baseCtx.copy(
            rideElapsedSec = 3660,
            power30sAvg = 170,  // big drop vs first hour avg
            power3minAvg = 170,
        )
        repeat(600) { sec ->
            narrative.onContext(droppedCtx.copy(rideElapsedSec = (3660 + sec).toLong()))
        }

        val text = narrative.buildNarrative(droppedCtx)
        assertNotNull("Narrative should be non-null after meaningful ride", text)
        assertTrue("Narrative should contain baseline", text!!.contains("Baseline"))
        assertTrue("Narrative should mention power drop", text.contains("dropped") || text.contains("avg power"))
        println("\n=== NARRATIVE SAMPLE ===\n$text\n========================\n")
    }

    @Test
    fun `live prompt without fueling acknowledgment warns at 45min`() {
        val ctx = TestHelpers.buildContext(RideMode.ENDURANCE, 200).copy(
            rideElapsedSec = 2700L, // 45 min
            lastFuelAckEpochSec = 0L,
        )

        val narrative = RideNarrative()
        // Fast-forward narrative
        repeat(2700) { sec ->
            narrative.onContext(ctx.copy(rideElapsedSec = sec.toLong()))
        }

        val text = narrative.buildNarrative(ctx)
        assertNotNull(text)
        assertTrue("Should warn about no fueling", text!!.contains("No fueling confirmed") || text.contains("fueling"))
    }

    @Test
    fun `prompt system instruction enforces 12-word output limit`() {
        assertTrue(
            "System prompt must explicitly state max 12 words",
            CoachingContextBuilder.SYSTEM_PROMPT.contains("12 words")
        )
        assertTrue(
            "System prompt must prohibit greetings",
            CoachingContextBuilder.SYSTEM_PROMPT.contains("greetings") ||
                CoachingContextBuilder.SYSTEM_PROMPT.lowercase().contains("no greetings")
        )
    }
}
