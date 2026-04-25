package io.hammerhead.pacepilot.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        // Mercury-2 is a reasoning model — it spends tokens internally before
        // emitting visible output. We pin "reasoning_effort=low" + a generous
        // max_tokens so a coaching cue actually surfaces. The legacy
        // "mercury-coder-small" id was deprecated for new accounts on 2026-02-24.
        private const val MODEL = "mercury-2"
        private const val REASONING_EFFORT = "low"
        private const val MAX_TOKENS = 100
        // Mercury-2 reasons before emitting; 6s read timeouts were causing null
        // responses (OkHttp throws) and "No AI reply" in the test + ride fallbacks.
        private const val TIMEOUT_GEN_SEC = 30L
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
            val text = generateCompletion(livePrompt, client)?.takeIf { it.isNotBlank() }
            val result = text ?: fallback
            if (text != null) warmedUp = true
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
                put("max_tokens", MAX_TOKENS)
                put("temperature", 0.75)
                put("reasoning_effort", REASONING_EFFORT)
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
            val message = json.parseToJsonElement(responseBody).jsonObject
                .get("choices")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?: return@runCatching null
            val raw = messageContentString(message) ?: return@runCatching null
            val cue = sanitiseCue(raw)
            if (cue.isBlank()) {
                Timber.w("MercuryClient: empty cue after parse; body=${responseBody.take(300)}")
                return@runCatching null
            }
            cue
        }.getOrElse {
            Timber.w(it, "MercuryClient: failed to parse response")
            null
        }
    }

    private fun messageContentString(message: JsonObject): String? {
        val c = message["content"] ?: return null
        return when {
            c is JsonPrimitive && c.isString -> c.content
            c is JsonArray -> c.joinToString("") { part ->
                when (part) {
                    is JsonObject ->
                        part["text"]?.jsonPrimitive?.content
                            ?: part["content"]?.jsonPrimitive?.content
                            ?: ""
                    is JsonPrimitive -> part.content
                    else -> ""
                }
            }
            else -> null
        }
    }

    /**
     * Mercury-2 sometimes wraps its reply in markdown bold or "**Cue:**" prefix
     * and surrounds the actual cue with curly quotes. Karoo's InRideAlert
     * doesn't render markdown, so strip leading labels, asterisks and quotes.
     */
    private fun sanitiseCue(raw: String): String {
        var s = raw.trim()
        // Drop leading label like "**Cue:**", "Cue:", "Coach:" etc.
        s = s.replace(Regex("^\\**\\s*(cue|coach|tip|advice)\\s*[:\\-]\\s*\\**\\s*", RegexOption.IGNORE_CASE), "")
        // Strip remaining markdown bold/italic markers.
        s = s.replace(Regex("\\*+"), "")
        // Trim wrapping quotes (straight or curly).
        s = s.trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', ' ')
        // Collapse multi-line replies into the first sentence/line.
        s = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: s
        return s.take(100)
    }
}
