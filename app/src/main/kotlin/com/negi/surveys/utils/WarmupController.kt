package com.negi.surveys.utils

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * SDK-agnostic system prompt definition used by warmup/compile runs.
 *
 * This exists to prevent SDK types (e.g., LiteRT-LM Message) from leaking into public APIs.
 */
sealed interface SystemPrompt {
    /** Plain text system prompt. */
    data class Text(val text: String) : SystemPrompt

    /**
     * Future-proof payload.
     *
     * Use this if you need to carry structured data without coupling to a specific SDK type.
     * Implementations MUST treat unknown payload keys safely.
     */
    data class Structured(
        val type: String,
        val payload: Map<String, Any?> = emptyMap(),
    ) : SystemPrompt
}

sealed interface PrefetchState {
    val elapsedMs: Long

    /** No prefetch has been requested or prefetch state has been reset. */
    data object Idle : PrefetchState {
        override val elapsedMs: Long = 0L
    }

    /** Prefetch is in progress (e.g., copying/downloading model assets). */
    data class Running(
        val file: File,
        val startedAtMs: Long,
        val downloaded: Long,
        val total: Long?,
        override val elapsedMs: Long,
    ) : PrefetchState

    /** Prefetch completed successfully and the asset is ready. */
    data class Prefetched(
        val file: File,
        val sizeBytes: Long,
        val startedAtMs: Long,
        override val elapsedMs: Long,
    ) : PrefetchState

    /** Prefetch failed; message is suitable for logs and basic UI. */
    data class Failed(
        val message: String,
        val startedAtMs: Long?,
        override val elapsedMs: Long,
    ) : PrefetchState

    /** Prefetch was cancelled (explicitly or via lifecycle shutdown). */
    data class Cancelled(
        val startedAtMs: Long?,
        override val elapsedMs: Long,
    ) : PrefetchState

    /** Prefetch was skipped because the app is not configured for warmup. */
    data class SkippedNotConfigured(
        val reason: String,
        override val elapsedMs: Long = 0L,
    ) : PrefetchState
}

sealed interface CompileState {
    val elapsedMs: Long

    /** No compile has been requested or compile state has been reset. */
    data object Idle : CompileState {
        override val elapsedMs: Long = 0L
    }

    /** Compile is waiting for prefetch to complete. */
    data class WaitingForPrefetch(
        val file: File,
        val requestedAtMs: Long,
        override val elapsedMs: Long,
    ) : CompileState

    /** Compile is currently executing. */
    data class Compiling(
        val file: File,
        val startedAtMs: Long,
        override val elapsedMs: Long,
    ) : CompileState

    /** Compile completed successfully. */
    data class Compiled(
        val file: File,
        val startedAtMs: Long,
        override val elapsedMs: Long,
    ) : CompileState

    /** Compile failed; message is suitable for logs and basic UI. */
    data class Failed(
        val message: String,
        val startedAtMs: Long?,
        override val elapsedMs: Long,
    ) : CompileState

    /** Compile was cancelled (explicitly or via lifecycle shutdown). */
    data class Cancelled(
        val startedAtMs: Long?,
        override val elapsedMs: Long,
    ) : CompileState

    /** Compile was skipped because the app is not configured for warmup. */
    data class SkippedNotConfigured(
        val reason: String,
        override val elapsedMs: Long = 0L,
    ) : CompileState
}

/**
 * Process-scoped warmup coordinator interface (SDK-agnostic).
 *
 * Terminal state definition:
 * - Prefetch terminal states: [PrefetchState.Prefetched], [PrefetchState.Failed],
 *   [PrefetchState.Cancelled], [PrefetchState.SkippedNotConfigured].
 * - Compile terminal states: [CompileState.Compiled], [CompileState.Failed],
 *   [CompileState.Cancelled], [CompileState.SkippedNotConfigured].
 */
interface WarmupController {

    /** Prefetch warmup state flow (public/UI model). */
    val prefetchState: StateFlow<PrefetchState>

    /** Compile warmup state flow (public/UI model). */
    val compileState: StateFlow<CompileState>

    /**
     * Updates conversation options used for warmup/compile runs.
     *
     * Tools contract:
     * - [tools] is intentionally loosely typed for API stability.
     * - Implementations MUST ignore unknown tool types safely (no crash).
     */
    fun setWarmupConversationOptions(
        supportImage: Boolean = false,
        supportAudio: Boolean = false,
        systemPrompt: SystemPrompt? = null,
        tools: List<Any> = emptyList(),
    )

    fun prefetchOnce()
    fun compileOnce()
    fun warmupOnce()
    fun requestCompileAfterPrefetch(reason: String = "autoAfterPrefetch")
    fun cancelAll(reason: String = "cancelAll")
    fun resetForRetry(reason: String = "resetForRetry")

    /**
     * Ensures compile warmup has reached a terminal state.
     *
     * Behavior:
     * - Triggers compile if configured.
     * - Waits until the compile state becomes terminal or timeout occurs.
     * - On timeout, returns the current state snapshot without throwing.
     */
    suspend fun ensureCompiled(
        timeoutMs: Long? = DEFAULT_ENSURE_TIMEOUT_MS,
        reason: String = "ensureCompiled",
    ): CompileState

    /** Suspends until prefetch reaches a terminal state (or returns current state on timeout). */
    suspend fun awaitPrefetchTerminal(timeoutMs: Long? = null): PrefetchState

    /** Suspends until compile reaches a terminal state (or returns current state on timeout). */
    suspend fun awaitCompileTerminal(timeoutMs: Long? = null): CompileState

    companion object {
        const val DEFAULT_ENSURE_TIMEOUT_MS: Long = 120_000L
    }
}