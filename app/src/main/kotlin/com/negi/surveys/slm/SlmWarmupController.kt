/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (SLM Warmup Facade)
 *  ---------------------------------------------------------------------
 *  File: SlmWarmupController.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.content.Context
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.logging.AppLog
import com.negi.surveys.utils.CompileState
import com.negi.surveys.utils.PrefetchState
import com.negi.surveys.utils.WarmupController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Default process-scoped implementation backed by [SlmWarmup].
 *
 * Implementation notes:
 * - Mirrors internal state flows to public state flows.
 * - Uses a process-long [CoroutineScope] (SupervisorJob + Default) to avoid accidental cancellation.
 * - Keeps forward compatibility by handling unknown internal states via `else` mapping.
 */
class SlmWarmupController(
    context: Context,
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) },
) : WarmupController {

    private val appContext: Context = context.applicationContext
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val instanceId: Long = NEXT_INSTANCE_ID.incrementAndGet()

    private val _prefetchState = MutableStateFlow(SlmWarmup.prefetchState.value.toPublicSafe())
    private val _compileState = MutableStateFlow(SlmWarmup.compileState.value.toPublicSafe())

    override val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()
    override val compileState: StateFlow<CompileState> = _compileState.asStateFlow()

    private val startedMirroring = AtomicBoolean(false)

    init {
        val createdCount = CREATED_COUNT.incrementAndGet()
        logger("init: instanceId=$instanceId createdCount=$createdCount pid=${Process.myPid()}")
        startMirroringOnce()
    }

    override fun setWarmupConversationOptions(
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message?,
        tools: List<Any>,
    ) {
        SlmWarmup.setWarmupConversationOptions(
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
        logger(
            "setWarmupConversationOptions: instanceId=$instanceId image=$supportImage audio=$supportAudio " +
                    "system=${systemMessage != null} tools=${tools.size}"
        )
    }

    override fun prefetchOnce() {
        logger("prefetchOnce: request instanceId=$instanceId")
        SlmWarmup.startPrefetchIfConfigured(appContext)
    }

    override fun compileOnce() {
        logger("compileOnce: request instanceId=$instanceId")
        SlmWarmup.startCompileIfConfigured(appContext)
    }

    override fun warmupOnce() {
        logger("warmupOnce: request instanceId=$instanceId")
        SlmWarmup.startPrefetchIfConfigured(appContext)
        SlmWarmup.requestCompileAfterPrefetch(appContext, reason = "warmupOnce")
    }

    override fun requestCompileAfterPrefetch(reason: String) {
        logger("requestCompileAfterPrefetch: request instanceId=$instanceId reason='$reason'")
        SlmWarmup.requestCompileAfterPrefetch(appContext, reason = reason)
    }

    override fun cancelAll(reason: String) {
        logger("cancelAll: instanceId=$instanceId reason='$reason'")
        SlmWarmup.cancelAll(reason = reason)
    }

    override fun resetForRetry(reason: String) {
        logger("resetForRetry: instanceId=$instanceId reason='$reason'")
        SlmWarmup.resetForRetry(reason = reason)
    }

    override suspend fun ensureCompiled(timeoutMs: Long?, reason: String): CompileState {
        logger("ensureCompiled: begin instanceId=$instanceId reason='$reason' timeoutMs=${timeoutMs ?: -1}")
        SlmWarmup.startCompileIfConfigured(appContext)

        val result = awaitCompileTerminal(timeoutMs = timeoutMs)
        logger(
            "ensureCompiled: end instanceId=$instanceId reason='$reason' state=${result.javaClass.simpleName} " +
                    "elapsedMs=${result.elapsedMs}"
        )
        return result
    }

    override suspend fun awaitPrefetchTerminal(timeoutMs: Long?): PrefetchState {
        return awaitTerminal(
            timeoutMs = timeoutMs,
            current = { prefetchState.value },
            wait = { prefetchState.first { it.isTerminal() } },
        )
    }

    override suspend fun awaitCompileTerminal(timeoutMs: Long?): CompileState {
        return awaitTerminal(
            timeoutMs = timeoutMs,
            current = { compileState.value },
            wait = { compileState.first { it.isTerminal() } },
        )
    }

    private fun startMirroringOnce() {
        if (!startedMirroring.compareAndSet(false, true)) {
            logger("mirror: already started instanceId=$instanceId")
            return
        }

        logger("mirror: start instanceId=$instanceId")

        scope.launch {
            try {
                SlmWarmup.prefetchState.collect { internal ->
                    _prefetchState.value = internal.toPublicSafe()
                }
            } catch (t: Throwable) {
                logger(
                    "prefetchState mirror crashed: instanceId=$instanceId " +
                            "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }

        scope.launch {
            try {
                SlmWarmup.compileState.collect { internal ->
                    _compileState.value = internal.toPublicSafe()
                }
            } catch (t: Throwable) {
                logger(
                    "compileState mirror crashed: instanceId=$instanceId " +
                            "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    private suspend fun <T> awaitTerminal(
        timeoutMs: Long?,
        current: () -> T,
        wait: suspend () -> T,
    ): T {
        if (timeoutMs == null) return wait()
        return try {
            withTimeout(timeoutMs) { wait() }
        } catch (_: TimeoutCancellationException) {
            logger("awaitTerminal: timeoutMs=$timeoutMs returning current state instanceId=$instanceId")
            current()
        }
    }

    /**
     * Returns true if the state is terminal (no further progress without a new request/reset).
     */
    private fun PrefetchState.isTerminal(): Boolean {
        return when (this) {
            is PrefetchState.Prefetched,
            is PrefetchState.Failed,
            is PrefetchState.Cancelled,
            is PrefetchState.SkippedNotConfigured -> true
            else -> false
        }
    }

    /**
     * Returns true if the state is terminal (no further progress without a new request/reset).
     */
    private fun CompileState.isTerminal(): Boolean {
        return when (this) {
            is CompileState.Compiled,
            is CompileState.Failed,
            is CompileState.Cancelled,
            is CompileState.SkippedNotConfigured -> true
            else -> false
        }
    }

    /**
     * Converts internal prefetch state into a public/UI-safe model.
     *
     * The `else` branch is intentional:
     * - Protects against future internal state additions.
     * - Avoids relying on Kotlin exhaustiveness quirks across build variants.
     */
    private fun SlmWarmup.PrefetchState.toPublicSafe(): PrefetchState {
        return when (this) {
            is SlmWarmup.PrefetchState.Idle -> PrefetchState.Idle
            is SlmWarmup.PrefetchState.Running -> PrefetchState.Running(
                file = file,
                startedAtMs = startedAtMs,
                downloaded = downloaded,
                total = total,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.PrefetchState.Prefetched -> PrefetchState.Prefetched(
                file = file,
                sizeBytes = sizeBytes,
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.PrefetchState.Failed -> PrefetchState.Failed(
                message = message,
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.PrefetchState.Cancelled -> PrefetchState.Cancelled(
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.PrefetchState.SkippedNotConfigured -> PrefetchState.SkippedNotConfigured(
                reason = reason,
                elapsedMs = elapsedMs,
            )
        }
    }

    /**
     * Converts internal compile state into a public/UI-safe model.
     *
     * The `else` branch is intentional:
     * - Protects against future internal state additions.
     * - Avoids relying on Kotlin exhaustiveness quirks across build variants.
     */
    private fun SlmWarmup.CompileState.toPublicSafe(): CompileState {
        return when (this) {
            is SlmWarmup.CompileState.Idle -> CompileState.Idle
            is SlmWarmup.CompileState.WaitingForPrefetch -> CompileState.WaitingForPrefetch(
                file = file,
                requestedAtMs = requestedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.CompileState.Compiling -> CompileState.Compiling(
                file = file,
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.CompileState.Compiled -> CompileState.Compiled(
                file = file,
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.CompileState.Failed -> CompileState.Failed(
                message = message,
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.CompileState.Cancelled -> CompileState.Cancelled(
                startedAtMs = startedAtMs,
                elapsedMs = elapsedMs,
            )
            is SlmWarmup.CompileState.SkippedNotConfigured -> CompileState.SkippedNotConfigured(
                reason = reason,
                elapsedMs = elapsedMs,
            )
        }
    }

    private companion object {
        private const val TAG = "SlmWarmupController"

        private val NEXT_INSTANCE_ID = AtomicLong(0L)
        private val CREATED_COUNT = AtomicLong(0L)
    }
}

/**
 * CompositionLocal entry point for accessing [WarmupController] from UI code.
 */
val LocalSlmWarmupController = staticCompositionLocalOf<WarmupController> {
    error("LocalSlmWarmupController is not provided. Wrap your app root with ProvideSlmWarmupController().")
}

/**
 * Provides a [WarmupController] instance to the composition.
 *
 * Usage:
 * - Screens read the controller via LocalSlmWarmupController.current.
 * - Screens must not import or reference SlmWarmup directly.
 */
@Composable
fun ProvideSlmWarmupController(
    controller: WarmupController? = null,
    content: @Composable () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val instance = remember(controller, appContext) {
        controller ?: SlmWarmupController(appContext)
    }

    CompositionLocalProvider(
        LocalSlmWarmupController provides instance,
        content = content,
    )
}