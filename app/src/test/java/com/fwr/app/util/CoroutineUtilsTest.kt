package com.fwr.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineUtilsTest {

    @Test
    fun runSuspendCatching_returnsSuccessForNormalValue() = runTest {
        val result = runSuspendCatching {
            delay(10)
            "reddit-ok"
        }
        assertTrue(result.isSuccess)
        assertEquals("reddit-ok", result.getOrNull())
    }

    @Test
    fun runSuspendCatching_capturesNonCancellationExceptions() = runTest {
        val ioResult = runSuspendCatching {
            throw IOException("network down")
        }
        assertTrue(ioResult.isFailure)
        assertTrue(ioResult.exceptionOrNull() is IOException)

        val stateResult = runSuspendCatching {
            throw IllegalStateException("bad state")
        }
        assertTrue(stateResult.isFailure)
        assertTrue(stateResult.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun runSuspendCatching_rethrowsCancellationException() = runTest {
        try {
            runSuspendCatching {
                throw CancellationException("explicit cancel")
            }
            fail("Expected CancellationException to be rethrown")
        } catch (expected: CancellationException) {
            assertEquals("explicit cancel", expected.message)
        }
    }

    @Test
    fun runSuspendCatching_rethrowsTimeoutCancellationException() = runTest {
        try {
            withTimeout(10L) {
                runSuspendCatching {
                    delay(1_000L)
                }
            }
            fail("Expected TimeoutCancellationException to be rethrown")
        } catch (_: TimeoutCancellationException) {
            // expected
        }
    }

    @Test
    fun runSuspendCatching_propagatesJobCancellationWithoutRunningFailureBranch() = runTest {
        var failureExecuted = false
        val job = launch {
            runSuspendCatching {
                delay(10_000L)
            }.onFailure {
                failureExecuted = true
            }
        }
        testScheduler.advanceTimeBy(50L)
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(!failureExecuted)
    }
}
