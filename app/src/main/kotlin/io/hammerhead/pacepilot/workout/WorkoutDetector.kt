package io.hammerhead.pacepilot.workout

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Checks if a structured workout is active at ride start by probing the
 * WORKOUT_REMAINING_INTERVAL_DURATION stream for a live value within a timeout.
 */
class WorkoutDetector(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    private val _isWorkoutActive = MutableStateFlow(false)
    val isWorkoutActive: StateFlow<Boolean> = _isWorkoutActive.asStateFlow()

    /**
     * Probe the workout stream. Returns true if a workout is active.
     * Times out after [timeoutMs] ms (default 5s — enough for first sample).
     */
    suspend fun detect(timeoutMs: Long = 5_000L): Boolean {
        Timber.i("WorkoutDetector: probing for active workout...")
        val result = withTimeoutOrNull(timeoutMs) {
            karooSystem.streamDataFlow(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION)
                .mapNotNull { state ->
                    when (state) {
                        is StreamState.Streaming -> {
                            val value = state.dataPoint.singleValue ?: 0.0
                            Timber.d("WorkoutDetector: stream value=$value")
                            if (value > 0.0) true else null // only positive remaining = active
                        }
                        is StreamState.Idle, is StreamState.NotAvailable -> false
                        else -> null
                    }
                }
                .first()
        } ?: false

        Timber.i("WorkoutDetector: workout active = $result")
        _isWorkoutActive.value = result
        return result
    }

    /** Subscribe to ongoing workout activity changes */
    fun monitor() {
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.WORKOUT_REMAINING_INTERVAL_DURATION)
                .collect { state ->
                    _isWorkoutActive.value = state is StreamState.Streaming &&
                        (state.dataPoint.singleValue ?: 0.0) > 0.0
                }
        }
    }
}
