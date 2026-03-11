package io.hammerhead.pacepilot.ai

import io.hammerhead.pacepilot.coaching.WorkoutTypePolicy
import io.hammerhead.pacepilot.history.RideHistory
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutType
import io.hammerhead.pacepilot.model.currentMode
import io.hammerhead.pacepilot.model.isHrBasedWorkout

/**
 * Builds Gemini prompts for coaching events.
 *
 * Split into two tiers:
 *
 *  1. [buildStableContext] — sent once per ride to Gemini's context cache.
 *     Contains: system prompt + rider profile + historical patterns.
 *     This block never changes mid-ride, so caching it eliminates re-sending
 *     ~500 tokens on every single event call.
 *
 *  2. [buildLivePrompt] — sent per-event, references the cached context.
 *     Contains: current situation, live ride state, ride narrative, and the
 *     specific coaching trigger. No fallback message anchor — the LLM finds
 *     its own words based purely on the situation description.
 */
object CoachingContextBuilder {

    // ----------------------------------------------------------------
    // Stable context (cached per ride)
    // ----------------------------------------------------------------

    val SYSTEM_PROMPT = """
You are an elite cycling coach speaking directly to a rider mid-ride via their Karoo bike computer.

OUTPUT RULES — NON-NEGOTIABLE:
- Respond with ONE sentence only. Max 12 words.
- No punctuation at the end unless it's "!" for urgency.
- No greetings. No filler words. No "Remember to".
- Use numbers when available. Be specific.
- Tone: direct, calm, like a trusted coach in your ear.
- Critical situations: short and sharp. "Back off. Now."
- Positive moments: brief, genuine. "Solid. Hold it."
- Never explain yourself. Just the cue.
""".trim()

    /**
     * Builds the stable block that will be cached with Gemini.
     * Include: system instructions, rider profile, historical patterns.
     */
    fun buildStableContext(history: RideHistory): String = buildString {
        appendLine(SYSTEM_PROMPT)
        appendLine()
        appendLine("---")
        appendLine()

        appendLine("## Rider Profile")
        if (history.recentRideCount > 0) {
            appendLine("Recent rides logged: ${history.recentRideCount}")
            appendLine("Dominant training zone: Z${history.dominantPowerZone}")
            appendLine("Days since last ride: ${history.daysSinceLastRide}")
            if (history.avgNormalizedPower > 0) {
                appendLine("Baseline normalized power (recent avg): ${history.avgNormalizedPower}W")
            }
            if (history.typicalHrRecoveryRate > 0) {
                appendLine("Typical HR recovery rate: ${"%.2f".format(history.typicalHrRecoveryRate)} bpm/sec after efforts")
            }
            if (history.avgHrDecoupling > 0) {
                appendLine("Avg HR decoupling on long rides: ${"%.1f".format(history.avgHrDecoupling)}%")
            }
            if (history.recentRideCount >= 3) {
                val workouts = history.rides.filter { it.wasStructuredWorkout }
                if (workouts.isNotEmpty()) {
                    appendLine("Structured workout compliance: ${"%.0f".format(history.avgComplianceScore * 100)}% avg")
                    if (history.frequentPowerFading) {
                        appendLine("⚠ Pattern: power fades in ${(history.rides.count { it.powerFadingDetected }.toFloat() / history.recentRideCount * 100).toInt()}% of recent sessions")
                    }
                }
            }
        } else {
            appendLine("No prior ride history yet — first-time rider.")
        }
    }.trim()

    // ----------------------------------------------------------------
    // Live per-event prompt (sent each time a rule fires)
    // ----------------------------------------------------------------

    /**
     * Builds the live prompt sent per coaching event.
     * Assumes the stable context has already been cached — this contains only
     * the dynamic data that changes event-to-event.
     */
    fun buildLivePrompt(
        event: CoachingEvent,
        ctx: RideContext,
        narrative: RideNarrative,
    ): String = buildString {
        appendLine("## Trigger")
        appendLine("${event.ruleId}: ${ruleDescription(event.ruleId)}")
        appendLine()

        appendLine("## Now")
        appendLine("${ctx.rideElapsedSec / 60}min in · ${ctx.currentMode.name.lowercase().replace('_', ' ')} mode")

        // For HR-based workouts, lead with HR as the primary metric
        if (ctx.isHrBasedWorkout) {
            if (ctx.heartRateBpm > 0) {
                val hrLine = buildString {
                    append("HR: ${ctx.heartRateBpm} bpm · Z${ctx.hrZone} of 5 (PRIMARY TARGET)")
                    if (ctx.hrRecoveryRate > 0) append(" · recovering at ${"%.2f".format(ctx.hrRecoveryRate)} bpm/s")
                    if (ctx.hrDecouplingPct > 3f) append(" · decoupling ${ctx.hrDecouplingPct.toInt()}%")
                }
                appendLine(hrLine)
            }
            appendLine("Power (secondary): ${ctx.power30sAvg}W · FTP ${ctx.ftp}W")
        } else {
            // Power-based: power is primary
            appendLine("Power: ${ctx.power5sAvg}W (5s) / ${ctx.power30sAvg}W (30s) / ${ctx.power3minAvg}W (3min) · Z${ctx.powerZone} of 7 · FTP ${ctx.ftp}W")
            if (ctx.heartRateBpm > 0) {
                val hrLine = buildString {
                    append("HR: ${ctx.heartRateBpm} bpm · Z${ctx.hrZone} of 5")
                    if (ctx.hrRecoveryRate > 0) append(" · recovering at ${"%.2f".format(ctx.hrRecoveryRate)} bpm/s")
                    if (ctx.hrDecouplingPct > 3f) append(" · decoupling ${ctx.hrDecouplingPct.toInt()}%")
                }
                appendLine(hrLine)
            }
        }

        if (ctx.cadenceRpm > 0) appendLine("Cadence: ${ctx.cadenceRpm} rpm")
        if (ctx.speedKmh > 0f) appendLine("Speed: ${"%.1f".format(ctx.speedKmh)} km/h · Distance: ${"%.1f".format(ctx.distanceKm)} km")
        if (ctx.elevationGradePct != 0f) {
            val terrain = when {
                ctx.elevationGradePct > 6f -> "steep climb"
                ctx.elevationGradePct > 2f -> "climb"
                ctx.elevationGradePct < -3f -> "descent"
                else -> "flat"
            }
            appendLine("Terrain: ${"%.1f".format(ctx.elevationGradePct)}% grade ($terrain)")
            if (ctx.distanceToClimbTopM != null) {
                appendLine("Distance to summit: ${ctx.distanceToClimbTopM.toInt()}m")
            }
        }

        // Workout interval context
        val ws = ctx.workout
        if (ws.isActive) {
            appendLine()
            appendLine("## Interval")
            val targetUnit = if (ws.targetType == TargetType.HEART_RATE) "bpm" else "W"

            // Workout type + coaching emphasis
            if (ws.workoutType != WorkoutType.UNKNOWN) {
                val typeLabel = ws.workoutType.name.replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
                val policy = WorkoutTypePolicy.forType(ws.workoutType)
                appendLine("Workout type: $typeLabel")
                appendLine("Coaching emphasis: ${policy.aiEmphasis}")
            }

            appendLine("${ws.currentPhase.name.lowercase()} · step ${ws.currentStep + 1}/${ws.totalSteps} · ${ws.intervalElapsedSec}s elapsed / ${ws.intervalRemainingSec}s left")
            if (ws.targetLow != null || ws.targetHigh != null) {
                val currentMetric = if (ws.targetType == TargetType.HEART_RATE) ctx.heartRateBpm else ctx.power30sAvg
                val diff = when {
                    ws.targetHigh != null && currentMetric > ws.targetHigh + (if (ws.targetType == TargetType.HEART_RATE) 5 else ws.targetHigh * 10 / 100) ->
                        "+${currentMetric - ws.targetHigh}$targetUnit above ceiling"
                    ws.targetLow != null && currentMetric < ws.targetLow - (if (ws.targetType == TargetType.HEART_RATE) ws.targetLow * 5 / 100 else ws.targetLow * 10 / 100) ->
                        "-${ws.targetLow - currentMetric}$targetUnit below floor"
                    else -> "on target"
                }
                appendLine("Target ${ws.targetLow ?: "?"}–${ws.targetHigh ?: "?"}$targetUnit · currently $diff")
                if (ws.targetType == TargetType.HEART_RATE) {
                    appendLine("Note: this is an HR-based workout. Power is secondary — coach to HR zones only.")
                }
            }
            if (ws.effortAvgPowers.size >= 2) {
                appendLine("Effort set avgs: ${ws.effortAvgPowers.joinToString("→")}W" +
                    if (ws.powerFadingTrend) " (fading)" else "")
            }
            if (ws.recoveryDropRates.size >= 2) {
                appendLine("HR recovery trend: ${if (ws.recoveryQualityDeclining) "slowing (fatigue)" else "stable"}")
            }
        }

        // Nutrition & hydration
        if (ctx.rideElapsedSec > 1200) {
            appendLine()
            appendLine("## Nutrition")
            appendLine("Carbs: ${ctx.carbsConsumedGrams}g consumed / ${ctx.carbTargetGrams}g target" +
                if (ctx.carbDeficitGrams > 10) " · deficit ${ctx.carbDeficitGrams}g" else " · on track")
            val msSinceEat = if (ctx.lastFuelAckEpochSec > 0)
                (System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec) / 60 else -1
            val msSinceDrink = if (ctx.lastDrinkAckEpochSec > 0)
                (System.currentTimeMillis() / 1000 - ctx.lastDrinkAckEpochSec) / 60 else -1
            if (msSinceEat >= 0) appendLine("Last ate: ${msSinceEat}min ago (${ctx.fuelAckCount} total)")
            if (msSinceDrink >= 0) appendLine("Last drank: ${msSinceDrink}min ago (${ctx.drinkAckCount} total)")
            if (msSinceEat < 0 && ctx.rideElapsedSec > 2700) appendLine("⚠ No fueling logged yet")
        }

        // Ride narrative — the story so far
        val narrativeText = narrative.buildNarrative(ctx)
        if (narrativeText != null) {
            appendLine()
            appendLine("## Ride so far")
            appendLine(narrativeText)
        }

        appendLine()
        appendLine("Give the coaching cue:")
    }.trim()

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun ruleDescription(ruleId: String): String = when (ruleId) {
        "pre_interval_alert" -> "Hard effort approaching in 60–90 seconds"
        "pre_interval_fueling" -> "Hard effort approaching, carb deficit exists"
        "power_above_target" -> "30s power above workout ceiling"
        "power_below_target" -> "30s power below workout floor"
        "power_on_target" -> "Power settled in target range — reinforce"
        "interval_countdown" -> "30 seconds remaining in effort"
        "cadence_dropping" -> "Cadence well below minimum during effort"
        "hr_ceiling_exceeded" -> "HR above HR-based workout ceiling — ease off"
        "hr_below_target" -> "HR below HR-based workout floor — rider needs to push more"
        "hr_on_target" -> "HR settled in target zone — positive reinforcement"
        "recovery_not_recovering" -> "Power still Z3+ during recovery — not backing off"
        "hr_not_dropping" -> "HR still elevated 60s into recovery"
        "recovery_fueling_window" -> "Long recovery window — good time to fuel"
        "power_fading_trend" -> "Power declining across effort sets this session"
        "recovery_quality_declining" -> "HR recovery rate slowing set-over-set"
        "session_complete" -> "All intervals finished, entering cooldown"
        "last_interval_motivation" -> "Final effort block of the session"
        "endurance_zone_drift" -> "Drifted from Z2 into Z3+ for more than 3 minutes"
        "fuel_time_based" -> "Carb deficit or time-based fueling reminder"
        "drink_reminder" -> "Hydration reminder — rider hasn't logged a drink recently"
        "fueling_reminder" -> "Elapsed time-based fueling reminder"
        "hr_decoupling_alert" -> "HR:power decoupling — cardiac drift detected"
        "late_ride_protection" -> "Pushing too hard in final portion of long ride"
        "pacing_too_variable" -> "Power too variable for endurance pacing"
        "climb_entry" -> "Entering a categorised climb"
        "climb_power_ceiling" -> "Above power ceiling for long climb"
        "climb_cadence_drop" -> "Cadence dropped on the climb"
        "summit_proximity" -> "Within ~500m of the summit"
        "descent_reminder" -> "On descent — recover and fuel opportunity"
        "multi_climb_fatigue" -> "Multiple climbs done — cumulative fatigue"
        "adaptive_observing" -> "Ride start — observing before coaching"
        "adaptive_endurance" -> "Ride classified as endurance — coaching adapts"
        "adaptive_recovery" -> "Going too hard on a recovery ride"
        "first_interval" -> "First effort block of the session"
        else -> ruleId.replace('_', ' ')
    }
}
