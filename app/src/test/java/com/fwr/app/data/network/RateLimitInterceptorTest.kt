package com.fwr.app.data.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RateLimitInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesRateLimitHeadersAndSeparatesOAuthAndPublicBuckets() {
        var nowMs = 1_000_000L
        val sleepLog = mutableListOf<Long>()
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms ->
                sleepLog.add(ms)
                nowMs += ms
            }
        )
        val interceptor = RateLimitInterceptor(
            tracker = tracker,
            maxRetries = 2,
            randomJitterMs = { 0L },
            bucketResolver = { req ->
                req.header("X-Test-Bucket") ?: RedditRateLimitTracker.BUCKET_OAUTH
            }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Remaining", "0.0")
                .addHeader("X-Ratelimit-Used", "600.0")
                .addHeader("X-Ratelimit-Reset", "45.0")
                .setBody("{}")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Remaining", "59.0")
                .addHeader("X-Ratelimit-Used", "1.0")
                .addHeader("X-Ratelimit-Reset", "60.0")
                .setBody("{\"ok\":true}")
        )

        val oauthReq = Request.Builder()
            .url(server.url("/hot.json"))
            .header("X-Test-Bucket", RedditRateLimitTracker.BUCKET_OAUTH)
            .build()
        client.newCall(oauthReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        val oauthState = tracker.getBucketState(RedditRateLimitTracker.BUCKET_OAUTH)
        assertNotNull(oauthState)
        assertEquals(0.0, oauthState!!.remaining, 0.001)
        assertEquals(600.0, oauthState.used, 0.001)

        // Second oauth call fails fast (> 15s reset)
        try {
            client.newCall(oauthReq).execute().close()
            fail("Expected fail-fast RateLimitException for exhausted oauth bucket")
        } catch (e: RateLimitException) {
            assertEquals(RedditRateLimitTracker.BUCKET_OAUTH, e.bucket)
            assertEquals(45L, e.retryAfterSeconds)
        }
        assertEquals(1, server.requestCount)
        assertTrue(sleepLog.isEmpty())

        // Public bucket remains completely independent and succeeds
        val publicReq = Request.Builder()
            .url(server.url("/r/all/hot.json"))
            .header("X-Test-Bucket", RedditRateLimitTracker.BUCKET_PUBLIC)
            .build()
        client.newCall(publicReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(2, server.requestCount)
        val publicState = tracker.getBucketState(RedditRateLimitTracker.BUCKET_PUBLIC)
        assertEquals(59.0, publicState!!.remaining, 0.001)
    }

    @Test
    fun proactivelyPacesLowQuotaWhenResetWindowIsShort() {
        var nowMs = 5_000_000L
        val sleepLog = mutableListOf<Long>()
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms ->
                sleepLog.add(ms)
                nowMs += ms
            }
        )
        val interceptor = RateLimitInterceptor(
            tracker = tracker,
            randomJitterMs = { 0L },
            bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Remaining", "2.0")
                .addHeader("X-Ratelimit-Reset", "4")
                .setBody("{}")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/1")).build()).execute().close()
        assertTrue(sleepLog.isEmpty())

        client.newCall(Request.Builder().url(server.url("/2")).build()).execute().close()
        // window = 4000ms, remaining = 2.0 -> pace = min(2000, 4000 / 2) = 2000ms
        assertEquals(2_000L, sleepLog.sum())
    }

    @Test
    fun parsesIntegerAndRfc1123RetryAfterHeaders() {
        val nowMs = 1_700_000_000_000L
        assertEquals(3L, RedditRateLimitTracker.parseRetryAfterSeconds("3", nowMs))
        assertEquals(0L, RedditRateLimitTracker.parseRetryAfterSeconds("-2", nowMs))

        val targetTime = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(nowMs + 12_000L),
            ZoneOffset.UTC
        )
        val rfc1123 = DateTimeFormatter.RFC_1123_DATE_TIME.format(targetTime)
        assertEquals(12L, RedditRateLimitTracker.parseRetryAfterSeconds(rfc1123, nowMs))
    }

    @Test
    fun retriesGet429And503UpToMaxRetriesAndClosesPriorResponses() {
        var nowMs = 10_000_000L
        val sleepLog = mutableListOf<Long>()
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms ->
                sleepLog.add(ms)
                nowMs += ms
            }
        )
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)
        val eventListener = object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                acquired.incrementAndGet()
            }

            override fun connectionReleased(call: Call, connection: Connection) {
                released.incrementAndGet()
            }
        }
        val pool = ConnectionPool(1, 5, TimeUnit.SECONDS)
        val client = OkHttpClient.Builder()
            .connectionPool(pool)
            .eventListener(eventListener)
            .addInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 2,
                    randomJitterMs = { 0L },
                    bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                )
            )
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
                .setBody("rate limited".repeat(500))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("unavailable".repeat(500))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
        )

        client.newCall(Request.Builder().url(server.url("/comments")).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("{\"status\":\"ok\"}", resp.body?.string())
        }

        assertEquals(3, server.requestCount)
        assertTrue(sleepLog.sum() >= 2_000L)
        assertEquals(acquired.get(), released.get())
        assertTrue(pool.connectionCount() <= 1)
    }

    @Test
    fun exhausted429ThrowsRateLimitExceptionAndPostIsNotRetried() {
        var nowMs = 20_000_000L
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms -> nowMs += ms }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 2,
                    randomJitterMs = { 0L },
                    bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                )
            )
            .build()

        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .addHeader("Retry-After", "1")
                    .setBody("{}")
            )
        }

        try {
            client.newCall(Request.Builder().url(server.url("/hot")).build()).execute().close()
            fail("Expected RateLimitException after exhausting 2 retries")
        } catch (e: RateLimitException) {
            assertEquals(1L, e.retryAfterSeconds)
        }
        assertEquals(3, server.requestCount)

        // Reset clock past retry window for POST checks
        nowMs += 10_000L
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "1")
                .setBody("{}")
        )
        val postReq = Request.Builder()
            .url(server.url("/api/vote"))
            .post("id=t3_1&dir=1".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        try {
            client.newCall(postReq).execute().close()
            fail("Expected immediate RateLimitException for POST 429")
        } catch (_: RateLimitException) {
            // expected
        }
        assertEquals(4, server.requestCount)

        nowMs += 10_000L
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        client.newCall(postReq).execute().use { resp ->
            assertEquals(502, resp.code)
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun cancelingCallAbortsSleepPromptly() {
        var nowMs = 30_000_000L
        val sleepLog = mutableListOf<Long>()
        val callRef = AtomicReference<Call?>(null)
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms ->
                sleepLog.add(ms)
                nowMs += ms
                callRef.get()?.cancel()
            }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 2,
                    randomJitterMs = { 0L },
                    bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                )
            )
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "10")
                .setBody("{}")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val call = client.newCall(Request.Builder().url(server.url("/feed")).build())
        callRef.set(call)
        try {
            call.execute().close()
            fail("Expected IOException when call is canceled mid-backoff")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("Cancel", ignoreCase = true) == true)
        }
        assertEquals(1, server.requestCount)
        assertTrue(sleepLog.sum() <= 400L)
    }

    @Test
    fun hugeAndInfiniteRetryAfterFailFastWithoutOverflowOrRetry() {
        val hugeValues = listOf("10000000000000000", "1e20", "Infinity")
        for (headerValue in hugeValues) {
            val localServer = MockWebServer()
            localServer.start()
            try {
                var nowMs = 50_000_000L
                val sleepLog = mutableListOf<Long>()
                val tracker = RedditRateLimitTracker(
                    clockMs = { nowMs },
                    sleeper = { ms ->
                        sleepLog.add(ms)
                        nowMs += ms
                    }
                )
                val client = OkHttpClient.Builder()
                    .addInterceptor(
                        RateLimitInterceptor(
                            tracker = tracker,
                            maxRetries = 2,
                            randomJitterMs = { 0L },
                            bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                        )
                    )
                    .build()

                localServer.enqueue(
                    MockResponse()
                        .setResponseCode(429)
                        .addHeader("Retry-After", headerValue)
                        .setBody("{}")
                )
                localServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

                try {
                    client.newCall(Request.Builder().url(localServer.url("/hot")).build()).execute().close()
                    fail("Expected fail-fast RateLimitException for Retry-After=$headerValue")
                } catch (e: RateLimitException) {
                    assertTrue(
                        "Expected retryAfterSeconds > 15 for $headerValue, got ${e.retryAfterSeconds}",
                        e.retryAfterSeconds > 15L
                    )
                }
                assertEquals(
                    "Should not retry GET 429 on Retry-After=$headerValue",
                    1,
                    localServer.requestCount
                )
                assertTrue("Should not sleep on fail-fast Retry-After=$headerValue", sleepLog.isEmpty())
            } finally {
                localServer.shutdown()
            }
        }
    }

    @Test
    fun post429AndGet503WithHugeRetryAfterPreserveSecondsAndFailFast() {
        var nowMs = 60_000_000L
        val sleepLog = mutableListOf<Long>()
        val tracker = RedditRateLimitTracker(
            clockMs = { nowMs },
            sleeper = { ms ->
                sleepLog.add(ms)
                nowMs += ms
            }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 2,
                    randomJitterMs = { 0L },
                    bucketResolver = { req -> req.header("X-Test-Bucket") }
                )
            )
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "10000000000000000")
                .setBody("{}")
        )
        val postReq = Request.Builder()
            .url(server.url("/api/vote"))
            .header("X-Test-Bucket", RedditRateLimitTracker.BUCKET_OAUTH)
            .post("id=t3_1&dir=1".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        try {
            client.newCall(postReq).execute().close()
            fail("Expected RateLimitException on POST 429 with huge Retry-After")
        } catch (e: RateLimitException) {
            assertEquals(10_000_000_000_000_000L, e.retryAfterSeconds)
        }
        assertEquals(1, server.requestCount)

        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .addHeader("Retry-After", Int.MAX_VALUE.toString())
                .setBody("unavailable")
        )
        val getReq = Request.Builder()
            .url(server.url("/r/all"))
            .header("X-Test-Bucket", RedditRateLimitTracker.BUCKET_PUBLIC)
            .build()
        try {
            client.newCall(getReq).execute().close()
            fail("Expected IOException on GET 503 with huge Retry-After")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeding interactive cap") == true)
        }
        assertEquals(2, server.requestCount)
        assertTrue(sleepLog.isEmpty())

        // Also verify 503 with Retry-After > Int.MAX_VALUE directly on RateLimitInterceptor
        // (bypasses OkHttp's internal RetryAndFollowUpInterceptor which uses Integer.valueOf)
        val networkClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 2,
                    randomJitterMs = { 0L },
                    bucketResolver = { RedditRateLimitTracker.BUCKET_PUBLIC }
                )
            )
            .build()
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .addHeader("Retry-After", "10000000000000000")
                .setBody("unavailable")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        try {
            networkClient.newCall(Request.Builder().url(server.url("/r/all-net")).build()).execute().close()
            fail("Expected IOException on GET 503 with 10^16 Retry-After")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeding interactive cap") == true)
        }
        assertEquals(3, server.requestCount)
        assertTrue(sleepLog.isEmpty())
    }

    @Test
    fun hugeAndInfiniteRateLimitResetTracksExhaustionWithoutOverflow() {
        val resetValues = listOf("1e20", "Infinity")
        for (resetValue in resetValues) {
            val localServer = MockWebServer()
            localServer.start()
            try {
                var nowMs = 70_000_000L
                val sleepLog = mutableListOf<Long>()
                val tracker = RedditRateLimitTracker(
                    clockMs = { nowMs },
                    sleeper = { ms ->
                        sleepLog.add(ms)
                        nowMs += ms
                    }
                )
                val client = OkHttpClient.Builder()
                    .addInterceptor(
                        RateLimitInterceptor(
                            tracker = tracker,
                            maxRetries = 2,
                            randomJitterMs = { 0L },
                            bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                        )
                    )
                    .build()

                localServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("X-Ratelimit-Remaining", "0.0")
                        .addHeader("X-Ratelimit-Reset", resetValue)
                        .setBody("{}")
                )
                localServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

                val req = Request.Builder().url(localServer.url("/hot")).build()
                client.newCall(req).execute().use { resp ->
                    assertEquals(200, resp.code)
                }

                try {
                    client.newCall(req).execute().close()
                    fail("Expected fail-fast RateLimitException when X-Ratelimit-Reset=$resetValue")
                } catch (e: RateLimitException) {
                    assertTrue(e.retryAfterSeconds > 15L)
                }
                assertEquals(1, localServer.requestCount)
                assertTrue(sleepLog.isEmpty())
            } finally {
                localServer.shutdown()
            }
        }
    }

    @Test
    fun nonFiniteNanAndNegativeInfinityHeadersAreSafelyIgnored() {
        val nowMs = 80_000_000L
        assertNull(RedditRateLimitTracker.parseRetryAfterSeconds("NaN", nowMs))
        assertNull(RedditRateLimitTracker.parseRetryAfterSeconds("-Infinity", nowMs))
        assertEquals(Long.MAX_VALUE, RedditRateLimitTracker.parseRetryAfterSeconds("Infinity", nowMs))
        assertEquals(Long.MAX_VALUE, RedditRateLimitTracker.parseRetryAfterSeconds("1e20", nowMs))

        var clock = nowMs
        val sleepLog = mutableListOf<Long>()
        val tracker = RedditRateLimitTracker(
            clockMs = { clock },
            sleeper = { ms ->
                sleepLog.add(ms)
                clock += ms
            }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(
                RateLimitInterceptor(
                    tracker = tracker,
                    maxRetries = 1,
                    randomJitterMs = { 0L },
                    bucketResolver = { RedditRateLimitTracker.BUCKET_OAUTH }
                )
            )
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Remaining", "NaN")
                .addHeader("X-Ratelimit-Used", "-Infinity")
                .addHeader("X-Ratelimit-Reset", "NaN")
                .setBody("{}")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "NaN")
                .addHeader("X-Ratelimit-Reset", "-Infinity")
                .setBody("{}")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("X-Ratelimit-Remaining", "10.0")
                .setBody("{\"ok\":true}")
        )

        client.newCall(Request.Builder().url(server.url("/first")).build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        val stateAfterFirst = tracker.getBucketState(RedditRateLimitTracker.BUCKET_OAUTH)!!
        assertEquals(Double.POSITIVE_INFINITY, stateAfterFirst.remaining, 0.0)
        assertEquals(0.0, stateAfterFirst.used, 0.0)
        assertEquals(0L, stateAfterFirst.resetAtEpochMs)

        // Second call gets 429 with Retry-After: NaN -> falls back cleanly to 5s bucket cooldown and succeeds
        client.newCall(Request.Builder().url(server.url("/second")).build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(3, server.requestCount)
        assertEquals(5_000L, sleepLog.sum())
    }
}
