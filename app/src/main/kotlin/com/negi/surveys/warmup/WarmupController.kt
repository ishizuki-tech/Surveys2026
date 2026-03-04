/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Warmup Controller)
 *  ---------------------------------------------------------------------
 *  File: WarmupController.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.warmup

import android.content.Context
import android.os.Process
import com.negi.surveys.logging.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Warmup controller used by UI / process services.
 *
 * Design goals:
 * - UI depends only on this controller (engine is injected).
 * - Engine can be swapped (real vs fake) without changing UI code.
 * - Privacy-safe logs: never log tokens/urls/file names/raw content.
 *
 * NOTE:
 * - This controller MUST NOT throw from the constructor.
 * - Environment failures should be represented as SkippedNotReady/Failed states by the engine.
 *
 * Key change:
 * - Removed Dependencies. Callers must provide explicit [Inputs] via [updateInputs].
 */
class WarmupController(
    context: Context,
    private val engine: Engine,
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) },
) {

    private val appContext: Context = context.applicationContext
    private val instanceId: Long = NEXT_INSTANCE_ID.incrementAndGet()

    /**
     * Warmup input bundle provided by the app (resolved & explicit).
     *
     * Privacy note:
     * - Do not log file names/paths derived from [file].
     */
    data class Inputs(
        val file: File,
        val repository: WarmupCapableRepository,
        val options: Options = Options(),
    )

    /**
     * Stable contract for repositories that can do a deterministic warmup.
     *
     * IMPORTANT:
     * - Implementations must not log file paths or exception messages.
     * - Returning false should mean "warmup did not complete successfully".
     */
    interface WarmupCapableRepository {
        suspend fun warmup(
            appContext: Context,
            modelFile: File,
            options: Options,
        ): Boolean
    }

    /** Public/UI warmup state model for IO prefetch. */
    sealed interface PrefetchState {
        val elapsedMs: Long

        data object Idle : PrefetchState {
            override val elapsedMs: Long = 0L
        }

        data class Running(
            val file: File,
            val startedAtMs: Long,
            val downloaded: Long,
            val total: Long?,
            override val elapsedMs: Long,
        ) : PrefetchState

        data class Prefetched(
            val file: File,
            val sizeBytes: Long,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : PrefetchState

        data class Failed(
            val message: String,
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : PrefetchState

        data class Cancelled(
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : PrefetchState

        /**
         * Indicates warmup was skipped because [Inputs] were not provided yet or invalid.
         *
         * IMPORTANT:
         * - This is a "not-ready" condition, not necessarily a permanent error.
         */
        data class SkippedNotReady(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : PrefetchState
    }

    /** Public/UI warmup state model for compile/init. */
    sealed interface CompileState {
        val elapsedMs: Long

        data object Idle : CompileState {
            override val elapsedMs: Long = 0L
        }

        data class WaitingForPrefetch(
            val file: File,
            val requestedAtMs: Long,
            override val elapsedMs: Long,
        ) : CompileState

        data class Compiling(
            val file: File,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : CompileState

        data class Compiled(
            val file: File,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : CompileState

        data class Failed(
            val message: String,
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : CompileState

        data class Cancelled(
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : CompileState

        /**
         * Indicates warmup was skipped because [Inputs] were not provided yet or invalid.
         *
         * IMPORTANT:
         * - This is a "not-ready" condition, not necessarily a permanent error.
         */
        data class SkippedNotReady(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : CompileState
    }

    data class Options(
        val supportImage: Boolean = false,
        val supportAudio: Boolean = false,
        val systemMessage: Any? = null,
        val tools: List<Any> = emptyList(),
    )

    /**
     * Engine contract (public) so implementations can live in separate files.
     *
     * IMPORTANT:
     * - Engine implementation MUST be safe to call multiple times (idempotent).
     * - Engine must treat missing inputs as a non-throwing, retryable state.
     *
     * Inputs flow:
     * - App calls [updateInputs] (controller forwards to engine).
     * - UI calls warmup methods; engine uses latest inputs.
     */
    interface Engine {
        val prefetchState: StateFlow<PrefetchState>
        val compileState: StateFlow<CompileState>

        /**
         * Updates the engine's inputs.
         *
         * IMPORTANT:
         * - Must NOT throw.
         * - Engine should store inputs atomically.
         */
        fun updateInputs(inputs: Inputs?)

        fun startPrefetch(appContext: Context)
        fun startCompile(appContext: Context)

        /**
         * Request compile to happen after prefetch completes.
         *
         * IMPORTANT:
         * - Must be robust to early calls where [Inputs] are not ready yet.
         * - Engine should treat "not ready" as retryable.
         */
        fun requestCompileAfterPrefetch(appContext: Context, reason: String)

        fun cancelAll(reason: String)
        fun resetForRetry(reason: String)
    }

    /** Prefetch warmup state flow (public/UI model). */
    val prefetchState: StateFlow<PrefetchState> = engine.prefetchState

    /** Compile warmup state flow (public/UI model). */
    val compileState: StateFlow<CompileState> = engine.compileState

    init {
        val createdCount = CREATED_COUNT.incrementAndGet()
        log("init: instanceId=$instanceId createdCount=$createdCount pid=${Process.myPid()}")
    }

    /**
     * Updates warmup inputs (resolved & explicit).
     *
     * Privacy:
     * - Do NOT log file name/path.
     */
    fun updateInputs(inputs: Inputs?) {
        log("updateInputs: instanceId=$instanceId pid=${Process.myPid()} hasInputs=${inputs != null}")
        engine.updateInputs(inputs)
    }

    fun prefetchOnce() {
        log("prefetchOnce: request instanceId=$instanceId pid=${Process.myPid()}")
        engine.startPrefetch(appContext)
    }

    fun compileOnce() {
        log("compileOnce: request instanceId=$instanceId pid=${Process.myPid()}")
        engine.startCompile(appContext)
    }

    fun warmupOnce() {
        log("warmupOnce: request instanceId=$instanceId pid=${Process.myPid()}")
        engine.startPrefetch(appContext)
        engine.requestCompileAfterPrefetch(appContext, reason = "warmupOnce")
    }

    fun requestCompileAfterPrefetch(reason: String = "autoAfterPrefetch") {
        log(
            "requestCompileAfterPrefetch: request instanceId=$instanceId pid=${Process.myPid()} " +
                    "reason='${safeReasonForLogs(reason)}'",
        )
        engine.requestCompileAfterPrefetch(appContext, reason = reason)
    }

    fun cancelAll(reason: String = "cancelAll") {
        log(
            "cancelAll: instanceId=$instanceId pid=${Process.myPid()} " +
                    "reason='${safeReasonForLogs(reason)}'",
        )
        engine.cancelAll(reason = reason)
    }

    fun resetForRetry(reason: String = "resetForRetry") {
        log(
            "resetForRetry: instanceId=$instanceId pid=${Process.myPid()} " +
                    "reason='${safeReasonForLogs(reason)}'",
        )
        engine.resetForRetry(reason = reason)
    }

    suspend fun ensureCompiled(
        timeoutMs: Long? = DEFAULT_ENSURE_TIMEOUT_MS,
        reason: String = "ensureCompiled",
    ): CompileState {
        log(
            "ensureCompiled: begin instanceId=$instanceId pid=${Process.myPid()} " +
                    "timeoutMs=${timeoutMs ?: -1} reason='${safeReasonForLogs(reason)}'",
        )
        engine.startCompile(appContext)

        val result = awaitCompileTerminal(timeoutMs = timeoutMs)

        log(
            "ensureCompiled: end instanceId=$instanceId pid=${Process.myPid()} " +
                    "state=${result.javaClass.simpleName} elapsedMs=${result.elapsedMs}",
        )
        return result
    }

    suspend fun awaitPrefetchTerminal(timeoutMs: Long? = null): PrefetchState {
        val cur0 = prefetchState.value
        if (cur0.isTerminal()) return cur0

        if (timeoutMs == null) return prefetchState.first { it.isTerminal() }
        if (timeoutMs <= 0L) return prefetchState.value

        return try {
            withTimeout(timeoutMs) { prefetchState.first { it.isTerminal() } }
        } catch (_: TimeoutCancellationException) {
            val cur = prefetchState.value
            log("awaitPrefetchTerminal: timeout returning current instanceId=$instanceId state=${cur.javaClass.simpleName}")
            cur
        }
    }

    suspend fun awaitCompileTerminal(timeoutMs: Long? = null): CompileState {
        val cur0 = compileState.value
        if (cur0.isTerminal()) return cur0

        if (timeoutMs == null) return compileState.first { it.isTerminal() }
        if (timeoutMs <= 0L) return compileState.value

        return try {
            withTimeout(timeoutMs) { compileState.first { it.isTerminal() } }
        } catch (_: TimeoutCancellationException) {
            val cur = compileState.value
            log("awaitCompileTerminal: timeout returning current instanceId=$instanceId state=${cur.javaClass.simpleName}")
            cur
        }
    }

    private fun PrefetchState.isTerminal(): Boolean {
        return when (this) {
            is PrefetchState.Prefetched,
            is PrefetchState.Failed,
            is PrefetchState.Cancelled,
            is PrefetchState.SkippedNotReady -> true
            else -> false
        }
    }

    private fun CompileState.isTerminal(): Boolean {
        return when (this) {
            is CompileState.Compiled,
            is CompileState.Failed,
            is CompileState.Cancelled,
            is CompileState.SkippedNotReady -> true
            else -> false
        }
    }

    private fun log(msg: String) {
        logger(msg)
    }

    /**
     * Sanitizes arbitrary strings before logging.
     *
     * Rationale:
     * - "reason" may be derived from UI/user inputs in the future.
     * - Keep logs stable and privacy-safe without losing signal.
     *
     * NOTE:
     * - Intentionally disallows path-like separators (/, \\) to reduce accidental leakage.
     */
    private fun safeReasonForLogs(raw: String): String {
        if (raw.isEmpty()) return ""
        val trimmed = raw.trim().take(64)
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            // Allow only a conservative set of characters for log messages.
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == ':' || ch == '.') {
                sb.append(ch)
            } else {
                sb.append('_')
            }
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_ENSURE_TIMEOUT_MS: Long = 120_000L

        private const val TAG: String = "WarmupController"
        private val NEXT_INSTANCE_ID = AtomicLong(0L)
        private val CREATED_COUNT = AtomicLong(0L)

        /**
         * Convenience factory for the real engine.
         *
         * NOTE:
         * - Callers must construct and provide the engine.
         * - This keeps WarmupController free from environment resolution dependencies.
         */
        fun createDefault(
            context: Context,
            engine: Engine,
            logger: (String) -> Unit = { AppLog.d(TAG, it) },
        ): WarmupController {
            return WarmupController(
                context = context,
                engine = engine,
                logger = logger,
            )
        }

        /**
         * Convenience factory for the fake engine.
         */
        fun createFake(
            context: Context,
            logger: (String) -> Unit = { AppLog.d(TAG, it) },
        ): WarmupController {
            return WarmupController(
                context = context,
                engine = FakeWarmupEngine(),
                logger = logger,
            )
        }
    }
}