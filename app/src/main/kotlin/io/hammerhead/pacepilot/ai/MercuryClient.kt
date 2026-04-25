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
     * under its 6s budget. Any HTTP response (even 4xx) counts as success.
     */
    private suspend fun warmUpConnection() = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext
        val request = Request.Builder()
            .url(WARMUP_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        // One retry: hotspot link often needs a few seconds to stabilize at ride start.
        repeat(2) { attempt ->
            val ok = runCatching {
                httpInit.newCall(request).execute().use { resp ->
                    Timber.i("MercuryClient: warmup attempt ${attempt + 1} → HTTP ${resp.code}")
                    true
                }
            }.getOrElse {
                Timber.w(it, "MercuryClient: warmup failed (attempt ${attempt + 1})")
                false
            }
            if (ok) return@withContext
            if (attempt == 0) kotlinx.coroutines.delay(2_000)
        }
        Timber.w("MercuryClient: warmup failed after retry — first generate may pay cold-start cost")
    }

    override suspend fun generate(livePrompt: String, fallback: String): String {
        if (apiKey.isBlank()) return fallback
        return try {
            generateCompletion(livePrompt) ?: fallback
        } catch (e: Exception) {
            Timber.w(e, "MercuryClient: generation failed, using fallback")
            fallback
        }
    }

    override suspend fun endRide() {
        storedSystemPrompt = ""
        storedStableContext = ""
        Timber.i("MercuryClient: endRide — context cleared")
    }

    // ----------------------------------------------------------------
    // HTTP call
    // ----------------------------------------------------------------

    private suspend fun generateCompletion(livePrompt: String): String? =
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
                httpGen.newCall(request).execute().use { resp ->
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
