package io.hammerhead.pacepilot.history

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class PostRideInsightsRepository(context: Context) {
    private val file = File(context.filesDir, "pacepilot_postride_insight.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun save(insight: PostRideInsight) {
        mutex.withLock {
            runCatching {
                file.writeText(json.encodeToString(PostRideInsight.serializer(), insight))
            }.onFailure { Timber.w(it, "PostRideInsightsRepository: save failed") }
        }
    }

    suspend fun load(): PostRideInsight? =
        mutex.withLock {
            runCatching {
                if (!file.exists()) return@runCatching null
                json.decodeFromString(PostRideInsight.serializer(), file.readText())
            }.getOrElse {
                Timber.w(it, "PostRideInsightsRepository: load failed")
                null
            }
        }
}

