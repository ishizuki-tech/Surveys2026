/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmRunController.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm.liteRT

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.Model
import com.negi.surveys.slm.liteRT.LiteRtLmLogging.cleanError
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Streaming run controller.
 *
 * Owns:
 * - RunState + runStates
 * - watchdog / hard-close
 * - pendingAfterStream
 */
internal object LiteRtLmRunController {

    private const val STREAM_WATCHDOG_MS = 120_000L

    private const val HARD_CLOSE_TIMEOUT_MS = 15_000L
    private const val HARD_CLOSE_POLL_MS = 750L
    private const val HARD_CLOSE_ENABLE = true

    private const val POST_TERMINATE_COOLDOWN_MS = 250L

    private const val MAIN_DELTA_MIN_CHARS = 64
    private const val MAIN_DELTA_MAX_INTERVAL_MS = 50L

    private const val PENDING_CANCEL_TTL_MS = 2_000L

    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingAfterStream = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>>()

    internal data class RunState(
        /**
         * True while a stream is actively running (sendMessageAsync in flight).
         *
         * Note:
         * - Do NOT use this flag for init/upgrade serialization.
         * - Use [preparing] to block concurrent runs during destructive init/upgrade steps.
         */
        val active: AtomicBoolean = AtomicBoolean(false),
        val recovering: AtomicBoolean = AtomicBoolean(false),
        /**
         * Preparation phase lock (auto-init / capability upgrade).
         *
         * Why:
         * - We must block concurrent runs for the same key while performing destructive init/upgrade.
         * - We must NOT mark the stream as active yet (otherwise init rejects it).
         */
        val preparing: AtomicBoolean = AtomicBoolean(false),
        val terminated: AtomicBoolean = AtomicBoolean(false),
        val logicalDone: AtomicBoolean = AtomicBoolean(false),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val pendingCancel: AtomicBoolean = AtomicBoolean(false),
        val pendingCancelAtMs: AtomicLong = AtomicLong(0L),
        val runId: AtomicLong = AtomicLong(0L),
        val lastTerminateAtMs: AtomicLong = AtomicLong(0L),
        val lastUseAtMs: AtomicLong = AtomicLong(0L),
        val lastMessageAtMs: AtomicLong = AtomicLong(0L),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        val logicalTerminator: AtomicReference<(() -> Unit)?> = AtomicReference(null),
        /**
         * Best-effort hook to deliver logical completion (resultListener(done=true)) from out-of-band
         * paths such as hard-close watchdog.
         *
         * Must be runId-scoped by setter.
         */
        val logicalDoneHook: AtomicReference<(() -> Unit)?> = AtomicReference(null),
        val hardCloseRunning: AtomicBoolean = AtomicBoolean(false),
        val cleanupToken: AtomicLong = AtomicLong(0L),
        val nativeDoneHook: AtomicReference<(() -> Unit)?> = AtomicReference(null),
    )

    private val runStates: ConcurrentHashMap<String, RunState> = ConcurrentHashMap()

    private fun getRunState(key: String): RunState {
        val existing = runStates[key]
        if (existing != null) return existing
        val created = RunState()
        val prev = runStates.putIfAbsent(key, created)
        return prev ?: created
    }

    internal fun isRunOccupiedKey(key: String): Boolean {
        val rs = getRunState(key)
        return rs.preparing.get() || rs.active.get() || rs.recovering.get()
    }

    /**
     * True only for actual stream/recovery activity (NOT preparing).
     *
     * Note:
     * - InitCoordinator should reject init only for these states.
     */
    internal fun isRunActiveOrRecoveringKey(key: String): Boolean {
        val rs = getRunState(key)
        return rs.active.get() || rs.recovering.get()
    }

    internal fun anyRunOccupied(): Boolean = runStates.values.any { it.preparing.get() || it.active.get() || it.recovering.get() }

    internal fun anyRunOccupiedForPrefix(prefix: String): Boolean {
        return runStates.entries.any { (k, rs) ->
            k.startsWith(prefix) && (rs.preparing.get() || rs.active.get() || rs.recovering.get())
        }
    }

    internal fun getLastUseAtMs(key: String): Long = getRunState(key).lastUseAtMs.get()
    internal fun getLastTerminateAtMs(key: String): Long = getRunState(key).lastTerminateAtMs.get()
    internal fun getCleanupToken(key: String): Long = getRunState(key).cleanupToken.get()

    /** Touch last-use time and invalidate any scheduled cleanup. */
    internal fun markUsed(key: String) {
        val now = SystemClock.elapsedRealtime()
        val rs = getRunState(key)
        rs.lastUseAtMs.set(now)
        rs.cleanupToken.incrementAndGet()
    }

    internal fun clearPendingAfterStream(key: String) {
        pendingAfterStream.remove(key)
    }

    internal fun deferAfterStream(key: String, action: () -> Unit) {
        val q = pendingAfterStream.computeIfAbsent(key) { java.util.concurrent.ConcurrentLinkedQueue() }
        q.add(action)
    }

    /** Reset RunState flags for "we are tearing down this instance". */
    internal fun resetStateForClose(key: String) {
        val rs = getRunState(key)
        rs.cancelRequested.set(false)
        rs.pendingCancel.set(false)
        rs.pendingCancelAtMs.set(0L)
        rs.preparing.set(false)
        rs.recovering.set(false)
        rs.logicalTerminator.set(null)
        rs.logicalDoneHook.set(null)
        rs.nativeDoneHook.set(null)
        rs.terminated.set(true)
        rs.logicalDone.set(true)
        rs.active.set(false)
    }

    private suspend fun awaitCooldownIfNeeded(key: String, reason: String) {
        val rs = getRunState(key)
        val now = SystemClock.elapsedRealtime()
        val until = rs.cooldownUntilMs.get()
        val delayMs = max(0L, until - now)
        if (delayMs > 0) {
            AppLog.d(LiteRtLmLogging.TAG, "Cooldown delay: key='$key' delay=${delayMs}ms reason='$reason'")
            delay(delayMs)
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Convert this Bitmap to PNG bytes. */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /** Build Content list for a single message (multimodal first, then text). */
    private fun buildContentList(input: String, images: List<Bitmap>, audioClips: List<ByteArray>): List<Content> {
        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        val t = input.trim()
        if (t.isNotEmpty()) contents.add(Content.Text(t))
        return contents
    }

    /**
     * Build a Contents object from a List<Content> with reflection to handle SDK drift.
     */
    private fun buildContentsObject(contents: List<Content>): Contents {
        val cls = Contents::class.java

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && List::class.java.isAssignableFrom(p[0])
            } ?: return@runCatching null
            (ctor.newInstance(contents) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && p[0].isArray
            } ?: return@runCatching null
            val arr = contents.toTypedArray()
            (ctor.newInstance(arr) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val m = cls.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) m.invoke(null, contents.toTypedArray()) else m.invoke(null, contents)
            (inst as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val companionField = cls.getDeclaredField("Companion")
            val companion = companionField.get(null) ?: return@runCatching null
            val m = companion.javaClass.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) m.invoke(companion, contents.toTypedArray()) else m.invoke(companion, contents)
            (inst as Contents)
        }.getOrNull()?.let { return it }

        throw IllegalStateException("Unable to construct Contents for current LiteRT-LM SDK.")
    }

    /**
     * Coalesces streaming deltas and delivers them on the main thread.
     */
    private class MainDeltaDispatcher(
        private val handler: Handler,
        private val minEmitChars: Int,
        private val maxEmitIntervalMs: Long,
        private val isSameRun: () -> Boolean,
        private val isStillActive: () -> Boolean,
        private val deliver: (String) -> Unit,
    ) {
        private val lock = Any()
        private val buffer = StringBuilder()
        private var lastFlushAtMs: Long = SystemClock.uptimeMillis()
        private var scheduled: Boolean = false

        private val flushRunnable = Runnable { flushOnMain(force = false) }

        fun offer(delta: String) {
            if (delta.isEmpty()) return
            if (!isStillActive()) return

            val now = SystemClock.uptimeMillis()
            var scheduleDelayMs: Long? = null

            synchronized(lock) {
                buffer.append(delta)

                val timeSince = now - lastFlushAtMs
                val timeReady = timeSince >= maxEmitIntervalMs
                val sizeReady = buffer.length >= minEmitChars
                val newlineReady = delta.indexOf('\n') >= 0

                if (!scheduled) {
                    scheduleDelayMs = if (timeReady || sizeReady || newlineReady) 0L
                    else (maxEmitIntervalMs - timeSince).coerceAtLeast(0L)
                    scheduled = true
                }
            }

            val d = scheduleDelayMs ?: return
            handler.removeCallbacks(flushRunnable)
            if (d <= 0L) handler.post(flushRunnable) else handler.postDelayed(flushRunnable, d)
        }

        fun flushNow(force: Boolean = false) {
            if (Looper.myLooper() == Looper.getMainLooper()) flushOnMain(force = force)
            else handler.post { flushOnMain(force = force) }
        }

        fun cancel() {
            synchronized(lock) {
                buffer.setLength(0)
                scheduled = false
            }
            handler.removeCallbacks(flushRunnable)
        }

        private fun flushOnMain(force: Boolean) {
            if (force) {
                if (!isSameRun()) {
                    cancel()
                    return
                }
            } else {
                if (!isStillActive()) {
                    cancel()
                    return
                }
            }

            val out = synchronized(lock) {
                scheduled = false
                val now = SystemClock.uptimeMillis()
                if (buffer.isEmpty()) {
                    lastFlushAtMs = now
                    ""
                } else {
                    val s = buffer.toString()
                    buffer.setLength(0)
                    lastFlushAtMs = now
                    s
                }
            }

            val ok = if (force) isSameRun() else isStillActive()
            if (out.isNotEmpty() && ok) {
                runCatching { deliver(out) }.onFailure { /* Keep UI stable */ }
            }
        }
    }

    /** Fire native done hook once (safe no-op if already cleared). */
    private fun fireNativeDoneHookOnce(key: String) {
        val rs = getRunState(key)
        val hook = rs.nativeDoneHook.getAndSet(null) ?: return
        runCatching { hook.invoke() }
            .onFailure { t -> AppLog.w(LiteRtLmLogging.TAG, "nativeDoneHook failed: key='$key' err=${t.message}", t) }
    }

    private fun debugState(key: String, rs: RunState, prefix: String) {
        if (!LiteRtLmLogging.DEBUG_STATE) return
        val rid = rs.runId.get()
        val preparing = rs.preparing.get()
        val active = rs.active.get()
        val recovering = rs.recovering.get()
        val term = rs.terminated.get()
        val logical = rs.logicalDone.get()
        val cancel = rs.cancelRequested.get()
        val pending = rs.pendingCancel.get()
        val lastMsg = rs.lastMessageAtMs.get()
        val lastTerm = rs.lastTerminateAtMs.get()
        AppLog.d(
            LiteRtLmLogging.TAG,
            "state[$prefix] key='$key' runId=$rid preparing=$preparing active=$active recovering=$recovering terminated=$term logicalDone=$logical " +
                    "cancel=$cancel pendingCancel=$pending lastMsgAt=$lastMsg lastTermAt=$lastTerm"
        )
    }

    /**
     * Emergency watchdog that attempts to recover from "never terminates" streams.
     *
     * Note:
     * - Must be runId-scoped to avoid killing newer runs for the same key.
     */
    private fun startHardCloseWatchdog(key: String, reason: String, expectedRunId: Long) {
        val rs = getRunState(key)
        if (!rs.hardCloseRunning.compareAndSet(false, true)) return

        ioScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                AppLog.w(
                    LiteRtLmLogging.TAG,
                    "Hard-close watchdog started: key='$key' runId=$expectedRunId reason='$reason' timeout=${HARD_CLOSE_TIMEOUT_MS}ms"
                )

                while (true) {
                    delay(HARD_CLOSE_POLL_MS)

                    if (rs.runId.get() != expectedRunId) {
                        AppLog.d(
                            LiteRtLmLogging.TAG,
                            "Hard-close watchdog exit: key='$key' runId changed (expected=$expectedRunId actual=${rs.runId.get()})"
                        )
                        return@launch
                    }

                    if (!isRunOccupiedKey(key) || rs.terminated.get()) {
                        AppLog.d(LiteRtLmLogging.TAG, "Hard-close watchdog exit: key='$key' already terminated/free")
                        return@launch
                    }

                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - start
                    val sinceMsg = now - rs.lastMessageAtMs.get()

                    if (sinceMsg in 0..2_000L && elapsed < HARD_CLOSE_TIMEOUT_MS) continue

                    if (elapsed >= HARD_CLOSE_TIMEOUT_MS) {
                        if (rs.runId.get() != expectedRunId) return@launch

                        val didTerminate = rs.terminated.compareAndSet(false, true)
                        if (!didTerminate) return@launch

                        AppLog.e(
                            LiteRtLmLogging.TAG,
                            "Hard-close watchdog firing: key='$key' runId=$expectedRunId elapsed=${elapsed}ms sinceMsg=${sinceMsg}ms"
                        )
                        debugState(key, rs, "hardClose:firing")

                        // Best-effort: deliver logical completion for this run.
                        rs.logicalDoneHook.getAndSet(null)?.let { hook ->
                            runCatching { hook.invoke() }
                                .onFailure { t -> AppLog.w(LiteRtLmLogging.TAG, "hardClose logicalDoneHook failed: key='$key' err=${t.message}", t) }
                        }

                        LiteRtLmSessionManager.withSessionLock(key, reason = "hardClose:$reason") {
                            val inst = LiteRtLmSessionManager.removeInstance(key)
                            clearPendingAfterStream(key)

                            if (inst != null) {
                                runCatching { inst.conversation.close() }
                                    .onFailure { AppLog.e(LiteRtLmLogging.TAG, "Hard-close: conversation.close failed: key='$key' err=${it.message}", it) }
                                runCatching { inst.engine.close() }
                                    .onFailure { AppLog.e(LiteRtLmLogging.TAG, "Hard-close: engine.close failed: key='$key' err=${it.message}", it) }
                            }

                            val tNow = SystemClock.elapsedRealtime()
                            rs.lastTerminateAtMs.set(tNow)
                            rs.cooldownUntilMs.set(tNow + POST_TERMINATE_COOLDOWN_MS)

                            rs.preparing.set(false)
                            rs.active.set(false)
                            rs.recovering.set(false)
                            rs.logicalDone.set(true)
                            rs.logicalTerminator.set(null)
                            rs.logicalDoneHook.set(null)

                            fireNativeDoneHookOnce(key)
                        }

                        AppLog.e(LiteRtLmLogging.TAG, "Hard-close completed: key='$key' runId=$expectedRunId")
                        debugState(key, rs, "hardClose:done")
                        return@launch
                    }
                }
            } finally {
                rs.hardCloseRunning.set(false)
            }
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract:
     * - resultListener(..., done=true) is "logical completion" (UI completion).
     * - cleanUpListener() is invoked ONLY after native termination (onDone/onError),
     *   or after hard-close watchdog if enabled.
     */
    internal fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        notifyCancelToOnError: Boolean = false,
        appContextProvider: () -> Context?,
    ) {
        val key = LiteRtLmKeys.runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            LiteRtLmSessionManager.cancelScheduledCleanup(key, "runInference")

            fun failFast(message: String, t: Throwable? = null) {
                if (t != null) AppLog.e(LiteRtLmLogging.TAG, message, t) else AppLog.e(LiteRtLmLogging.TAG, message)
                postToMain {
                    onError(message)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
            }

            val rs = getRunState(key)

            // Acquire preparation lock first (blocks concurrent runs during init/upgrade).
            if (!rs.preparing.compareAndSet(false, true)) {
                failFast(cleanError("Busy (already preparing)"))
                return@launch
            }
            if (rs.active.get() || rs.recovering.get()) {
                rs.preparing.set(false)
                failFast(cleanError("Busy (stream/recovery already active)"))
                return@launch
            }

            val myRunId = rs.runId.incrementAndGet()
            rs.terminated.set(false)
            rs.logicalDone.set(false)
            rs.recovering.set(false)
            val nowStartMs = SystemClock.elapsedRealtime()
            rs.lastMessageAtMs.set(nowStartMs)

            // Transfer pending cancel into the current run window WITHOUT clobbering an already requested cancel.
            val preCancelled = rs.pendingCancel.getAndSet(false)
            val preCancelAtMs = rs.pendingCancelAtMs.getAndSet(0L)
            val preCancelFresh = preCancelled && preCancelAtMs > 0L && (nowStartMs - preCancelAtMs) <= PENDING_CANCEL_TTL_MS
            if (preCancelFresh) rs.cancelRequested.set(true)

            runCatching { LiteRtLmInitCoordinator.awaitInitIfInFlight(key, reason = "runInference") }
                .onFailure { t ->
                    rs.preparing.set(false)
                    failFast(
                        "LiteRT-LM cannot start inference while initialization is in progress: ${LiteRtLmLogging.cleanError(t.message)}",
                        t
                    )
                    return@launch
                }

            val wantImage = images.isNotEmpty()
            val wantAudio = audioClips.isNotEmpty()

            val needAutoInit = !LiteRtLmSessionManager.hasInstance(key)
            if (needAutoInit) {
                val ctx = appContextProvider()
                if (ctx == null) {
                    rs.preparing.set(false)
                    failFast(
                        "LiteRT-LM model '${model.name}' is not initialized, and no application context is set. " +
                                "Call setApplicationContext() or initializeIfNeeded() first."
                    )
                    return@launch
                }

                runCatching {
                    LiteRtLmInitCoordinator.awaitInitializedInternal(
                        context = ctx,
                        model = model,
                        supportImage = wantImage,
                        supportAudio = wantAudio,
                    )
                }.onFailure { t ->
                    rs.preparing.set(false)
                    failFast("LiteRT-LM auto-init failed: ${LiteRtLmLogging.cleanError(t.message)}", t)
                    return@launch
                }
            }

            // Upgrade capabilities if needed (allowed during preparing).
            val appContext = appContextProvider()
            if ((wantImage || wantAudio) && appContext != null) {
                runCatching {
                    LiteRtLmInitCoordinator.upgradeCapabilitiesIfNeeded(
                        context = appContext,
                        model = model,
                        wantImage = wantImage,
                        wantAudio = wantAudio,
                    )
                }.onFailure { t ->
                    rs.preparing.set(false)
                    failFast("LiteRT-LM upgrade failed: ${LiteRtLmLogging.cleanError(t.message)}", t)
                    return@launch
                }
            }

            awaitCooldownIfNeeded(key, reason = "runInference")

            var inst: LiteRtLmInstance? = null
            var conversation: Conversation? = null
            var rejectMsg: String? = null

            LiteRtLmSessionManager.withSessionLock(key, reason = "runInference-start") {
                val i = LiteRtLmSessionManager.getInstance(key)
                if (i == null) {
                    rejectMsg = "Not initialized"
                    return@withSessionLock
                }
                if (wantImage && !i.supportImage) {
                    rejectMsg = "Image input not supported by this session"
                    return@withSessionLock
                }
                if (wantAudio && !i.supportAudio) {
                    rejectMsg = "Audio input not supported by this session"
                    return@withSessionLock
                }
                inst = i
                conversation = i.conversation
                debugState(key, rs, "run:prepared(runId=$myRunId)")
            }

            rejectMsg?.let { msg ->
                rs.preparing.set(false)
                failFast(msg)
                return@launch
            }

            val i = inst ?: run {
                rs.preparing.set(false)
                failFast("Not initialized")
                return@launch
            }
            var conv = conversation ?: run {
                rs.preparing.set(false)
                failFast("Not initialized")
                return@launch
            }

            // Transition to active streaming now.
            rs.active.set(true)
            rs.preparing.set(false)

            val trimmed = input.trim()
            val hasText = trimmed.isNotEmpty()
            val hasMm = wantImage || wantAudio

            AppLog.d(
                LiteRtLmLogging.TAG,
                "runInference start: key='$key' runId=$myRunId hasText=$hasText textLen=${trimmed.length} images=${images.size} audioClips=${audioClips.size}"
            )

            val callbackLock = Any()
            var emittedSoFar = ""
            var msgCount = 0

            val nativeStarted = AtomicBoolean(false)

            val mainDelta = MainDeltaDispatcher(
                handler = mainHandler,
                minEmitChars = MAIN_DELTA_MIN_CHARS,
                maxEmitIntervalMs = MAIN_DELTA_MAX_INTERVAL_MS,
                isSameRun = { rs.runId.get() == myRunId },
                isStillActive = { rs.runId.get() == myRunId && !rs.terminated.get() && !rs.logicalDone.get() },
                deliver = { chunk -> resultListener(chunk, false) }
            )

            fun scheduleDeferredActions() {
                val q = pendingAfterStream.remove(key) ?: return
                while (true) {
                    val act = q.poll() ?: break
                    runCatching { act.invoke() }.onFailure { t ->
                        AppLog.w(LiteRtLmLogging.TAG, "Deferred action failed for key='$key': ${t.message}", t)
                    }
                }
            }

            fun scheduleCleanUpListener() {
                postToMain {
                    runCatching { cleanUpListener.invoke() }
                        .onFailure { t -> AppLog.w(LiteRtLmLogging.TAG, "cleanUpListener failed: ${t.message}", t) }
                }
            }

            rs.nativeDoneHook.set(hook@{
                if (rs.runId.get() != myRunId) return@hook
                scheduleCleanUpListener()
                scheduleDeferredActions()
            })

            var watchdog: Job? = null

            fun deliverLogicalDoneOnce(errorMessage: String? = null, isCancel: Boolean = false) {
                if (!rs.logicalDone.compareAndSet(false, true)) return
                if (LiteRtLmLogging.DEBUG_STATE) debugState(key, rs, "logicalDone")

                postToMain {
                    mainDelta.flushNow(force = true)

                    val cancelled = isCancel || rs.cancelRequested.get()
                    if (cancelled) {
                        if (notifyCancelToOnError && !errorMessage.isNullOrBlank()) onError(errorMessage)
                    } else if (!errorMessage.isNullOrBlank()) {
                        onError(errorMessage)
                    }
                    resultListener("", true)
                    mainDelta.cancel()
                }
            }

            // Allow hard-close watchdog to deliver logical completion for this run.
            rs.logicalDoneHook.set(hook@{
                if (rs.runId.get() != myRunId) return@hook
                val cancelled = rs.cancelRequested.get()
                val msg = if (cancelled) "Cancelled" else "Hard-close watchdog terminated stream"
                deliverLogicalDoneOnce(errorMessage = msg, isCancel = cancelled)
            })

            fun cancelProcessBestEffort(stage: String) {
                ioScope.launch {
                    val convNow = LiteRtLmSessionManager.getInstance(key)?.conversation
                    if (convNow == null) {
                        AppLog.w(LiteRtLmLogging.TAG, "cancelProcess skipped: conversation missing key='$key' stage='$stage'")
                        return@launch
                    }
                    runCatching { convNow.cancelProcess() }
                        .onFailure { t -> AppLog.w(LiteRtLmLogging.TAG, "cancelProcess() failed: key='$key' stage='$stage' err=${t.message}", t) }
                }
            }

            fun markNativeDoneOnce(errorMessage: String? = null, isCancel: Boolean = false) {
                if (!rs.terminated.compareAndSet(false, true)) return

                watchdog?.cancel()
                watchdog = null

                val now = SystemClock.elapsedRealtime()
                rs.lastTerminateAtMs.set(now)
                rs.cooldownUntilMs.set(now + POST_TERMINATE_COOLDOWN_MS)

                rs.preparing.set(false)
                rs.active.set(false)
                rs.recovering.set(false)
                rs.logicalTerminator.set(null)
                rs.logicalDoneHook.set(null)

                if (!isCancel) {
                    rs.cancelRequested.set(false)
                    rs.pendingCancel.set(false)
                }

                deliverLogicalDoneOnce(errorMessage = errorMessage, isCancel = isCancel)
                fireNativeDoneHookOnce(key)

                if (LiteRtLmLogging.DEBUG_STATE) debugState(key, rs, "nativeDone")
            }

            fun requestLogicalCancel(reason: String) {
                rs.cancelRequested.set(true)
                deliverLogicalDoneOnce(errorMessage = reason, isCancel = true)

                cancelProcessBestEffort(stage = "logicalCancel")
                if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "logicalCancel", expectedRunId = myRunId)
            }

            rs.logicalTerminator.set { requestLogicalCancel("Cancelled") }

            if (rs.cancelRequested.get()) {
                AppLog.i(LiteRtLmLogging.TAG, "LiteRT-LM start cancelled before sendMessageAsync: key='$key'")
                markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                return@launch
            }

            watchdog = ioScope.launch {
                delay(STREAM_WATCHDOG_MS)
                if (rs.runId.get() != myRunId) return@launch
                if (rs.terminated.get()) return@launch

                AppLog.e(LiteRtLmLogging.TAG, "Stream watchdog fired: key='$key' runId=$myRunId timeout=${STREAM_WATCHDOG_MS}ms")
                debugState(key, rs, "watchdog:fired")

                deliverLogicalDoneOnce("Timeout: inference did not complete in ${STREAM_WATCHDOG_MS}ms")
                cancelProcessBestEffort(stage = "watchdog")
                if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "watchdog", expectedRunId = myRunId)

                if (!nativeStarted.get()) markNativeDoneOnce("Timeout before native start")
            }

            val callback = object : MessageCallback {

                override fun onMessage(message: Message) {
                    if (rs.runId.get() != myRunId) return
                    if (rs.terminated.get()) return
                    if (rs.logicalDone.get() || rs.cancelRequested.get()) return

                    val now = SystemClock.elapsedRealtime()
                    rs.lastMessageAtMs.set(now)

                    val snapshotRaw = LiteRtLmTextDelta.extractRenderedText(message)
                    if (snapshotRaw.isEmpty()) return

                    var deltaRaw = ""
                    var nextEmitted = ""

                    synchronized(callbackLock) {
                        msgCount++
                        val pair = LiteRtLmTextDelta.computeDeltaSmart(emittedSoFar, snapshotRaw)
                        deltaRaw = pair.first
                        nextEmitted = pair.second
                        emittedSoFar = nextEmitted
                    }

                    if (deltaRaw.isEmpty()) return

                    val delta = LiteRtLmTextDelta.normalizeDeltaText(deltaRaw)

                    if (LiteRtLmLogging.DEBUG_STREAM) {
                        val c: Int = synchronized(callbackLock) { msgCount }
                        if (c == 1 || c % LiteRtLmLogging.DEBUG_STREAM_EVERY_N == 0) {
                            val dPreview = delta.take(LiteRtLmLogging.DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                            val sPreview = snapshotRaw.take(LiteRtLmLogging.DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                            AppLog.d(
                                LiteRtLmLogging.TAG,
                                "stream[key=$key runId=$myRunId] msg#$c snapLen=${snapshotRaw.length} deltaLen=${delta.length} " +
                                        "snapPreview='$sPreview' deltaPreview='$dPreview' emittedLen=${nextEmitted.length}"
                            )
                        }
                    }

                    mainDelta.offer(delta)
                }

                override fun onDone() {
                    if (rs.runId.get() != myRunId) return
                    markNativeDoneOnce(null)
                }

                override fun onError(throwable: Throwable) {
                    if (rs.runId.get() != myRunId) return

                    val rawMsg = throwable.message ?: throwable.toString()
                    val msg = LiteRtLmLogging.cleanError(rawMsg)
                    val code = LiteRtLmLogging.extractStatusCodeBestEffort(throwable)

                    val cancelled = rs.cancelRequested.get() || LiteRtLmLogging.isCancellationThrowable(throwable, msg)
                    if (cancelled) {
                        AppLog.i(LiteRtLmLogging.TAG, "LiteRT-LM inference cancelled: key='$key' runId=$myRunId")
                        markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                        return
                    }

                    val decorated = if (code != null) "Error($code): $msg" else "Error: $msg"
                    AppLog.e(LiteRtLmLogging.TAG, "LiteRT-LM inference error: key='$key' runId=$myRunId $decorated")
                    markNativeDoneOnce(decorated)
                }
            }

            try {
                if (!hasMm) {
                    if (BuildConfig.DEBUG) {
                        AppLog.i(
                            LiteRtLmLogging.TAG,
                            "LiteRT-LM sendMessageAsync(text): key='$key' runId=$myRunId len=${trimmed.length} preview='${LiteRtLmLogging.safeLogPreview(trimmed)}'"
                        )
                    } else {
                        AppLog.i(LiteRtLmLogging.TAG, "LiteRT-LM sendMessageAsync(text): key='$key' runId=$myRunId len=${trimmed.length}")
                    }
                    conv.sendMessageAsync(trimmed, callback)
                } else {
                    AppLog.i(
                        LiteRtLmLogging.TAG,
                        "LiteRT-LM sendMessageAsync(mm): key='$key' runId=$myRunId textLen=${trimmed.length} images=${images.size} audio=${audioClips.size}"
                    )
                    val contentList = buildContentList(trimmed, images, audioClips)
                    val contentsObj = buildContentsObject(contentList)
                    conv.sendMessageAsync(contentsObj, callback)
                }
                nativeStarted.set(true)
            } catch (e: Exception) {
                val recoverable = LiteRtLmLogging.isConversationNotAliveError(e)
                AppLog.e(LiteRtLmLogging.TAG, "LiteRT-LM sendMessageAsync failed: key='$key' msg=${e.message}", e)

                if (recoverable) {
                    rs.recovering.set(true)
                    rs.lastMessageAtMs.set(SystemClock.elapsedRealtime())

                    AppLog.w(LiteRtLmLogging.TAG, "Recovering from not-alive conversation: key='$key' runId=$myRunId")

                    if (rs.cancelRequested.get()) {
                        AppLog.i(LiteRtLmLogging.TAG, "Recovery aborted due to cancellation: key='$key' runId=$myRunId")
                        markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                        return@launch
                    }

                    val ok = runCatching {
                        LiteRtLmSessionManager.withSessionLock(key, reason = "runInference-recover") {
                            val i2 = LiteRtLmSessionManager.getInstance(key)
                            if (i2 != null) {
                                val cfg = i2.conversationConfigSnapshot
                                runCatching { i2.conversation.close() }
                                delay(POST_TERMINATE_COOLDOWN_MS)
                                val fresh = LiteRtLmSessionManager.createConversationWithRetry(
                                    engine = i2.engine,
                                    cfg = cfg,
                                    key = key,
                                    reason = "runInference-recover",
                                    timeoutMs = 6_500L,
                                )
                                i2.conversation = fresh
                                conv = fresh
                            }
                        }
                    }.isSuccess

                    if (ok) {
                        if (rs.cancelRequested.get()) {
                            AppLog.i(LiteRtLmLogging.TAG, "Recovery retry skipped due to cancellation: key='$key' runId=$myRunId")
                            markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                            return@launch
                        }

                        runCatching {
                            if (!hasMm) {
                                conv.sendMessageAsync(trimmed, callback)
                            } else {
                                val contentList = buildContentList(trimmed, images, audioClips)
                                val contentsObj = buildContentsObject(contentList)
                                conv.sendMessageAsync(contentsObj, callback)
                            }
                        }.onSuccess {
                            AppLog.w(LiteRtLmLogging.TAG, "Recovery retry succeeded: key='$key' runId=$myRunId")
                            nativeStarted.set(true)
                            rs.recovering.set(false)
                        }.onFailure { e2 ->
                            AppLog.e(LiteRtLmLogging.TAG, "Recovery retry failed: key='$key' runId=$myRunId err=${e2.message}", e2)
                            markNativeDoneOnce(LiteRtLmLogging.cleanError(e2.message))
                        }
                    } else {
                        markNativeDoneOnce(LiteRtLmLogging.cleanError(e.message))
                    }
                } else {
                    markNativeDoneOnce(LiteRtLmLogging.cleanError(e.message))
                }
            } finally {
                if (rs.runId.get() == myRunId && rs.terminated.get()) rs.recovering.set(false)
                rs.preparing.set(false)
            }
        }
    }

    /**
     * Best-effort cancellation.
     *
     * Behavior:
     * - Cancels an ACTIVE native stream (or recovery-owned run slot).
     * - If preparing (init/upgrade), records cancelRequested so the upcoming sendMessageAsync is skipped.
     * - If not occupied, sets a short-lived pending cancel for start-race windows.
     */
    internal fun cancel(key: String) {
        ioScope.launch {
            val rs = getRunState(key)
            val nowMs = SystemClock.elapsedRealtime()

            // If we are in preparation phase, record cancellation and let runInference abort naturally.
            if (rs.preparing.get() && !rs.active.get() && !rs.recovering.get()) {
                rs.cancelRequested.set(true)
                rs.pendingCancelAtMs.set(nowMs)
                rs.pendingCancel.set(true)
                AppLog.d(LiteRtLmLogging.TAG, "cancel requested during preparing: key='$key'")
                return@launch
            }

            if (!isRunOccupiedKey(key)) {
                rs.pendingCancelAtMs.set(nowMs)
                rs.pendingCancel.set(true)

                runCatching {
                    val conv = LiteRtLmSessionManager.getInstance(key)?.conversation
                    conv?.cancelProcess()
                }.onFailure { t ->
                    AppLog.w(LiteRtLmLogging.TAG, "cancelProcess() failed (no-active best-effort): key='$key' err=${t.message}", t)
                }

                AppLog.d(LiteRtLmLogging.TAG, "cancel requested (no active/recovering/preparing run): key='$key' (pending TTL ${PENDING_CANCEL_TTL_MS}ms)")
                return@launch
            }

            rs.cancelRequested.set(true)

            val terminator = rs.logicalTerminator.get()
            if (terminator != null) {
                terminator.invoke()
            } else {
                runCatching {
                    val conv = LiteRtLmSessionManager.getInstance(key)?.conversation
                    conv?.cancelProcess()
                }.onFailure {
                    AppLog.w(LiteRtLmLogging.TAG, "cancelProcess() failed in cancel(): key='$key' err=${it.message}", it)
                }
                if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "cancel()", expectedRunId = rs.runId.get())
            }

            if (LiteRtLmLogging.DEBUG_STATE) debugState(key, rs, "cancel")
        }
    }
}