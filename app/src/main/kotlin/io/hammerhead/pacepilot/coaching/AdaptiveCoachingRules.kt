package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.currentMode
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * Coaching rules for ADAPTIVE and RECOVERY modes.
 *
 * ADAPTIVE mode: observe first 10 min, then infer ride type and hand off
 * to appropriate coaching sub-strategy.
 *
 * RECOVERY mode: strict Z1 ceiling enforcement.
 */
object AdaptiveCoachingRules {

    private val OBSERVATION_PERIOD_SEC = 600  // 10 minutes

    /**
     * Observation announcement — fires once at ride start in ADAPTIVE mode.
     */
    fun adaptiveObserving(ctx: RideContext): CoachingEvent? {
        if (ctx.currentMode != RideMode.ADAPTIVE) return null
        if (ctx.rideElapsedSec > 60) return null // only in first 60s

        return CoachingEvent(
            ruleId = RuleId.ADAPTIVE_OBSERVING,
            message = "Reading ride. Coaching starts soon.",
            priority = CoachingPriority.INFO,
            alertStyle = AlertStyle.INFO,
            suppressIfFiredInLastSec = 3600, // once per ride
        )
    }

    /**
     * After observation period: infer ride type and announce coaching mode.
     * Endurance profile: avg zone 2-3, low variability.
     */
    fun adaptiveEnduranceDetected(ctx: RideContext): CoachingEvent? {
        if (ctx.currentMode != RideMode.ADAPTIVE) return null
        if (ctx.rideElapsedSec < OBSERVATION_PERIOD_SEC) return null
        if (ctx.rideElapsedSec > OBSERVATION_PERIOD_SEC + 60) return null // only fire once

        if (ctx.ftp <= 0) return null
        val avgZone = ctx.powerZone // approximation using current zone
        if (avgZone < 2 || avgZone > 3) return null // not an endurance zone
        if (ctx.variabilityIndex > 1.15f) return null // too variable for endurance

        return CoachingEvent(
            ruleId = RuleId.ADAPTIVE_ENDURANCE,
            message = "Endurance ride. Coaching active.",
            priority = CoachingPriority.INFO,
            alertStyle = AlertStyle.INFO,
            suppressIfFiredInLastSec = 3600,
        )
    }

    /**
     * Unstructured ride detected — high variability, mixed zones.
     */
    fun adaptiveUnstructured(ctx: RideContext): CoachingEvent? {
        if (ctx.currentMode != RideMode.ADAPTIVE) return null
        if (ctx.rideElapsedSec < OBSERVATION_PERIOD_SEC) return null
        if (ctx.rideElapsedSec > OBSERVATION_PERIOD_SEC + 60) return null

        if (ctx.variabilityIndex < 1.15f) return null // not variable enough

        return CoachingEvent(
            ruleId = RuleId.ADAPTIVE_UNSTRUCTURED,
            message = "Unstructured ride. Fueling coach on.",
            priority = CoachingPriority.INFO,
            alertStyle = AlertStyle.INFO,
            suppressIfFiredInLastSec = 3600,
        )
    }

    /**
     * First fueling reminder after 30 min for adaptive/recovery rides.
     */
    fun fuelFirst30Min(ctx: RideContext): CoachingEvent? {
        if (ctx.currentMode != RideMode.ADAPTIVE && ctx.currentMode != RideMode.RECOVERY) return null
        if (ctx.rideElapsedSec < 1800 || ctx.rideElapsedSec > 1860) return null // 30-31 min window

        val sinceAck = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec else ctx.rideElapsedSec
        if (sinceAck < 1800) return null // already fueled

        return CoachingEvent(
            ruleId = RuleId.FUEL_FIRST_30MIN,
            message = "30 min in. Time to start fueling.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 3600,
        )
    }

    /**
     * Recovery mode strict ceiling — fire when power goes above Z1.
     */
    fun recoveryTooHard(ctx: RideContext): CoachingEvent? {
        if (ctx.currentMode != RideMode.RECOVERY) return null
        if (ctx.ftp <= 0) return null

        val z1Upper = ZoneCalculator.powerZoneUpperWatts(1, ctx.ftp)
        if (ctx.power30sAvg <= z1Upper) return null

        // Don't fire in first 5 min (rider finding their legs)
        if (ctx.rideElapsedSec < 300) return null

        return CoachingEvent(
            ruleId = RuleId.ADAPTIVE_RECOVERY,
            message = "Too hard. Recovery = Z1. Ease off.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 180,
        )
    }

    fun evaluateAll(ctx: RideContext): List<CoachingEvent> {
        val base = listOfNotNull(
            adaptiveObserving(ctx),
            adaptiveEnduranceDetected(ctx),
            adaptiveUnstructured(ctx),
            fuelFirst30Min(ctx),
            recoveryTooHard(ctx),
        )

        // After observation period in ADAPTIVE, delegate to endurance rules
        // so the rider gets real coaching (zone drift, fueling, pacing, etc.)
        if (ctx.currentMode == RideMode.ADAPTIVE && ctx.rideElapsedSec > OBSERVATION_PERIOD_SEC) {
            return base + EnduranceCoachingRules.evaluateAll(ctx)
        }
        return base
    }
}
