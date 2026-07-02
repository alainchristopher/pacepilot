package io.hammerhead.pacepilot.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class RacePlanRepository(context: Context) {

    private val file = File(context.filesDir, "race_plan.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    @Volatile private var cached: RacePlan? = null

    suspend fun load(): RacePlan = mutex.withLock {
        cached ?: readDisk().also { cached = it }
    }

    /** Always read disk so settings saved from MainActivity apply mid-ride. */
    fun current(): RacePlan = readDisk()

    suspend fun save(plan: RacePlan) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.writeText(json.encodeToString(plan))
        }
        cached = plan
    }

    private fun readDisk(): RacePlan = runCatching {
        if (!file.exists()) return RacePlan()
        json.decodeFromString<RacePlan>(file.readText())
    }.getOrElse { RacePlan() }
}
