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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Warmup controller used by UI / process services.
 *
 * Design goals:
 * - UI depends only on this controller.
 * - Engine can be swapped (real vs fake) without changing UI code.
 * - Privacy-safe logs: never log tokens/urls/file names/raw content.
 * - Keep compile readiness separate from runtime-hot readiness.
 *
 * NOTE:
 * - This controller MUST NOT throw from the constructor.
 * - Environment failures should be represented as SkippedNotReady/Failed states by the engine.
 *
 * Key change:
 * - Runtime warmup is now represented explicitly via [RuntimeWarmState].
 * - Backward compatibility is preserved by making runtime support optional through
 *   [RuntimeAwareEngine].
 */
class WarmupController(
    context: Context,
    private val engine: Engine,
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) },
) {

    private val appContext: Context = context.applicationContext
    private val instanceId: Long = NEXT_INSTANCE_ID.incrementAndGet()

    /**
     * Optional runtime-aware engine view.
     *
     * Why:
     * - Existing engine implementations may still implement only [Engine].
     * - This keeps the controller source-compatible while allowing gradual rollout.
     */
    private val runtimeAwareEngine: RuntimeAwareEngine? = engine as? RuntimeAwareEngine

    /**
     * Fallback state used when runtime warmup is not implemented by the engine.
     *
     * Notes:
     * - This is intentionally terminal and non-throwing.
     * - UI can observe that runtime warmup is unsupported without breaking compile readiness.
     */
    private val fallbackRuntimeWarmState =
        MutableStateFlow<RuntimeWarmState>(
            RuntimeWarmState.SkippedNotReady(reason = "RuntimeWarmNotSupported"),
        )

    /**
     * Warmup input bundle provided by the app (resolved & explicit).
     *
     * Privacy note:
     * - Do not log file names/paths derived from [file].
     */
    data class Inputs(
        val file: File?,
        val repository: WarmupCapableRepository,
        val options: Options = Options(),
    )

    /**
     * Stable contract for repositories that can do a deterministic compile warmup.
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

    /**
     * Optional repository contract for runtime prewarm.
     *
     * Meaning:
     * - "compile warmup" answers: can the engine avoid first-use compile stalls?
     * - "runtime warmup" answers: is the live inference runtime already hot?
     *
     * IMPORTANT:
     * - Implementations must not log file paths or raw exception messages.
     * - Returning false should mean "runtime did not become ready".
     */
    interface RuntimeWarmCapableRepository : WarmupCapableRepository {
        suspend fun prepareRuntime(
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
         * Indicates prefetch was skipped because [Inputs] were not provided yet or invalid.
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
         * Indicates compile was skipped because [Inputs] were not provided yet or invalid.
         *
         * IMPORTANT:
         * - This is a "not-ready" condition, not necessarily a permanent error.
         */
        data class SkippedNotReady(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : CompileState
    }

    /**
     * Public/UI runtime-hot state model.
     *
     * Meaning:
     * - Compile success does not necessarily mean the live runtime is hot.
     * - This state tracks whether the app already paid the cost of first real inference setup.
     */
    sealed interface RuntimeWarmState {
        val elapsedMs: Long

        data object Idle : RuntimeWarmState {
            override val elapsedMs: Long = 0L
        }

        data class WaitingForCompile(
            val file: File,
            val requestedAtMs: Long,
            override val elapsedMs: Long,
        ) : RuntimeWarmState

        data class Warming(
            val file: File,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : RuntimeWarmState

        data class Ready(
            val file: File,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : RuntimeWarmState

        data class Failed(
            val message: String,
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : RuntimeWarmState

        data class Cancelled(
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : RuntimeWarmState

        /**
         * Indicates runtime warmup was skipped because the engine/repository/input set
         * is not ready for runtime prewarm yet.
         *
         * IMPORTANT:
         * - This is intentionally distinct from [CompileState.SkippedNotReady].
         */
        data class SkippedNotReady(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : RuntimeWarmState
    }

    data class Options(
        val supportImage: Boolean = false,
        val supportAudio: Boolean = false,
        val systemMessage: Any? = null,
        val tools: List<Any> = emptyList(),
    )

    /**
     * Base engine contract used by UI and process services.
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
         * Requests compile to happen after prefetch completes.
         *
         * IMPORTANT:
         * - Must be robust to early calls where [Inputs] are not ready yet.
         * - Engine should treat "not ready" as retryable.
         */
        fun requestCompileAfterPrefetch(appContext: Context, reason: String)

        fun cancelAll(reason: String)
        fun resetForRetry(reason: String)
    }

    /**
     * Optional engine extension for runtime-hot preparation.
     *
     * Why optional:
     * - Existing engines can continue implementing only [Engine].
     * - Real engines can opt into runtime prewarm gradually.
     */
    interface RuntimeAwareEngine : Engine {
        val runtimeWarmState: StateFlow<RuntimeWarmState>

        /**
         * Starts runtime prewarm immediately.
         *
         * IMPORTANT:
         * - Implementations must not throw.
         * - When compile is not yet satisfied, implementations should switch to a
         *   waiting/not-ready state instead of hard failing.
         */
        fun startRuntimeWarm(appContext: Context)

        /**
         * Requests runtime warmup to happen after compile is satisfied.
         *
         * IMPORTANT:
         * - Implementations should behave like a sticky request.
         * - Calling this repeatedly must be safe.
         */
        fun requestRuntimeWarmAfterCompile(appContext: Context, reason: String)
    }

    /** Prefetch warmup state flow (public/UI model). */
    val prefetchState: StateFlow<PrefetchState> = engine.prefetchState

    /** Compile warmup state flow (public/UI model). */
    val compileState: StateFlow<CompileState> = engine.compileState

    /**
     * Runtime-hot state flow (public/UI model).
     *
     * Notes:
     * - When the engine is not runtime-aware, this stays at [RuntimeWarmState.SkippedNotReady].
     * - This keeps the controller non-throwing and source-compatible.
     */
    val runtimeWarmState: StateFlow<RuntimeWarmState> =
        runtimeAwareEngine?.runtimeWarmState ?: fallbackRuntimeWarmState

    init {
        val createdCount = CREATED_COUNT.incrementAndGet()
        log("init: instanceId=$instanceId createdCount=$createdCount pid=${Process.myPid()}")
        log(
            "capabilities: instanceId=$instanceId pid=${Process.myPid()} " +
                    "runtimeWarmSupported=${runtimeAwareEngine != null}",
        )
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

    /**
     * Starts the full first-use preparation pipeline.
     *
     * Pipeline:
     * - prefetch
     * - compile after prefetch
     * - runtime warm after compile (when supported)
     */
    fun warmupOnce() {
        log("warmupOnce: request instanceId=$instanceId pid=${Process.myPid()}")
        engine.startPrefetch(appContext)
        engine.requestCompileAfterPrefetch(appContext, reason = "warmupOnce")
        requestRuntimeWarmAfterCompile(reason = "warmupOnce")
    }

    fun requestCompileAfterPrefetch(reason: String = "autoAfterPrefetch") {
        log(
            "requestCompileAfterPrefetch: request instanceId=$instanceId pid=${Process.myPid()} " +
                    "reason='${safeReasonForLogs(reason)}'",
        )
        engine.requestCompileAfterPrefetch(appContext, reason = reason)
    }

    /**
     * Requests runtime warmup after compile is satisfied.
     *
     * Behavior:
     * - No-op and non-throwing when runtime warmup is not supported by the engine.
     */
    fun requestRuntimeWarmAfterCompile(reason: String = "autoAfterCompile") {
        val safeReason = safeReasonForLogs(reason)
        log(
            "requestRuntimeWarmAfterCompile: request instanceId=$instanceId pid=${Process.myPid()} " +
                    "reason='$safeReason' supported=${runtimeAwareEngine != null}",
        )

        val runtimeEngine = runtimeAwareEngine
        if (runtimeEngine == null) {
            fallbackRuntimeWarmState.value =
                RuntimeWarmState.SkippedNotReady(reason = "RuntimeWarmNotSupported")
            return
        }

        runtimeEngine.requestRuntimeWarmAfterCompile(appContext, reason = reason)
    }

    /**
     * Starts runtime warmup immediately.
     *
     * Behavior:
     * - No-op and non-throwing when runtime warmup is not supported by the engine.
     */
    fun runtimeWarmOnce() {
        log("runtimeWarmOnce: request instanceId=$instanceId pid=${Process.myPid()}")
        val runtimeEngine = runtimeAwareEngine
        if (runtimeEngine == null) {
            fallbackRuntimeWarmState.value =
                RuntimeWarmState.SkippedNotReady(reason = "RuntimeWarmNotSupported")
            return
        }
        runtimeEngine.startRuntimeWarm(appContext)
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

        if (runtimeAwareEngine == null) {
            fallbackRuntimeWarmState.value =
                RuntimeWarmState.SkippedNotReady(reason = "RuntimeWarmNotSupported")
        }
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

    /**
     * Ensures the runtime-hot state is reached when supported.
     *
     * Notes:
     * - This does not throw when runtime warmup is unsupported.
     * - Callers should treat [RuntimeWarmState.Ready] as the only strong success signal.
     */
    suspend fun ensureRuntimeReady(
        timeoutMs: Long? = DEFAULT_RUNTIME_ENSURE_TIMEOUT_MS,
        reason: String = "ensureRuntimeReady",
    ): RuntimeWarmState {
        log(
            "ensureRuntimeReady: begin instanceId=$instanceId pid=${Process.myPid()} " +
                    "timeoutMs=${timeoutMs ?: -1} reason='${safeReasonForLogs(reason)}' " +
                    "supported=${runtimeAwareEngine != null}",
        )

        val runtimeEngine = runtimeAwareEngine
        if (runtimeEngine == null) {
            val current = RuntimeWarmState.SkippedNotReady(reason = "RuntimeWarmNotSupported")
            fallbackRuntimeWarmState.value = current
            log(
                "ensureRuntimeReady: end instanceId=$instanceId pid=${Process.myPid()} " +
                        "state=${current.javaClass.simpleName} elapsedMs=${current.elapsedMs}",
            )
            return current
        }

        runtimeEngine.requestRuntimeWarmAfterCompile(appContext, reason = reason)
        val result = awaitRuntimeTerminal(timeoutMs = timeoutMs)

        log(
            "ensureRuntimeReady: end instanceId=$instanceId pid=${Process.myPid()} " +
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
            log(
                "awaitPrefetchTerminal: timeout returning current " +
                        "instanceId=$instanceId state=${cur.javaClass.simpleName}",
            )
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
            log(
                "awaitCompileTerminal: timeout returning current " +
                        "instanceId=$instanceId state=${cur.javaClass.simpleName}",
            )
            cur
        }
    }

    suspend fun awaitRuntimeTerminal(timeoutMs: Long? = null): RuntimeWarmState {
        val flow = runtimeWarmState
        val cur0 = flow.value
        if (cur0.isTerminal()) return cur0

        if (timeoutMs == null) return flow.first { it.isTerminal() }
        if (timeoutMs <= 0L) return flow.value

        return try {
            withTimeout(timeoutMs) { flow.first { it.isTerminal() } }
        } catch (_: TimeoutCancellationException) {
            val cur = flow.value
            log(
                "awaitRuntimeTerminal: timeout returning current " +
                        "instanceId=$instanceId state=${cur.javaClass.simpleName}",
            )
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

    private fun RuntimeWarmState.isTerminal(): Boolean {
        return when (this) {
            is RuntimeWarmState.Ready,
            is RuntimeWarmState.Failed,
            is RuntimeWarmState.Cancelled,
            is RuntimeWarmState.SkippedNotReady -> true
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
        const val DEFAULT_RUNTIME_ENSURE_TIMEOUT_MS: Long = 120_000L

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