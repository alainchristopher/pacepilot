package io.hammerhead.pacepilot.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Persists RideHistory to a JSON file in the app's files directory.
 * Thread-safe via Dispatchers.IO.
 */
class RideHistoryRepository(context: Context) {

    private val file = File(context.filesDir, "ride_history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _history = MutableStateFlow(RideHistory())
    val history: StateFlow<RideHistory> = _history

    val current: RideHistory get() = _history.value

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        runCatching {
            val raw = file.readText()
            _history.value = json.decodeFromString(RideHistory.serializer(), raw)
            Timber.d("RideHistory: loaded ${_history.value.recentRideCount} rides")
        }.onFailure {
            Timber.w(it, "RideHistory: failed to load, starting fresh")
        }
    }

    suspend fun saveRide(summary: RideSummary) = withContext(Dispatchers.IO) {
        val updated = _history.value.withNewRide(summary)
        runCatching {
            file.writeText(json.encodeToString(RideHistory.serializer(), updated))
            _history.value = updated
            Timber.i("RideHistory: saved ride, total=${updated.recentRideCount}")
        }.onFailure {
            Timber.e(it, "RideHistory: failed to save")
        }
    }
}
