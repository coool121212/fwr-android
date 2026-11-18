package com.fwr.app.data.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Thrown when Reddit's rate limit is exhausted and the required wait exceeds the interactive cap
 * or bounded retries are exhausted on HTTP 429.
 */
class RateLimitException(
    val retryAfterSeconds: Long,
    val bucket: String = RedditRateLimitTracker.BUCKET_OAUTH,
    message: String = "Reddit rate limit reached ($bucket). Retry after ${retryAfterSeconds}s."
) : IOException(message)

/**
 * Tracks Reddit rate-limit headers (`X-Ratelimit-Remaining`, `X-Ratelimit-Used`,
 * `X-Ratelimit-Reset`, `Retry-After`) separately per host bucket and enforces
 * proactive pacing or fail-fast [RateLimitException].
 */
class RedditRateLimitTracker(
    val maxInteractiveWaitMs: Long = DEFAULT_MAX_INTERACTIVE_WAIT_MS,
    private val lowQuotaThreshold: Double = DEFAULT_LOW_QUOTA_THRESHOLD,
    private val maxProactivePaceMs: Long = DEFAULT_MAX_PROACTIVE_PACE_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    data class BucketState(
        val remaining: Double = Double.POSITIVE_INFINITY,
        val used: Double = 0.0,
        val resetAtEpochMs: Long = 0L,
        val nextAllowedEpochMs: Long = 0L
    )

    private class BucketEntry {
        val lock = Any()
        var state = BucketState()
    }

    private val buckets = ConcurrentHashMap<String, BucketEntry>()

    private fun entryFor(bucket: String): BucketEntry {
        return buckets.computeIfAbsent(bucket) { BucketEntry() }
    }

    fun getBucketState(bucket: String): BucketState? {
        val entry = buckets[bucket] ?: return null
        return synchronized(entry.lock) { entry.state }
    }

    fun currentTimeMs(): Long = clockMs()

    fun recordResponse(bucket: String, headers: Headers, statusCode: Int) {
        val entry = entryFor(bucket)
        synchronized(entry.lock) {
            val now = clockMs()
            val prev = entry.state

            val parsedRemaining = parseFiniteDouble(headers["X-Ratelimit-Remaining"])
            val parsedUsed = parseFiniteDouble(headers["X-Ratelimit-Used"])
            val parsedResetSec = parseResetSeconds(headers["X-Ratelimit-Reset"])
            val parsedResetMs = parseResetMillis(headers["X-Ratelimit-Reset"])
            val parsedRetryAfterSec = parseRetryAfterSeconds(headers["Retry-After"], now)

            val resetAtMs = parsedResetMs?.let { ms ->
                saturatingAdd(now, ms)
            } ?: prev.resetAtEpochMs

            var nextRemaining = parsedRemaining ?: prev.remaining
            val nextUsed = parsedUsed ?: prev.used
            var nextAllowedMs = prev.nextAllowedEpochMs

            if (statusCode == 429) {
                nextRemaining = 0.0
                val waitSec = parsedRetryAfterSec
                    ?: parsedResetSec
                    ?: DEFAULT_429_FALLBACK_SECONDS
                val waitMs = secondsToMillisSaturating(waitSec)
                nextAllowedMs = maxOf(nextAllowedMs, saturatingAdd(now, waitMs))
            } else if (parsedRemaining != null) {
                if (parsedRemaining <= 0.0 && resetAtMs > now) {
                    nextAllowedMs = maxOf(nextAllowedMs, resetAtMs)
                } else if (parsedRemaining <= lowQuotaThreshold && resetAtMs > now) {
                    val windowMs = saturatingDiffPositive(resetAtMs, now)
                    val paceMs = minOf(
                        maxProactivePaceMs,
                        (windowMs / parsedRemaining.coerceAtLeast(1.0)).toLong().coerceAtLeast(0L)
                    )
                    nextAllowedMs = maxOf(nextAllowedMs, saturatingAdd(now, paceMs))
                }
            }

            entry.state = BucketState(
                remaining = nextRemaining,
                used = nextUsed,
                resetAtEpochMs = resetAtMs,
                nextAllowedEpochMs = nextAllowedMs
            )
        }
    }

    @Throws(IOException::class)
    fun awaitPermit(bucket: String, isCanceled: () -> Boolean) {
        val entry = entryFor(bucket)
        while (true) {
            if (isCanceled()) {
                throw IOException("Canceled")
            }

            var waitMs: Long
            var observedNow: Long
            var observedNextAllowed: Long
            var observedResetAt: Long

            synchronized(entry.lock) {
                val now = clockMs()
                observedNow = now
                var state = entry.state
                if (state.resetAtEpochMs in 1..now) {
                    state = state.copy(
                        remaining = Double.POSITIVE_INFINITY,
                        resetAtEpochMs = 0L
                    )
                    entry.state = state
                }

                val resetWait = if (state.remaining <= 0.0 && state.resetAtEpochMs > now) {
                    saturatingDiffPositive(state.resetAtEpochMs, now)
                } else {
                    0L
                }
                val paceWait = saturatingDiffPositive(state.nextAllowedEpochMs, now)
                waitMs = maxOf(0L, resetWait, paceWait)

                if (waitMs == 0L) {
                    return
                }
                if (waitMs > maxInteractiveWaitMs) {
                    val retryAfterSec = ceilSeconds(waitMs / 1000.0).coerceAtLeast(1L)
                    throw RateLimitException(retryAfterSeconds = retryAfterSec, bucket = bucket)
                }
                observedNextAllowed = state.nextAllowedEpochMs
                observedResetAt = state.resetAtEpochMs
            }

            sleepInterruptibly(waitMs, isCanceled)

            synchronized(entry.lock) {
                val afterNow = clockMs()
                if (afterNow <= observedNow &&
                    entry.state.nextAllowedEpochMs == observedNextAllowed &&
                    entry.state.resetAtEpochMs == observedResetAt
                ) {
                    // Static clock in tests does not advance during sleeper; exit after waiting once.
                    return
                }
            }
        }
    }

    @Throws(IOException::class)
    fun sleepInterruptibly(totalMs: Long, isCanceled: () -> Boolean) {
        if (totalMs <= 0L) {
            if (isCanceled()) throw IOException("Canceled")
            return
        }
        var remaining = totalMs
        while (remaining > 0L) {
            if (isCanceled()) {
                throw IOException("Canceled")
            }
            val chunk = minOf(SLEEP_CHUNK_MS, remaining)
            try {
                sleeper(chunk)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for rate limit").apply {
                    initCause(e)
                }
            }
            remaining -= chunk
            if (isCanceled()) {
                throw IOException("Canceled")
            }
        }
    }

    companion object {
        const val BUCKET_OAUTH = "oauth.reddit.com"
        const val BUCKET_PUBLIC = "www.reddit.com"

        const val DEFAULT_MAX_INTERACTIVE_WAIT_MS = 15_000L
        const val DEFAULT_LOW_QUOTA_THRESHOLD = 5.0
        const val DEFAULT_MAX_PROACTIVE_PACE_MS = 2_000L
        private const val DEFAULT_429_FALLBACK_SECONDS = 5L
        private const val SLEEP_CHUNK_MS = 200L
        private const val MAX_SECONDS_BEFORE_MILLIS_OVERFLOW = Long.MAX_VALUE / 1000L

        internal fun secondsToMillisSaturating(seconds: Long): Long {
            if (seconds <= 0L) return 0L
            return if (seconds > MAX_SECONDS_BEFORE_MILLIS_OVERFLOW) {
                Long.MAX_VALUE
            } else {
                seconds * 1000L
            }
        }

        internal fun saturatingAdd(a: Long, b: Long): Long {
            if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE
            if (b < 0L && a < Long.MIN_VALUE - b) return Long.MIN_VALUE
            return a + b
        }

        internal fun saturatingDiffPositive(target: Long, base: Long): Long {
            if (target <= base) return 0L
            if (base < 0L && target > Long.MAX_VALUE + base) return Long.MAX_VALUE
            return target - base
        }

        private fun parseFiniteDouble(raw: String?): Double? {
            val parsed = raw?.trim()?.toDoubleOrNull() ?: return null
            return parsed.takeIf { it.isFinite() }
        }

        private fun ceilSeconds(value: Double): Long {
            val ceiled = ceil(value)
            return if (!ceiled.isFinite() || ceiled >= Long.MAX_VALUE.toDouble()) {
                Long.MAX_VALUE
            } else {
                ceiled.toLong()
            }
        }

        internal fun parseResetSeconds(raw: String?): Long? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = trimmed.toDoubleOrNull() ?: return null
            return when {
                parsed.isNaN() || parsed == Double.NEGATIVE_INFINITY -> null
                parsed == Double.POSITIVE_INFINITY || parsed >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
                parsed <= 0.0 -> 0L
                else -> ceilSeconds(parsed)
            }
        }

        internal fun parseResetMillis(raw: String?): Long? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = trimmed.toDoubleOrNull() ?: return null
            return when {
                parsed.isNaN() || parsed == Double.NEGATIVE_INFINITY -> null
                parsed == Double.POSITIVE_INFINITY -> Long.MAX_VALUE
                parsed <= 0.0 -> 0L
                else -> {
                    val ms = ceil(parsed * 1000.0)
                    if (!ms.isFinite() || ms >= Long.MAX_VALUE.toDouble()) {
                        Long.MAX_VALUE
                    } else {
                        ms.toLong()
                    }
                }
            }
        }

        fun parseRetryAfterSeconds(raw: String?, nowMs: Long): Long? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
            trimmed.toDoubleOrNull()?.let { parsed ->
                return when {
                    parsed.isNaN() || parsed == Double.NEGATIVE_INFINITY -> null
                    parsed == Double.POSITIVE_INFINITY || parsed >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
                    parsed <= 0.0 -> 0L
                    else -> ceilSeconds(parsed)
                }
            }
            return try {
                val zdt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                val diffMs = Duration.between(Instant.ofEpochMilli(nowMs), zdt.toInstant()).toMillis()
                if (diffMs <= 0L) 0L else ceilSeconds(diffMs / 1000.0)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * OkHttp Interceptor placed immediately after [AuthInterceptor] that tracks Reddit rate limits
 * per host bucket, paces low-quota requests, fails fast when wait exceeds 15s, and performs
 * bounded retries (up to [maxRetries]) on HTTP 429 and idempotent transient 5xx responses.
 */
class RateLimitInterceptor(
    private val tracker: RedditRateLimitTracker = RedditRateLimitTracker(),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val randomJitterMs: () -> Long = { Random.nextLong(0L, 400L) },
    private val bucketResolver: (Request) -> String? = ::defaultBucketResolver
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val bucket = bucketResolver(request) ?: return chain.proceed(request)
        val isIdempotent = request.method.equals("GET", ignoreCase = true) ||
            request.method.equals("HEAD", ignoreCase = true)

        for (attempt in 0..maxRetries) {
            tracker.awaitPermit(bucket) { chain.call().isCanceled() }
            val response = chain.proceed(request)
            tracker.recordResponse(bucket, response.headers, response.code)

            val code = response.code
            val isTransient5xx = code == 500 || code == 502 || code == 503 || code == 504
            if (code != 429 && !isTransient5xx) {
                return response
            }

            val now = tracker.currentTimeMs()
            val retryAfterSecHeader = RedditRateLimitTracker.parseRetryAfterSeconds(
                response.header("Retry-After"),
                now
            )
            val retryAfterMs = retryAfterSecHeader?.let(RedditRateLimitTracker::secondsToMillisSaturating)

            val rawReset = response.header("X-Ratelimit-Reset")
            val resetSecHeader = RedditRateLimitTracker.parseResetSeconds(rawReset)
            val resetMs = RedditRateLimitTracker.parseResetMillis(rawReset)

            if (code == 429 && (!isIdempotent || attempt == maxRetries)) {
                val retryAfterSec = (retryAfterSecHeader ?: resetSecHeader ?: 5L).coerceAtLeast(1L)
                response.close()
                throw RateLimitException(retryAfterSeconds = retryAfterSec, bucket = bucket)
            }

            if (isTransient5xx && (!isIdempotent || attempt == maxRetries)) {
                return response
            }

            val jitterMs = randomJitterMs().coerceAtLeast(0L)
            val delayMs = if (code == 429) {
                val base429Ms = retryAfterMs ?: resetMs ?: (1_000L shl attempt)
                RedditRateLimitTracker.saturatingAdd(base429Ms, jitterMs)
            } else {
                val base5xx = retryAfterMs ?: minOf(4_000L, 500L shl attempt)
                RedditRateLimitTracker.saturatingAdd(base5xx, jitterMs)
            }

            if (delayMs > tracker.maxInteractiveWaitMs) {
                response.close()
                if (code == 429) {
                    val ceilDelaySec = ceil(delayMs / 1000.0).let {
                        if (!it.isFinite() || it >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else it.toLong()
                    }
                    val retryAfterSec = (retryAfterSecHeader ?: resetSecHeader ?: ceilDelaySec).coerceAtLeast(1L)
                    throw RateLimitException(retryAfterSeconds = retryAfterSec, bucket = bucket)
                } else {
                    throw IOException("Server requested ${delayMs}ms retry delay exceeding interactive cap")
                }
            }

            response.close()
            tracker.sleepInterruptibly(delayMs) { chain.call().isCanceled() }
        }

        throw IOException("Rate limit retry loop terminated unexpectedly for $bucket")
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2

        fun defaultBucketResolver(request: Request): String? {
            return when (request.url.host.lowercase(Locale.ROOT)) {
                "oauth.reddit.com" -> RedditRateLimitTracker.BUCKET_OAUTH
                "www.reddit.com",
                "reddit.com",
                "old.reddit.com",
                "api.reddit.com" -> RedditRateLimitTracker.BUCKET_PUBLIC
                else -> null
            }
        }
    }
}
