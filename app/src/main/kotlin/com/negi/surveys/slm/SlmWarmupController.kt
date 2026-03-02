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
import android.os.SystemClock
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.logging.AppLog
import com.negi.surveys.utils.CompileState
import com.negi.surveys.utils.PrefetchState
import com.negi.surveys.utils.SystemPrompt
import com.negi.surveys.utils.WarmupController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Default process-scoped implementation backed by [SlmWarmup].
 *
 * Implementation notes:
 * - Mirrors internal state flows to public state flows.
 * - Uses a process-long [CoroutineScope] (SupervisorJob + Default) for global mirroring.
 * - Avoids duplicate collectors by mirroring once per process.
 *
 * SDK boundary:
 * - Public API accepts [SystemPrompt] (SDK-agnostic).
 * - This class attempts to convert it into LiteRT [Message] internally (best-effort).
 */
class SlmWarmupController(
    context: Context,
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) },
) : WarmupController {

    private val appContext: Context = context.applicationContext
    private val instanceId: Long = NEXT_INSTANCE_ID.incrementAndGet()

    override val prefetchState: StateFlow<PrefetchState> = GLOBAL_PREFETCH.asStateFlow()
    override val compileState: StateFlow<CompileState> = GLOBAL_COMPILE.asStateFlow()

    init {
        val createdCount = CREATED_COUNT.incrementAndGet()
        logger("init: instanceId=$instanceId createdCount=$createdCount pid=${Process.myPid()}")
        startGlobalMirroringOnce(logger)
    }

    override fun setWarmupConversationOptions(
        supportImage: Boolean,
        supportAudio: Boolean,
        systemPrompt: SystemPrompt?,
        tools: List<Any>,
    ) {
        val systemMessage: Message? = buildLiteRtSystemMessage(systemPrompt).also { msg ->
            if (systemPrompt != null && msg == null) {
                logger(
                    "setWarmupConversationOptions: instanceId=$instanceId " +
                            "systemPrompt provided but could not build LiteRT Message (passed null)",
                )
            }
        }

        SlmWarmup.setWarmupConversationOptions(
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )

        logger(
            "setWarmupConversationOptions: instanceId=$instanceId image=$supportImage audio=$supportAudio " +
                    "systemPrompt=${systemPrompt != null} tools=${tools.size}",
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
            "ensureCompiled: end instanceId=$instanceId reason='$reason' " +
                    "state=${result.javaClass.simpleName} elapsedMs=${result.elapsedMs}",
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

    private suspend fun <T> awaitTerminal(
        timeoutMs: Long?,
        current: () -> T,
        wait: suspend () -> T,
    ): T {
        if (timeoutMs == null) return wait()
        return try {
            withTimeout(timeoutMs) { wait() }
        } catch (_: TimeoutCancellationException) {
            val cur = current()
            logger(
                "awaitTerminal: timeoutMs=$timeoutMs returning current state " +
                        "instanceId=$instanceId state=${cur?.javaClass?.simpleName ?: "null"}",
            )
            cur
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

    private companion object {
        private const val TAG: String = "SlmWarmupController"

        private val NEXT_INSTANCE_ID = AtomicLong(0L)
        private val CREATED_COUNT = AtomicLong(0L)

        private val GLOBAL_SCOPE: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val GLOBAL_PREFETCH: MutableStateFlow<PrefetchState> =
            MutableStateFlow(SlmWarmup.prefetchState.value.toPublicSafe())

        private val GLOBAL_COMPILE: MutableStateFlow<CompileState> =
            MutableStateFlow(SlmWarmup.compileState.value.toPublicSafe())

        private val GLOBAL_MIRROR_STARTED = AtomicBoolean(false)

        private fun startGlobalMirroringOnce(logger: (String) -> Unit) {
            if (!GLOBAL_MIRROR_STARTED.compareAndSet(false, true)) {
                logger("global mirror: already started pid=${Process.myPid()}")
                return
            }

            logger("global mirror: start pid=${Process.myPid()}")

            GLOBAL_SCOPE.launch {
                while (isActiveSafe()) {
                    try {
                        SlmWarmup.prefetchState.collect { internal ->
                            GLOBAL_PREFETCH.value = internal.toPublicSafe()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        logger(
                            "global mirror: prefetch crashed " +
                                    "${t.javaClass.simpleName}: ${t.message} (restart)",
                        )
                        delay(300L)
                    }
                }
            }

            GLOBAL_SCOPE.launch {
                while (isActiveSafe()) {
                    try {
                        SlmWarmup.compileState.collect { internal ->
                            GLOBAL_COMPILE.value = internal.toPublicSafe()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        logger(
                            "global mirror: compile crashed " +
                                    "${t.javaClass.simpleName}: ${t.message} (restart)",
                        )
                        delay(300L)
                    }
                }
            }
        }

        private fun isActiveSafe(): Boolean {
            // Avoid importing coroutineContext here; SupervisorJob in GLOBAL_SCOPE will control cancellation.
            return true
        }

        /**
         * Converts internal prefetch state into a public/UI-safe model.
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

        /**
         * Best-effort conversion from SDK-agnostic [SystemPrompt] to LiteRT [Message].
         *
         * Safety:
         * - Uses reflection to tolerate LiteRT SDK API drift.
         * - Returns null if it cannot build a [Message] instance.
         */
        private fun buildLiteRtSystemMessage(systemPrompt: SystemPrompt?): Message? {
            if (systemPrompt == null) return null

            val text: String = when (systemPrompt) {
                is SystemPrompt.Text -> systemPrompt.text
                is SystemPrompt.Structured -> {
                    // Minimal safe fallback: stringify structured prompt.
                    // Implementations can improve this mapping later without breaking public API.
                    "type=${systemPrompt.type} payload=${systemPrompt.payload}"
                }
            }.trim()

            if (text.isBlank()) return null

            // Try common constructor shapes defensively.
            return runCatching {
                val cls = Message::class.java

                // (String) constructor
                cls.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
                    ?.newInstance(text) as? Message
            }.getOrNull() ?: runCatching {
                val cls = Message::class.java

                // (String, String) constructor: (role, text) or (text, role) depending on SDK
                val ctor = cls.constructors.firstOrNull {
                    it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == String::class.java
                } ?: return@runCatching null

                // Prefer (role="system", text)
                (ctor.newInstance("system", text) as? Message)
                    ?: (ctor.newInstance(text, "system") as? Message)
            }.getOrNull() ?: runCatching {
                // Fallback: try a Builder pattern if present.
                val builderCls = Class.forName("com.google.ai.edge.litertlm.Message\$Builder")
                val builder = builderCls.getDeclaredConstructor().newInstance()

                // Try builder.setRole("system") and builder.setText(text) / setContent(text)
                runCatching { builderCls.getMethod("setRole", String::class.java).invoke(builder, "system") }
                runCatching { builderCls.getMethod("setText", String::class.java).invoke(builder, text) }
                    .recoverCatching { builderCls.getMethod("setContent", String::class.java).invoke(builder, text) }

                val build = builderCls.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
                    ?: return@runCatching null

                build.invoke(builder) as? Message
            }.getOrNull()
        }
    }
}