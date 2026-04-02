package io.hammerhead.pacepilot.fields

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Lightweight numeric data type implementation for Karoo ride profiles.
 *
 * Uses standard numeric rendering (non-graphical DataType) so we can ship
 * stable fields quickly across all profile layouts.
 */
class PacePilotNumericDataType(
    extensionId: String,
    typeId: String,
    private val sampleMs: Long = 1000L,
    private val valueProvider: () -> Double,
) : DataTypeImpl(extensionId, typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        emitter.onNext(StreamState.Searching)
        val job: Job = streamScope.launch {
            while (true) {
                val value = runCatching { valueProvider() }
                    .getOrElse {
                        Timber.w(it, "PacePilotNumericDataType(%s): valueProvider failed", typeId)
                        0.0
                    }
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf("value" to value),
                        )
                    )
                )
                delay(sampleMs)
            }
        }
        emitter.setCancellable {
            job.cancel()
            streamScope.cancel()
        }
    }
}

