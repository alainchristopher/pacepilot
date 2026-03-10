package io.hammerhead.pacepilot.detection

import io.hammerhead.pacepilot.model.ActiveMode
import io.hammerhead.pacepilot.model.ModeSource
import io.hammerhead.pacepilot.model.RideContext
import io.hammerhead.pacepilot.model.RideMode
import io.hammerhead.pacepilot.model.currentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Monitors [RideContext] for conditions that should trigger a mode transition.
 *
 * Transitions:
 * - WORKOUT, all intervals done -> ENDURANCE
 * - ENDURANCE, grade > threshold sustained -> temp CLIMB_FOCUSED (reverts on descent)
 * - ADAPTIVE, sustained Z1 for 15+ min -> RECOVERY
 * - CLIMB_FOCUSED temp, grade < threshold -> revert to previous mode
 */
class ModeTransitionEngine(
    private val rideContext: StateFlow<RideContext>,
    private val onModeChange: (ActiveMode) -> Unit,
    private val scope: CoroutineScope,
) {
    // Track how long we've been on a climb during ENDURANCE for hysteresis
    private var climbEntryTimeSec: Long = 0L
    private var wasOnClimbDuringEndurance = false

    // Grade hysteresis counters (to avoid flapping)
    private var gradeAboveThresholdSec = 0
    private var gradeBelowThresholdSec = 0

    private val CLIMB_ENTRY_SUSTAINED_SEC = 30    // must be climbing for 30s before switching
    private val CLIMB_EXIT_SUSTAINED_SEC = 60     // must be flat for 60s before reverting
    private val Z1_SUSTAINED_FOR_RECOVERY_MIN = 15f

    fun start() {
        rideContext
            .distinctUntilChangedBy { ctx ->
                Triple(ctx.currentMode, ctx.workout.isActive, ctx.isOnClimb)
            }
            .onEach { ctx -> evaluate(ctx) }
            .launchIn(scope)
    }

    private fun evaluate(ctx: RideContext) {
        val current = ctx.activeMode

        when (current.mode) {
            RideMode.WORKOUT -> evaluateWorkout(ctx, current)
            RideMode.ENDURANCE -> evaluateEndurance(ctx, current)
            RideMode.ADAPTIVE -> evaluateAdaptive(ctx, current)
            RideMode.CLIMB_FOCUSED -> evaluateClimbFocused(ctx, current)
            RideMode.RECOVERY -> {} // no auto-transition out of recovery
        }
    }

    private fun evaluateWorkout(ctx: RideContext, current: ActiveMode) {
        val ws = ctx.workout
        if (!ws.isActive && ws.completedEffortCount > 0) {
            // All intervals done — transition to ENDURANCE for cooldown
            Timber.i("ModeTransition: workout complete -> ENDURANCE")
            onModeChange(
                ActiveMode(
                    mode = RideMode.ENDURANCE,
                    source = ModeSource.TRANSITION,
                    previousMode = RideMode.WORKOUT,
                )
            )
        }
    }

    private fun evaluateEndurance(ctx: RideContext, current: ActiveMode) {
        val gradeThreshold = 4.0f
        if (ctx.isOnClimb && ctx.elevationGradePct >= gradeThreshold) {
            gradeAboveThresholdSec++
            gradeBelowThresholdSec = 0
            if (gradeAboveThresholdSec >= CLIMB_ENTRY_SUSTAINED_SEC && !wasOnClimbDuringEndurance) {
                wasOnClimbDuringEndurance = true
                Timber.i("ModeTransition: sustained climb in ENDURANCE -> temp CLIMB_FOCUSED")
                onModeChange(
                    ActiveMode(
                        mode = RideMode.CLIMB_FOCUSED,
                        source = ModeSource.TRANSITION,
                        previousMode = RideMode.ENDURANCE,
                        revertToMode = RideMode.ENDURANCE,
                    )
                )
            }
        } else {
            gradeAboveThresholdSec = 0
        }
    }

    private fun evaluateClimbFocused(ctx: RideContext, current: ActiveMode) {
        val revertTo = current.revertToMode ?: return // only auto-revert temp transitions

        if (!ctx.isOnClimb || ctx.isDescending) {
            gradeBelowThresholdSec++
            gradeAboveThresholdSec = 0
            if (gradeBelowThresholdSec >= CLIMB_EXIT_SUSTAINED_SEC) {
                gradeBelowThresholdSec = 0
                wasOnClimbDuringEndurance = false
                Timber.i("ModeTransition: descent/flat -> reverting to $revertTo")
                onModeChange(
                    ActiveMode(
                        mode = revertTo,
                        source = ModeSource.TRANSITION,
                        previousMode = RideMode.CLIMB_FOCUSED,
                    )
                )
            }
        } else {
            gradeBelowThresholdSec = 0
        }
    }

    private fun evaluateAdaptive(ctx: RideContext, current: ActiveMode) {
        if (ctx.minutesInZ1Sustained >= Z1_SUSTAINED_FOR_RECOVERY_MIN) {
            Timber.i("ModeTransition: sustained Z1 in ADAPTIVE -> RECOVERY")
            onModeChange(
                ActiveMode(
                    mode = RideMode.RECOVERY,
                    source = ModeSource.TRANSITION,
                    previousMode = RideMode.ADAPTIVE,
                )
            )
        }
    }

    /** Apply a manual override from BonusAction */
    fun applyManualOverride(mode: RideMode) {
        val current = rideContext.value.activeMode
        Timber.i("ModeTransition: manual override -> $mode")
        onModeChange(ActiveMode(mode = mode, source = ModeSource.MANUAL_OVERRIDE, previousMode = current.mode))
        // Reset hysteresis counters
        gradeAboveThresholdSec = 0
        gradeBelowThresholdSec = 0
        wasOnClimbDuringEndurance = false
    }
}
