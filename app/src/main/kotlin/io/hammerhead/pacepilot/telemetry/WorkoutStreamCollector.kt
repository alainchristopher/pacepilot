package io.hammerhead.pacepilot.telemetry

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.pacepilot.model.IntervalPhase
import io.hammerhead.pacepilot.model.TargetType
import io.hammerhead.pacepilot.model.WorkoutState
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Subscribes to all WORKOUT_* DataType streams from Karoo and aggregates
 * them into a single [WorkoutState] flow.
 */
class WorkoutStreamCollector(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    /** True if the workout state stream is actively emitting data */
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private fun streamValue(typeId: String) =
        karooSystem.streamDataFlow(typeId).mapNotNull { s ->
            (s as? StreamState.Streaming)?.dataPoint?.singleValue
        }

    private fun streamValues(typeId: String) =
        karooSystem.streamDataFlow(typeId).mapNotNull { s ->
            (s as? StreamState.Streaming)?.dataPoint?.values
        }

    fun start() {
        scope.launch {
            // Combine first 5 flows
            val firstFive = combine(
                streamValue(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION),
                streamValues(DataType.Type.WORKOUT_POWER_TARGET),
                streamValues(DataType.Type.WORKOUT_HEART_RATE_TARGET),
                streamValues(DataType.Type.WORKOUT_CADENCE_TARGET),
                streamValues(DataType.Type.WORKOUT_INTERVAL_COUNT),
            ) { remaining, powerTarget, hrTarget, cadenceTarget, intervalCount ->
                QuintInput(remaining, powerTarget, hrTarget, cadenceTarget, intervalCount)
            }
            // Chain in the 6th flow
            combine(
                firstFive,
                streamValues(DataType.Type.WORKOUT_REMAINING_TOTAL_DURATION),
            ) { quint, totalRemaining ->
                buildWorkoutState(quint, totalRemaining)
            }.collect { ws ->
                _state.value = ws
                _isActive.value = ws.isActive
            }
        }
    }

    private data class QuintInput(
        val remaining: Double,
        val powerTarget: Map<String, Double>,
        val hrTarget: Map<String, Double>,
        val cadenceTarget: Map<String, Double>,
        val intervalCount: Map<String, Double>,
    )

    private fun buildWorkoutState(
        q: QuintInput,
        totalRemaining: Map<String, Double>,
    ): WorkoutState {
        val remainingSec = q.remaining.toInt()
        val isActive = remainingSec > 0

        // Interval count: Karoo uses WORKOUT_CURRENT_STEP and WORKOUT_STEP_COUNT field names
        val currentStep = q.intervalCount["WORKOUT_CURRENT_STEP"]?.toInt()
            ?: q.intervalCount.values.firstOrNull()?.toInt()
            ?: 0
        val totalSteps = q.intervalCount["WORKOUT_STEP_COUNT"]?.toInt()
            ?: q.intervalCount.values.drop(1).firstOrNull()?.toInt()
            ?: 0

        // Power target: Karoo uses WORKOUT_TARGET_MIN_VALUE / WORKOUT_TARGET_MAX_VALUE / WORKOUT_TARGET_VALUE
        val powerLow = q.powerTarget["WORKOUT_TARGET_MIN_VALUE"]?.toInt()
            ?: q.powerTarget["WORKOUT_TARGET_VALUE"]?.toInt()
        val powerHigh = q.powerTarget["WORKOUT_TARGET_MAX_VALUE"]?.toInt()
            ?: q.powerTarget["WORKOUT_TARGET_VALUE"]?.toInt()

        // HR target
        val hrLow = q.hrTarget["WORKOUT_TARGET_MIN_VALUE"]?.toInt()
            ?: q.hrTarget["WORKOUT_TARGET_VALUE"]?.toInt()
        val hrHigh = q.hrTarget["WORKOUT_TARGET_MAX_VALUE"]?.toInt()
            ?: q.hrTarget["WORKOUT_TARGET_VALUE"]?.toInt()

        // Cadence target
        val cadenceLow = q.cadenceTarget["WORKOUT_TARGET_MIN_VALUE"]?.toInt()
        val cadenceHigh = q.cadenceTarget["WORKOUT_TARGET_MAX_VALUE"]?.toInt()

        // Determine target type
        val targetType = when {
            powerLow != null || powerHigh != null -> TargetType.POWER
            hrLow != null || hrHigh != null -> TargetType.HEART_RATE
            else -> TargetType.NONE
        }

        // Infer interval phase from step position and target zone
        val phase = inferPhase(currentStep, totalSteps, powerLow, powerHigh)

        Timber.d("WorkoutStream: step=$currentStep/$totalSteps remaining=${remainingSec}s phase=$phase")

        return WorkoutState(
            isActive = isActive,
            currentStep = currentStep,
            totalSteps = totalSteps,
            currentPhase = phase,
            intervalRemainingSec = remainingSec,
            targetType = targetType,
            targetLow = if (targetType == TargetType.POWER) powerLow else hrLow,
            targetHigh = if (targetType == TargetType.POWER) powerHigh else hrHigh,
            cadenceTargetMin = cadenceLow,
            cadenceTargetMax = cadenceHigh,
        )
    }

    /**
     * Infer interval phase from step index.
     * Step 0 = warmup, last step = cooldown, middle steps: odd = effort, even = recovery
     * (This is a heuristic — Karoo doesn't expose phase type directly in the stream values)
     */
    private fun inferPhase(step: Int, total: Int, powerLow: Int?, powerHigh: Int?): IntervalPhase {
        if (total == 0) return IntervalPhase.UNKNOWN
        return when {
            step == 0 -> IntervalPhase.WARMUP
            step == total - 1 -> IntervalPhase.COOLDOWN
            step % 2 == 1 -> IntervalPhase.EFFORT   // odd steps = effort (1,3,5...)
            else -> IntervalPhase.RECOVERY           // even middle = recovery
        }
    }
}
