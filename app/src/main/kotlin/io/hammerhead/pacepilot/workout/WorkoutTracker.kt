package io.hammerhead.pacepilot.workout

import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.IntervalTransitionEvent
import io.hammerhead.pacepilot.model.WorkoutState
import io.hammerhead.pacepilot.model.WorkoutType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Observes [WorkoutState] changes and emits [IntervalTransitionEvent] when
 * the interval step changes. Also enriches WorkoutState with workout type
 * classification and session-level trend data computed by PowerAnalyzer/HrAnalyzer.
 *
 * This is stateless relative to telemetry — it only tracks step-level transitions.
 */
class WorkoutTracker {

    private var lastStep: Int = -1
    private var lastPhase: IntervalPhase = IntervalPhase.UNKNOWN
    private var classifiedType: WorkoutType = WorkoutType.UNKNOWN

    private val _transitionEvents = MutableSharedFlow<IntervalTransitionEvent>(extraBufferCapacity = 8)
    val transitionEvents: Flow<IntervalTransitionEvent> = _transitionEvents.asSharedFlow()

    /**
     * Feed updated WorkoutState; returns the state enriched with type classification.
     * Emits transition events when step changes.
     */
    fun update(state: WorkoutState, ftp: Int): WorkoutState {
        val currentStep = state.currentStep
        val currentPhase = state.currentPhase

        if (currentStep != lastStep && lastStep >= 0) {
            _transitionEvents.tryEmit(
                IntervalTransitionEvent(
                    fromPhase = lastPhase,
                    toPhase = currentPhase,
                    fromStep = lastStep,
                    toStep = currentStep,
                )
            )
        }

        lastStep = currentStep
        lastPhase = currentPhase

        // Re-classify workout type when entering a new effort block
        if (currentPhase == IntervalPhase.EFFORT && classifiedType == WorkoutType.UNKNOWN) {
            classifiedType = WorkoutTypeClassifier.classify(state, ftp)
        }

        return state.copy(workoutType = classifiedType)
    }

    fun reset() {
        lastStep = -1
        lastPhase = IntervalPhase.UNKNOWN
        classifiedType = WorkoutType.UNKNOWN
    }

    /** True if the workout has just completed (was active, now step == total-1 and remaining==0) */
    fun isSessionComplete(state: WorkoutState): Boolean {
        if (!state.isActive) return false
        return state.intervalRemainingSec <= 0 &&
            state.currentStep >= state.totalSteps - 1
    }
}
