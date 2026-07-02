package io.hammerhead.pacepilot.history

import kotlinx.serialization.Serializable

@Serializable
data class CueBank(
    val generatedAtEpochMs: Long = 0L,
    val language: String = "en",
    val provider: String = "",
    /** ruleId → list of short coaching cues */
    val messages: Map<String, List<String>> = emptyMap(),
) {
    val cueCount: Int get() = messages.values.sumOf { it.size }
    val isReady: Boolean get() = messages.isNotEmpty() && generatedAtEpochMs > 0L
}
