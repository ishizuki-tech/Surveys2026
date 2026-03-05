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
 * Utility for collecting streaming Flow<String> safely.
 */
object FlowTextCollector {

    /**
     * Reason why flow collection stopped.
     */
    enum class StopReason {
        COMPLETED,
        TIMEOUT,
        MAX_CHARS,
        ERROR
    }

    /**
     * Result of collecting text.
     */
    data class Result(
        val text: String,
        val reason: StopReason,
        val errorToken: String? = null
    )

    /**
     * Collect Flow<String> into String.
     */
    suspend fun Flow<String>.collectToText(
        timeoutMs: Long = 30_000L,
        maxChars: Int = 32_000,
        onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
    ): String {
        return collectToTextResult(timeoutMs, maxChars, onChunk).text
    }

    /**
     * Collect Flow<String> into Result.
     */
    suspend fun Flow<String>.collectToTextResult(
        timeoutMs: Long = 30_000L,
        maxChars: Int = 32_000,
        onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
    ): Result {

        val limit = maxChars.coerceAtLeast(0)

        if (limit == 0) {
            return Result("", StopReason.MAX_CHARS)
        }

        val sb = StringBuilder(minOf(limit, 4096))

        return try {

            if (timeoutMs > 0) {
                withTimeout(timeoutMs) {
                    collectInto(sb, limit, onChunk)
                }
            } else {
                collectInto(sb, limit, onChunk)
            }

            repairTail(sb)

            Result(
                text = sb.toString(),
                reason = StopReason.COMPLETED
            )

        } catch (_: OutputLimitReached) {

            repairTail(sb)

            Result(
                text = sb.toString(),
                reason = StopReason.MAX_CHARS
            )

        } catch (_: TimeoutCancellationException) {

            repairTail(sb)

            Result(
                text = sb.toString(),
                reason = StopReason.TIMEOUT
            )

        } catch (ce: CancellationException) {

            throw ce

        } catch (t: Throwable) {

            repairTail(sb)

            Result(
                text = sb.toString(),
                reason = StopReason.ERROR,
                errorToken = sanitizeErrorToken(t)
            )
        }
    }

    /**
     * Budget-based timeout variant.
     */
    suspend fun Flow<String>.collectToTextResultWithBudget(
        timeoutBudgetMs: Long,
        maxChars: Int = 32_000,
        onChunk: ((chunk: String, totalChars: Int) -> Unit)? = null
    ): Result {

        if (timeoutBudgetMs <= 0) {
            return Result("", StopReason.TIMEOUT)
        }

        return collectToTextResult(
            timeoutMs = timeoutBudgetMs,
            maxChars = maxChars,
            onChunk = onChunk
        )
    }

    private suspend fun Flow<String>.collectInto(
        sb: StringBuilder,
        maxChars: Int,
        onChunk: ((chunk: String, totalChars: Int) -> Unit)?
    ) {

        collect { rawChunk ->

            if (rawChunk.isEmpty()) return@collect

            if (sb.length > 0) {
                val last = sb[sb.length - 1]

                if (Character.isHighSurrogate(last)) {
                    val head = rawChunk.firstOrNull()

                    if (head == null || !Character.isLowSurrogate(head)) {
                        sb.setLength(sb.length - 1)
                    }
                }
            }

            var start = 0

            if (Character.isLowSurrogate(rawChunk[0])) {
                val prevHigh =
                    sb.length > 0 && Character.isHighSurrogate(sb[sb.length - 1])

                if (!prevHigh) start = 1
            }

            if (start >= rawChunk.length) return@collect

            val remaining = maxChars - sb.length

            if (remaining <= 0) {
                repairTail(sb)
                throw OutputLimitReached()
            }

            val clipped = clipToBudgetPreservingSurrogates(rawChunk, start, remaining)

            if (clipped > 0) {

                val end = start + clipped

                sb.append(rawChunk, start, end)

                onChunk?.let { callback ->
                    try {
                        callback.invoke(rawChunk.substring(start, end), sb.length)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
            }

            if (sb.length >= maxChars) {
                repairTail(sb)
                throw OutputLimitReached()
            }
        }
    }

    private fun clipToBudgetPreservingSurrogates(
        chunk: String,
        start: Int,
        budget: Int
    ): Int {

        val remainingLength = chunk.length - start

        if (remainingLength <= 0) return 0
        if (remainingLength <= budget) return remainingLength

        var end = start + budget

        if (end < chunk.length) {

            val last = chunk[end - 1]
            val next = chunk[end]

            if (Character.isHighSurrogate(last) &&
                Character.isLowSurrogate(next)
            ) {
                end -= 1
            }
        }

        return end - start
    }

    private fun repairTail(sb: StringBuilder) {

        if (sb.length == 0) return

        val last = sb[sb.length - 1]

        if (Character.isHighSurrogate(last)) {
            sb.setLength(sb.length - 1)
        }
    }

    private fun sanitizeErrorToken(t: Throwable): String {

        val simple = t.javaClass.simpleName

        if (simple.isNotBlank()) {
            return simple.take(32)
        }

        val fqcn = t.javaClass.name

        val last =
            fqcn.substringAfterLast('.')
                .substringAfterLast('$')

        return last.ifBlank { "error" }.take(32)
    }

    private class OutputLimitReached : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}