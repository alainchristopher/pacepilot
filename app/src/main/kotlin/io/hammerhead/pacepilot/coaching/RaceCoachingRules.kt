package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.history.RacePlan
import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.settings.UserSettings

/**
 * Race-day coaching for triathlon bike legs — steady power, run-leg protection,
 * negative split, finish-line awareness, and race fueling cadence.
 */
object RaceCoachingRules {

    private const val POWER_BAND_W = 8
    private const val VI_THRESHOLD = 1.06f
    private const val T2_FUEL_CUTOFF_MIN = 20

    fun evaluateAll(
        ctx: RideContext,
        plan: RacePlan,
        settings: UserSettings,
    ): List<CoachingEvent> {
        if (!plan.enabled) return emptyList()
        val target = plan.resolveTargetWatts(ctx.ftp)
        if (target <= 0) return emptyList()

        return listOfNotNull(
            negativeSplitOpening(ctx, plan, target),
            powerAbovePlan(ctx, target),
            powerBelowPlan(ctx, target),
            viWatchdog(ctx),
            finishLine75(ctx, plan),
            finishLine90(ctx, plan),
            raceFuel(ctx, plan, settings),
            raceDrink(ctx, settings),
            t2Prep(ctx, plan),
        )
    }

    /** First ~40 km slightly under race watts */
    private fun negativeSplitOpening(ctx: RideContext, plan: RacePlan, target: Int): CoachingEvent? {
        if (ctx.distanceKm > plan.negativeSplitThroughKm) return null
        if (ctx.rideElapsedSec < 600) return null
        val opening = plan.openingTargetWatts(ctx.ftp)
        if (ctx.power30sAvg <= opening + POWER_BAND_W) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_NEGATIVE_SPLIT,
            message = "Hold ${opening}W. Bank time early.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 600,
        )
    }

    /** Run-leg protection — NP / 30s avg above plan */
    private fun powerAbovePlan(ctx: RideContext, target: Int): CoachingEvent? {
        val avg = if (ctx.normalizedPower > 0) ctx.normalizedPower else ctx.power30sAvg
        if (avg <= target + POWER_BAND_W) return null
        if (ctx.rideElapsedSec < 300) return null
        val over = avg - target
        return CoachingEvent(
            ruleId = RuleId.RACE_POWER_HIGH,
            message = "Over plan +${over}W. Protect run.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 180,
        )
    }

    private fun powerBelowPlan(ctx: RideContext, target: Int): CoachingEvent? {
        if (ctx.rideElapsedSec < 1200) return null
        if (ctx.power30sAvg >= target - POWER_BAND_W * 2) return null
        if (ctx.elevationGradePct > 3f) return null // allow lower on climbs
        return CoachingEvent(
            ruleId = RuleId.RACE_POWER_LOW,
            message = "Below ${target}W. Hold race plan.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 300,
        )
    }

    private fun viWatchdog(ctx: RideContext): CoachingEvent? {
        if (ctx.rideElapsedSec < 1800) return null
        if (ctx.variabilityIndex <= VI_THRESHOLD) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_VI_HIGH,
            message = "VI ${"%.2f".format(ctx.variabilityIndex)}. Steady watts.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 600,
        )
    }

    private fun finishLine75(ctx: RideContext, plan: RacePlan): CoachingEvent? {
        val totalKm = routeTotalKm(ctx, plan)
        if (totalKm <= 0f) return null
        val pct = ctx.distanceKm / totalKm
        if (pct < 0.74f || pct > 0.78f) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_FINISH_75,
            message = "75% done. Execute plan.",
            priority = CoachingPriority.INFO,
            alertStyle = AlertStyle.INFO,
            suppressIfFiredInLastSec = 3600,
        )
    }

    private fun finishLine90(ctx: RideContext, plan: RacePlan): CoachingEvent? {
        val totalKm = routeTotalKm(ctx, plan)
        if (totalKm <= 0f) return null
        val pct = ctx.distanceKm / totalKm
        if (pct < 0.88f || pct > 0.92f) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_FINISH_90,
            message = "90% done. Finish smart.",
            priority = CoachingPriority.INFO,
            alertStyle = AlertStyle.INFO,
            suppressIfFiredInLastSec = 3600,
        )
    }

    private fun raceFuel(ctx: RideContext, plan: RacePlan, settings: UserSettings): CoachingEvent? {
        if (ctx.rideElapsedSec < 1200) return null
        val remainingMin = plan.durationMin - (ctx.rideElapsedSec / 60).toInt()
        if (remainingMin <= T2_FUEL_CUTOFF_MIN) return null
        val sinceEat = if (ctx.lastFuelAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastFuelAckEpochSec
        else ctx.rideElapsedSec
        if (sinceEat < 1200 && ctx.carbDeficitGrams < settings.fuelingAlertThresholdGrams) return null
        if (ctx.carbDeficitGrams < settings.fuelingAlertThresholdGrams && sinceEat < 1500) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_FUEL,
            message = "Race fuel. ${ctx.carbDeficitGrams}g deficit.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 1200,
        )
    }

    private fun raceDrink(ctx: RideContext, settings: UserSettings): CoachingEvent? {
        if (ctx.rideElapsedSec < 900) return null
        val sinceDrink = if (ctx.lastDrinkAckEpochSec > 0)
            System.currentTimeMillis() / 1000 - ctx.lastDrinkAckEpochSec
        else ctx.rideElapsedSec
        val intervalSec = settings.drinkReminderMinutes * 60L
        if (sinceDrink < intervalSec) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_DRINK,
            message = "Race drink. Hydrate now.",
            priority = CoachingPriority.LOW,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = intervalSec.toInt(),
        )
    }

    private fun t2Prep(ctx: RideContext, plan: RacePlan): CoachingEvent? {
        val remainingMin = plan.durationMin - (ctx.rideElapsedSec / 60).toInt()
        if (remainingMin !in 18..22) return null
        return CoachingEvent(
            ruleId = RuleId.RACE_T2_PREP,
            message = "T2 in ~20min. Ease fuel.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 3600,
        )
    }

    private fun routeTotalKm(ctx: RideContext, plan: RacePlan): Float {
        if (ctx.routeDistanceKm > 0f) return ctx.routeDistanceKm
        return plan.distanceKm
    }

    /** Signed watts vs race plan for data field */
    fun racePowerDelta(ctx: RideContext, plan: RacePlan): Int {
        val target = plan.resolveTargetWatts(ctx.ftp)
        if (target <= 0) return 0
        val avg = if (ctx.power30sAvg > 0) ctx.power30sAvg else ctx.powerWatts
        return avg - target
    }
}
