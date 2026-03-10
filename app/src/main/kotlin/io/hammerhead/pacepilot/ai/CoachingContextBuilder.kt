package io.hammerhead.pacepilot.ai

import io.hammerhead.pacepilot.history.RideHistory
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.currentMode

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

        // Power — most important signal, always include
        appendLine("Power: ${ctx.power5sAvg}W (5s) / ${ctx.power30sAvg}W (30s) / ${ctx.power3minAvg}W (3min) · Z${ctx.powerZone} of 7 · FTP ${ctx.ftp}W")

        // HR — if available
        if (ctx.heartRateBpm > 0) {
            val hrLine = buildString {
                append("HR: ${ctx.heartRateBpm} bpm · Z${ctx.hrZone} of 5")
                if (ctx.hrRecoveryRate > 0) append(" · recovering at ${"%.2f".format(ctx.hrRecoveryRate)} bpm/s")
                if (ctx.hrDecouplingPct > 3f) append(" · decoupling ${ctx.hrDecouplingPct.toInt()}%")
            }
            appendLine(hrLine)
        }

        if (ctx.cadenceRpm > 0) appendLine("Cadence: ${ctx.cadenceRpm} rpm")
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
            appendLine("${ws.currentPhase.name.lowercase()} · step ${ws.currentStep + 1}/${ws.totalSteps} · ${ws.intervalElapsedSec}s elapsed / ${ws.intervalRemainingSec}s left")
            if (ws.targetLow != null || ws.targetHigh != null) {
                val diff = when {
                    ws.targetHigh != null && ctx.power30sAvg > ws.targetHigh ->
                        "+${ctx.power30sAvg - ws.targetHigh}W above ceiling"
                    ws.targetLow != null && ctx.power30sAvg < ws.targetLow ->
                        "-${ws.targetLow - ctx.power30sAvg}W below floor"
                    else -> "on target"
                }
                appendLine("Target ${ws.targetLow ?: "?"}–${ws.targetHigh ?: "?"}W · currently $diff")
            }
            if (ws.effortAvgPowers.size >= 2) {
                appendLine("Effort set avgs: ${ws.effortAvgPowers.joinToString("→")}W" +
                    if (ws.powerFadingTrend) " (fading)" else "")
            }
            if (ws.recoveryDropRates.size >= 2) {
                appendLine("HR recovery trend: ${if (ws.recoveryQualityDeclining) "slowing (fatigue)" else "stable"}")
            }
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
        "hr_ceiling_exceeded" -> "HR above HR-based workout ceiling"
        "recovery_not_recovering" -> "Power still Z3+ during recovery — not backing off"
        "hr_not_dropping" -> "HR still elevated 60s into recovery"
        "recovery_fueling_window" -> "Long recovery window — good time to fuel"
        "power_fading_trend" -> "Power declining across effort sets this session"
        "recovery_quality_declining" -> "HR recovery rate slowing set-over-set"
        "session_complete" -> "All intervals finished, entering cooldown"
        "last_interval_motivation" -> "Final effort block of the session"
        "endurance_zone_drift" -> "Drifted from Z2 into Z3+ for more than 3 minutes"
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
