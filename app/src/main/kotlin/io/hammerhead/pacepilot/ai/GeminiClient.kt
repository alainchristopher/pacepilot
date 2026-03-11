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
        private const val TIMEOUT_SEC = 6L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // AiCoachingClient state
    private var storedSystemPrompt: String = ""
    private var cacheName: String? = null

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
        val name = cacheName ?: return
        deleteCache(name)
        cacheName = null
        storedSystemPrompt = ""
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

        runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("Gemini cache create HTTP ${resp.code} — will use uncached calls")
                    return@withContext null
                }
                val root = json.parseToJsonElement(resp.body?.string() ?: return@withContext null).jsonObject
                val name = root["name"]?.jsonPrimitive?.content
                Timber.i("GeminiClient: cache created: $name")
                name
            }
        }.getOrElse {
            Timber.w(it, "GeminiClient: cache creation failed")
            null
        }
    }

    /** Delete a previously created cache (best-effort, fire-and-forget). */
    suspend fun deleteCache(cacheName: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext
        runCatching {
            val request = Request.Builder()
                .url("$BASE/$cacheName?key=$apiKey")
                .delete()
                .build()
            http.newCall(request).execute().close()
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

            runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("Gemini cached gen HTTP ${resp.code}")
                        return@withContext null
                    }
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

            runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("Gemini uncached gen HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        return@withContext null
                    }
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
