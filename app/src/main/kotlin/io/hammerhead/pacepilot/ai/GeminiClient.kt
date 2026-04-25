package io.hammerhead.pacepilot.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Gemini 2.0 Flash client with context caching support.
 *
 * Implements [AiCoachingClient]:
 *   - [initRide] creates a server-side context cache from stable content.
 *   - [generate] sends only the per-event live prompt (cached calls are cheap).
 *   - [endRide] deletes the cache.
 *
 * If caching fails (first ride, network issue), falls back to uncached calls.
 */
class GeminiClient(private val apiKey: String) : AiCoachingClient {

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODEL = "models/gemini-2.0-flash-001"
        // Minimum token count for cache to be cost-effective (Gemini requirement: ≥32k tokens)
        // In practice our system+history prompt is well under that, so we use ephemeral caching
        // via system_instruction + cached_content when supported, otherwise plain calls.
        private const val GENERATE_ENDPOINT = "$BASE/$MODEL:generateContent"
        private const val CACHE_ENDPOINT = "$BASE/cachedContents"
        // Generate runs on the alert critical path (must beat 6–12s auto-dismiss).
        private const val TIMEOUT_GEN_SEC = 6L
        // initRide is off-path and is the first call after a hotspot connect — DNS+TLS can take 3–5s.
        private const val TIMEOUT_INIT_SEC = 15L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    // httpGen derives from httpInit so they share a ConnectionPool: the cache
    // create acts as warm-up, and per-event generate calls reuse the warm TLS.
    private val httpInit = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_INIT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_INIT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_INIT_SEC, TimeUnit.SECONDS)
        .build()

    private val httpGen = httpInit.newBuilder()
        .connectTimeout(TIMEOUT_GEN_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_GEN_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_GEN_SEC, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // AiCoachingClient state
    private var storedSystemPrompt: String = ""
    private var cacheName: String? = null
    @Volatile private var warmedUp = false

    // ----------------------------------------------------------------
    // AiCoachingClient implementation
    // ----------------------------------------------------------------

    override suspend fun initRide(systemPrompt: String, stableContext: String) {
        storedSystemPrompt = systemPrompt
        cacheName = createCache(stableContext)
        Timber.i("GeminiClient: initRide cache=${cacheName ?: "none (uncached fallback)"}")
    }

    override suspend fun generate(livePrompt: String, fallback: String): String =
        generateWithCache(storedSystemPrompt, cacheName, livePrompt, fallback)

    override suspend fun endRide() {
        val name = cacheName
        cacheName = null
        storedSystemPrompt = ""
        warmedUp = false
        if (name != null) deleteCache(name)
    }

    // ----------------------------------------------------------------
    // Context caching (stable per-ride content)
    // ----------------------------------------------------------------

    /**
     * Creates a Gemini cached content object from [stableContent].
     * Returns the cache resource name (e.g. "cachedContents/abc123") or null on failure.
     * TTL is 1 hour — more than enough for a ride.
     */
    suspend fun createCache(stableContent: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val body = buildJsonObject {
            put("model", MODEL)
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", stableContent) })
                    })
                })
            })
            put("ttl", "3600s")
        }.toString()

        val request = Request.Builder()
            .url("$CACHE_ENDPOINT?key=$apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        // Retry budget ≈48s wallclock — covers the realistic hotspot timing race
        // where the rider taps "Start ride" first, THEN enables their phone hotspot
        // (Karoo typically reconnects within ~15-30s). UnknownHostException fires
        // fast during that window, so spaced delays absorb the wait.
        val delaysMs = listOf(0L, 3_000L, 5_000L, 8_000L, 12_000L, 20_000L)
        delaysMs.forEachIndexed { idx, delay ->
            if (delay > 0) kotlinx.coroutines.delay(delay)
            val result = runCatching {
                httpInit.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("Gemini cache create ${idx + 1}/${delaysMs.size} HTTP ${resp.code}")
                        // Even a failed HTTP response means TLS is now warm — bail and use uncached.
                        warmedUp = true
                        return@use null
                    }
                    val root = json.parseToJsonElement(resp.body?.string() ?: return@use null).jsonObject
                    val name = root["name"]?.jsonPrimitive?.content
                    warmedUp = true
                    Timber.i("GeminiClient: cache created: $name")
                    name
                }
            }.getOrElse { e ->
                Timber.w("GeminiClient: cache create ${idx + 1}/${delaysMs.size} failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
                null
            }
            if (result != null) return@withContext result
            if (warmedUp) return@withContext null // got a 4xx but TLS is warm
        }
        Timber.w("Gemini cache create failed after ${delaysMs.size} attempts — will use uncached calls")
        null
    }

    /** Delete a previously created cache (best-effort, fire-and-forget). */
    suspend fun deleteCache(cacheName: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext
        runCatching {
            val request = Request.Builder()
                .url("$BASE/$cacheName?key=$apiKey")
                .delete()
                .build()
            httpInit.newCall(request).execute().close()
            Timber.i("GeminiClient: cache deleted: $cacheName")
        }
    }

    // ----------------------------------------------------------------
    // Generation
    // ----------------------------------------------------------------

    /**
     * Generate a coaching cue using a previously created [cacheName] for the
     * stable context, and [livePrompt] for the per-event live data.
     *
     * Falls back to [generateUncached] if [cacheName] is null.
     */
    suspend fun generateWithCache(
        systemPrompt: String,
        cacheName: String?,
        livePrompt: String,
        fallback: String,
    ): String {
        if (apiKey.isBlank()) return fallback
        return try {
            if (cacheName != null) {
                generateCached(cacheName, livePrompt) ?: generateUncached(systemPrompt, livePrompt)
            } else {
                generateUncached(systemPrompt, livePrompt)
            } ?: fallback
        } catch (e: Exception) {
            Timber.w(e, "GeminiClient: generation failed, using fallback")
            fallback
        }
    }

    private suspend fun generateCached(cacheName: String, livePrompt: String): String? =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("cachedContent", cacheName)
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", livePrompt) })
                        })
                    })
                })
                put("generationConfig", generationConfig())
            }.toString()

            val request = Request.Builder()
                .url("$GENERATE_ENDPOINT?key=$apiKey")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            // Use the longer-budget client until warmup confirmed — gives the very first
            // generate a chance to land instead of always hitting the rule fallback.
            val client = if (warmedUp) httpGen else httpInit
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("Gemini cached gen HTTP ${resp.code}")
                        warmedUp = true
                        return@withContext null
                    }
                    warmedUp = true
                    extractText(resp.body?.string() ?: return@withContext null)
                }
            }.getOrElse {
                Timber.w(it, "GeminiClient: cached generation failed")
                null
            }
        }

    private suspend fun generateUncached(systemPrompt: String, userPrompt: String): String? =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("system_instruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemPrompt) })
                    })
                })
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", userPrompt) })
                        })
                    })
                })
                put("generationConfig", generationConfig())
            }.toString()

            val request = Request.Builder()
                .url("$GENERATE_ENDPOINT?key=$apiKey")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val client = if (warmedUp) httpGen else httpInit
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("Gemini uncached gen HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        warmedUp = true
                        return@withContext null
                    }
                    warmedUp = true
                    extractText(resp.body?.string() ?: return@withContext null)
                }
            }.getOrElse {
                Timber.w(it, "GeminiClient: uncached generation failed")
                null
            }
        }

    private fun generationConfig() = buildJsonObject {
        put("maxOutputTokens", 40)
        put("temperature", 0.75)
        put("stopSequences", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("\n"))
        })
    }

    private fun extractText(responseBody: String): String? {
        return runCatching {
            json.parseToJsonElement(responseBody).jsonObject
                .get("candidates")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.trim()
                ?.take(100) // hard cap — Karoo banner is small
        }.getOrElse {
            Timber.w(it, "GeminiClient: failed to parse response")
            null
        }
    }
}
