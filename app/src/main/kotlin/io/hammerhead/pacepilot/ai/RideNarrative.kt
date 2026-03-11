package io.hammerhead.pacepilot.ai

import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode
import io.hammerhead.pacepilot.util.RollingAverage

/**
 * Accumulates a live "story" of the current ride that the LLM uses as context.
 *
 * Updated every second via [onContext]. Provides [buildNarrative] to produce
 * a concise text summary of key trends, milestones, and warnings that occurred
 * during the current ride — things the rule engine can't communicate to the LLM
 * otherwise.
 *
 * This is what makes the LLM feel like it was *watching the ride from the start*
 * rather than just seeing a snapshot.
 */
class RideNarrative {

    // Baseline established in first 10 minutes
    private var baselinePower: Int = 0
    private var baselineHr: Int = 0
    private var baselineEstablished = false

    // Rolling averages for first-hour reference
    private val firstHourPower = RollingAverage(3600)
    private var firstHourLocked = false
    private var firstHourAvgPower: Int = 0

    // Interval phase tracking for auto-detection of completed efforts
    private var lastPhase: IntervalPhase = IntervalPhase.UNKNOWN
    private var lastStep: Int = -1

    // HR drift tracking
    private var hrDriftStartSec: Long = 0L
    private var hrDriftBasePower: Int = 0
    private var hrDriftBaseHr: Int = 0
    private var hrDriftWarned = false

    // Power drop vs first hour
    private var powerDropWarned = false

    // Key events log (capped, most recent kept)
    private val events = ArrayDeque<RideEvent>(MAX_EVENTS)

    // Fueling timeline
    private var lastFuelAckEpochSec: Long = 0L
    private var fuelAckCount: Int = 0

    // Interval stats
    private var completedIntervals: Int = 0
    private var missedIntervals: Int = 0   // below target
    private var overcooked: Int = 0        // above target ceiling

    companion object {
        private const val MAX_EVENTS = 12
        private const val BASELINE_WINDOW_SEC = 600     // 10 min
        private const val FIRST_HOUR_SEC = 3600
        private const val HR_DRIFT_THRESHOLD_PCT = 8f   // 8% HR rise at same power = drift
    }

    fun onContext(ctx: RideContext) {
        val elapsed = ctx.rideElapsedSec

        // Establish baseline from 5-10 min window
        if (!baselineEstablished && elapsed in 300..600) {
            if (ctx.power30sAvg > 30 && ctx.heartRateBpm > 60) {
                baselinePower = ctx.power30sAvg
                baselineHr = ctx.heartRateBpm
                baselineEstablished = true
            }
        }

        // First-hour rolling avg
        if (!firstHourLocked) {
            if (ctx.power30sAvg > 0) firstHourPower.add(ctx.power30sAvg.toDouble())
            if (elapsed >= FIRST_HOUR_SEC) {
                firstHourAvgPower = firstHourPower.average.toInt()
                firstHourLocked = true
            }
        }

        // Power drop >10% vs first hour (after 90 min)
        if (firstHourLocked && !powerDropWarned && elapsed > 5400 && ctx.power30sAvg > 0 && firstHourAvgPower > 0) {
            val dropPct = (firstHourAvgPower - ctx.power3minAvg).toFloat() / firstHourAvgPower * 100f
            if (dropPct > 10f) {
                logEvent("Power dropped ${dropPct.toInt()}% vs first hour (${firstHourAvgPower}W → ${ctx.power3minAvg}W avg)", elapsed)
                powerDropWarned = true
            }
        }

        // HR drift: same power but HR climbing (decoupling signal)
        if (baselineEstablished && elapsed > 3600 && !hrDriftWarned && ctx.hrDecouplingPct > 5f) {
            logEvent("HR decoupling ${ctx.hrDecouplingPct.toInt()}% — cardiac drift (fatigue signal)", elapsed)
            hrDriftWarned = true
        }

        // Auto-detect interval completion (EFFORT → RECOVERY/COOLDOWN transition)
        if (ctx.workout.isActive) {
            val currentPhase = ctx.workout.currentPhase
            val currentStep = ctx.workout.currentStep
            if (lastPhase == IntervalPhase.EFFORT &&
                currentPhase != IntervalPhase.EFFORT &&
                currentStep != lastStep
            ) {
                val wasOver = ctx.workout.complianceScore < 0.8f && ctx.power30sAvg > (ctx.workout.targetHigh ?: Int.MAX_VALUE)
                val wasBelow = ctx.workout.complianceScore < 0.8f && ctx.power30sAvg < (ctx.workout.targetLow ?: 0)
                onIntervalCompleted(wasOver, wasBelow)
            }
            lastPhase = currentPhase
            lastStep = currentStep
        }

        // Fueling ack tracking
        if (ctx.lastFuelAckEpochSec > lastFuelAckEpochSec) {
            lastFuelAckEpochSec = ctx.lastFuelAckEpochSec
            fuelAckCount++
            logEvent("Fueled (x$fuelAckCount total)", elapsed)
        }
    }

    /** Call when an interval phase completes to track compliance */
    fun onIntervalCompleted(wasOverTarget: Boolean, wasBelowTarget: Boolean) {
        completedIntervals++
        if (wasOverTarget) overcooked++
        if (wasBelowTarget) missedIntervals++
    }

    /** Log an arbitrary key event (mode changes, climbs, etc.) */
    fun logEvent(description: String, rideElapsedSec: Long) {
        val minMark = rideElapsedSec / 60
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(RideEvent(minMark, description))
    }

    /**
     * Produces a compact narrative paragraph for the LLM prompt.
     * Only includes information that's meaningful — returns null if ride is too short.
     */
    fun buildNarrative(ctx: RideContext): String? {
        if (ctx.rideElapsedSec < 300) return null // too early for meaningful narrative

        return buildString {
            // Baseline
            if (baselineEstablished) {
                appendLine("Baseline (10 min): ${baselinePower}W @ ${baselineHr} bpm")
            }

            // First-hour reference
            if (firstHourLocked) {
                appendLine("First-hour avg power: ${firstHourAvgPower}W")
                val currentAvg = ctx.power3minAvg
                if (currentAvg > 0) {
                    val delta = currentAvg - firstHourAvgPower
                    val sign = if (delta >= 0) "+" else ""
                    appendLine("Current 3-min avg vs first hour: ${sign}${delta}W")
                }
            }

            // Mode history (if changed)
            if (ctx.currentMode != RideMode.ADAPTIVE) {
                appendLine("Current mode: ${ctx.currentMode.name}")
            }

            // Interval summary
            if (completedIntervals > 0) {
                val summary = buildString {
                    append("$completedIntervals intervals completed")
                    if (overcooked > 0) append(", $overcooked overcook")
                    if (missedIntervals > 0) append(", $missedIntervals below target")
                }
                appendLine(summary)
            }

            // Fueling
            if (fuelAckCount > 0) {
                appendLine("Fueled $fuelAckCount time(s) this ride")
            } else if (ctx.rideElapsedSec >= 2700) {
                appendLine("⚠ No fueling confirmed yet (${ctx.rideElapsedSec / 60} min in)")
            }

            // Key events
            if (events.isNotEmpty()) {
                appendLine("Key events:")
                events.forEach { appendLine("  ${it.minMark}min: ${it.description}") }
            }
        }.trim().takeIf { it.isNotBlank() }
    }

    fun reset() {
        baselinePower = 0; baselineHr = 0; baselineEstablished = false
        firstHourPower.clear(); firstHourLocked = false; firstHourAvgPower = 0
        hrDriftWarned = false; powerDropWarned = false
        hrDriftStartSec = 0; hrDriftBasePower = 0; hrDriftBaseHr = 0
        events.clear()
        lastFuelAckEpochSec = 0; fuelAckCount = 0
        completedIntervals = 0; missedIntervals = 0; overcooked = 0
    }

    private data class RideEvent(val minMark: Long, val description: String)
}
