package io.hammerhead.pacepilot.detection

import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.settings.UserSettings
import io.hammerhead.pacepilot.workout.WorkoutDetector
import timber.log.Timber

/**
 * Determines the initial ride mode at ride start using the detection cascade:
 *
 *   1. Forced override in settings? -> that mode
 *   2. Structured workout active? -> WORKOUT
 *   3. Route with significant climbing? -> CLIMB_FOCUSED
 *   4. Route loaded, flat/rolling? -> ENDURANCE
 *   5. Nothing? -> ADAPTIVE
 */
class ModeDetector(
    private val workoutDetector: WorkoutDetector,
) {
    /**
     * Run the detection cascade. Call once at ride start after telemetry is initialized.
     * [workoutActive] should come from WorkoutDetector.detect().
     */
    fun detect(
        context: RideContext,
        settings: UserSettings,
        workoutActive: Boolean,
    ): ActiveMode {
        // Priority 0: forced override
        settings.forcedMode?.let { forced ->
            Timber.i("ModeDetector: forced mode = $forced")
            return ActiveMode(mode = forced, source = ModeSource.MANUAL_OVERRIDE)
        }

        // Priority 1: structured workout active
        if (workoutActive) {
            Timber.i("ModeDetector: workout detected -> WORKOUT")
            return ActiveMode(mode = RideMode.WORKOUT, source = ModeSource.AUTO_DETECTED)
        }

        // Priority 2 & 3: route loaded
        if (context.hasRoute) {
            val isClimbFocused = isClimbFocusedRoute(
                totalGainM = context.routeTotalElevationGainM,
                steepPct = context.routeSteeplyGradedPct,
                minGainM = settings.climbRouteMinGainM,
                gradThresholdPct = settings.climbRouteGradientThresholdPct,
            )
            return if (isClimbFocused) {
                Timber.i("ModeDetector: climb route -> CLIMB_FOCUSED (gain=${context.routeTotalElevationGainM}m, steep=${context.routeSteeplyGradedPct}%)")
                ActiveMode(mode = RideMode.CLIMB_FOCUSED, source = ModeSource.AUTO_DETECTED)
            } else {
                Timber.i("ModeDetector: flat/rolling route -> ENDURANCE")
                ActiveMode(mode = RideMode.ENDURANCE, source = ModeSource.AUTO_DETECTED)
            }
        }

        // Priority 4: nothing loaded
        Timber.i("ModeDetector: no route/workout -> ADAPTIVE")
        return ActiveMode(mode = RideMode.ADAPTIVE, source = ModeSource.AUTO_DETECTED)
    }

    private fun isClimbFocusedRoute(
        totalGainM: Float,
        steepPct: Float,
        minGainM: Float,
        gradThresholdPct: Float,
    ): Boolean {
        // steepPct = % of route segments above gradThresholdPct gradient
        // A route is "climb-focused" if total gain is big OR > 30% of it is steep
        return totalGainM >= minGainM || steepPct >= 30f
    }
}
