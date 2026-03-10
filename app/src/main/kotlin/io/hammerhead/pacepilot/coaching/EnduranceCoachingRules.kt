package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.model.isHrBasedWorkout
import io.hammerhead.pacepilot.util.ZoneCalculator

/**
 * Coaching rules for ENDURANCE mode.
 * Goal: zone discipline, drift prevention, fueling cadence, late-ride protection.
 */
object EnduranceCoachingRules {

    /**
     * Zone drift — rider drifting from Z2 into Z3 (tempo) for >3 min.
     * The most common endurance-ride mistake.
     */
    fun zoneDrift(ctx: RideContext): CoachingEvent? {
        if (ctx.ftp <= 0) return null
        // Don't warn about power zone when following an HR-based workout plan —
        // higher power is expected and the HR rules will coach appropriately
        if (ctx.isHrBasedWorkout) return null

        val z3Lower = ZoneCalculator.powerZoneLowerWatts(3, ctx.ftp)
        val z4Lower = ZoneCalculator.powerZoneLowerWatts(4, ctx.ftp)

        // 3-min average in Z3 (not Z4+, which would be a different coaching message)
        if (ctx.power3minAvg < z3Lower || ctx.power3minAvg >= z4Lower) return null

        // Only fire after 15 min of riding (let rider settle)
        if (ctx.rideElapsedSec < 900) return null

        val pct = ctx.power3minAvg * 100 / ctx.ftp
        return CoachingEvent(
            ruleId = RuleId.ZONE_DRIFT,
            message = "You're drifting into tempo. Settle back to Z2.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 300, // max once per 5 min
        )
    }

    /**
     * Time-based fueling reminder.
     * Every 30-45 min, prompt to eat/drink.
     */
    fun fuelTimeBasedReminder(ctx: RideContext): CoachingEvent? {
        if (ctx.rideElapsedSec < 1800) return null // min 30 min before first prompt

        // If NomRide is available, defer to its data
        if (ctx.carbDeficitGrams != null) {
            val deficit = ctx.carbDeficitGrams
            if (deficit < 15) return null // deficit not significant enough
        }

        // Time since last ack
        val sinceAck = System.currentTimeMillis() / 1000 - ctx.lastFuelingAckSec
        val timeSinceFuel = ctx.timeSinceLastFuelSec
        val minInterval = if (ctx.rideElapsedSec > 7200) 1800L else 2700L // more often in long rides

        val shouldFire = when {
            timeSinceFuel != null -> timeSinceFuel >= minInterval
            sinceAck >= minInterval -> true
            else -> false
        }
        if (!shouldFire) return null

        return CoachingEvent(
            ruleId = RuleId.FUEL_TIME_BASED,
            message = "Time to fuel. Take something.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = minInterval.toInt(),
        )
    }

    /**
     * HR:power decoupling — HR drifting up relative to power (cardiac drift).
     * Signals fatigue accumulation in a long ride.
     */
    fun hrDecoupling(ctx: RideContext): CoachingEvent? {
        if (ctx.hrDecouplingPct < 5f) return null // < 5% decoupling is normal
        if (ctx.rideElapsedSec < 5400) return null // needs at least 90 min to establish baseline

        return CoachingEvent(
            ruleId = RuleId.HR_DECOUPLING,
            message = "HR drifting vs power. Protect the effort.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 1800, // once per 30 min
        )
    }

    /**
     * Late-ride protection — keep the last hour of a long ride in Z2.
     * Fires when ride is in the final hour and rider is drifting up.
     */
    fun protectLastHour(ctx: RideContext): CoachingEvent? {
        // Only relevant on rides > 2.5 hours (at min 1.5h mark)
        if (ctx.rideElapsedSec < 5400) return null // less than 1.5h in

        val z3Lower = ZoneCalculator.powerZoneLowerWatts(3, ctx.ftp)
        if (ctx.power30sAvg < z3Lower) return null // already in Z2, fine

        // Only fire once we're clearly in the long-ride phase
        // Heuristic: if 3-min avg keeps creeping up and decoupling exists
        if (ctx.hrDecouplingPct < 3f && ctx.power30sAvg < z3Lower + ctx.ftp * 5 / 100) return null

        return CoachingEvent(
            ruleId = RuleId.PROTECT_LAST_HOUR,
            message = "Protect the last hour. Stay in Z2.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 1800,
        )
    }

    /**
     * Pacing consistency — positive reinforcement for clean Z2 riding.
     * Sparse — fires once early in the ride to confirm good execution.
     */
    fun pacingConsistent(ctx: RideContext): CoachingEvent? {
        if (ctx.rideElapsedSec < 1200 || ctx.rideElapsedSec > 2400) return null // 20-40 min in only

        val z2Lower = ZoneCalculator.powerZoneLowerWatts(2, ctx.ftp)
        val z2Upper = ZoneCalculator.powerZoneUpperWatts(2, ctx.ftp)

        if (ctx.power3minAvg < z2Lower || ctx.power3minAvg > z2Upper) return null
        if (ctx.variabilityIndex > 1.08f) return null // too variable

        return CoachingEvent(
            ruleId = RuleId.PACING_CONSISTENT,
            message = "Good pacing. Hold this.",
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.POSITIVE,
            suppressIfFiredInLastSec = 3600, // once per ride
        )
    }

    /**
     * Early-ride steady-state check — fires around 5 min mark.
     * Gives the rider early feedback that coaching is active and watching.
     */
    fun earlyRideCheck(ctx: RideContext): CoachingEvent? {
        if (ctx.rideElapsedSec < 300 || ctx.rideElapsedSec > 420) return null
        if (ctx.ftp <= 0) return null

        val z2Lower = ZoneCalculator.powerZoneLowerWatts(2, ctx.ftp)
        val z2Upper = ZoneCalculator.powerZoneUpperWatts(2, ctx.ftp)
        val inZ2 = ctx.power3minAvg in z2Lower..z2Upper

        val msg = if (inZ2) "Settled in nicely. Z2 on target." else "Find your rhythm. Settle into Z2."
        val style = if (inZ2) AlertStyle.POSITIVE else AlertStyle.COACHING

        return CoachingEvent(
            ruleId = RuleId.EARLY_RIDE_CHECK,
            message = msg,
            priority = CoachingPriority.LOW,
            alertStyle = style,
            suppressIfFiredInLastSec = 3600,
        )
    }

    fun evaluateAll(ctx: RideContext): List<CoachingEvent> =
        listOfNotNull(
            earlyRideCheck(ctx),
            zoneDrift(ctx),
            fuelTimeBasedReminder(ctx),
            hrDecoupling(ctx),
            protectLastHour(ctx),
            pacingConsistent(ctx),
        )
}
