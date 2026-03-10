package io.hammerhead.pacepilot.util

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull

/** Convert a Karoo data stream into a cold Flow<StreamState>. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> =
    callbackFlow {
        val id = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose { removeConsumer(id) }
    }

/** Subscribe to a typed KarooEvent as a Flow. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> =
    callbackFlow {
        val id = addConsumer { event: T -> trySendBlocking(event) }
        awaitClose { removeConsumer(id) }
    }

/** Extract the numeric value from a streaming data point, or null if not streaming. */
fun Flow<StreamState>.dataValues(): Flow<Map<String, Double>> =
    mapNotNull { state ->
        (state as? StreamState.Streaming)?.dataPoint?.values
    }

fun Flow<StreamState>.singleValues(): Flow<Double> =
    mapNotNull { state ->
        (state as? StreamState.Streaming)?.dataPoint?.singleValue
    }

/** Clamp a value to [min, max]. */
fun Int.clamp(min: Int, max: Int): Int = maxOf(min, minOf(max, this))
fun Float.clamp(min: Float, max: Float): Float = maxOf(min, minOf(max, this))

/** Rolling average over a fixed-size window. */
class RollingAverage(private val maxSize: Int) {
    private val buffer = ArrayDeque<Double>(maxSize)

    fun add(value: Double) {
        if (buffer.size >= maxSize) buffer.removeFirst()
        buffer.addLast(value)
    }

    val average: Double get() = if (buffer.isEmpty()) 0.0 else buffer.average()
    val size: Int get() = buffer.size
    fun isFull(): Boolean = buffer.size >= maxSize
    fun clear() = buffer.clear()
}

/** Simple linear trend — returns slope of last N values (positive = rising). */
@JvmName("linearSlopeDouble")
fun List<Double>.linearSlope(): Double {
    if (size < 2) return 0.0
    val n = size.toDouble()
    val sumX = (0 until size).sumOf { it.toDouble() }
    val sumY = sum()
    val sumXY = indices.sumOf { i -> i.toDouble() * this[i] }
    val sumX2 = indices.sumOf { i -> i.toDouble() * i.toDouble() }
    val denom = n * sumX2 - sumX * sumX
    return if (denom == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denom
}

@JvmName("linearSlopeInt")
fun List<Int>.linearSlope(): Double = map { it.toDouble() }.linearSlope()
