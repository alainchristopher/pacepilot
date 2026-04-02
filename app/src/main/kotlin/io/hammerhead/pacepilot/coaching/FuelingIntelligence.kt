package io.hammerhead.pacepilot.coaching

import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.RideContext

/**
 * Weight-based fueling intelligence model.
 *
 * Carb demand model:
 * - Derives g/hr from watts/kg (w/kg) relative to FTP — a physiologically grounded estimate
 *   of glycogen oxidation rate.
 * - Below 50% FTP: fat-dominant, ~30g/hr exogenous carbs needed
 * - 50-75% FTP (Z2-Z3): mixed, ~45-60g/hr
 * - 75-100% FTP (Z4): threshold, ~70-80g/hr
 * - 100%+ FTP (Z5+): supramaximal, ~85-100g/hr
 * - Absolute watt values are used when FTP is known; falls back to zone-based if not.
 *
 * Reference: Burke et al. (2011), Jeukendrup (2014) carbohydrate oxidation models.
 */
object FuelingIntelligence {

    /**
     * Returns the recommended carb intake in grams/hour based on:
     * 1. Current power as % of FTP (physiologically grounded glycogen depletion rate)
     * 2. Rider weight (used to contextualise w/kg intensity — heavier riders burn more)
     * 3. Workout phase boost (intervals demand more)
     * 4. Pre-climb preparation boost (stock up before the climb)
     * 5. User-set baseline as hard floor (respects manual override)
     */
    fun recommendedCarbsPerHour(ctx: RideContext, baseSettingGph: Int): Int {
        val intensityBased = intensityBasedCarbsPerHour(ctx)
        val workoutBoost = if (ctx.workout.isActive && ctx.workout.currentPhase == IntervalPhase.EFFORT) 10 else 0
        val climbPrepBoost = if (isClimbImminent(ctx) && ctx.carbDeficitGrams > 10) 10 else 0

        val computed = intensityBased + workoutBoost + climbPrepBoost
        // Never go below user baseline preference.
        return computed.coerceAtLeast(baseSettingGph).coerceIn(30, 120)
    }

    /**
     * Derives carb demand from intensity as % FTP.
     *
     * Uses 30s avg power vs FTP. If FTP is 0 (not set), falls back to power-zone heuristic.
     * Weight modulates the upper end: a 90kg rider at 300W burns more than a 60kg rider at 300W
     * because their w/kg is lower, but absolute carb oxidation scales with absolute power output.
     * The weight factor adds 0-10g/hr correction for riders significantly above/below 75kg baseline.
     */
    private fun intensityBasedCarbsPerHour(ctx: RideContext): Int {
        if (ctx.ftp <= 0 || ctx.power30sAvg <= 0) {
            // Fallback: zone-based when FTP unknown
            return zoneBasedCarbsPerHour(ctx.powerZone)
        }

        val intensityPct = ctx.power30sAvg * 100 / ctx.ftp

        // Base carb oxidation curve (g/hr) vs % FTP
        val base = when {
            intensityPct < 45  -> 25   // recovery / very easy
            intensityPct < 56  -> 35   // Z1 upper / Z2 low
            intensityPct < 66  -> 45   // Z2 mid
            intensityPct < 76  -> 55   // Z2 upper / Z3 low
            intensityPct < 86  -> 65   // Z3 / tempo
            intensityPct < 96  -> 75   // Z4 / threshold
            intensityPct < 106 -> 85   // FTP / slightly over
            else               -> 95   // VO2max / anaerobic
        }

        // Weight correction: add ~1g/hr per 3kg above 75kg baseline, subtract below.
        // Keeps correction in -10..+15 range so it doesn't dominate.
        val weightCorrection = ((ctx.weightKg - 75f) / 3f).toInt().coerceIn(-10, 15)

        return (base + weightCorrection).coerceIn(25, 110)
    }

    private fun zoneBasedCarbsPerHour(powerZone: Int): Int = when (powerZone) {
        0, 1, 2 -> 30
        3 -> 45
        4 -> 60
        5 -> 75
        else -> 90
    }

    /**
     * Estimates the total carb budget for the entire ride based on:
     * - Rider weight
     * - Expected ride duration
     * - Estimated average intensity (NP or 3-min avg as proxy)
     *
     * Used to give context in coaching messages: "You need ~Xg total today."
     * Returns 0 if not enough data to estimate.
     */
    fun estimatedRideCarbBudget(
        weightKg: Float,
        ftp: Int,
        estimatedDurationHours: Float,
        estimatedAvgIntensityPct: Int = 65,
    ): Int {
        if (ftp <= 0 || estimatedDurationHours <= 0f) return 0
        val avgWatts = ftp * estimatedAvgIntensityPct / 100

        // Base g/hr from the intensity curve
        val gPerHour = when {
            estimatedAvgIntensityPct < 56 -> 35
            estimatedAvgIntensityPct < 66 -> 45
            estimatedAvgIntensityPct < 76 -> 55
            estimatedAvgIntensityPct < 86 -> 65
            estimatedAvgIntensityPct < 96 -> 75
            else -> 85
        }
        val weightCorrection = ((weightKg - 75f) / 3f).toInt().coerceIn(-10, 15)
        val effectiveGph = (gPerHour + weightCorrection).coerceIn(25, 110)

        return (effectiveGph * estimatedDurationHours).toInt()
    }

    fun isClimbImminent(ctx: RideContext): Boolean {
        val dist = ctx.distanceToClimbTopM ?: return false
        // Conservative "prepare now" window: climb likely starts soon.
        return ctx.hasRoute && !ctx.isOnClimb && dist in 1200f..3500f
    }
}
