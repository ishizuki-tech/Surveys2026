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
import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-scoped warmup orchestrator for on-device SLM.
 *
 * Split design:
 * - Prefetch phase: IO-only page-cache warming (safe to start while Home/SurveyStart is visible).
 * - Compile phase: heavy GPU/driver/delegate compilation (start right before Question screen uses the model).
 *
 * Goals:
 * - Reduce startup jank by avoiding compile work at app launch.
 * - Keep both phases idempotent per process, even under concurrent call races.
 * - Provide separate state flows for UI gating and debug.
 *
 * Important:
 * - Prefetch is a best-effort optimization. Compile must NOT be blocked by prefetch.
 * - If compile is requested while prefetch is still running, prefetch is cancelled to avoid IO contention.
 */
object SlmWarmup {

    // ---------------------------------------------------------------------
    // Internal state flows
    // ---------------------------------------------------------------------

    private val _prefetchState = MutableStateFlow<PrefetchState>(PrefetchState.Idle)
    private val _compileState = MutableStateFlow<CompileState>(CompileState.Idle)

    // ---------------------------------------------------------------------
    // Public states
    // ---------------------------------------------------------------------

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

        data class SkippedNotConfigured(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : PrefetchState
    }

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

        data class SkippedNotConfigured(
            val reason: String,
            override val elapsedMs: Long = 0L,
        ) : CompileState
    }

    val state: StateFlow<PrefetchState> get() = prefetchState
    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()
    val compileState: StateFlow<CompileState> = _compileState.asStateFlow()

    // ---------------------------------------------------------------------
    // Warmup conversation options
    // ---------------------------------------------------------------------

    private val warmupSupportImage = AtomicBoolean(false)
    private val warmupSupportAudio = AtomicBoolean(false)
    private val warmupSystemMessageRef = AtomicReference<Message?>(null)
    private val warmupToolsRef = AtomicReference<List<Any>>(emptyList())

    /**
     * Configure conversation options used during compile warmup.
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
        debugLog(
            "warmupOptions: image=$supportImage audio=$supportAudio " +
                    "system=${systemMessage != null} tools=${tools.size}"
        )
    }

    // ---------------------------------------------------------------------
    // Internals / guards
    // ---------------------------------------------------------------------

    private const val TAG = "SlmWarmup"

    private const val CONFIG_ASSET_NAME = "survey.yaml"
    private const val FALLBACK_MODEL_NAME = "Gemma3n4B"
    private val FALLBACK_ACCEL = Accelerator.GPU
    private const val FALLBACK_MAX_TOKENS = 4096
    private const val FALLBACK_TOPK = 40
    private const val FALLBACK_TOPP = 0.9
    private const val FALLBACK_TEMPERATURE = 0.7

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefetchJobRef = AtomicReference<Job?>(null)
    private val compileJobRef = AtomicReference<Job?>(null)
    private val eglThreadRef = AtomicReference<EglPbufferThread?>(null)

    private val compileAfterPrefetchRequested = AtomicBoolean(false)
    private val compileAfterPrefetchJobRef = AtomicReference<Job?>(null)

    private val cachedConfigRef = AtomicReference<com.negi.surveys.config.SurveyConfig?>(null)

    private val prefetchRequested = AtomicBoolean(false)
    private val compileRequested = AtomicBoolean(false)

    private val prefetchRunIdGen = AtomicLong(0L)
    private val compileRunIdGen = AtomicLong(0L)

    private val activePrefetchRunId = AtomicLong(0L)

    private data class PrefetchCancelRequest(
        val runId: Long,
        val reason: String,
        val requestedAtMs: Long,
    )

    private val prefetchCancelRequestRef = AtomicReference<PrefetchCancelRequest?>(null)

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun startPrefetchIfConfigured(appContext: Context) {
        val file = resolveModelFileOrNull(appContext)
        if (file == null) {
            _prefetchState.value = PrefetchState.SkippedNotConfigured("model file not found")
            debugLog("prefetch: skipped (no model file) pid=${Process.myPid()}")
            return
        }

        when (_prefetchState.value) {
            is PrefetchState.Prefetched,
            is PrefetchState.Failed,
            is PrefetchState.Cancelled,
            is PrefetchState.SkippedNotConfigured -> return
            else -> Unit
        }

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
                runPrefetch(file, runId = runId)
            } catch (ce: CancellationException) {
                // runPrefetch() already updates state on cancel; keep this as a safety net.
                val now = SystemClock.elapsedRealtime()
                val startedAt = startedAtForPrefetch(_prefetchState.value)
                _prefetchState.value = PrefetchState.Cancelled(
                    startedAtMs = startedAt,
                    elapsedMs = elapsedSince(startedAt, now)
                )
                debugLog("prefetch: cancelled pid=${Process.myPid()} runId=$runId")
                throw ce
            } catch (t: Throwable) {
                val now = SystemClock.elapsedRealtime()
                val startedAt = startedAtForPrefetch(_prefetchState.value)
                _prefetchState.value = PrefetchState.Failed(
                    message = "${t.javaClass.simpleName}(${t.message})",
                    startedAtMs = startedAt,
                    elapsedMs = elapsedSince(startedAt, now)
                )
                debugLog(
                    "prefetch: failed pid=${Process.myPid()} runId=$runId " +
                            "err=${t.javaClass.simpleName}(${t.message})"
                )
            }
        }

        prefetchJobRef.set(job)
        debugLog(
            "prefetch: started pid=${Process.myPid()} runId=$runId file='${file.name}' size=${file.length()}"
        )
    }

    fun startCompileIfConfigured(appContext: Context) {
        val file = resolveModelFileOrNull(appContext)
        if (file == null) {
            _compileState.value = CompileState.SkippedNotConfigured("model file not found")
            debugLog("compile: skipped (no model file) pid=${Process.myPid()}")
            return
        }

        when (_compileState.value) {
            is CompileState.Compiled,
            is CompileState.Failed,
            is CompileState.Cancelled,
            is CompileState.SkippedNotConfigured -> return
            else -> Unit
        }

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

                // Kick prefetch if it hasn't started yet (best-effort).
                startPrefetchIfConfigured(appContext)

                // Compile must not wait for prefetch.
                runCompile(file, appContext, runId = runId)
            } catch (ce: CancellationException) {
                val now = SystemClock.elapsedRealtime()
                val startedAt = startedAtForCompile(_compileState.value)
                _compileState.value = CompileState.Cancelled(
                    startedAtMs = startedAt,
                    elapsedMs = elapsedSince(startedAt, now)
                )
                debugLog("compile: cancelled pid=${Process.myPid()} runId=$runId")
                throw ce
            } catch (t: Throwable) {
                val now = SystemClock.elapsedRealtime()
                val startedAt = startedAtForCompile(_compileState.value)
                _compileState.value = CompileState.Failed(
                    message = "${t.javaClass.simpleName}(${t.message})",
                    startedAtMs = startedAt,
                    elapsedMs = elapsedSince(startedAt, now)
                )
                debugLog(
                    "compile: failed pid=${Process.myPid()} runId=$runId " +
                            "err=${t.javaClass.simpleName}(${t.message})"
                )
            }
        }

        compileJobRef.set(job)
        debugLog("compile: started pid=${Process.myPid()} runId=$runId file='${file.name}'")
    }

    fun startWarmupIfConfigured(appContext: Context) {
        startPrefetchIfConfigured(appContext)
        startCompileIfConfigured(appContext)
    }

    /**
     * Requests compile warmup to start right after prefetch reaches a terminal state.
     *
     * Design intent:
     * - On the Home screen, we can prefetch early (IO-only).
     * - As soon as prefetch finishes, we begin compile while the user is still on Home,
     *   reducing the chance of jank during the Question flow.
     *
     * Notes:
     * - This is idempotent per process.
     * - If prefetch is already terminal, compile is requested immediately.
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

        // Ensure prefetch is at least requested.
        startPrefetchIfConfigured(appContext)

        // If prefetch already finished (or cannot run), request compile right away.
        val current = _prefetchState.value
        if (isPrefetchTerminal(current)) {
            scope.launch(Dispatchers.Default) {
                delay(AUTO_COMPILE_DELAY_MS)
                startCompileIfConfigured(appContext)
                debugLog("autoCompileAfterPrefetch: triggered (prefetch already terminal) reason='$reason'")
            }
            return
        }

        val job = scope.launch(Dispatchers.Default) {
            runCatching {
                prefetchState.first { isPrefetchTerminal(it) }
                delay(AUTO_COMPILE_DELAY_MS)
                startCompileIfConfigured(appContext)
                debugLog("autoCompileAfterPrefetch: triggered reason='$reason'")
            }.onFailure { t ->
                debugLog(
                    "autoCompileAfterPrefetch: watcher failed reason='$reason' " +
                            "err=${t.javaClass.simpleName}(${t.message})"
                )
            }
        }

        compileAfterPrefetchJobRef.getAndSet(job)?.cancel()
    }

    fun cancelAll(reason: String = "cancelAll") {
        debugLog("cancelAll: reason='$reason' pid=${Process.myPid()}")

        compileAfterPrefetchJobRef.getAndSet(null)?.cancel()
        compileAfterPrefetchRequested.set(false)

        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = activePrefetchRunId.get(),
                reason = "cancelAll:$reason",
                requestedAtMs = SystemClock.elapsedRealtime()
            )
        )
        prefetchJobRef.getAndSet(null)?.cancel()
        compileJobRef.getAndSet(null)?.cancel()

        val now = SystemClock.elapsedRealtime()

        val ps = _prefetchState.value
        val prefetchTerminal =
            ps is PrefetchState.Prefetched ||
                    ps is PrefetchState.Failed ||
                    ps is PrefetchState.Cancelled ||
                    ps is PrefetchState.SkippedNotConfigured

        if (!prefetchTerminal && (prefetchRequested.get() || ps is PrefetchState.Running || ps is PrefetchState.Idle)) {
            val startedAt = startedAtForPrefetch(ps)
            _prefetchState.value = PrefetchState.Cancelled(
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, now)
            )
        }

        val cs = _compileState.value
        val compileTerminal =
            cs is CompileState.Compiled ||
                    cs is CompileState.Failed ||
                    cs is CompileState.Cancelled ||
                    cs is CompileState.SkippedNotConfigured

        if (!compileTerminal && (compileRequested.get() || cs is CompileState.Compiling || cs is CompileState.Idle)) {
            val startedAt = startedAtForCompile(cs)
            _compileState.value = CompileState.Cancelled(
                startedAtMs = startedAt,
                elapsedMs = elapsedSince(startedAt, now)
            )
        }

        closeEglThread(reason = "cancelAll:$reason")
    }

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

        _prefetchState.value = PrefetchState.Idle
        _compileState.value = CompileState.Idle

        debugLog("resetForRetry: done reason='$reason' pid=${Process.myPid()}")
    }

    // ---------------------------------------------------------------------
    // Prefetch / Compile implementations
    // ---------------------------------------------------------------------

    private fun getConfigBestEffort(appContext: Context): com.negi.surveys.config.SurveyConfig? {
        val existing = cachedConfigRef.get()
        if (existing != null) return existing

        val loaded = runCatching {
            SurveyConfigLoader.fromAssetsValidated(appContext.applicationContext, CONFIG_ASSET_NAME)
        }.getOrNull()

        if (loaded != null) cachedConfigRef.compareAndSet(null, loaded)
        return cachedConfigRef.get()
    }

    private fun buildWarmupModel(appContext: Context, file: File): Model {
        val cfg = getConfigBestEffort(appContext)

        val modelName = cfg?.modelDefaults?.modelName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_MODEL_NAME

        val accel = cfg?.slm?.accelerator
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_ACCEL.label

        val maxTokens = cfg?.slm?.maxTokens ?: FALLBACK_MAX_TOKENS
        val topK = cfg?.slm?.topK ?: FALLBACK_TOPK
        val topP = (cfg?.slm?.topP ?: FALLBACK_TOPP).toFloat()
        val temp = (cfg?.slm?.temperature ?: FALLBACK_TEMPERATURE).toFloat()

        val config: Map<ConfigKey, Any> = mapOf(
            ConfigKey.ACCELERATOR to accel,
            ConfigKey.MAX_TOKENS to maxTokens,
            ConfigKey.TOP_K to topK,
            ConfigKey.TOP_P to topP,
            ConfigKey.TEMPERATURE to temp
        )

        return Model(
            name = modelName,
            taskPath = file.absolutePath,
            config = config
        )
    }

    /**
     * Prefetch implementation: sequentially read the model file to warm OS page cache.
     *
     * Cancellation:
     * - If this coroutine is cancelled (e.g., compile requested), it must NOT report Prefetched.
     * - State/logging must reflect the actual bytes read.
     */
    private suspend fun runPrefetch(file: File, runId: Long) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val total = file.length().takeIf { it > 0L }

        _prefetchState.value = PrefetchState.Running(
            file = file,
            startedAtMs = startedAtMs,
            downloaded = 0L,
            total = total,
            elapsedMs = 0L
        )

        val buf = ByteArray(PREFETCH_BUF_SIZE)
        var readTotal = 0L
        var lastEmitAt = startedAtMs

        FileInputStream(file).use { fis ->
            while (coroutineContext.isActive) {
                val n = fis.read(buf)
                if (n <= 0) break
                readTotal += n.toLong()

                val now = SystemClock.elapsedRealtime()
                if (now - lastEmitAt >= PREFETCH_EMIT_INTERVAL_MS) {
                    _prefetchState.value = PrefetchState.Running(
                        file = file,
                        startedAtMs = startedAtMs,
                        downloaded = readTotal,
                        total = total,
                        elapsedMs = now - startedAtMs
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
                elapsedMs = end - startedAtMs
            )

            if (req != null && req.runId == runId) {
                prefetchCancelRequestRef.compareAndSet(req, null)
            }

            debugLog(
                "prefetch: cancelled(pid=${Process.myPid()} runId=$runId) " +
                        "reason='$reason' readBytes=$readTotal total=${total ?: -1} elapsedMs=${end - startedAtMs}"
            )
            return
        }

        _prefetchState.value = PrefetchState.Prefetched(
            file = file,
            sizeBytes = readTotal,
            startedAtMs = startedAtMs,
            elapsedMs = end - startedAtMs
        )

        debugLog(
            "prefetch: completed pid=${Process.myPid()} runId=$runId file='${file.name}' " +
                    "readBytes=$readTotal total=${total ?: -1} elapsedMs=${end - startedAtMs}"
        )
    }

    /**
     * Cancel prefetch if it is running, to avoid IO contention with compile.
     *
     * Important:
     * - Do NOT update state/log here. Let runPrefetch() be the single source of truth.
     */
    private fun cancelPrefetchIfRunning(reason: String) {
        val st = _prefetchState.value
        if (st !is PrefetchState.Running) return

        val runId = activePrefetchRunId.get()
        prefetchCancelRequestRef.set(
            PrefetchCancelRequest(
                runId = runId,
                reason = reason,
                requestedAtMs = SystemClock.elapsedRealtime()
            )
        )

        prefetchJobRef.getAndSet(null)?.cancel()
    }

    private suspend fun runCompile(file: File, appContext: Context, runId: Long) {
        cancelPrefetchIfRunning(reason = "compile-start")

        val startedAtMs = SystemClock.elapsedRealtime()
        _compileState.value = CompileState.Compiling(
            file = file,
            startedAtMs = startedAtMs,
            elapsedMs = 0L
        )
        debugLog("compile: begin pid=${Process.myPid()} runId=$runId file='${file.name}'")

        val ticker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(COMPILE_EMIT_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val st = _compileState.value
                if (st is CompileState.Compiling && st.startedAtMs == startedAtMs) {
                    _compileState.value = st.copy(elapsedMs = now - startedAtMs)
                } else {
                    break
                }
            }
        }

        try {
            val model = buildWarmupModel(appContext, file)
            runCompileViaEglIfPossible(appContext = appContext, model = model, runId = runId)

            val end = SystemClock.elapsedRealtime()
            _compileState.value = CompileState.Compiled(
                file = file,
                startedAtMs = startedAtMs,
                elapsedMs = end - startedAtMs
            )
            debugLog(
                "compile: end pid=${Process.myPid()} runId=$runId elapsedMs=${end - startedAtMs} file='${file.name}'"
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
        val thread = eglThreadRef.get() ?: run {
            val created = runCatching { EglPbufferThread(threadName = "SlmWarmupEgl") }.getOrNull()
            if (created != null && eglThreadRef.compareAndSet(null, created)) {
                created
            } else {
                eglThreadRef.get()
            }
        }

        LiteRtLM.setApplicationContext(appContext.applicationContext)

        val supportImage = warmupSupportImage.get()
        val supportAudio = warmupSupportAudio.get()
        val systemMessage = warmupSystemMessageRef.get()
        val tools = warmupToolsRef.get()

        debugLog(
            "compile: init options pid=${Process.myPid()} runId=$runId " +
                    "image=$supportImage audio=$supportAudio system=${systemMessage != null} tools=${tools.size}"
        )

        if (thread == null) {
            debugLog(
                "compile: egl thread unavailable; running initializeIfNeeded directly " +
                        "pid=${Process.myPid()} runId=$runId"
            )
            LiteRtLM.initializeIfNeeded(
                context = appContext.applicationContext,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools
            )
            return
        }

        try {
            debugLog("compile: entering egl context pid=${Process.myPid()} runId=$runId")
            thread.withEglContext {
                LiteRtLM.initializeIfNeeded(
                    context = appContext.applicationContext,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    systemMessage = systemMessage,
                    tools = tools
                )
            }
        } catch (t: Throwable) {
            debugLog(
                "compile: egl warmup failed; retrying direct pid=${Process.myPid()} runId=$runId " +
                        "err=${t.javaClass.simpleName}(${t.message})"
            )
            LiteRtLM.initializeIfNeeded(
                context = appContext.applicationContext,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools
            )
        }
    }

    private fun resolveModelFileOrNull(appContext: Context): File? {
        val dir = appContext.filesDir ?: return null
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val candidates = files.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.length() }
    }

    private fun closeEglThread(reason: String) {
        val thread = eglThreadRef.getAndSet(null) ?: return
        runCatching { thread.close() }
            .onFailure { t ->
                AppLog.w(TAG, "eglThread close failed: reason='$reason' err=${t.message}", t)
            }
        debugLog("eglThread closed: reason='$reason'")
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

    private fun isPrefetchTerminal(state: PrefetchState): Boolean {
        return when (state) {
            is PrefetchState.Prefetched,
            is PrefetchState.Failed,
            is PrefetchState.Cancelled,
            is PrefetchState.SkippedNotConfigured -> true
            else -> false
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

    private fun debugLog(msg: String) {
        AppLog.d(TAG, msg)
    }

    private const val PREFETCH_BUF_SIZE = 4 * 1024 * 1024
    private const val PREFETCH_EMIT_INTERVAL_MS = 250L
    private const val COMPILE_EMIT_INTERVAL_MS = 250L
    private const val AUTO_COMPILE_DELAY_MS = 120L
}