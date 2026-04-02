package io.hammerhead.pacepilot.integrations

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.pacepilot.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

data class NomRideSignal(
    val carbBalanceGrams: Int? = null,
    val burnRateGph: Int? = null,
    val carbsEatenGrams: Int? = null,
    val waterMl: Int? = null,
    val updatedAtEpochSec: Long = System.currentTimeMillis() / 1000,
) {
    val isFresh: Boolean
        get() = (System.currentTimeMillis() / 1000 - updatedAtEpochSec) <= 120
}

/**
 * Adapter for consuming NomRide extension data fields when available.
 *
 * Graceful fallback behavior:
 * - If no NomRide stream is available, no exception is raised and callers keep
 *   using internal fueling estimation.
 * - If partial fields are available, only those fields are updated.
 */
class NomRideAdapter(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    fun start(onSignal: (NomRideSignal) -> Unit) {
        subscribeNumeric(
            ids = listOf(
                "nomride:carb_balance",
                "nomride.carb_balance",
                "nomride/carb_balance",
            )
        ) { value ->
            onSignal(NomRideSignal(carbBalanceGrams = value))
        }
        subscribeNumeric(
            ids = listOf(
                "nomride:burn_rate",
                "nomride.burn_rate",
                "nomride/burn_rate",
            )
        ) { value ->
            onSignal(NomRideSignal(burnRateGph = value))
        }
        subscribeNumeric(
            ids = listOf(
                "nomride:carbs_eaten",
                "nomride.carbs_eaten",
                "nomride/carbs_eaten",
            )
        ) { value ->
            onSignal(NomRideSignal(carbsEatenGrams = value))
        }
        subscribeNumeric(
            ids = listOf(
                "nomride:water_ml",
                "nomride.water_ml",
                "nomride/water_ml",
            )
        ) { value ->
            onSignal(NomRideSignal(waterMl = value))
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun subscribeNumeric(ids: List<String>, onValue: (Int) -> Unit) {
        ids.forEach { id ->
            jobs += scope.launch {
                runCatching {
                    karooSystem.streamDataFlow(id)
                        .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }
                        .collect { value ->
                            onValue(value)
                        }
                }.onFailure {
                    Timber.d("NomRideAdapter: stream not available for %s", id)
                }
            }
        }
    }
}

