package io.hammerhead.pacepilot.ai

/**
 * Common interface for all AI coaching providers.
 *
 * Each provider decides internally how to store/cache [systemPrompt] and [stableContext]
 * provided in [initRide]. [generate] only receives the per-event live prompt.
 *
 * Lifecycle per ride:
 *   1. [initRide]  — called once at ride start (suspend; may do network I/O)
 *   2. [generate]  — called per coaching event (suspend; network call)
 *   3. [endRide]   — called at ride stop (suspend; cleanup)
 */
interface AiCoachingClient {
    suspend fun initRide(systemPrompt: String, stableContext: String)
    suspend fun generate(livePrompt: String, fallback: String): String
    suspend fun endRide()
}
