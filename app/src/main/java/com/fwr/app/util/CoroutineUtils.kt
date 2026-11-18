package com.fwr.app.util

import kotlinx.coroutines.CancellationException

/**
 * Executes [block] and encapsulates its return value or non-cancellation [Throwable] in [Result].
 * Always rethrows [CancellationException] so structured coroutine cancellation is never swallowed.
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
