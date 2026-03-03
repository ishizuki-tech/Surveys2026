/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (SLM Warmup)
 *  ---------------------------------------------------------------------
 *  File: SlmWarmup.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.slm.SlmWarmup.Const.CONFIG_ASSET_NAME
import com.negi.surveys.slm.SlmWarmup.Const.TAG
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-scoped warmup orchestrator for on-device SLM.
 *
 * Design:
 * - Prefetch: IO-only page-cache warming (safe early).
 * - Compile: heavier initialization (GPU/driver/delegate compilation) closer to first real usage.
 *
 * Goals:
 * - Reduce startup jank by deferring heavy work.
 * - Keep phases idempotent per process under concurrent call races.
 * - Provide separate state flows for UI gating and debugging.
 */
object SlmWarmup {

    /* ───────────────────────────── Public state models ───────────────────────────── */

    sealed interface PrefetchState {
        val elapsedMs: Long

        data object Idle : PrefetchState { override val elapsedMs: Long = 0L }

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

        data class SkippedNotConfigured(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : PrefetchState
    }

    sealed interface CompileState {
        val elapsedMs: Long

        data object Idle : CompileState { override val elapsedMs: Long = 0L }

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

        data class SkippedNotConfigured(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : CompileState
    }

    /* ───────────────────────────── Configurable warmup options ───────────────────────────── */

    private val warmupSupportImage = AtomicBoolean(false)
    private val warmupSupportAudio = AtomicBoolean(false)
    private val warmupSystemMessageRef = AtomicReference<Message?>(null)
    private val warmupToolsRef = AtomicReference<List<Any>>(emptyList())

    /**
     * Configures conversation options used during compile warmup.
     *
     * This affects the parameters passed into SLM.initializeIfNeeded during warmup.
     */
    fun setWarmupConversationOptions(
        supportImage: Boolean = false,
        supportAudio: Boolean = false,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        warmupSupportImage.set(supportImage)
        warmupSupportAudio.set(supportAudio)
        warmupSystemMessageRef.set(systemMessage)
        warmupToolsRef.set(tools.toList())

        debugLog("warmupOptions: image=$supportImage audio=$supportAudio system=${systemMessage != null} tools=${tools.size}")
    }

    /* ───────────────────────────── Public state flows ───────────────────────────── */

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _prefetchState = MutableStateFlow<PrefetchState>(PrefetchState.Idle)
    private val _compileState = MutableStateFlow<CompileState>(CompileState.Idle)

    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()
    val compileState: StateFlow<CompileState> = _compileState.asStateFlow()

    /* ───────────────────────────── Internal guards/refs ───────────────────────────── */

    private val cachedConfigRef = AtomicReference<com.negi.surveys.config.SurveyConfig?>(null)

    private val prefetchRequested = AtomicBoolean(false)
    private val compileRequested = AtomicBoolean(false)

    private val prefetchRunIdGen = AtomicLong(0L)
    private val compileRunIdGen = AtomicLong(0L)
    private val activePrefetchRunId = AtomicLong(0L)

    private val prefetchJobRef = AtomicReference<Job?>(null)
    private val compileJobRef = AtomicReference<Job?>(null)

    private val compileAfterPrefetchRequested = AtomicBoolean(false)
    private val compileAfterPrefetchJobRef = AtomicReference<Job?>(null)

    private val eglThreadRef = AtomicReference<EglPbufferThread?>(null)

    // Shared resolved target to avoid "prefetch uses A, compile uses B" divergence.
    private val activeResolvedRef = AtomicReference<SlmModelResolver.Resolved?>(null)

    private data class PrefetchCancelRequest(
        val runId: Long,
        val reason: String,
        val requestedAtMs: Long,
    )

    private val prefetchCancelRequestRef = AtomicReference<PrefetchCancelRequest?>(null)

    /* ───────────────────────────── Public API ───────────────────────────── */

    /**
     * Starts prefetch warmup if the model file is present and warmup is configured.
     */
    fun startPrefetchIfConfigured(appContext: Context) {
        val resolved = resolveActiveOrBestEffort(appContext)
        val file = resolved?.file

        if (!isModelFileReady(file)) {
            _prefetchState.value = PrefetchState.SkippedNotConfigured("model file missing or empty")
            debugLog("prefetch: skipped (no usable model file) pid=${Process.myPid()}")
            return
        }

        if (isPrefetchTerminal(_prefetchState.value)) return

        if (!prefetchRequested.compareAndSet(false, true)) {
            debugLog("prefetch: ignored (already requested) pid=${Process.myPid()}")
            return
        }

        val existing = prefetchJobRef.get()
        if (existing != null && existing.isActive) {
            debugLog("prefetch: ignored (job already active) pid=${Process.myPid()}")
            return
        }

        val runId = prefetchRunIdGen.incrementAndGet()
        activePrefetchRunId.set(runId)
        prefetchCancelRequestRef.set(null)

        val job = scope.launch(Dispatchers.IO) {
            try {
                runPrefetch(file!!, runId = runId)
            } catch (ce: CancellationException) {
                setPrefetchCancelled(nowMs = SystemClock.elapsedRealtime())
                debugLog("prefetch: cancelled pid=${Process.myPid()} runId=$runId")
                throw ce
            } catch (t: Throwable) {
                setPrefetchFailed(t, nowMs = SystemClock.elapsedRealtime())
                debugLog("prefetch: failed pid=${Process.myPid()} runId=$runId err=${t.javaClass.simpleName}(${t.message})")
            }
        }

        prefetchJobRef.set(job)
        debugLog("prefetch: started pid=${Process.myPid()} runId=$runId file='${file!!.name}' size=${file.length()}")
    }

    /**
     * Starts compile warmup if the model file is present and warmup is configured.
     */
    fun startCompileIfConfigured(appContext: Context) {
        val resolved = resolveActiveOrBestEffort(appContext)
        val file = resolved?.file
        val model = resolved?.model

        if (!isModelFileReady(file) || model == null) {
            _compileState.value = CompileState.SkippedNotConfigured("model file missing or empty")
            debugLog("compile: skipped (no usable model file) pid=${Process.myPid()}")
            return
        }

        if (isCompileTerminal(_compileState.value)) return

        if (!compileRequested.compareAndSet(false, true)) {
            debugLog("compile: ignored (already requested) pid=${Process.myPid()}")
            return
        }

        val existing = compileJobRef.get()
        if (existing != null && existing.isActive) {
            debugLog("compile: ignored (job already active) pid=${Process.myPid()}")
            return
        }

        val runId = compileRunIdGen.incrementAndGet()

        val job = scope.launch(Dispatchers.Default) {
            try {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {
                    // Best effort.
                }

                // Ensure prefetch uses the same file as compile.
                startPrefetchIfConfigured(appContext)

                runCompile(file = file!!, model = model, appContext = appContext, runId = runId)
            } catch (ce: CancellationException) {
                setCompileCancelled(nowMs = SystemClock.elapsedRealtime())
                debugLog("compile: cancelled pid=${Process.myPid()} runId=$runId")
                throw ce
            } catch (t: Throwable) {
                setCompileFailed(t, nowMs = SystemClock.elapsedRealtime())
                debugLog("compile: failed pid=${Process.myPid()} runId=$runId err=${t.javaClass.simpleName}(${t.message})")
            }
        }

        compileJobRef.set(job)
        debugLog("compile: started pid=${Process.myPid()} runId=$runId file='${file!!.name}'")
    }

    /** Convenience method that triggers both phases. */
    fun startWarmupIfConfigured(appContext: Context) {
        startPrefetchIfConfigured(appContext)
        startCompileIfConfigured(appContext)
    }

    /**
     * Requests compile warmup to start after prefetch reaches a terminal state.
     */
    fun requestCompileAfterPrefetch(
        appContext: Context,
        reason: String = "autoAfterPrefetch",
    ) {
        if (!compileAfterPrefetchRequested.compareAndSet(false, true)) {
            debugLog("autoCompileAfterPrefetch: ignored (already requested) reason='$reason'")
            return
        }

        debugLog("autoCompileAfterPrefetch: requested reason='$reason'")

        startPrefetchIfConfigured(appContext)

        val job = scope.launch(Dispatchers.Default) {
            val waitedForPrefetch = withTimeoutOrNull(Const.COMPILE_AFTER_PREFETCH_MAX_WAIT_MS) {
                prefetchState.first { isPrefetchTerminal(it) }
            } != null

            delay(Const.AUTO_COMPILE_DELAY_MS)

            startCompileIfConfigured(appContext)

            debugLog("autoCompileAfterPrefetch: triggered reason='$reason' waitedPrefetch=$waitedForPrefetch")
        }

        compileAfterPrefetchJobRef.getAndSet(job)?.cancel()
    }

    /**
     * Cancels all warmup work and updates state flows best-effort.
     */
    fun cancelAll(reason: String = "cancelAll") {
        debugLog("cancelAll: reason='$reason' pid=${Process.myPid()}")

        compileAfterPrefetchJobRef.getAndSet(null)?.cancel()
        compileAfterPrefetchRequested.set(false)

        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = activePrefetchRunId.get(),
                reason = "cancelAll:$reason",
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        prefetchJobRef.getAndSet(null)?.cancel()
        compileJobRef.getAndSet(null)?.cancel()

        val now = SystemClock.elapsedRealtime()

        if (!isPrefetchTerminal(_prefetchState.value)) {
            setPrefetchCancelled(nowMs = now)
        }
        if (!isCompileTerminal(_compileState.value)) {
            setCompileCancelled(nowMs = now)
        }

        closeEglThread(reason = "cancelAll:$reason")
    }

    /**
     * Resets internal guards and state to allow re-running warmup within the same process.
     */
    fun resetForRetry(reason: String = "resetForRetry") {
        cancelAll(reason = "resetForRetry:$reason")

        compileAfterPrefetchJobRef.getAndSet(null)?.cancel()
        compileAfterPrefetchRequested.set(false)

        prefetchRequested.set(false)
        compileRequested.set(false)

        prefetchRunIdGen.set(0L)
        compileRunIdGen.set(0L)
        activePrefetchRunId.set(0L)

        cachedConfigRef.set(null)
        prefetchCancelRequestRef.set(null)
        activeResolvedRef.set(null)

        _prefetchState.value = PrefetchState.Idle
        _compileState.value = CompileState.Idle

        debugLog("resetForRetry: done reason='$reason' pid=${Process.myPid()}")
    }

    /* ───────────────────────────── Prefetch implementation ───────────────────────────── */

    private suspend fun runPrefetch(file: File, runId: Long) {
        val startedAtMs = SystemClock.elapsedRealtime()

        val fileSize = file.length().coerceAtLeast(0L)
        val targetTotal = fileSize.coerceAtMost(Const.PREFETCH_BUDGET_BYTES)
        val total = targetTotal.takeIf { it > 0L }

        _prefetchState.value = PrefetchState.Running(
            file = file,
            startedAtMs = startedAtMs,
            downloaded = 0L,
            total = total,
            elapsedMs = 0L,
        )

        val buf = ByteArray(Const.PREFETCH_BUF_SIZE)
        var readTotal = 0L
        var lastEmitAt = startedAtMs

        FileInputStream(file).use { fis ->
            while (true) {
                coroutineContext.ensureActive()

                val n = fis.read(buf)
                if (n <= 0) break

                readTotal += n.toLong()
                if (total != null && readTotal >= total) break

                val now = SystemClock.elapsedRealtime()
                if (now - lastEmitAt >= Const.PREFETCH_EMIT_INTERVAL_MS) {
                    _prefetchState.value = PrefetchState.Running(
                        file = file,
                        startedAtMs = startedAtMs,
                        downloaded = readTotal,
                        total = total,
                        elapsedMs = now - startedAtMs,
                    )
                    lastEmitAt = now
                }
            }
        }

        val end = SystemClock.elapsedRealtime()

        if (!coroutineContext.isActive) {
            val req = prefetchCancelRequestRef.get()
            val reason = if (req != null && req.runId == runId) req.reason else "unknown"

            _prefetchState.value = PrefetchState.Cancelled(
                startedAtMs = startedAtMs,
                elapsedMs = end - startedAtMs,
            )

            if (req != null && req.runId == runId) {
                prefetchCancelRequestRef.compareAndSet(req, null)
            }

            debugLog(
                "prefetch: cancelled(pid=${Process.myPid()} runId=$runId) reason='$reason' " +
                        "readBytes=$readTotal target=${total ?: -1} fileSize=$fileSize elapsedMs=${end - startedAtMs}",
            )
            return
        }

        _prefetchState.value = PrefetchState.Prefetched(
            file = file,
            sizeBytes = readTotal,
            startedAtMs = startedAtMs,
            elapsedMs = end - startedAtMs,
        )

        debugLog(
            "prefetch: completed pid=${Process.myPid()} runId=$runId file='${file.name}' " +
                    "readBytes=$readTotal target=${total ?: -1} fileSize=$fileSize elapsedMs=${end - startedAtMs}",
        )
    }

    private fun cancelPrefetchIfRunning(reason: String) {
        val st = _prefetchState.value
        if (st !is PrefetchState.Running) return

        val runId = activePrefetchRunId.get()
        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = runId,
                reason = reason,
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        prefetchJobRef.getAndSet(null)?.cancel()
        debugLog("prefetch: cancel requested pid=${Process.myPid()} runId=$runId reason='$reason'")
    }

    private suspend fun awaitOrCancelPrefetchBeforeCompile(runId: Long): Long {
        val st = _prefetchState.value
        if (st !is PrefetchState.Running) return 0L

        val remainingBytes = st.total?.let { total -> (total - st.downloaded).coerceAtLeast(0L) }
        val waitStartMs = SystemClock.elapsedRealtime()

        val shouldWaitForFinish =
            remainingBytes != null && remainingBytes <= Const.PREFETCH_FINISH_WAIT_REMAINING_BYTES

        if (shouldWaitForFinish) {
            debugLog(
                "compile: waiting briefly for prefetch to finish pid=${Process.myPid()} runId=$runId " +
                        "remainingBytes=$remainingBytes",
            )

            val finished = withTimeoutOrNull(Const.PREFETCH_FINISH_WAIT_TIMEOUT_MS) {
                prefetchState.first { isPrefetchTerminal(it) }
            } != null

            if (!finished) {
                debugLog("compile: prefetch wait timeout; cancelling to reduce IO contention pid=${Process.myPid()} runId=$runId")
                cancelPrefetchIfRunning(reason = "compile-wait-timeout")
                withTimeoutOrNull(Const.PREFETCH_CANCEL_JOIN_TIMEOUT_MS) {
                    prefetchState.first { isPrefetchTerminal(it) }
                }
            }
        } else {
            debugLog("compile: cancelling prefetch to reduce IO contention pid=${Process.myPid()} runId=$runId")
            cancelPrefetchIfRunning(reason = "compile-start")
            withTimeoutOrNull(Const.PREFETCH_CANCEL_JOIN_TIMEOUT_MS) {
                prefetchState.first { isPrefetchTerminal(it) }
            }
        }

        return SystemClock.elapsedRealtime() - waitStartMs
    }

    /* ───────────────────────────── Compile implementation ───────────────────────────── */

    private suspend fun runCompile(
        file: File,
        model: Model,
        appContext: Context,
        runId: Long,
    ) = coroutineScope {
        val prefetchRunning = _prefetchState.value is PrefetchState.Running
        if (prefetchRunning) {
            val requestedAtMs = SystemClock.elapsedRealtime()
            _compileState.value = CompileState.WaitingForPrefetch(
                file = file,
                requestedAtMs = requestedAtMs,
                elapsedMs = 0L,
            )
        }

        val ticker = launch(Dispatchers.Default) {
            while (isActive) {
                delay(Const.COMPILE_EMIT_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                when (val st = _compileState.value) {
                    is CompileState.WaitingForPrefetch -> _compileState.value = st.copy(elapsedMs = now - st.requestedAtMs)
                    is CompileState.Compiling -> _compileState.value = st.copy(elapsedMs = now - st.startedAtMs)
                    else -> break
                }
            }
        }

        var prefetchWaitMs = 0L
        try {
            if (prefetchRunning) {
                prefetchWaitMs = awaitOrCancelPrefetchBeforeCompile(runId = runId)
            } else {
                cancelPrefetchIfRunning(reason = "compile-start")
            }

            val startedAtMs = SystemClock.elapsedRealtime()
            _compileState.value = CompileState.Compiling(
                file = file,
                startedAtMs = startedAtMs,
                elapsedMs = 0L,
            )

            debugLog(
                "compile: begin pid=${Process.myPid()} runId=$runId file='${file.name}' " +
                        "prefetchWaitMs=$prefetchWaitMs model='${model.name}'",
            )

            runCompileViaEglIfPossible(appContext = appContext, model = model, runId = runId)

            val end = SystemClock.elapsedRealtime()
            _compileState.value = CompileState.Compiled(
                file = file,
                startedAtMs = startedAtMs,
                elapsedMs = end - startedAtMs,
            )

            debugLog(
                "compile: end pid=${Process.myPid()} runId=$runId elapsedMs=${end - startedAtMs} " +
                        "prefetchWaitMs=$prefetchWaitMs file='${file.name}'",
            )
        } finally {
            runCatching { ticker.cancel() }
            closeEglThread(reason = "compileDone")
        }
    }

    private suspend fun runCompileViaEglIfPossible(
        appContext: Context,
        model: Model,
        runId: Long,
    ) {
        val appCtx = appContext.applicationContext

        // Ensure SLM has an application context.
        SLM.setApplicationContext(appCtx)

        val supportImage = warmupSupportImage.get()
        val supportAudio = warmupSupportAudio.get()
        val systemMessage = warmupSystemMessageRef.get()
        val tools = warmupToolsRef.get()

        debugLog(
            "compile: init options pid=${Process.myPid()} runId=$runId " +
                    "image=$supportImage audio=$supportAudio system=${systemMessage != null} tools=${tools.size}",
        )

        val thread = ensureEglThread(runId = runId)
        if (thread == null) {
            debugLog("compile: egl thread unavailable; running initializeIfNeeded directly pid=${Process.myPid()} runId=$runId")
            SLM.initializeIfNeeded(
                context = appCtx,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
            )
            return
        }

        try {
            debugLog("compile: entering egl context pid=${Process.myPid()} runId=$runId")
            thread.withEglContext {
                SLM.initializeIfNeeded(
                    context = appCtx,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    systemMessage = systemMessage,
                    tools = tools,
                )
            }
        } catch (t: Throwable) {
            debugLog(
                "compile: egl warmup failed; retrying direct pid=${Process.myPid()} runId=$runId " +
                        "err=${t.javaClass.simpleName}(${t.message})",
            )
            SLM.initializeIfNeeded(
                context = appCtx,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
            )
        }
    }

    private fun ensureEglThread(runId: Long): EglPbufferThread? {
        val existing = eglThreadRef.get()
        if (existing != null) return existing

        val created = runCatching { EglPbufferThread(threadName = "SlmWarmupEgl") }.getOrNull()
            ?: return null

        return if (eglThreadRef.compareAndSet(null, created)) {
            debugLog("eglThread: created and installed pid=${Process.myPid()} runId=$runId")
            created
        } else {
            runCatching { created.close() }
            debugLog("eglThread: CAS lost; closed extra instance pid=${Process.myPid()} runId=$runId")
            eglThreadRef.get()
        }
    }

    private fun closeEglThread(reason: String) {
        val thread = eglThreadRef.getAndSet(null) ?: return
        runCatching { thread.close() }
            .onFailure { t ->
                AppLog.w(Const.TAG, "eglThread close failed: reason='$reason' err=${t.message}", t)
            }
        debugLog("eglThread closed: reason='$reason'")
    }

    /* ───────────────────────────── Config + resolve ───────────────────────────── */


    private fun getConfigBestEffort(appContext: Context): SurveyConfig? {
        // Prefer the process-installed config from SurveyAppRoot (single source of truth).
        SurveyConfigLoader.getInstalledConfigOrNull()?.let { cfg ->
            cachedConfigRef.set(cfg)
            return cfg
        }

        val existing = cachedConfigRef.get()
        if (existing != null) return existing

        val loaded = runCatching {
            SurveyConfigLoader.fromAssetsValidated(appContext.applicationContext, CONFIG_ASSET_NAME)
        }.onFailure { t ->
            SafeLog.e(TAG, "getConfigBestEffort: load failed type=${t::class.java.simpleName}", t)
        }.getOrNull()

        if (loaded != null) cachedConfigRef.set(loaded)
        return loaded
    }

    private fun resolveActiveOrBestEffort(appContext: Context): SlmModelResolver.Resolved? {
        val active = activeResolvedRef.get()
        if (active != null && isModelFileReady(active.file)) return active

        val cfg = getConfigBestEffort(appContext)
        val resolved = SlmModelResolver.resolve(
            appContext = appContext.applicationContext,
            config = cfg,
            strict = false,
            fallbackModelFileName = null,
        )

        if (resolved == null || !isModelFileReady(resolved.file)) {
            debugLog("resolve: failed (no usable model file) pid=${Process.myPid()}")
            return null
        }

        activeResolvedRef.set(resolved)
        debugLog("resolve: ok file='${resolved.file.name}' size=${resolved.file.length()} model='${resolved.model.name}'")
        return resolved
    }

    private fun isModelFileReady(file: File?): Boolean {
        if (file == null) return false
        if (!file.exists() || !file.isFile) return false
        return file.length() > 0L
    }

    /* ───────────────────────────── State helpers ───────────────────────────── */

    private fun setPrefetchCancelled(nowMs: Long) {
        val startedAt = startedAtForPrefetch(_prefetchState.value)
        _prefetchState.value = PrefetchState.Cancelled(
            startedAtMs = startedAt,
            elapsedMs = elapsedSince(startedAt, nowMs),
        )
    }

    private fun setPrefetchFailed(t: Throwable, nowMs: Long) {
        val startedAt = startedAtForPrefetch(_prefetchState.value)
        _prefetchState.value = PrefetchState.Failed(
            message = "${t.javaClass.simpleName}(${t.message})",
            startedAtMs = startedAt,
            elapsedMs = elapsedSince(startedAt, nowMs),
        )
    }

    private fun setCompileCancelled(nowMs: Long) {
        val startedAt = startedAtForCompile(_compileState.value)
        _compileState.value = CompileState.Cancelled(
            startedAtMs = startedAt,
            elapsedMs = elapsedSince(startedAt, nowMs),
        )
    }

    private fun setCompileFailed(t: Throwable, nowMs: Long) {
        val startedAt = startedAtForCompile(_compileState.value)
        _compileState.value = CompileState.Failed(
            message = "${t.javaClass.simpleName}(${t.message})",
            startedAtMs = startedAt,
            elapsedMs = elapsedSince(startedAt, nowMs),
        )
    }

    private fun startedAtForPrefetch(state: PrefetchState): Long? {
        return when (state) {
            is PrefetchState.Running -> state.startedAtMs
            is PrefetchState.Prefetched -> state.startedAtMs
            is PrefetchState.Failed -> state.startedAtMs
            is PrefetchState.Cancelled -> state.startedAtMs
            else -> null
        }
    }

    private fun startedAtForCompile(state: CompileState): Long? {
        return when (state) {
            is CompileState.Compiling -> state.startedAtMs
            is CompileState.Compiled -> state.startedAtMs
            is CompileState.Failed -> state.startedAtMs
            is CompileState.Cancelled -> state.startedAtMs
            else -> null
        }
    }

    private fun elapsedSince(startedAtMs: Long?, nowMs: Long): Long {
        return if (startedAtMs == null) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)
    }

    /**
     * Terminal means "no further progress without a new request/reset".
     */
    private fun isPrefetchTerminal(state: PrefetchState): Boolean {
        return when (state) {
            is PrefetchState.Prefetched,
            is PrefetchState.Failed,
            is PrefetchState.Cancelled,
            is PrefetchState.SkippedNotConfigured -> true
            else -> false
        }
    }

    /**
     * Terminal means "no further progress without a new request/reset".
     */
    private fun isCompileTerminal(state: CompileState): Boolean {
        return when (state) {
            is CompileState.Compiled,
            is CompileState.Failed,
            is CompileState.Cancelled,
            is CompileState.SkippedNotConfigured -> true
            else -> false
        }
    }

    private fun debugLog(msg: String) {
        AppLog.d(Const.TAG, msg)
    }

    /* ───────────────────────────── EGL helper thread ───────────────────────────── */

    /**
     * Single-thread EGL owner with a tiny pbuffer surface.
     *
     * Purpose:
     * - Some GPU stacks expect a current EGL context during initialization.
     * - This provides a best-effort place to run "compile warmup" code.
     *
     * Behavior:
     * - If EGL init fails, work still runs on the dedicated thread, but without a guaranteed current context.
     */
    class EglPbufferThread(
        threadName: String = "slm-egl",
        private val glesVersion: Int = 2,
    ) : Closeable {

        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, threadName).apply { isDaemon = true }
        }

        private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

        /** Coroutine context bound to the dedicated EGL thread. */
        val coroutineContext: CoroutineContext get() = dispatcher

        private val initGate = CompletableDeferred<Unit>()
        private val stateRef = AtomicReference<EglState?>(null)

        private val loggedInitFailure = AtomicBoolean(false)
        private val loggedNoContext = AtomicBoolean(false)

        init {
            executor.execute {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {
                    // Best effort.
                }

                try {
                    stateRef.set(createEglState(glesVersion))
                } catch (t: Throwable) {
                    stateRef.set(null)
                    if (loggedInitFailure.compareAndSet(false, true)) {
                        AppLog.w(
                            EGL_TAG,
                            "EGL init failed (will run without current context): glesVersion=$glesVersion " +
                                    "err=${t.javaClass.simpleName}(${t.message})",
                        )
                    }
                } finally {
                    initGate.complete(Unit)
                }
            }
        }

        /**
         * Runs [block] on the EGL worker thread after initialization completes.
         *
         * If EGL init failed, the block still runs on the worker thread, but without a guaranteed current EGL context.
         */
        suspend fun <T> withEglContext(block: suspend () -> T): T {
            return withContext(dispatcher) {
                initGate.await()

                val state = stateRef.get()
                if (state == null) {
                    if (loggedNoContext.compareAndSet(false, true)) {
                        AppLog.w(EGL_TAG, "EGL context unavailable; executing block without eglMakeCurrent.")
                    }
                    return@withContext block()
                }

                runCatching {
                    EGL14.eglMakeCurrent(state.display, state.surface, state.surface, state.context)
                }.onFailure {
                    if (loggedNoContext.compareAndSet(false, true)) {
                        AppLog.w(
                            EGL_TAG,
                            "eglMakeCurrent failed; executing block anyway: err=${it.javaClass.simpleName}(${it.message})",
                        )
                    }
                }

                block()
            }
        }

        override fun close() {
            executor.execute {
                runCatching { stateRef.getAndSet(null)?.close() }
            }
            executor.shutdown()
            runCatching { dispatcher.close() }
        }

        private data class EglState(
            val display: EGLDisplay,
            val surface: EGLSurface,
            val context: EGLContext,
        ) : Closeable {
            override fun close() {
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                runCatching { EGL14.eglDestroySurface(display, surface) }
                runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglTerminate(display) }
            }
        }

        private fun createEglState(glesVersion: Int): EglState {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            var context: EGLContext = EGL14.EGL_NO_CONTEXT

            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                require(display != EGL14.EGL_NO_DISPLAY) { "EGL display not available." }

                val version = IntArray(2)
                require(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed." }

                runCatching { EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API) }

                val renderable = if (glesVersion >= 3) EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT

                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, renderable,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )

                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                require(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) {
                    "eglChooseConfig failed."
                }
                require(numConfigs[0] > 0) { "No EGLConfig found." }
                val config = requireNotNull(configs[0]) { "EGLConfig was null." }

                val ctxAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, glesVersion,
                    EGL14.EGL_NONE,
                )

                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                if (context == EGL14.EGL_NO_CONTEXT && glesVersion >= 3) {
                    val ctxAttribs2 = intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE,
                    )
                    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs2, 0)
                }
                require(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed." }

                val pbufferAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
                )
                surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
                require(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed." }

                require(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed." }

                return EglState(display, surface, context)
            } catch (t: Throwable) {
                runCatching {
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        EGL14.eglTerminate(display)
                    }
                }
                throw t
            }
        }

        private companion object {
            private const val EGL_TAG = "EglPbufferThread"

            /** Khronos EGL_OPENGL_ES3_BIT_KHR = 0x00000040 */
            private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
        }
    }

    private object Const {
        const val TAG: String = "SlmWarmup"
        const val CONFIG_ASSET_NAME: String = "survey.yaml"

        const val PREFETCH_BUDGET_BYTES: Long = 512L * 1024L * 1024L
        const val PREFETCH_BUF_SIZE: Int = 4 * 1024 * 1024
        const val PREFETCH_EMIT_INTERVAL_MS: Long = 250L

        const val PREFETCH_FINISH_WAIT_TIMEOUT_MS: Long = 1_500L
        const val PREFETCH_CANCEL_JOIN_TIMEOUT_MS: Long = 1_000L
        const val PREFETCH_FINISH_WAIT_REMAINING_BYTES: Long = 32L * 1024L * 1024L

        const val COMPILE_EMIT_INTERVAL_MS: Long = 250L
        const val AUTO_COMPILE_DELAY_MS: Long = 120L
        const val COMPILE_AFTER_PREFETCH_MAX_WAIT_MS: Long = 15_000L
    }
}