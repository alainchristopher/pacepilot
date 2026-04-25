package io.hammerhead.pacepilot

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies the v1.3.2 hotspot fix at the OkHttp layer:
 *
 * 1. `httpGen = httpInit.newBuilder().build()` shares the ConnectionPool
 *    (this is the foundation of the fix — warmup primes a pool that
 *    subsequent generates reuse).
 * 2. After a successful httpInit request, the pool has a live connection.
 * 3. A subsequent httpGen request reuses that connection (idle count drops
 *    by one, total reuse confirmed via OkHttp's pool stats).
 *
 * No network — uses MockWebServer.
 */
class AiClientWarmupTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `httpGen shares ConnectionPool with httpInit`() {
        val httpInit = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val httpGen = httpInit.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        // Reference equality on the ConnectionPool — the whole point.
        assertSame(
            "httpGen must share httpInit's ConnectionPool for warmup to benefit generates",
            httpInit.connectionPool,
            httpGen.connectionPool,
        )
        // Timeouts diverge as expected.
        assertEquals(15_000, httpInit.connectTimeoutMillis)
        assertEquals(6_000, httpGen.connectTimeoutMillis)
    }

    @Test
    fun `successful httpInit warmup leaves a connection in the shared pool`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val httpInit = OkHttpClient.Builder().build()

        val request = okhttp3.Request.Builder().url(server.url("/v1/models")).build()
        httpInit.newCall(request).execute().use { resp ->
            assertEquals(200, resp.code)
            // Drain so the connection is returned to the pool, not closed.
            resp.body?.string()
        }

        // After a clean response, OkHttp puts the connection back in the pool.
        // idleConnectionCount() reflects this immediately.
        assertTrue(
            "warmup should leave at least one idle connection in pool",
            httpInit.connectionPool.idleConnectionCount() >= 1,
        )
    }

    @Test
    fun `httpGen reuses warm connection without re-handshake`() = runBlocking {
        // Two responses queued — one for warmup, one for the gen call.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"choices\":[]}"))

        val httpInit = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val httpGen = httpInit.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()

        val warmupReq = okhttp3.Request.Builder().url(server.url("/v1/models")).build()
        httpInit.newCall(warmupReq).execute().use { it.body?.string() }

        val poolBefore = httpInit.connectionPool.connectionCount()
        assertTrue("pool should have a connection after warmup", poolBefore >= 1)

        val genReq = okhttp3.Request.Builder()
            .url(server.url("/v1/chat/completions"))
            .post(okhttp3.RequestBody.create(null, "{}".toByteArray()))
            .build()
        httpGen.newCall(genReq).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body?.string()
        }

        // Same pool, same single connection — no new socket opened.
        assertEquals(
            "httpGen must reuse the pooled connection, not open a new one",
            poolBefore,
            httpGen.connectionPool.connectionCount(),
        )
    }
}
