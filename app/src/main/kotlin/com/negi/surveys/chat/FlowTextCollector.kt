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
import kotlinx.coroutines.withTimeout

/**
 * Reason why flow collection stopped.
 */
enum class FlowTextStopReason {
    /** Upstream completed normally. */
    COMPLETED,

    /** Timeout reached; partial text returned. */
    TIMEOUT,

    /** Output size cap reached; collection stopped early and text was clipped. */
    MAX_CHARS,

    /** Unexpected error; partial text returned with a sanitized error token. */
    ERROR
}

/**
 * Result of collecting a [Flow] of text chunks into a single string.
 *
 * @property text Collected text (possibly partial).
 * @property reason Why collection stopped.
 * @property errorToken Sanitized error token when [reason] == [FlowTextStopReason.ERROR].
 */
data class FlowTextResult(
    val text: String,
    val reason: FlowTextStopReason,
    val errorToken: String? = null
)

/**
 * Collects a streaming [Flow]<[String]> into a single [String].
 *
 * Design goals:
 * - Bound memory usage via [maxChars].
 * - Optional per-chunk hook for live UI updates.
 * - Optional timeout to avoid hanging forever.
 *
 * Behavior:
 * - Timeout => returns partial text.
 * - Cancellation => rethrows [CancellationException].
 * - Other errors => returns partial text (see [collectToTextResult]).
 *
 * Timeout semantics:
 * - [timeoutMs] > 0 => enforce timeout via [withTimeout].
 * - [timeoutMs] <= 0 => NO TIMEOUT (collect until completion or [maxChars]).
 *
 * NOTE:
 * - This API intentionally does not log user content.
 */
suspend fun Flow<String>.collectToText(
    timeoutMs: Long = 30_000L,
    maxChars: Int = 32_000,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
): String {
    return collectToTextResult(timeoutMs, maxChars, onChunk).text
}

/**
 * Collects a streaming [Flow]<[String]> into text and returns a structured result.
 *
 * Differences vs [collectToText]:
 * - Returns a [FlowTextResult] including stop reason and an error token.
 * - Still rethrows [CancellationException] to respect structured concurrency.
 *
 * Timeout semantics:
 * - [timeoutMs] > 0 => enforce timeout.
 * - [timeoutMs] <= 0 => NO TIMEOUT (collect until completion or [maxChars]).
 */
suspend fun Flow<String>.collectToTextResult(
    timeoutMs: Long = 30_000L,
    maxChars: Int = 32_000,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
): FlowTextResult {
    val limit = maxChars.coerceAtLeast(0)
    if (limit == 0) {
        return FlowTextResult(
            text = "",
            reason = FlowTextStopReason.MAX_CHARS
        )
    }

    val sb = StringBuilder(minOf(limit, 4_096))

    return try {
        if (timeoutMs > 0L) {
            withTimeout(timeoutMs) {
                collectInto(sb, limit, onChunk)
            }
        } else {
            collectInto(sb, limit, onChunk)
        }

        // Ensure validity even if upstream produced odd boundaries.
        dropDanglingHighSurrogateAtEnd(sb)

        FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.COMPLETED
        )
    } catch (_: OutputLimitReached) {
        dropDanglingHighSurrogateAtEnd(sb)
        FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.MAX_CHARS
        )
    } catch (_: TimeoutCancellationException) {
        dropDanglingHighSurrogateAtEnd(sb)
        FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.TIMEOUT
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        dropDanglingHighSurrogateAtEnd(sb)
        FlowTextResult(
            text = sb.toString(),
            reason = FlowTextStopReason.ERROR,
            errorToken = sanitizeErrorToken(t)
        )
    }
}

/**
 * Collects with a strict timeout budget.
 *
 * Why this exists:
 * - Some callers compute remaining time budgets (e.g., total deadline minus setup time).
 * - Passing timeoutMs=0 into [collectToTextResult] means "NO TIMEOUT", which is dangerous for budgets.
 *
 * Budget semantics:
 * - [timeoutBudgetMs] <= 0 => immediate TIMEOUT (returns empty text).
 * - [timeoutBudgetMs] > 0 => behaves like [collectToTextResult] with timeout enforcement.
 *
 * This function intentionally does NOT change semantics of [collectToTextResult].
 */
suspend fun Flow<String>.collectToTextResultWithBudget(
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
    return collectToTextResult(
        timeoutMs = timeoutBudgetMs,
        maxChars = maxChars,
        onChunk = onChunk
    )
}

/**
 * Collects chunks into [sb] while enforcing the [maxChars] ceiling.
 *
 * Implementation note:
 * - Throws [OutputLimitReached] to stop upstream collection early.
 *
 * Callback note:
 * - Exceptions from [onChunk] must not break collection (to avoid losing model output).
 * - [CancellationException] is rethrown to respect structured concurrency.
 *
 * Surrogate safety:
 * - Handles rare cases where upstream splits UTF-16 surrogate pairs across chunk boundaries.
 */
private suspend fun Flow<String>.collectInto(
    sb: StringBuilder,
    maxChars: Int,
    onChunk: ((chunk: String, totalChars: Int) -> Unit)?
) {
    collect { rawChunk ->
        if (rawChunk.isEmpty()) return@collect

        // --- Cross-chunk surrogate repair ---------------------------------
        // If sb ends with a dangling high surrogate, decide based on next chunk's head.
        if (sb.isNotEmpty() && Character.isHighSurrogate(sb[sb.length - 1])) {
            val head = rawChunk.firstOrNull()
            if (head == null || !Character.isLowSurrogate(head)) {
                // Previous chunk ended with an unmatched high surrogate; drop it.
                sb.setLength(sb.length - 1)
            }
        }

        // If a chunk starts with a low surrogate and there is no preceding high surrogate,
        // drop the leading low surrogate (it cannot be valid).
        var start = 0
        if (rawChunk.isNotEmpty() && Character.isLowSurrogate(rawChunk[0])) {
            val prevIsHigh = sb.isNotEmpty() && Character.isHighSurrogate(sb[sb.length - 1])
            if (!prevIsHigh) start = 1
        }

        val chunk = if (start == 0) rawChunk else rawChunk.substring(start)
        if (chunk.isEmpty()) return@collect

        // --- Budget enforcement -------------------------------------------
        val remaining = maxChars - sb.length
        if (remaining <= 0) {
            dropDanglingHighSurrogateAtEnd(sb)
            throw OutputLimitReached()
        }

        val clipped = clipToBudgetPreservingSurrogates(chunk, remaining)
        if (clipped.isNotEmpty()) {
            sb.append(clipped)

            onChunk?.let { callback ->
                try {
                    callback.invoke(clipped, sb.length)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Swallow callback failures to avoid losing collected output.
                }
            }
        }

        if (sb.length >= maxChars) {
            // Avoid leaving a dangling high surrogate due to clipping.
            dropDanglingHighSurrogateAtEnd(sb)
            throw OutputLimitReached()
        }
    }
}

/**
 * Clips [chunk] to at most [budget] UTF-16 code units, avoiding surrogate pair splits.
 *
 * Notes:
 * - Kotlin String indexing uses UTF-16 code units.
 * - Cutting between a high surrogate and a low surrogate produces an invalid string.
 * - This function steps back by 1 code unit when it detects such a split.
 */
private fun clipToBudgetPreservingSurrogates(chunk: String, budget: Int): String {
    if (budget <= 0) return ""
    if (chunk.length <= budget) return chunk

    var end = budget.coerceAtMost(chunk.length)
    if (end <= 0) return ""

    // If we would split a surrogate pair inside THIS chunk, step back by 1.
    if (end < chunk.length) {
        val last = chunk[end - 1]
        val next = chunk[end]
        if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
            end -= 1
        }
    }

    if (end <= 0) return ""
    return chunk.substring(0, end)
}

/**
 * Ensures we never end with a dangling high surrogate.
 *
 * This is important when we stop early due to MAX_CHARS/TIMEOUT/ERROR.
 */
private fun dropDanglingHighSurrogateAtEnd(sb: StringBuilder) {
    if (sb.isEmpty()) return
    val last = sb[sb.length - 1]
    if (Character.isHighSurrogate(last)) {
        sb.setLength(sb.length - 1)
    }
}

/**
 * Sanitizes an error into a short, non-PII token.
 *
 * Avoid using exception messages because they may contain file paths or user content.
 */
private fun sanitizeErrorToken(t: Throwable): String {
    val simple = t.javaClass.simpleName
    if (simple.isNotBlank()) return simple.take(32)

    // Fallback for anonymous/local classes: keep only the last segment, still non-PII.
    val fqcn = t.javaClass.name
    val last = fqcn.substringAfterLast('.').substringAfterLast('$')
    return last.ifBlank { "error" }.take(32)
}

/**
 * Internal signal used to stop collection when the output size cap is reached.
 *
 * This is intentionally thrown in cap scenarios, so avoid stack trace cost.
 */
private class OutputLimitReached : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}