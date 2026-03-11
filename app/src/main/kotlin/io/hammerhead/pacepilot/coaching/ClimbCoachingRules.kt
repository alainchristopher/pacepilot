package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.AlertStyle
import io.hammerhead.pacepilot.model.CoachingEvent
import io.hammerhead.pacepilot.model.CoachingPriority
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RuleId
import io.hammerhead.pacepilot.util.ZoneCalculator
import kotlin.math.roundToInt

/**
 * Coaching rules for CLIMB_FOCUSED mode and temporary climb coaching during ENDURANCE.
 */
object ClimbCoachingRules {

    /**
     * Climb entry — fired once when entering a climb.
     * "Settle in, don't overcook the start."
     */
    fun climbEntry(ctx: RideContext): CoachingEvent? {
        if (!ctx.isOnClimb) return null
        if (ctx.elevationGradePct < 3f) return null // only on real climbs

        // Fire in the first 30s of the climb
        // We detect "new climb" via distance to top appearing (7Climb integration)
        // or via distanceToClimbTopM being set
        val distToTop = ctx.distanceToClimbTopM ?: return null
        if (distToTop <= 0) return null

        // Only at start of a new climb segment
        // Use a large distance window: fires once when distToTop is large (>1km) and we just entered
        // (The CoachingEngine's suppression window handles not re-firing mid-climb)
        if (distToTop < 800) return null // not at start anymore

        val multiClimbMsg = if (ctx.totalClimbsOnRoute > 1) {
            "Climb ${ctx.climbNumber}/${ctx.totalClimbsOnRoute}. Settle in."
        } else {
            "Climb ahead. Settle in."
        }

        return CoachingEvent(
            ruleId = RuleId.CLIMB_ENTRY,
            message = multiClimbMsg,
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 600, // don't re-fire for 10 min
        )
    }

    /**
     * Power ceiling on long climb — pace sustainably for the full ascent.
     * Fires when rider is significantly above sweet spot zone on a long climb.
     */
    fun climbPowerCeiling(ctx: RideContext): CoachingEvent? {
        if (!ctx.isOnClimb || ctx.ftp <= 0) return null
        if (ctx.elevationGradePct < 3f) return null

        // For long climbs (>2km remaining), enforce sweet spot ceiling (~90% FTP)
        val distToTop = ctx.distanceToClimbTopM ?: 0f
        if (distToTop < 500) return null // near summit, let them push

        val sweetSpotCeiling = ctx.ftp * 95 / 100
        if (ctx.power30sAvg <= sweetSpotCeiling) return null

        val overBy = ctx.power30sAvg - sweetSpotCeiling
        return CoachingEvent(
            ruleId = RuleId.CLIMB_POWER_CEILING,
            message = "Long climb. Back off ${overBy}W.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 180,
        )
    }

    /**
     * Cadence dropping on climb — prompt to shift lighter.
     */
    fun climbCadenceDrop(ctx: RideContext): CoachingEvent? {
        if (!ctx.isOnClimb) return null
        if (ctx.cadenceRpm <= 0) return null
        if (ctx.cadenceRpm >= 65) return null // above 65 rpm, fine on a climb

        return CoachingEvent(
            ruleId = RuleId.CLIMB_CADENCE_DROP,
            message = "Cadence dropping. Shift lighter.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 120,
        )
    }

    /**
     * Summit proximity — fire when 500m or less to top.
     */
    fun climbSummitNear(ctx: RideContext): CoachingEvent? {
        if (!ctx.isOnClimb) return null
        val distToTop = ctx.distanceToClimbTopM ?: return null

        // Fire in the 200-600m window
        if (distToTop !in 100f..600f) return null

        val dist = distToTop.roundToInt()
        return CoachingEvent(
            ruleId = RuleId.CLIMB_SUMMIT_NEAR,
            message = "${dist}m to summit. Hold it.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.COACHING,
            suppressIfFiredInLastSec = 120,
        )
    }

    /**
     * Descent coaching — recover and fuel on the way down.
     */
    fun climbDescent(ctx: RideContext): CoachingEvent? {
        if (!ctx.isDescending) return null

        // Fire within first 30s of descending
        // Engine suppression handles de-duplication
        return CoachingEvent(
            ruleId = RuleId.CLIMB_DESCENT,
            message = "Descending. Recover and fuel up.",
            priority = CoachingPriority.MEDIUM,
            alertStyle = AlertStyle.FUEL,
            suppressIfFiredInLastSec = 600,
        )
    }

    /**
     * Multi-climb fatigue — HR baseline rising across climbs.
     */
    fun multiClimbFatigue(ctx: RideContext): CoachingEvent? {
        if (ctx.totalClimbsOnRoute < 2) return null
        if (ctx.climbNumber < 2) return null
        if (!ctx.isOnClimb) return null

        // Proxy: HR decoupling > 5% signals rising baseline
        if (ctx.hrDecouplingPct < 5f) return null

        return CoachingEvent(
            ruleId = RuleId.MULTI_CLIMB_FATIGUE,
            message = "Climb ${ctx.climbNumber}/${ctx.totalClimbsOnRoute}. HR up. Pace it.",
            priority = CoachingPriority.HIGH,
            alertStyle = AlertStyle.WARNING,
            suppressIfFiredInLastSec = 600,
        )
    }

    fun evaluateAll(ctx: RideContext): List<CoachingEvent> =
        listOfNotNull(
            climbEntry(ctx),
            climbPowerCeiling(ctx),
            climbCadenceDrop(ctx),
            climbSummitNear(ctx),
            climbDescent(ctx),
            multiClimbFatigue(ctx),
        )
}
