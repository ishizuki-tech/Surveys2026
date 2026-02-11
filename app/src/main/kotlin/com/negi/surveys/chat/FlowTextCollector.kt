/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Flow Text Collector)
 *  ---------------------------------------------------------------------
 *  File: FlowTextCollector.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * Reason why collection finished.
 */
enum class FlowTextStopReason {
    /** Upstream completed normally. */
    COMPLETED,

    /** Timeout reached; partial text returned. */
    TIMEOUT,

    /** maxChars reached; collection stopped early and text was clipped. */
    MAX_CHARS,

    /** Unexpected error; partial text returned with a sanitized token. */
    ERROR
}

/**
 * Result of collecting a Flow<String> into text.
 *
 * @property text Collected (possibly partial) text.
 * @property reason Why collection stopped.
 * @property errorToken Sanitized error token when reason == ERROR.
 */
data class FlowTextResult(
    val text: String,
    val reason: FlowTextStopReason,
    val errorToken: String? = null
)

/**
 * Collects a streaming Flow<String> into a single String safely.
 *
 * Design goals:
 * - Bound memory usage via maxChars.
 * - Provide optional per-chunk hook (for live UI updates).
 * - Support timeout to avoid hanging forever.
 *
 * Behavior:
 * - Timeout => returns partial text.
 * - Cancellation => rethrows CancellationException.
 * - Other errors => returns partial text as much as possible (use [collectToTextSafely] for details).
 *
 * Timeout semantics:
 * - timeoutMs > 0 => enforce timeout using withTimeout(timeoutMs).
 * - timeoutMs <= 0 => NO TIMEOUT (collect until completion or maxChars).
 *
 * NOTE:
 * - This function intentionally does not log user content.
 */
suspend fun Flow<String>.collectToText(
    timeoutMs: Long = 30_000L,
    maxChars: Int = 32_000,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
): String {
    return collectToTextSafely(timeoutMs, maxChars, onChunk).text
}

/**
 * Collects a streaming Flow<String> into text and returns a structured result.
 *
 * Differences vs [collectToText]:
 * - Returns a [FlowTextResult] including stop reason and a sanitized error token.
 * - Still rethrows [CancellationException] to respect structured concurrency.
 *
 * Timeout semantics:
 * - timeoutMs > 0 => enforce timeout using withTimeout(timeoutMs).
 * - timeoutMs <= 0 => NO TIMEOUT (collect until completion or maxChars).
 */
suspend fun Flow<String>.collectToTextSafely(
    timeoutMs: Long = 30_000L,
    maxChars: Int = 32_000,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
): FlowTextResult {
    val maxCharsSafe = maxChars.coerceAtLeast(0)
    if (maxCharsSafe == 0) {
        return FlowTextResult(
            text = "",
            reason = FlowTextStopReason.MAX_CHARS
        )
    }

    val sb = StringBuilder(minOf(maxCharsSafe, 4_096))

    try {
        if (timeoutMs > 0L) {
            withTimeout(timeoutMs) {
                collectInto(sb, maxCharsSafe, onChunk)
            }
        } else {
            // No timeout requested; collect until completion or maxChars limit.
            collectInto(sb, maxCharsSafe, onChunk)
        }

        return FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.COMPLETED
        )
    } catch (_: MaxCharsReached) {
        return FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.MAX_CHARS
        )
    } catch (_: TimeoutCancellationException) {
        return FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.TIMEOUT
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        return FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.ERROR,
            errorToken = sanitizeErrorToken(t)
        )
    }
}

/**
 * Collects a streaming Flow<String> using a strict "budget" timeout.
 *
 * Why this exists:
 * - Some callers compute a remaining time budget (e.g., total timeout minus request-creation time).
 * - Passing timeoutMs=0 into [collectToTextSafely] means "NO TIMEOUT", which is a footgun for budgets.
 *
 * Budget semantics:
 * - timeoutBudgetMs <= 0 => immediate TIMEOUT (returns empty text).
 * - timeoutBudgetMs > 0 => behaves like [collectToTextSafely] with timeout enforcement.
 *
 * This function does NOT change existing semantics of [collectToTextSafely].
 */
suspend fun Flow<String>.collectToTextSafelyWithBudget(
    timeoutBudgetMs: Long,
    maxChars: Int = 32_000,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
): FlowTextResult {
    if (timeoutBudgetMs <= 0L) {
        return FlowTextResult(
            text = "",
            reason = FlowTextStopReason.TIMEOUT
        )
    }
    return collectToTextSafely(
        timeoutMs = timeoutBudgetMs,
        maxChars = maxChars,
        onChunk = onChunk
    )
}

/**
 * Collects chunks into [sb] while enforcing the [maxChars] ceiling.
 *
 * Implementation note:
 * - Throws [MaxCharsReached] to stop upstream collection early.
 *
 * Callback note:
 * - Exceptions from [onChunk] must not break collection (to avoid losing model output).
 * - CancellationException is rethrown to respect structured concurrency.
 */
private suspend fun Flow<String>.collectInto(
    sb: StringBuilder,
    maxChars: Int,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)?
) {
    collect { chunk ->
        if (chunk.isEmpty()) return@collect

        val remain = maxChars - sb.length
        if (remain <= 0) throw MaxCharsReached()

        val toAppend = if (chunk.length <= remain) chunk else chunk.substring(0, remain)
        sb.append(toAppend)

        if (onChunk != null) {
            try {
                onChunk.invoke(toAppend, sb.length)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Swallow onChunk exceptions to avoid losing the collected output.
            }
        }

        if (sb.length >= maxChars) throw MaxCharsReached()
    }
}

/**
 * Sanitizes an error into a short, non-PII token.
 *
 * Avoid using exception messages because they may include file paths or user data.
 */
private fun sanitizeErrorToken(t: Throwable): String {
    return t.javaClass.simpleName.ifBlank { "error" }.take(32)
}

/**
 * Internal signal used to stop collection when the output size cap is reached.
 *
 * This is intentionally thrown often in cap scenarios, so avoid stack trace cost.
 */
private class MaxCharsReached : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
