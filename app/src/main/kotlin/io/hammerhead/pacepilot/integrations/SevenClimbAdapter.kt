package io.hammerhead.pacepilot.integrations

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

data class SevenClimbSignal(
    val distanceToTopM: Float? = null,
    val climbNumber: Int? = null,
    val totalClimbs: Int? = null,
    val gradePct: Float? = null,
    val updatedAtEpochSec: Long = System.currentTimeMillis() / 1000,
) {
    val isFresh: Boolean
        get() = (System.currentTimeMillis() / 1000 - updatedAtEpochSec) <= 120
}

/**
 * Optional adapter for consuming 7Climb data fields when present.
 *
 * Fallback behavior: if streams are absent, native Karoo climb streams continue
 * to power coaching with no hard dependency.
 */
class SevenClimbAdapter(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    fun start(onSignal: (SevenClimbSignal) -> Unit) {
        subscribeFloat(
            listOf("7climb:distance_to_top", "7climb.distance_to_top", "7climb/distance_to_top")
        ) { onSignal(SevenClimbSignal(distanceToTopM = it)) }

        subscribeInt(
            listOf("7climb:climb_number", "7climb.climb_number", "7climb/climb_number")
        ) { onSignal(SevenClimbSignal(climbNumber = it)) }

        subscribeInt(
            listOf("7climb:total_climbs", "7climb.total_climbs", "7climb/total_climbs")
        ) { onSignal(SevenClimbSignal(totalClimbs = it)) }

        subscribeFloat(
            listOf("7climb:grade", "7climb.grade", "7climb/grade")
        ) { onSignal(SevenClimbSignal(gradePct = it)) }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun subscribeFloat(ids: List<String>, onValue: (Float) -> Unit) {
        ids.forEach { id ->
            jobs += scope.launch {
                runCatching {
                    karooSystem.streamDataFlow(id)
                        .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toFloat() }
                        .collect { onValue(it) }
                }.onFailure { Timber.d("SevenClimbAdapter: stream unavailable for %s", id) }
            }
        }
    }

    private fun subscribeInt(ids: List<String>, onValue: (Int) -> Unit) {
        ids.forEach { id ->
            jobs += scope.launch {
                runCatching {
                    karooSystem.streamDataFlow(id)
                        .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }
                        .collect { onValue(it) }
                }.onFailure { Timber.d("SevenClimbAdapter: stream unavailable for %s", id) }
            }
        }
    }
}

