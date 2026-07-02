package io.hammerhead.pacepilot.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class CueBankRepository(context: Context) {

    private val file = File(context.filesDir, "cue_bank.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    @Volatile private var cached: CueBank? = null

    suspend fun load(): CueBank = mutex.withLock {
        cached ?: readDisk().also { cached = it }
    }

    fun current(): CueBank? = cached

    suspend fun save(bank: CueBank) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.writeText(json.encodeToString(bank))
        }
        cached = bank
    }

    suspend fun clear() = save(CueBank())

    private fun readDisk(): CueBank = runCatching {
        if (!file.exists()) return CueBank()
        json.decodeFromString<CueBank>(file.readText())
    }.getOrElse { CueBank() }
}
