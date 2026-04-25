package io.hammerhead.pacepilot.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * Mercury-2 client (Inception Labs) implementing [AiCoachingClient].
 *
 * Mercury-2 is OpenAI-compatible and generates 1,000+ tokens/sec via diffusion.
 * No server-side caching API — [initRide] stores system + stable context in memory
 * and sends the full payload (~1,500 tokens) on every [generate] call.
 *
 * Cost: ~$0.25/1M input tokens → ~$0.000375/call → ~$0.005/ride. Acceptable.
 * Speed: sub-200ms response for 10-40 token outputs.
 *
 * Get 10M free tokens at platform.inceptionlabs.ai
 */
class MercuryClient(private val apiKey: String) : AiCoachingClient {

    companion object {
        private const val BASE_URL = "https://api.inceptionlabs.ai/v1"
        private const val CHAT_ENDPOINT = "$BASE_URL/chat/completions"
        private const val WARMUP_ENDPOINT = "$BASE_URL/models"
        private const val MODEL = "mercury-coder-small"
        // Generate runs on the alert critical path (must beat 6–12s auto-dismiss).
        private const val TIMEOUT_GEN_SEC = 6L
        // initRide is off-path and is the first call after a hotspot connect — DNS+TLS can take 3–5s.
        private const val TIMEOUT_INIT_SEC = 15L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    // httpGen derives from httpInit so they share a ConnectionPool: the
    // initRide warmup primes TLS, and per-event generate calls reuse it.
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

    private var storedSystemPrompt: String = ""
    private var storedStableContext: String = ""

    // ----------------------------------------------------------------
    // AiCoachingClient implementation
    // ----------------------------------------------------------------

    override suspend fun initRide(systemPrompt: String, stableContext: String) {
        storedSystemPrompt = systemPrompt
        storedStableContext = stableContext
        Timber.i("MercuryClient: initRide — context stored in memory (no server cache)")
        warmUpConnection()
    }

    /**
     * Lightweight GET to prime the shared ConnectionPool with a warm TLS
     * connection so the first per-event generate doesn't pay cold-start cost
     * under its 6s budget. Any HTTP response (even 4xx) counts as success
     * because TLS/connection-pool warming has already happened by then.
     *
     * Retry budget ≈48s wallclock — covers the realistic hotspot timing race
     * where the rider taps "Start ride" first, THEN enables their phone
     * hotspot (Karoo typically reconnects within ~15-30s). UnknownHostException
     * fires fast during that window so plain `repeat(N)` would burn through
     * retries in milliseconds; spaced delays absorb the wait.
     */
    @Volatile private var warmedUp = false

    private suspend fun warmUpConnection() = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext
        val request = Request.Builder()
            .url(WARMUP_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val delaysMs = listOf(0L, 3_000L, 5_000L, 8_000L, 12_000L, 20_000L)
        delaysMs.forEachIndexed { idx, delay ->
            if (delay > 0) kotlinx.coroutines.delay(delay)
            val ok = runCatching {
                httpInit.newCall(request).execute().use { resp ->
                    Timber.i("MercuryClient: warmup ${idx + 1}/${delaysMs.size} → HTTP ${resp.code}")
                    true
                }
            }.getOrElse { e ->
                Timber.w("MercuryClient: warmup ${idx + 1}/${delaysMs.size} failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
                false
            }
            if (ok) {
                warmedUp = true
                return@withContext
            }
        }
        Timber.w("MercuryClient: warmup failed after ${delaysMs.size} attempts — first generate will pay cold-start cost")
    }

    override suspend fun generate(livePrompt: String, fallback: String): String {
        if (apiKey.isBlank()) return fallback
        return try {
            // If warmup hasn't succeeded yet (e.g. hotspot still stabilizing), give
            // the very first generate a longer budget via httpInit so it has a fair
            // shot at landing instead of always hitting the rule fallback.
            val client = if (warmedUp) httpGen else httpInit
            val result = generateCompletion(livePrompt, client) ?: fallback
            if (!warmedUp && result !== fallback) warmedUp = true
            result
        } catch (e: Exception) {
            Timber.w(e, "MercuryClient: generation failed, using fallback")
            fallback
        }
    }

    override suspend fun endRide() {
        storedSystemPrompt = ""
        storedStableContext = ""
        warmedUp = false
        Timber.i("MercuryClient: endRide — context cleared")
    }

    // ----------------------------------------------------------------
    // HTTP call
    // ----------------------------------------------------------------

    private suspend fun generateCompletion(livePrompt: String, client: OkHttpClient): String? =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", MODEL)
                put("messages", buildJsonArray {
                    // System message — coaching persona and rules
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", storedSystemPrompt)
                    })
                    // Stable ride context (history + rider profile)
                    if (storedStableContext.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", storedStableContext)
                        })
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", "Understood. Ready to coach.")
                        })
                    }
                    // Live per-event prompt
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", livePrompt)
                    })
                })
                put("max_tokens", 40)
                put("temperature", 0.75)
                put("stop", buildJsonArray { add(JsonPrimitive("\n")) })
            }.toString()

            val request = Request.Builder()
                .url(CHAT_ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.w("MercuryClient: HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                        return@withContext null
                    }
                    extractText(resp.body?.string() ?: return@withContext null)
                }
            }.getOrElse {
                Timber.w(it, "MercuryClient: request failed")
                null
            }
        }

    private fun extractText(responseBody: String): String? {
        return runCatching {
            json.parseToJsonElement(responseBody).jsonObject
                .get("choices")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?.trim()
                ?.take(100)
        }.getOrElse {
            Timber.w(it, "MercuryClient: failed to parse response")
            null
        }
    }
}
