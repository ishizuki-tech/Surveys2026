/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (SLM Warmup Engine)
 *  ---------------------------------------------------------------------
 *  File: SLMWarmupEngine.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.warmup

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.logging.AppLog
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default (real) engine for [WarmupController].
 *
 * Properties:
 * - Idempotent under concurrent call races.
 * - Prefetch (IO cache warming), Compile (heavy init), and Runtime warm phases.
 * - Best-effort EGL context warmup (optional).
 *
 * Key invariants:
 * - Public entry points MUST NOT block the caller thread.
 * - This engine MUST NOT throw.
 * - "Not ready yet" is treated as retryable.
 *
 * Key change:
 * - Implements [WarmupController.RuntimeAwareEngine].
 * - Compile warmup and runtime hot preparation are tracked separately.
 * - Runtime warmup only becomes effective when the repository implements
 *   [WarmupController.RuntimeWarmCapableRepository].
 */
class SLMWarmupEngine(
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) },
    externalScope: CoroutineScope? = null,
) : WarmupController.RuntimeAwareEngine {

    private val scope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val warmupDispatcher: CoroutineDispatcher = WarmupDispatchers.background
    private val prefetchDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _prefetchState =
        MutableStateFlow<WarmupController.PrefetchState>(WarmupController.PrefetchState.Idle)

    private val _compileState =
        MutableStateFlow<WarmupController.CompileState>(WarmupController.CompileState.Idle)

    private val _runtimeWarmState =
        MutableStateFlow<WarmupController.RuntimeWarmState>(WarmupController.RuntimeWarmState.Idle)

    override val prefetchState: StateFlow<WarmupController.PrefetchState> =
        _prefetchState.asStateFlow()

    override val compileState: StateFlow<WarmupController.CompileState> =
        _compileState.asStateFlow()

    override val runtimeWarmState: StateFlow<WarmupController.RuntimeWarmState> =
        _runtimeWarmState.asStateFlow()

    private val prefetchRunIdGen = AtomicLong(0L)
    private val compileRunIdGen = AtomicLong(0L)
    private val runtimeRunIdGen = AtomicLong(0L)

    private val activePrefetchRunId = AtomicLong(0L)

    private val prefetchJobRef = AtomicReference<Job?>(null)
    private val compileJobRef = AtomicReference<Job?>(null)
    private val runtimeWarmJobRef = AtomicReference<Job?>(null)

    private val compileAfterPrefetchJobRef = AtomicReference<Job?>(null)
    private val runtimeAfterCompileJobRef = AtomicReference<Job?>(null)

    private val eglThreadRef = AtomicReference<EglPbufferThread?>(null)

    /**
     * Latest inputs provided by the app. Can be null until ready.
     *
     * IMPORTANT:
     * - Engine must treat null as retryable "not ready".
     * - Never log file path or exception messages.
     */
    private val inputsRef = AtomicReference<WarmupController.Inputs?>(null)

    /**
     * Application context snapshot captured from the latest public call.
     *
     * Rationale:
     * - [updateInputs] does not carry context, but we may need to trigger pending work
     *   when inputs become ready later.
     * - Store applicationContext only to avoid leaking Activity references.
     */
    private val appContextRef = AtomicReference<Context?>(null)

    private data class PendingAutoCompile(
        val requestId: Long,
        val reason: String,
        val requestedAtMs: Long,
    )

    private data class PendingAutoRuntimeWarm(
        val requestId: Long,
        val reason: String,
        val requestedAtMs: Long,
    )

    private val autoCompileRequestIdGen = AtomicLong(0L)
    private val autoRuntimeRequestIdGen = AtomicLong(0L)

    private val pendingAutoCompileRef = AtomicReference<PendingAutoCompile?>(null)
    private val pendingAutoRuntimeRef = AtomicReference<PendingAutoRuntimeWarm?>(null)

    /**
     * Safe logger wrapper.
     */
    private fun log(msg: String) {
        runCatching { logger(msg) }
    }

    override fun updateInputs(inputs: WarmupController.Inputs?) {
        inputsRef.set(inputs)
        log("inputs: updated pid=${Process.myPid()} hasInputs=${inputs != null}")

        triggerPendingAutoCompileIfPossible(trigger = "updateInputs")
        triggerPendingAutoRuntimeIfPossible(trigger = "updateInputs")
    }

    /**
     * Attempts to run a previously requested auto-compile once inputs become ready.
     *
     * IMPORTANT:
     * - Never blocks the caller thread.
     * - If inputs are still not ready, keeps the pending request as-is.
     */
    private fun triggerPendingAutoCompileIfPossible(trigger: String) {
        val pending = pendingAutoCompileRef.get() ?: return
        val ctx = appContextRef.get() ?: return

        val inputs = inputsRef.get()
        val ready = inputs != null && isModelFileReady(inputs.file)
        if (!ready) return

        if (!pendingAutoCompileRef.compareAndSet(pending, null)) return

        val safeReason = safeReasonForLogs(pending.reason)
        log(
            "autoCompileAfterPrefetch: inputs ready -> triggering pid=${Process.myPid()} " +
                    "requestId=${pending.requestId} via='$trigger' reason='$safeReason'",
        )

        scope.launch(warmupDispatcher) {
            triggerAutoCompileNow(ctx = ctx, safeReason = safeReason)
        }
    }

    /**
     * Attempts to run a previously requested runtime warm once inputs and compile become ready.
     *
     * IMPORTANT:
     * - Never blocks the caller thread.
     * - If prerequisites are still not ready, keeps the pending request as-is.
     */
    private fun triggerPendingAutoRuntimeIfPossible(trigger: String) {
        val pending = pendingAutoRuntimeRef.get() ?: return
        val ctx = appContextRef.get() ?: return

        val inputs = inputsRef.get() ?: return
        val fileReady = isModelFileReady(inputs.file)
        val runtimeCapable = inputs.repository is WarmupController.RuntimeWarmCapableRepository
        val compileReady = _compileState.value is WarmupController.CompileState.Compiled

        if (!fileReady || !runtimeCapable || !compileReady) return

        if (!pendingAutoRuntimeRef.compareAndSet(pending, null)) return

        val safeReason = safeReasonForLogs(pending.reason)
        log(
            "autoRuntimeAfterCompile: prerequisites ready -> triggering pid=${Process.myPid()} " +
                    "requestId=${pending.requestId} via='$trigger' reason='$safeReason'",
        )

        scope.launch(warmupDispatcher) {
            triggerAutoRuntimeWarmNow(ctx = ctx, safeReason = safeReason)
        }
    }

    private suspend fun triggerAutoCompileNow(ctx: Context, safeReason: String) {
        startPrefetch(ctx)

        withTimeoutOrNull(Const.PREFETCH_FINISH_WAIT_TIMEOUT_MS) {
            prefetchState.first { isPrefetchTerminalOrSkipped(it) }
        }

        currentCoroutineContext().ensureActive()
        delay(Const.AUTO_COMPILE_DELAY_MS)
        currentCoroutineContext().ensureActive()

        startCompile(ctx)

        log("autoCompileAfterPrefetch: triggered pid=${Process.myPid()} reason='$safeReason'")
    }

    private suspend fun triggerAutoRuntimeWarmNow(ctx: Context, safeReason: String) {
        currentCoroutineContext().ensureActive()
        delay(Const.AUTO_RUNTIME_DELAY_MS)
        currentCoroutineContext().ensureActive()

        startRuntimeWarm(ctx)

        log("autoRuntimeAfterCompile: triggered pid=${Process.myPid()} reason='$safeReason'")
    }

    override fun startPrefetch(appContext: Context) {
        val ctx = appContext.applicationContext
        appContextRef.set(ctx)

        if (_prefetchState.value is WarmupController.PrefetchState.Prefetched) return

        val existing = prefetchJobRef.get()
        if (existing?.isActive == true) return

        val runId = prefetchRunIdGen.incrementAndGet()

        val job =
            scope.launch(prefetchDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val inputs = inputsRef.get()
                    if (inputs == null) {
                        _prefetchState.value =
                            WarmupController.PrefetchState.SkippedNotReady("inputs missing")
                        log("prefetch: skipped(inputs missing) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    val file = inputs.file
                    if (!isModelFileReady(file)) {
                        _prefetchState.value =
                            WarmupController.PrefetchState.SkippedNotReady("file missing/empty")
                        log("prefetch: skipped(file missing/empty) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    runPrefetch(file = file!!, runId = runId)
                } catch (ce: CancellationException) {
                    setPrefetchCancelled(nowMs = SystemClock.elapsedRealtime())
                    val req = prefetchCancelRequestRef.get()
                    val why = if (req != null && req.runId == runId) req.reason else "cancelled"
                    log(
                        "prefetch: cancelled pid=${Process.myPid()} runId=$runId " +
                                "reason='${safeReasonForLogs(why)}'",
                    )
                    throw ce
                } catch (t: Throwable) {
                    setPrefetchFailed(t, nowMs = SystemClock.elapsedRealtime())
                    log("prefetch: failed pid=${Process.myPid()} runId=$runId err=${t.javaClass.simpleName}")
                }
            }

        if (!prefetchJobRef.compareAndSet(existing, job)) {
            job.cancel()
            return
        }

        activePrefetchRunId.set(runId)
        prefetchCancelRequestRef.set(null)

        job.invokeOnCompletion {
            prefetchJobRef.compareAndSet(job, null)
        }

        log("prefetch: scheduled pid=${Process.myPid()} runId=$runId")
        job.start()
    }

    override fun startCompile(appContext: Context) {
        val ctx = appContext.applicationContext
        appContextRef.set(ctx)

        if (_compileState.value is WarmupController.CompileState.Compiled) {
            triggerPendingAutoRuntimeIfPossible(trigger = "startCompileAlreadyCompiled")
            return
        }

        val existing = compileJobRef.get()
        if (existing?.isActive == true) return

        val runId = compileRunIdGen.incrementAndGet()
        val job =
            scope.launch(warmupDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val inputs = inputsRef.get()
                    if (inputs == null) {
                        _compileState.value =
                            WarmupController.CompileState.SkippedNotReady("inputs missing")
                        log("compile: skipped(inputs missing) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    val file = inputs.file
                    if (!isModelFileReady(file)) {
                        _compileState.value =
                            WarmupController.CompileState.SkippedNotReady("file missing/empty")
                        log("compile: skipped(file missing/empty) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    startPrefetch(ctx)

                    runCompile(
                        file = file!!,
                        repo = inputs.repository,
                        opts = inputs.options,
                        appContext = ctx,
                        runId = runId,
                    )
                } catch (ce: CancellationException) {
                    setCompileCancelled(nowMs = SystemClock.elapsedRealtime())
                    log("compile: cancelled pid=${Process.myPid()} runId=$runId")
                    throw ce
                } catch (t: Throwable) {
                    setCompileFailed(t, nowMs = SystemClock.elapsedRealtime())
                    log("compile: failed pid=${Process.myPid()} runId=$runId err=${t.javaClass.simpleName}")
                } finally {
                    closeEglThread(reason = "compileDone")
                }
            }

        if (!compileJobRef.compareAndSet(existing, job)) {
            job.cancel()
            return
        }

        job.invokeOnCompletion {
            compileJobRef.compareAndSet(job, null)
        }

        log("compile: scheduled pid=${Process.myPid()} runId=$runId")
        job.start()
    }

    override fun startRuntimeWarm(appContext: Context) {
        val ctx = appContext.applicationContext
        appContextRef.set(ctx)

        if (_runtimeWarmState.value is WarmupController.RuntimeWarmState.Ready) return

        val existing = runtimeWarmJobRef.get()
        if (existing?.isActive == true) return

        val runId = runtimeRunIdGen.incrementAndGet()
        val job =
            scope.launch(warmupDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val inputs = inputsRef.get()
                    if (inputs == null) {
                        _runtimeWarmState.value =
                            WarmupController.RuntimeWarmState.SkippedNotReady("inputs missing")
                        log("runtimeWarm: skipped(inputs missing) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    val file = inputs.file
                    if (!isModelFileReady(file)) {
                        _runtimeWarmState.value =
                            WarmupController.RuntimeWarmState.SkippedNotReady("file missing/empty")
                        log("runtimeWarm: skipped(file missing/empty) pid=${Process.myPid()} runId=$runId")
                        return@launch
                    }

                    val runtimeRepo =
                        inputs.repository as? WarmupController.RuntimeWarmCapableRepository
                    if (runtimeRepo == null) {
                        _runtimeWarmState.value =
                            WarmupController.RuntimeWarmState.SkippedNotReady(
                                "repository not runtime-capable",
                            )
                        log(
                            "runtimeWarm: skipped(repository not runtime-capable) " +
                                    "pid=${Process.myPid()} runId=$runId",
                        )
                        return@launch
                    }

                    runRuntimeWarm(
                        file = file!!,
                        repo = runtimeRepo,
                        opts = inputs.options,
                        appContext = ctx,
                        runId = runId,
                    )
                } catch (ce: CancellationException) {
                    setRuntimeWarmCancelled(nowMs = SystemClock.elapsedRealtime())
                    log("runtimeWarm: cancelled pid=${Process.myPid()} runId=$runId")
                    throw ce
                } catch (t: Throwable) {
                    setRuntimeWarmFailed(t, nowMs = SystemClock.elapsedRealtime())
                    log(
                        "runtimeWarm: failed pid=${Process.myPid()} runId=$runId " +
                                "err=${t.javaClass.simpleName}",
                    )
                } finally {
                    closeEglThread(reason = "runtimeWarmDone")
                }
            }

        if (!runtimeWarmJobRef.compareAndSet(existing, job)) {
            job.cancel()
            return
        }

        job.invokeOnCompletion {
            runtimeWarmJobRef.compareAndSet(job, null)
        }

        log("runtimeWarm: scheduled pid=${Process.myPid()} runId=$runId")
        job.start()
    }

    override fun requestCompileAfterPrefetch(appContext: Context, reason: String) {
        val ctx = appContext.applicationContext
        appContextRef.set(ctx)

        val requestId = autoCompileRequestIdGen.incrementAndGet()
        pendingAutoCompileRef.set(
            PendingAutoCompile(
                requestId = requestId,
                reason = reason,
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        val job =
            scope.launch(warmupDispatcher, start = CoroutineStart.LAZY) {
                val safeReason = safeReasonForLogs(reason)
                log(
                    "autoCompileAfterPrefetch: requested pid=${Process.myPid()} " +
                            "requestId=$requestId reason='$safeReason'",
                )

                if (_compileState.value is WarmupController.CompileState.Compiled) {
                    pendingAutoCompileRef.compareAndSet(pendingAutoCompileRef.get(), null)
                    triggerPendingAutoRuntimeIfPossible(trigger = "compileAlreadyReady")
                    return@launch
                }

                val ready =
                    withTimeoutOrNull(Const.COMPILE_AFTER_PREFETCH_MAX_WAIT_MS) {
                        while (true) {
                            currentCoroutineContext().ensureActive()

                            val inputs = inputsRef.get()
                            val ok = inputs != null && isModelFileReady(inputs.file)
                            if (ok) return@withTimeoutOrNull true

                            delay(Const.AUTO_COMPILE_POLL_INTERVAL_MS)
                        }
                    } == true

                currentCoroutineContext().ensureActive()

                if (!ready) {
                    log(
                        "autoCompileAfterPrefetch: inputs not ready (timeout) -> pending kept pid=${Process.myPid()} " +
                                "requestId=$requestId reason='$safeReason'",
                    )
                    return@launch
                }

                val pending = pendingAutoCompileRef.get()
                if (pending == null || pending.requestId != requestId) {
                    log(
                        "autoCompileAfterPrefetch: superseded -> skip pid=${Process.myPid()} " +
                                "requestId=$requestId reason='$safeReason'",
                    )
                    return@launch
                }
                pendingAutoCompileRef.compareAndSet(pending, null)

                triggerAutoCompileNow(ctx = ctx, safeReason = safeReason)
            }

        compileAfterPrefetchJobRef.getAndSet(job)?.cancel()

        job.invokeOnCompletion {
            compileAfterPrefetchJobRef.compareAndSet(job, null)
        }

        log("autoCompileAfterPrefetch: scheduled pid=${Process.myPid()} requestId=$requestId")
        job.start()
    }

    override fun requestRuntimeWarmAfterCompile(appContext: Context, reason: String) {
        val ctx = appContext.applicationContext
        appContextRef.set(ctx)

        val requestId = autoRuntimeRequestIdGen.incrementAndGet()
        pendingAutoRuntimeRef.set(
            PendingAutoRuntimeWarm(
                requestId = requestId,
                reason = reason,
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        val job =
            scope.launch(warmupDispatcher, start = CoroutineStart.LAZY) {
                val safeReason = safeReasonForLogs(reason)
                log(
                    "autoRuntimeAfterCompile: requested pid=${Process.myPid()} " +
                            "requestId=$requestId reason='$safeReason'",
                )

                if (_runtimeWarmState.value is WarmupController.RuntimeWarmState.Ready) {
                    pendingAutoRuntimeRef.compareAndSet(pendingAutoRuntimeRef.get(), null)
                    return@launch
                }

                val ready =
                    withTimeoutOrNull(Const.RUNTIME_AFTER_COMPILE_MAX_WAIT_MS) {
                        while (true) {
                            currentCoroutineContext().ensureActive()

                            val inputs = inputsRef.get()
                            val fileReady = inputs != null && isModelFileReady(inputs.file)
                            val runtimeCapable =
                                inputs?.repository is WarmupController.RuntimeWarmCapableRepository

                            if (!fileReady) {
                                delay(Const.AUTO_RUNTIME_POLL_INTERVAL_MS)
                                continue
                            }

                            if (!runtimeCapable) {
                                return@withTimeoutOrNull false
                            }

                            if (_compileState.value !is WarmupController.CompileState.Compiled) {
                                startCompile(ctx)
                                delay(Const.AUTO_RUNTIME_POLL_INTERVAL_MS)
                                continue
                            }

                            return@withTimeoutOrNull true
                        }
                    }

                currentCoroutineContext().ensureActive()

                if (ready == false) {
                    _runtimeWarmState.value =
                        WarmupController.RuntimeWarmState.SkippedNotReady(
                            "repository not runtime-capable",
                        )
                    val pending = pendingAutoRuntimeRef.get()
                    if (pending != null && pending.requestId == requestId) {
                        pendingAutoRuntimeRef.compareAndSet(pending, null)
                    }
                    log(
                        "autoRuntimeAfterCompile: skipped(repository not runtime-capable) " +
                                "pid=${Process.myPid()} requestId=$requestId reason='$safeReason'",
                    )
                    return@launch
                }

                if (ready != true) {
                    log(
                        "autoRuntimeAfterCompile: prerequisites not ready (timeout) -> pending kept pid=${Process.myPid()} " +
                                "requestId=$requestId reason='$safeReason'",
                    )
                    return@launch
                }

                val pending = pendingAutoRuntimeRef.get()
                if (pending == null || pending.requestId != requestId) {
                    log(
                        "autoRuntimeAfterCompile: superseded -> skip pid=${Process.myPid()} " +
                                "requestId=$requestId reason='$safeReason'",
                    )
                    return@launch
                }
                pendingAutoRuntimeRef.compareAndSet(pending, null)

                triggerAutoRuntimeWarmNow(ctx = ctx, safeReason = safeReason)
            }

        runtimeAfterCompileJobRef.getAndSet(job)?.cancel()

        job.invokeOnCompletion {
            runtimeAfterCompileJobRef.compareAndSet(job, null)
        }

        log("autoRuntimeAfterCompile: scheduled pid=${Process.myPid()} requestId=$requestId")
        job.start()
    }

    override fun cancelAll(reason: String) {
        val safeReason = safeReasonForLogs(reason)
        log("cancelAll: pid=${Process.myPid()} reason='$safeReason'")

        compileAfterPrefetchJobRef.getAndSet(null)?.cancel()
        runtimeAfterCompileJobRef.getAndSet(null)?.cancel()

        pendingAutoCompileRef.set(null)
        pendingAutoRuntimeRef.set(null)

        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = activePrefetchRunId.get(),
                reason = "cancelAll:$safeReason",
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        prefetchJobRef.getAndSet(null)?.cancel()
        compileJobRef.getAndSet(null)?.cancel()
        runtimeWarmJobRef.getAndSet(null)?.cancel()

        val now = SystemClock.elapsedRealtime()
        if (!isPrefetchTerminalOrSkipped(_prefetchState.value)) setPrefetchCancelled(nowMs = now)
        if (!isCompileTerminalOrSkipped(_compileState.value)) setCompileCancelled(nowMs = now)
        if (!isRuntimeTerminalOrSkipped(_runtimeWarmState.value)) {
            setRuntimeWarmCancelled(nowMs = now)
        }

        closeEglThread(reason = "cancelAll")
    }

    override fun resetForRetry(reason: String) {
        cancelAll(reason = "resetForRetry:${safeReasonForLogs(reason)}")

        prefetchRunIdGen.set(0L)
        compileRunIdGen.set(0L)
        runtimeRunIdGen.set(0L)

        activePrefetchRunId.set(0L)

        pendingAutoCompileRef.set(null)
        pendingAutoRuntimeRef.set(null)

        appContextRef.set(null)

        _prefetchState.value = WarmupController.PrefetchState.Idle
        _compileState.value = WarmupController.CompileState.Idle
        _runtimeWarmState.value = WarmupController.RuntimeWarmState.Idle

        log("resetForRetry: done pid=${Process.myPid()}")
    }

    private suspend fun runPrefetch(file: File, runId: Long) {
        val startedAtMs = SystemClock.elapsedRealtime()

        val fileSize = file.length().coerceAtLeast(0L)
        val targetTotal = fileSize.coerceAtMost(Const.PREFETCH_BUDGET_BYTES)
        val total = targetTotal.takeIf { it > 0L }

        _prefetchState.value =
            WarmupController.PrefetchState.Running(
                file = file,
                startedAtMs = startedAtMs,
                downloaded = 0L,
                total = total,
                elapsedMs = 0L,
            )

        val buf = ByteArray(Const.PREFETCH_BUF_SIZE)

        var downloaded = 0L
        var lastEmitAt = startedAtMs

        try {
            FileInputStream(file).use { input ->
                while (downloaded < targetTotal) {
                    currentCoroutineContext().ensureActive()

                    val remaining = targetTotal - downloaded
                    val toRead = remaining.coerceAtMost(buf.size.toLong()).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    downloaded += n.toLong()

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmitAt >= Const.PREFETCH_EMIT_INTERVAL_MS) {
                        lastEmitAt = now
                        _prefetchState.value =
                            WarmupController.PrefetchState.Running(
                                file = file,
                                startedAtMs = startedAtMs,
                                downloaded = downloaded,
                                total = total,
                                elapsedMs = now - startedAtMs,
                            )
                    }
                }
            }
        } catch (ce: CancellationException) {
            val end = SystemClock.elapsedRealtime()
            _prefetchState.value =
                WarmupController.PrefetchState.Cancelled(
                    startedAtMs = startedAtMs,
                    elapsedMs = end - startedAtMs,
                )

            val req = prefetchCancelRequestRef.get()
            val why = if (req != null && req.runId == runId) req.reason else "cancelled"
            if (req != null && req.runId == runId) {
                prefetchCancelRequestRef.compareAndSet(req, null)
            }

            log(
                "prefetch: cancelled(pid=${Process.myPid()} runId=$runId) " +
                        "reason='${safeReasonForLogs(why)}' downloaded=$downloaded " +
                        "target=$targetTotal fileSize=$fileSize elapsedMs=${end - startedAtMs}",
            )
            throw ce
        }

        val end = SystemClock.elapsedRealtime()
        _prefetchState.value =
            WarmupController.PrefetchState.Prefetched(
                file = file,
                sizeBytes = downloaded.coerceAtLeast(0L),
                startedAtMs = startedAtMs,
                elapsedMs = end - startedAtMs,
            )

        log(
            "prefetch: end pid=${Process.myPid()} runId=$runId downloaded=$downloaded " +
                    "target=$targetTotal fileSize=$fileSize elapsedMs=${end - startedAtMs}",
        )
    }

    private data class PrefetchCancelRequest(
        val runId: Long,
        val reason: String,
        val requestedAtMs: Long,
    )

    private val prefetchCancelRequestRef = AtomicReference<PrefetchCancelRequest?>(null)

    /**
     * Cancels prefetch if currently running (best effort).
     *
     * Used to reduce IO contention when compile starts.
     */
    private fun cancelPrefetchIfRunning(reason: String) {
        val st = _prefetchState.value
        if (st !is WarmupController.PrefetchState.Running) return

        val runId = activePrefetchRunId.get()
        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = runId,
                reason = reason,
                requestedAtMs = SystemClock.elapsedRealtime(),
            ),
        )

        prefetchJobRef.getAndSet(null)?.cancel()
        log(
            "prefetch: cancel requested pid=${Process.myPid()} runId=$runId " +
                    "reason='${safeReasonForLogs(reason)}'",
        )
    }

    /**
     * Waits briefly for prefetch to finish if close to completion, otherwise cancels it.
     *
     * Returns milliseconds spent waiting/cancelling.
     */
    private suspend fun awaitOrCancelPrefetchBeforeCompile(runId: Long): Long {
        val st = _prefetchState.value
        if (st !is WarmupController.PrefetchState.Running) return 0L

        val remainingBytes = st.total?.let { total -> (total - st.downloaded).coerceAtLeast(0L) }
        val waitStartMs = SystemClock.elapsedRealtime()

        val shouldWaitForFinish =
            remainingBytes != null && remainingBytes <= Const.PREFETCH_FINISH_WAIT_REMAINING_BYTES

        if (shouldWaitForFinish) {
            log(
                "compile: waiting briefly for prefetch to finish pid=${Process.myPid()} runId=$runId " +
                        "remainingBytes=$remainingBytes",
            )

            val finished = withTimeoutOrNull(Const.PREFETCH_FINISH_WAIT_TIMEOUT_MS) {
                prefetchState.first { isPrefetchTerminalOrSkipped(it) }
            } != null

            if (!finished) {
                log(
                    "compile: prefetch wait timeout; cancelling to reduce IO contention " +
                            "pid=${Process.myPid()} runId=$runId",
                )
                cancelPrefetchIfRunning(reason = "compile-wait-timeout")
                withTimeoutOrNull(Const.PREFETCH_CANCEL_JOIN_TIMEOUT_MS) {
                    prefetchState.first { isPrefetchTerminalOrSkipped(it) }
                }
            }
        } else {
            log("compile: cancelling prefetch to reduce IO contention pid=${Process.myPid()} runId=$runId")
            cancelPrefetchIfRunning(reason = "compile-start")
            withTimeoutOrNull(Const.PREFETCH_CANCEL_JOIN_TIMEOUT_MS) {
                prefetchState.first { isPrefetchTerminalOrSkipped(it) }
            }
        }

        return SystemClock.elapsedRealtime() - waitStartMs
    }

    private suspend fun runCompile(
        file: File,
        repo: WarmupController.WarmupCapableRepository,
        opts: WarmupController.Options,
        appContext: Context,
        runId: Long,
    ) {
        val prefetchRunning = _prefetchState.value is WarmupController.PrefetchState.Running
        if (prefetchRunning) {
            val requestedAtMs = SystemClock.elapsedRealtime()
            _compileState.value =
                WarmupController.CompileState.WaitingForPrefetch(
                    file = file,
                    requestedAtMs = requestedAtMs,
                    elapsedMs = 0L,
                )
        }

        val ticker =
            scope.launch(warmupDispatcher) {
                while (true) {
                    delay(Const.COMPILE_EMIT_INTERVAL_MS)
                    currentCoroutineContext().ensureActive()

                    val now = SystemClock.elapsedRealtime()
                    when (val st = _compileState.value) {
                        is WarmupController.CompileState.WaitingForPrefetch ->
                            _compileState.value = st.copy(elapsedMs = now - st.requestedAtMs)

                        is WarmupController.CompileState.Compiling ->
                            _compileState.value = st.copy(elapsedMs = now - st.startedAtMs)

                        else -> return@launch
                    }
                }
            }

        val startedAtMs = SystemClock.elapsedRealtime()
        var prefetchWaitMs = 0L
        try {
            if (prefetchRunning) {
                prefetchWaitMs = awaitOrCancelPrefetchBeforeCompile(runId = runId)
                currentCoroutineContext().ensureActive()
            } else {
                cancelPrefetchIfRunning(reason = "compile-start")
            }

            _compileState.value =
                WarmupController.CompileState.Compiling(
                    file = file,
                    startedAtMs = startedAtMs,
                    elapsedMs = 0L,
                )

            log(
                "compile: begin pid=${Process.myPid()} runId=$runId prefetchWaitMs=$prefetchWaitMs " +
                        "opts=image=${opts.supportImage} audio=${opts.supportAudio} " +
                        "tools=${opts.tools.size} system=${opts.systemMessage != null}",
            )

            currentCoroutineContext().ensureActive()

            withTimeout(Const.INITIALIZE_TIMEOUT_MS) {
                runCompileViaEglIfPossible(
                    appContext = appContext,
                    repo = repo,
                    file = file,
                    opts = opts,
                    runId = runId,
                )
            }

            val end = SystemClock.elapsedRealtime()
            _compileState.value =
                WarmupController.CompileState.Compiled(
                    file = file,
                    startedAtMs = startedAtMs,
                    elapsedMs = end - startedAtMs,
                )

            log(
                "compile: end pid=${Process.myPid()} runId=$runId elapsedMs=${end - startedAtMs} " +
                        "prefetchWaitMs=$prefetchWaitMs",
            )

            triggerPendingAutoRuntimeIfPossible(trigger = "compileComplete")
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun runRuntimeWarm(
        file: File,
        repo: WarmupController.RuntimeWarmCapableRepository,
        opts: WarmupController.Options,
        appContext: Context,
        runId: Long,
    ) {
        if (_compileState.value !is WarmupController.CompileState.Compiled) {
            val requestedAtMs = SystemClock.elapsedRealtime()
            _runtimeWarmState.value =
                WarmupController.RuntimeWarmState.WaitingForCompile(
                    file = file,
                    requestedAtMs = requestedAtMs,
                    elapsedMs = 0L,
                )

            startCompile(appContext)

            val compileResult =
                withTimeoutOrNull(Const.RUNTIME_WAIT_FOR_COMPILE_TIMEOUT_MS) {
                    compileState.first { isCompileTerminalOrSkipped(it) }
                } ?: compileState.value

            when (compileResult) {
                is WarmupController.CompileState.Compiled -> {
                    // Continue.
                }

                is WarmupController.CompileState.Failed -> {
                    _runtimeWarmState.value =
                        WarmupController.RuntimeWarmState.Failed(
                            message = "CompileFailed",
                            startedAtMs = requestedAtMs,
                            elapsedMs = elapsedSince(requestedAtMs, SystemClock.elapsedRealtime()),
                        )
                    log(
                        "runtimeWarm: compile prerequisite failed pid=${Process.myPid()} " +
                                "runId=$runId",
                    )
                    return
                }

                is WarmupController.CompileState.Cancelled -> {
                    _runtimeWarmState.value =
                        WarmupController.RuntimeWarmState.Cancelled(
                            startedAtMs = requestedAtMs,
                            elapsedMs = elapsedSince(requestedAtMs, SystemClock.elapsedRealtime()),
                        )
                    log(
                        "runtimeWarm: compile prerequisite cancelled pid=${Process.myPid()} " +
                                "runId=$runId",
                    )
                    return
                }

                is WarmupController.CompileState.SkippedNotReady -> {
                    _runtimeWarmState.value =
                        WarmupController.RuntimeWarmState.SkippedNotReady(
                            reason = "compile not ready",
                        )
                    log(
                        "runtimeWarm: compile prerequisite not ready pid=${Process.myPid()} " +
                                "runId=$runId",
                    )
                    return
                }

                else -> {
                    _runtimeWarmState.value =
                        WarmupController.RuntimeWarmState.Failed(
                            message = "CompileTimeout",
                            startedAtMs = requestedAtMs,
                            elapsedMs = elapsedSince(requestedAtMs, SystemClock.elapsedRealtime()),
                        )
                    log(
                        "runtimeWarm: compile prerequisite timeout pid=${Process.myPid()} " +
                                "runId=$runId",
                    )
                    return
                }
            }
        }

        val ticker =
            scope.launch(warmupDispatcher) {
                while (true) {
                    delay(Const.RUNTIME_EMIT_INTERVAL_MS)
                    currentCoroutineContext().ensureActive()

                    val now = SystemClock.elapsedRealtime()
                    when (val st = _runtimeWarmState.value) {
                        is WarmupController.RuntimeWarmState.WaitingForCompile ->
                            _runtimeWarmState.value = st.copy(elapsedMs = now - st.requestedAtMs)

                        is WarmupController.RuntimeWarmState.Warming ->
                            _runtimeWarmState.value = st.copy(elapsedMs = now - st.startedAtMs)

                        else -> return@launch
                    }
                }
            }

        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            _runtimeWarmState.value =
                WarmupController.RuntimeWarmState.Warming(
                    file = file,
                    startedAtMs = startedAtMs,
                    elapsedMs = 0L,
                )

            log(
                "runtimeWarm: begin pid=${Process.myPid()} runId=$runId " +
                        "opts=image=${opts.supportImage} audio=${opts.supportAudio} " +
                        "tools=${opts.tools.size} system=${opts.systemMessage != null}",
            )

            currentCoroutineContext().ensureActive()

            withTimeout(Const.RUNTIME_PREPARE_TIMEOUT_MS) {
                runRuntimeWarmViaEglIfPossible(
                    appContext = appContext,
                    repo = repo,
                    file = file,
                    opts = opts,
                    runId = runId,
                )
            }

            val end = SystemClock.elapsedRealtime()
            _runtimeWarmState.value =
                WarmupController.RuntimeWarmState.Ready(
                    file = file,
                    startedAtMs = startedAtMs,
                    elapsedMs = end - startedAtMs,
                )

            log(
                "runtimeWarm: end pid=${Process.myPid()} runId=$runId " +
                        "elapsedMs=${end - startedAtMs}",
            )
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun runCompileViaEglIfPossible(
        appContext: Context,
        repo: WarmupController.WarmupCapableRepository,
        file: File,
        opts: WarmupController.Options,
        runId: Long,
    ) {
        currentCoroutineContext().ensureActive()

        val thread = ensureEglThread(runId = runId)
        currentCoroutineContext().ensureActive()

        if (thread == null) {
            log("compile: egl unavailable; running warmup directly pid=${Process.myPid()} runId=$runId")
            val ok = repo.warmup(appContext, file, opts)
            if (!ok) error("warmup returned false")
            return
        }

        try {
            log("compile: entering egl context pid=${Process.myPid()} runId=$runId")
            thread.withEglContext {
                currentCoroutineContext().ensureActive()
                val ok = repo.warmup(appContext, file, opts)
                if (!ok) error("warmup returned false")
            }
        } catch (t: Throwable) {
            log(
                "compile: egl warmup failed; retrying direct pid=${Process.myPid()} runId=$runId " +
                        "err=${t.javaClass.simpleName}",
            )
            val ok = repo.warmup(appContext, file, opts)
            if (!ok) error("warmup returned false")
        }
    }

    private suspend fun runRuntimeWarmViaEglIfPossible(
        appContext: Context,
        repo: WarmupController.RuntimeWarmCapableRepository,
        file: File,
        opts: WarmupController.Options,
        runId: Long,
    ) {
        currentCoroutineContext().ensureActive()

        val thread = ensureEglThread(runId = runId)
        currentCoroutineContext().ensureActive()

        if (thread == null) {
            log(
                "runtimeWarm: egl unavailable; running prepareRuntime directly " +
                        "pid=${Process.myPid()} runId=$runId",
            )
            val ok = repo.prepareRuntime(appContext, file, opts)
            if (!ok) error("prepareRuntime returned false")
            return
        }

        try {
            log("runtimeWarm: entering egl context pid=${Process.myPid()} runId=$runId")
            thread.withEglContext {
                currentCoroutineContext().ensureActive()
                val ok = repo.prepareRuntime(appContext, file, opts)
                if (!ok) error("prepareRuntime returned false")
            }
        } catch (t: Throwable) {
            log(
                "runtimeWarm: egl prepare failed; retrying direct pid=${Process.myPid()} runId=$runId " +
                        "err=${t.javaClass.simpleName}",
            )
            val ok = repo.prepareRuntime(appContext, file, opts)
            if (!ok) error("prepareRuntime returned false")
        }
    }

    private fun ensureEglThread(runId: Long): EglPbufferThread? {
        val existing = eglThreadRef.get()
        if (existing != null) return existing

        val created =
            runCatching { EglPbufferThread(threadName = "WarmupEgl", preferredGlesVersion = 3) }
                .getOrNull() ?: return null

        return if (eglThreadRef.compareAndSet(null, created)) {
            log("eglThread: created pid=${Process.myPid()} runId=$runId")
            created
        } else {
            runCatching { created.close() }
            eglThreadRef.get()
        }
    }

    private fun closeEglThread(reason: String) {
        val thread = eglThreadRef.getAndSet(null) ?: return
        runCatching { thread.close() }
            .onFailure { t ->
                AppLog.w(
                    TAG,
                    "eglThread close failed: reason='${safeReasonForLogs(reason)}' " +
                            "err=${t.javaClass.simpleName}",
                )
            }
        log("eglThread closed: reason='${safeReasonForLogs(reason)}' pid=${Process.myPid()}")
    }

    private fun isModelFileReady(file: File?): Boolean {
        if (file == null) return false
        if (!file.exists() || !file.isFile) return false
        return file.length() > 0L
    }

    private fun setPrefetchFailed(t: Throwable, nowMs: Long) {
        val startedAt = startedAtForPrefetch(_prefetchState.value)
        _prefetchState.value =
            WarmupController.PrefetchState.Failed(
                message = safeFailureMessage(t),
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    private fun setPrefetchCancelled(nowMs: Long) {
        val startedAt = startedAtForPrefetch(_prefetchState.value)
        _prefetchState.value =
            WarmupController.PrefetchState.Cancelled(
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    private fun setCompileFailed(t: Throwable, nowMs: Long) {
        val startedAt = startedAtForCompile(_compileState.value)
        _compileState.value =
            WarmupController.CompileState.Failed(
                message = safeFailureMessage(t),
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    private fun setCompileCancelled(nowMs: Long) {
        val startedAt = startedAtForCompile(_compileState.value)
        _compileState.value =
            WarmupController.CompileState.Cancelled(
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    private fun setRuntimeWarmFailed(t: Throwable, nowMs: Long) {
        val startedAt = startedAtForRuntime(_runtimeWarmState.value)
        _runtimeWarmState.value =
            WarmupController.RuntimeWarmState.Failed(
                message = safeFailureMessage(t),
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    private fun setRuntimeWarmCancelled(nowMs: Long) {
        val startedAt = startedAtForRuntime(_runtimeWarmState.value)
        _runtimeWarmState.value =
            WarmupController.RuntimeWarmState.Cancelled(
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, nowMs),
            )
    }

    /**
     * Returns a failure message safe to surface in UI/state without leaking file paths.
     */
    private fun safeFailureMessage(t: Throwable): String {
        return when (t) {
            is TimeoutCancellationException -> "Timeout"
            else -> t.javaClass.simpleName
        }
    }

    private fun startedAtForPrefetch(state: WarmupController.PrefetchState): Long? {
        return when (state) {
            is WarmupController.PrefetchState.Running -> state.startedAtMs
            is WarmupController.PrefetchState.Prefetched -> state.startedAtMs
            is WarmupController.PrefetchState.Failed -> state.startedAtMs
            is WarmupController.PrefetchState.Cancelled -> state.startedAtMs
            else -> null
        }
    }

    private fun startedAtForCompile(state: WarmupController.CompileState): Long? {
        return when (state) {
            is WarmupController.CompileState.WaitingForPrefetch -> state.requestedAtMs
            is WarmupController.CompileState.Compiling -> state.startedAtMs
            is WarmupController.CompileState.Compiled -> state.startedAtMs
            is WarmupController.CompileState.Failed -> state.startedAtMs
            is WarmupController.CompileState.Cancelled -> state.startedAtMs
            else -> null
        }
    }

    private fun startedAtForRuntime(state: WarmupController.RuntimeWarmState): Long? {
        return when (state) {
            is WarmupController.RuntimeWarmState.WaitingForCompile -> state.requestedAtMs
            is WarmupController.RuntimeWarmState.Warming -> state.startedAtMs
            is WarmupController.RuntimeWarmState.Ready -> state.startedAtMs
            is WarmupController.RuntimeWarmState.Failed -> state.startedAtMs
            is WarmupController.RuntimeWarmState.Cancelled -> state.startedAtMs
            else -> null
        }
    }

    private fun elapsedSince(startedAtMs: Long?, nowMs: Long): Long {
        return if (startedAtMs == null) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)
    }

    private fun isPrefetchTerminalOrSkipped(state: WarmupController.PrefetchState): Boolean {
        return when (state) {
            is WarmupController.PrefetchState.Prefetched,
            is WarmupController.PrefetchState.Failed,
            is WarmupController.PrefetchState.Cancelled,
            is WarmupController.PrefetchState.SkippedNotReady -> true
            else -> false
        }
    }

    private fun isCompileTerminalOrSkipped(state: WarmupController.CompileState): Boolean {
        return when (state) {
            is WarmupController.CompileState.Compiled,
            is WarmupController.CompileState.Failed,
            is WarmupController.CompileState.Cancelled,
            is WarmupController.CompileState.SkippedNotReady -> true
            else -> false
        }
    }

    private fun isRuntimeTerminalOrSkipped(state: WarmupController.RuntimeWarmState): Boolean {
        return when (state) {
            is WarmupController.RuntimeWarmState.Ready,
            is WarmupController.RuntimeWarmState.Failed,
            is WarmupController.RuntimeWarmState.Cancelled,
            is WarmupController.RuntimeWarmState.SkippedNotReady -> true
            else -> false
        }
    }

    /**
     * Sanitizes arbitrary strings before logging.
     *
     * Keep logs stable and privacy-safe without losing signal.
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

    /**
     * Background dispatchers used by warmup operations.
     *
     * Rationale:
     * - Never mutate priorities of shared Dispatchers.Default threads.
     * - Use dedicated background threads with best-effort background priority.
     */
    private object WarmupDispatchers {
        private val threadId = AtomicInteger(0)
        private val executor =
            Executors.newFixedThreadPool(2) { r ->
                val id = threadId.incrementAndGet()
                Thread(
                    {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        } catch (_: Throwable) {
                            // Best effort.
                        }
                        r.run()
                    },
                    "WarmupBg-$id",
                ).apply {
                    isDaemon = true
                }
            }

        val background: CoroutineDispatcher = executor.asCoroutineDispatcher()
    }

    /**
     * EGL warmup thread:
     * - Runs a single-thread owner with a tiny pbuffer surface.
     *
     * Best-effort:
     * - If EGL init fails, the block still runs on the worker thread without eglMakeCurrent.
     */
    private class EglPbufferThread(
        threadName: String = "warmup-egl",
        private val preferredGlesVersion: Int = 3,
    ) : Closeable {

        private val executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, threadName).apply { isDaemon = true }
            }

        private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

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
                    runCatching { EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API) }
                    stateRef.set(createEglState(preferredGlesVersion))
                } catch (t: Throwable) {
                    stateRef.set(null)
                    if (loggedInitFailure.compareAndSet(false, true)) {
                        AppLog.w(
                            TAG,
                            "EGL init failed (will run without current context): " +
                                    "preferredGles=$preferredGlesVersion err=${t.javaClass.simpleName}",
                        )
                    }
                } finally {
                    initGate.complete(Unit)
                }
            }
        }

        /**
         * Runs [block] on the EGL thread after initialization completes.
         *
         * NOTE:
         * - initGate.await() is performed inside the dispatcher context.
         */
        suspend fun withEglContext(block: suspend () -> Unit) {
            withContext(dispatcher) {
                initGate.await()

                val st = stateRef.get()
                if (st == null) {
                    if (loggedNoContext.compareAndSet(false, true)) {
                        AppLog.w(TAG, "EGL state unavailable; running without eglMakeCurrent")
                    }
                    block()
                    return@withContext
                }

                if (!EGL14.eglMakeCurrent(st.display, st.surface, st.surface, st.context)) {
                    if (loggedNoContext.compareAndSet(false, true)) {
                        AppLog.w(
                            TAG,
                            "eglMakeCurrent failed: err=${eglErrorString()}; " +
                                    "running without current context",
                        )
                    }
                    block()
                    return@withContext
                }

                try {
                    block()
                } finally {
                    runCatching {
                        EGL14.eglMakeCurrent(
                            st.display,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT,
                        )
                    }
                }
            }
        }

        override fun close() {
            executor.execute {
                runCatching { stateRef.getAndSet(null)?.close() }
            }

            executor.shutdown()
            val terminated =
                runCatching {
                    executor.awaitTermination(CLOSE_AWAIT_TERMINATION_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)

            if (!terminated) {
                executor.shutdownNow()
            }

            runCatching { dispatcher.close() }
        }

        private data class EglState(
            val display: EGLDisplay,
            val config: EGLConfig,
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

        private fun createEglState(preferred: Int): EglState {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            var context: EGLContext = EGL14.EGL_NO_CONTEXT

            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) {
                    "eglGetDisplay failed: err=${eglErrorString()}"
                }

                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                    "eglInitialize failed: err=${eglErrorString()}"
                }

                val config = chooseConfigWithFallback(display, preferred)
                surface = createPbufferSurface(display, config)
                context = createContext(display, config, preferred)

                check(context != EGL14.EGL_NO_CONTEXT) {
                    "eglCreateContext failed: err=${eglErrorString()}"
                }
                check(surface != EGL14.EGL_NO_SURFACE) {
                    "eglCreatePbufferSurface failed: err=${eglErrorString()}"
                }

                return EglState(display = display, config = config, surface = surface, context = context)
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

        /**
         * Chooses an EGLConfig with ES3 -> ES2 fallback.
         */
        private fun chooseConfigWithFallback(display: EGLDisplay, preferred: Int): EGLConfig {
            if (preferred >= 3) {
                val es3 = runCatching { chooseConfig(display, EGL_OPENGL_ES3_BIT_KHR) }.getOrNull()
                if (es3 != null) return es3
            }
            val es2 = runCatching { chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT) }.getOrNull()
            if (es2 != null) return es2

            val renderable =
                if (preferred >= 3) EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
            return chooseConfig(display, renderable)
        }

        private fun chooseConfig(display: EGLDisplay, renderable: Int): EGLConfig {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)

            val attribs =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, renderable,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )

            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, num, 0)) {
                "eglChooseConfig failed: err=${eglErrorString()}"
            }
            check(num[0] > 0) { "eglChooseConfig returned 0 configs: err=${eglErrorString()}" }

            return configs[0] ?: error("eglChooseConfig returned null config")
        }

        private fun createPbufferSurface(display: EGLDisplay, config: EGLConfig): EGLSurface {
            val attribs =
                intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
                )

            return EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        }

        private fun createContext(
            display: EGLDisplay,
            config: EGLConfig,
            preferred: Int,
        ): EGLContext {
            var ctx = eglCreateContext(display, config, preferred)
            if (ctx != EGL14.EGL_NO_CONTEXT) return ctx

            if (preferred >= 3) {
                ctx = eglCreateContext(display, config, 2)
            }
            return ctx
        }

        private fun eglCreateContext(
            display: EGLDisplay,
            config: EGLConfig,
            version: Int,
        ): EGLContext {
            val attribs =
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, version,
                    EGL14.EGL_NONE,
                )
            return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
        }

        private fun eglErrorString(): String {
            val err = EGL14.eglGetError()
            return "0x" + err.toString(16)
        }

        private companion object {
            private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
            private const val CLOSE_AWAIT_TERMINATION_MS: Long = 250L
        }
    }

    private companion object {
        private const val TAG: String = "SLMWarmupEngine"

        private object Const {
            const val PREFETCH_BUDGET_BYTES: Long = 512L * 1024L * 1024L
            const val PREFETCH_BUF_SIZE: Int = 4 * 1024 * 1024
            const val PREFETCH_EMIT_INTERVAL_MS: Long = 250L

            const val PREFETCH_FINISH_WAIT_TIMEOUT_MS: Long = 1_500L
            const val PREFETCH_CANCEL_JOIN_TIMEOUT_MS: Long = 1_000L
            const val PREFETCH_FINISH_WAIT_REMAINING_BYTES: Long = 32L * 1024L * 1024L

            const val COMPILE_EMIT_INTERVAL_MS: Long = 250L
            const val AUTO_COMPILE_DELAY_MS: Long = 120L
            const val AUTO_COMPILE_POLL_INTERVAL_MS: Long = 250L
            const val COMPILE_AFTER_PREFETCH_MAX_WAIT_MS: Long = 15_000L

            const val RUNTIME_EMIT_INTERVAL_MS: Long = 250L
            const val AUTO_RUNTIME_DELAY_MS: Long = 80L
            const val AUTO_RUNTIME_POLL_INTERVAL_MS: Long = 250L
            const val RUNTIME_AFTER_COMPILE_MAX_WAIT_MS: Long = 120_000L
            const val RUNTIME_WAIT_FOR_COMPILE_TIMEOUT_MS: Long = 120_000L
            const val RUNTIME_PREPARE_TIMEOUT_MS: Long = 120_000L

            /**
             * Hard timeout for repository compile warmup (including EGL warmup).
             */
            const val INITIALIZE_TIMEOUT_MS: Long = 120_000L
        }
    }
}