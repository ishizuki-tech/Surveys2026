/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmSessionManager.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm.liteRT

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.Model
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages Engine/Conversation lifecycle per runtime key.
 *
 * Owns:
 * - instances map
 * - per-key session mutex
 * - createConversationWithRetry
 * - resetConversationInternal / closeInstanceNowBestEffort / closeInstanceIfStillIdle
 * - idle cleanup scheduling
 */
internal object LiteRtLmSessionManager {

    private object Const {
        const val IDLE_CLEANUP_MS: Long = 120_000L
        const val CLOSE_GRACE_MS: Long = 5_000L
        const val RETIRED_CLOSE_GRACE_MS: Long = 1_500L
        const val POST_TERMINATE_COOLDOWN_MS: Long = 250L

        const val RETRY_INITIAL_DELAY_MS: Long = 25L
        const val RETRY_MAX_DELAY_MS: Long = 250L
    }

    /**
     * Close plan computed under [stateMutex] so we can safely apply grace delays even if
     * run-controller state gets reset for close.
     */
    private data class ClosePlan(
        val instance: LiteRtLmInstance,
        val nowMs: Long,
        val lastTerminateAtMs: Long,
    )

    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Protects [instances] mutations/reads and ensures close-plans are computed atomically.
     *
     * Note:
     * - [instances] is a plain HashMap because we always guard it with this mutex.
     */
    private val stateMutex: Mutex = Mutex()
    private val instances: MutableMap<String, LiteRtLmInstance> = HashMap()

    /**
     * Per-key session mutexes to serialize operations like reset/close for the same runtime key.
     */
    private val sessionMutexes: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    /**
     * Debounced cleanup jobs keyed by runtime key.
     */
    private val cleanupJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /**
     * Get or create the per-key session mutex.
     */
    private fun getSessionMutex(key: String): Mutex {
        val existing = sessionMutexes[key]
        if (existing != null) return existing

        val created = Mutex()
        val prev = sessionMutexes.putIfAbsent(key, created)
        return prev ?: created
    }

    /**
     * Run a block under the per-key session lock.
     *
     * Note:
     * - [reason] is intentionally carried for traceability at call sites.
     */
    internal suspend fun <T> withSessionLock(
        key: String,
        reason: String,
        block: suspend () -> T,
    ): T {
        return getSessionMutex(key).withLock { block() }
    }

    internal suspend fun hasInstance(key: String): Boolean =
        stateMutex.withLock { instances.containsKey(key) }

    internal suspend fun getInstance(key: String): LiteRtLmInstance? =
        stateMutex.withLock { instances[key] }

    internal suspend fun setInstance(key: String, instance: LiteRtLmInstance) {
        stateMutex.withLock { instances[key] = instance }
    }

    internal suspend fun removeInstance(key: String): LiteRtLmInstance? =
        stateMutex.withLock { instances.remove(key) }

    /**
     * Cancel any scheduled idle cleanup for this key.
     */
    internal fun cancelScheduledCleanup(key: String, reason: String) {
        val job = cleanupJobs.remove(key)
        if (job != null) {
            if (job.isActive) job.cancel()
            AppLog.d(LiteRtLmLogging.TAG, "Idle cleanup cancelled: key='$key' reason='$reason'")
        }
    }

    /**
     * Schedule an idle cleanup (debounced + token-guarded).
     */
    private fun scheduleIdleCleanup(key: String, delayMs: Long, reason: String) {
        cancelScheduledCleanup(key, "reschedule:$reason")

        val tokenAtSchedule = LiteRtLmRunController.getCleanupToken(key)
        val job = ioScope.launch {
            try {
                AppLog.d(
                    LiteRtLmLogging.TAG,
                    "Idle cleanup scheduled: key='$key' in ${delayMs}ms reason='$reason'",
                )
                delay(delayMs)
                closeInstanceIfStillIdle(
                    key = key,
                    requiredIdleMs = delayMs,
                    requiredToken = tokenAtSchedule,
                    reason = "idle:$reason",
                )
            } finally {
                cleanupJobs.remove(key)
            }
        }

        cleanupJobs[key] = job
    }

    /**
     * Request a deferred idle cleanup.
     */
    internal fun cleanUp(key: String, onDone: () -> Unit) {
        ioScope.launch {
            runCatching { LiteRtLmInitCoordinator.awaitInitIfInFlight(key, reason = "cleanUp") }
                .onFailure {
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "cleanUp: init wait failed: key='$key' err=${it.message}",
                    )
                }

            val action: () -> Unit = {
                scheduleIdleCleanup(key, Const.IDLE_CLEANUP_MS, "explicit-cleanUp")
                onDone()
            }

            val defer = LiteRtLmRunController.isRunOccupiedKey(key)
            if (defer) {
                LiteRtLmRunController.deferAfterStream(key) { action.invoke() }
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "cleanUp deferred (will schedule after termination): key='$key'",
                )
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Force immediate teardown (best-effort).
     *
     * Contract:
     * - If a run slot is occupied, defer until after termination.
     * - onDone() must be invoked exactly once.
     */
    internal fun forceCleanUp(key: String, onDone: () -> Unit) {
        ioScope.launch {
            runCatching { LiteRtLmInitCoordinator.awaitInitIfInFlight(key, reason = "forceCleanUp") }
                .onFailure {
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "forceCleanUp: init wait failed: key='$key' err=${it.message}",
                    )
                }

            suspend fun runAndNotify() {
                runCatching {
                    closeInstanceNowBestEffort(key, reason = "forceCleanUp")
                }.onFailure { t ->
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "forceCleanUp failed: key='$key' err=${t.message}",
                        t,
                    )
                }
                onDone()
            }

            val defer = LiteRtLmRunController.isRunOccupiedKey(key)
            if (defer) {
                LiteRtLmRunController.deferAfterStream(key) { ioScope.launch { runAndNotify() } }
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "forceCleanUp deferred (active/recovering run): key='$key'",
                )
                return@launch
            }

            runAndNotify()
        }
    }

    /**
     * Create a conversation with retry on FAILED_PRECONDITION ("session already exists").
     */
    internal suspend fun createConversationWithRetry(
        engine: Engine,
        cfg: ConversationConfig,
        key: String,
        reason: String,
        timeoutMs: Long = Const.CLOSE_GRACE_MS,
        initialDelayMs: Long = Const.RETRY_INITIAL_DELAY_MS,
        maxDelayMs: Long = Const.RETRY_MAX_DELAY_MS,
    ): Conversation {
        val start = SystemClock.elapsedRealtime()
        var delayMs = initialDelayMs
        var attempt = 0

        while (true) {
            attempt++
            try {
                val conv = engine.createConversation(cfg)
                if (attempt > 1) {
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "createConversationWithRetry succeeded: key='$key' attempts=$attempt reason='$reason'",
                    )
                }
                return conv
            } catch (t: Throwable) {
                if (!LiteRtLmLogging.isSessionAlreadyExistsError(t)) throw t

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - start
                if (elapsed >= timeoutMs) {
                    AppLog.e(
                        LiteRtLmLogging.TAG,
                        "createConversationWithRetry timed out: key='$key' attempts=$attempt " +
                                "elapsed=${elapsed}ms reason='$reason' err=${t.message}",
                        t,
                    )
                    throw t
                }

                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "createConversationWithRetry: session exists, retrying: key='$key' attempt=$attempt " +
                            "elapsed=${elapsed}ms nextDelay=${delayMs}ms reason='$reason'",
                )
                delay(delayMs)
                delayMs = kotlin.math.min(maxDelayMs, delayMs * 2)
            }
        }
    }

    /**
     * Reset conversation while reusing the existing Engine.
     *
     * Contract:
     * - Must not run while a run slot is occupied.
     */
    internal suspend fun resetConversationInternal(
        key: String,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message?,
        tools: List<Any>,
        reason: String,
        appContext: Context?,
    ) {
        withSessionLock(key, reason = "resetConversationInternal:$reason") {
            val (inst, occupied) = stateMutex.withLock {
                instances[key] to LiteRtLmRunController.isRunOccupiedKey(key)
            }

            if (inst == null) {
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "resetConversationInternal skipped: not initialized key='$key'",
                )
                return@withSessionLock
            }

            if (occupied) {
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "resetConversationInternal rejected: active/recovering run key='$key'",
                )
                return@withSessionLock
            }

            if (inst.supportImage != supportImage || inst.supportAudio != supportAudio) {
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "resetConversationInternal rejected: capability mismatch key='$key' " +
                            "have(image=${inst.supportImage},audio=${inst.supportAudio}) " +
                            "want(image=$supportImage,audio=$supportAudio)",
                )
                return@withSessionLock
            }

            val cfg = runCatching {
                // Rebuild config via InitCoordinator logic.
                val topK = model.getIntConfigValue(Model.ConfigKey.TOP_K, 40).coerceAtLeast(1)
                val topP = model.getFloatConfigValue(Model.ConfigKey.TOP_P, 0.9f)
                    .toDouble()
                    .coerceIn(0.0, 1.0)
                val temp = model.getFloatConfigValue(Model.ConfigKey.TEMPERATURE, 0.7f)
                    .toDouble()
                    .coerceIn(0.0, 2.0)

                ConversationConfig(
                    samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                        topK = topK,
                        topP = topP,
                        temperature = temp,
                    ),
                    systemMessage = systemMessage,
                    tools = tools,
                )
            }.getOrElse { e ->
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "resetConversationInternal config build failed: key='$key' err=${e.message}",
                    e,
                )
                return@withSessionLock
            }

            val engine = inst.engine
            val old = inst.conversation

            runCatching { old.close() }
                .onFailure {
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "resetConversationInternal: close old conversation failed: ${it.message}",
                        it,
                    )
                }

            delay(Const.POST_TERMINATE_COOLDOWN_MS)

            val fresh = try {
                createConversationWithRetry(
                    engine = engine,
                    cfg = cfg,
                    key = key,
                    reason = "resetConversationInternal:$reason",
                    timeoutMs = Const.CLOSE_GRACE_MS + Const.RETIRED_CLOSE_GRACE_MS,
                )
            } catch (t: Throwable) {
                AppLog.e(
                    LiteRtLmLogging.TAG,
                    "resetConversationInternal failed: key='$key' err=${t.message}",
                    t,
                )

                runCatching {
                    closeInstanceNowBestEffort(key, reason = "resetConversationInternal-recover")
                }.onFailure {
                    AppLog.w(
                        LiteRtLmLogging.TAG,
                        "resetConversationInternal recovery close failed: key='$key' err=${it.message}",
                        it,
                    )
                }

                if (appContext != null) {
                    runCatching {
                        LiteRtLmInitCoordinator.awaitInitializedInternal(
                            context = appContext,
                            model = model,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                            systemMessage = systemMessage,
                            tools = tools,
                        )
                    }.onFailure { re ->
                        AppLog.e(
                            LiteRtLmLogging.TAG,
                            "resetConversationInternal recovery re-init failed: key='$key' err=${re.message}",
                            re,
                        )
                    }
                }
                return@withSessionLock
            }

            inst.conversation = fresh
            inst.conversationConfigSnapshot = cfg

            /**
             * Clear transient run-controller state after a successful conversation reset.
             *
             * Why:
             * - Reset creates a fresh conversation boundary.
             * - Any stale cancel / hook / terminal state from an older run should not
             *   leak into the next run after reset.
             *
             * Safety:
             * - This reset path is already rejected when a run is occupied.
             * - We are under the per-key session lock here.
             */
            LiteRtLmRunController.clearTransientStateForReuse(key)

            AppLog.d(
                LiteRtLmLogging.TAG,
                "resetConversationInternal done: key='$key' reason='$reason'",
            )
        }
    }

    /**
     * Close and remove an instance NOW (best-effort).
     */
    internal suspend fun closeInstanceNowBestEffort(key: String, reason: String) {
        cancelScheduledCleanup(key, "closeNow:$reason")

        runCatching { LiteRtLmInitCoordinator.awaitInitIfInFlight(key, reason = "closeNow:$reason") }
            .onFailure {
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "closeInstanceNowBestEffort: init wait failed: key='$key' reason='$reason' err=${it.message}",
                )
                return
            }

        withSessionLock(key, reason = "closeNow:$reason") {
            val plan: ClosePlan? = stateMutex.withLock {
                if (LiteRtLmRunController.isRunOccupiedKey(key)) return@withLock null
                if (LiteRtLmInitCoordinator.isInitInFlight(key)) return@withLock null

                val instance = instances[key] ?: return@withLock null
                val nowMs = SystemClock.elapsedRealtime()
                val lastTerminateAtMs = LiteRtLmRunController.getLastTerminateAtMs(key)

                // Clear run-controller state for this key and drop any pending after-stream callbacks.
                LiteRtLmRunController.resetStateForClose(key)
                LiteRtLmRunController.clearPendingAfterStream(key)

                instances.remove(key)
                ClosePlan(
                    instance = instance,
                    nowMs = nowMs,
                    lastTerminateAtMs = lastTerminateAtMs,
                )
            }

            if (plan == null) {
                AppLog.d(
                    LiteRtLmLogging.TAG,
                    "closeInstanceNowBestEffort: nothing to close (or active/recovering/initInFlight): " +
                            "key='$key' reason='$reason'",
                )
                return@withSessionLock
            }

            delayCloseGraceIfNeeded(plan.nowMs, plan.lastTerminateAtMs)

            runCatching { plan.instance.conversation.close() }
                .onFailure {
                    AppLog.e(
                        LiteRtLmLogging.TAG,
                        "close conversation failed: key='$key' reason='$reason' err=${it.message}",
                        it,
                    )
                }

            runCatching { plan.instance.engine.close() }
                .onFailure {
                    AppLog.e(
                        LiteRtLmLogging.TAG,
                        "close engine failed: key='$key' reason='$reason' err=${it.message}",
                        it,
                    )
                }

            AppLog.d(
                LiteRtLmLogging.TAG,
                "LiteRT-LM closed: key='$key' reason='$reason'",
            )
        }
    }

    /**
     * Token + idleness guarded closer for idle cleanup.
     */
    private suspend fun closeInstanceIfStillIdle(
        key: String,
        requiredIdleMs: Long,
        requiredToken: Long,
        reason: String,
    ) {
        if (LiteRtLmInitCoordinator.isInitInFlight(key)) {
            AppLog.d(
                LiteRtLmLogging.TAG,
                "Idle cleanup skipped (init in flight): key='$key'",
            )
            return
        }

        withSessionLock(key, reason = "idleClose:$reason") {
            val plan: ClosePlan? = stateMutex.withLock {
                val nowMs = SystemClock.elapsedRealtime()
                val idleForMs = nowMs - LiteRtLmRunController.getLastUseAtMs(key)
                val tokenNow = LiteRtLmRunController.getCleanupToken(key)

                if (LiteRtLmRunController.isRunOccupiedKey(key)) return@withLock null
                if (LiteRtLmInitCoordinator.isInitInFlight(key)) return@withLock null
                if (tokenNow != requiredToken) return@withLock null
                if (idleForMs < requiredIdleMs) return@withLock null

                val instance = instances[key] ?: return@withLock null
                val lastTerminateAtMs = LiteRtLmRunController.getLastTerminateAtMs(key)

                LiteRtLmRunController.resetStateForClose(key)
                LiteRtLmRunController.clearPendingAfterStream(key)

                instances.remove(key)
                ClosePlan(
                    instance = instance,
                    nowMs = nowMs,
                    lastTerminateAtMs = lastTerminateAtMs,
                )
            }

            if (plan == null) return@withSessionLock

            delayCloseGraceIfNeeded(plan.nowMs, plan.lastTerminateAtMs)

            runCatching { plan.instance.conversation.close() }
                .onFailure {
                    AppLog.e(
                        LiteRtLmLogging.TAG,
                        "idle close conversation failed: key='$key' err=${it.message}",
                        it,
                    )
                }

            runCatching { plan.instance.engine.close() }
                .onFailure {
                    AppLog.e(
                        LiteRtLmLogging.TAG,
                        "idle close engine failed: key='$key' err=${it.message}",
                        it,
                    )
                }

            AppLog.d(
                LiteRtLmLogging.TAG,
                "LiteRT-LM closed: key='$key' reason='$reason'",
            )
        }
    }

    /**
     * Delay close to respect a grace window after native termination.
     *
     * Why:
     * - Some runtimes keep a native session around briefly.
     * - Closing/recreating too soon can trigger FAILED_PRECONDITION ("session already exists").
     */
    private suspend fun delayCloseGraceIfNeeded(nowMs: Long, lastTerminateAtMs: Long) {
        if (lastTerminateAtMs <= 0L) return

        val sinceTerminate = nowMs - lastTerminateAtMs
        if (sinceTerminate < 0L) return

        val extraDelay =
            if (sinceTerminate in 0..Const.CLOSE_GRACE_MS) {
                Const.CLOSE_GRACE_MS - sinceTerminate
            } else {
                0L
            }

        if (extraDelay > 0L) {
            delay(extraDelay)
        }
    }
}