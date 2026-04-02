package io.hammerhead.pacepilot.integrations

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

data class HeadwindSignal(
    val windSpeedKmh: Float? = null,
    val relativeWindPct: Float? = null, // +headwind, -tailwind
    val updatedAtEpochSec: Long = System.currentTimeMillis() / 1000,
) {
    val isFresh: Boolean
        get() = (System.currentTimeMillis() / 1000 - updatedAtEpochSec) <= 120
}

/**
 * Optional adapter for Headwind extension streams.
 * Missing streams are ignored; coaching falls back to no-wind logic.
 */
class HeadwindAdapter(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    fun start(onSignal: (HeadwindSignal) -> Unit) {
        subscribeFloat(
            listOf("headwind:wind_speed_kmh", "headwind.wind_speed_kmh", "headwind/wind_speed_kmh")
        ) { onSignal(HeadwindSignal(windSpeedKmh = it)) }
        subscribeFloat(
            listOf("headwind:relative_wind_pct", "headwind.relative_wind_pct", "headwind/relative_wind_pct")
        ) { onSignal(HeadwindSignal(relativeWindPct = it)) }
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
                }.onFailure { Timber.d("HeadwindAdapter: stream unavailable for %s", id) }
            }
        }
    }
}

