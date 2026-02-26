/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmWarmup.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  App-startup warmup coordinator:
 *   1) Load SurveyConfig (assets)
 *   2) Download model file (HeavyInitializer)
 *   3) Initialize LiteRT-LM (LiteRtLM.initializeIfNeeded)
 *   4) Best-effort compile warmup (first inference) to avoid jank on first user submit
 *
 *  Goals:
 *  - Idempotent per process
 *  - Never block UI thread
 *  - Expose state/progress for UI/debug
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.content.Context
import android.os.SystemClock
import com.negi.surveys.BuildConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.utils.HeavyInitializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "SlmWarmup"

/**
 * Timeout for the best-effort "compile warmup" inference.
 *
 * Rationale:
 * - The first inference on GPU often triggers expensive delegate compilation / shader build.
 * - If we do it here (startup), we avoid a big jank spike when the user presses Submit.
 */
private const val COMPILE_WARMUP_TIMEOUT_MS = 120_000L

/**
 * Extra run time after receiving the first non-empty delta.
 *
 * Rationale:
 * - Cancelling immediately on the first token can be too early for some GPU backends:
 *   compilation/initialization may still be in-flight.
 * - Letting it run a bit more improves the chance that the heavy path completes during warmup.
 */
private const val COMPILE_WARMUP_EXTRA_RUN_AFTER_FIRST_DELTA_MS = 2_500L

/**
 * Force-cancel guard in case we never see any delta (TTFT too slow or stalled).
 *
 * This prevents warmup from hogging the engine forever on problematic devices.
 */
private const val COMPILE_WARMUP_FORCE_CANCEL_MS = 25_000L

object SlmWarmup {

    /**
     * Warmup state for UI/debug.
     *
     * NOTE:
     * - `startedAtMs` is elapsedRealtime() base.
     * - `elapsedMs` exists on the base sealed type so callers can always reference it.
     */
    sealed class PrefetchState(open val startedAtMs: Long? = null) {
        val elapsedMs: Long
            get() = startedAtMs?.let { now -> (SystemClock.elapsedRealtime() - now).coerceAtLeast(0L) } ?: 0L

        data object Idle : PrefetchState()

        data class Running(
            val downloaded: Long,
            val total: Long?,
            override val startedAtMs: Long
        ) : PrefetchState(startedAtMs)

        data class Initializing(
            val file: File,
            override val startedAtMs: Long
        ) : PrefetchState(startedAtMs)

        data class Initialized(
            val file: File
        ) : PrefetchState()

        data class Failed(val message: String) : PrefetchState()
        data object Cancelled : PrefetchState()
        data class SkippedNotConfigured(val reason: String) : PrefetchState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow<PrefetchState>(PrefetchState.Idle)
    val state: StateFlow<PrefetchState> = _state.asStateFlow()

    private val modelRef = AtomicReference<Model?>(null)
    private val modelFileRef = AtomicReference<File?>(null)

    private const val DEFAULT_CONFIG_ASSET = "survey.yaml"

    /**
     * Start warmup once per process.
     *
     * This will:
     * - Load SurveyConfig from assets
     * - Download the model file if needed
     * - Initialize LiteRT-LM
     * - Trigger a tiny first inference (best-effort) to pre-compile GPU delegate
     */
    fun startWarmupIfConfigured(
        context: Context,
        configAssetName: String = DEFAULT_CONFIG_ASSET,
        forceFresh: Boolean = false,
    ) {
        if (!started.compareAndSet(false, true)) return

        val app = context.applicationContext

        scope.launch {
            try {
                // Step 0: Load config (best-effort).
                val cfg = runCatching {
                    SurveyConfigLoader.fromAssetsValidated(app, configAssetName)
                }.getOrElse { t ->
                    val msg = "config load failed: ${t.message ?: t::class.java.simpleName}"
                    AppLog.w(TAG, "Prefetch skipped: $msg", t)
                    _state.value = PrefetchState.SkippedNotConfigured(msg)
                    return@launch
                }

                val url = cfg.modelDefaults.defaultModelUrl?.trim().orEmpty()
                val fileName = cfg.modelDefaults.defaultFileName?.trim().orEmpty()

                if (url.isBlank() || fileName.isBlank()) {
                    val msg = "model_defaults not set (default_model_url/default_file_name missing)"
                    AppLog.w(TAG, "Prefetch skipped: $msg")
                    _state.value = PrefetchState.SkippedNotConfigured(msg)
                    return@launch
                }

                val token = readOptionalBuildConfigString("HF_TOKEN")?.takeIf { it.isNotBlank() }

                // Step 1: Download/ensure file exists.
                val downloadStart = SystemClock.elapsedRealtime()
                _state.value = PrefetchState.Running(downloaded = 0L, total = null, startedAtMs = downloadStart)

                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = url,
                    hfToken = token,
                    fileName = fileName,
                    timeoutMs = cfg.modelDefaults.timeoutMs ?: (10 * 60_000L),
                    forceFresh = forceFresh,
                    onProgress = { downloaded, total ->
                        _state.value = PrefetchState.Running(
                            downloaded = downloaded,
                            total = total,
                            startedAtMs = downloadStart
                        )
                    }
                )

                val modelFile = result.getOrElse { t ->
                    val msg = "download failed: ${t.message ?: t::class.java.simpleName}"
                    AppLog.w(TAG, msg, t)
                    _state.value = PrefetchState.Failed(msg)
                    return@launch
                }

                modelFileRef.set(modelFile)
                AppLog.i(TAG, "Prefetch completed: path='${modelFile.absolutePath}' size=${modelFile.length()}")

                // Step 2: Initialize LiteRT-LM AFTER download completion.
                val initStart = SystemClock.elapsedRealtime()
                _state.value = PrefetchState.Initializing(file = modelFile, startedAtMs = initStart)

                val modelName = cfg.modelDefaults.modelName?.takeIf { it.isNotBlank() }
                    ?: modelFile.nameWithoutExtension.ifBlank { "SLM" }

                val configMap = buildModelConfig(cfg.slm)

                val model = Model(
                    name = modelName,
                    taskPath = modelFile.absolutePath,
                    config = configMap
                )
                modelRef.set(model)

                // Keep init off the UI thread.
                // `initializeIfNeeded` is suspend and already guarded internally.
                LiteRtLM.initializeIfNeeded(
                    context = app,
                    model = model,
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList(),
                )

                // Step 3: Best-effort compile warmup.
                // Non-fatal. If it fails, we still consider the engine initialized.
                runCatching {
                    compileWarmupInference(model)
                }.onFailure { t ->
                    val msg = t.message ?: t::class.java.simpleName
                    AppLog.w(TAG, "compile warmup skipped/failed (non-fatal): $msg", t)
                }

                _state.value = PrefetchState.Initialized(file = modelFile)
                AppLog.i(TAG, "LiteRT-LM warmup completed: model='${model.name}' file='${modelFile.name}'")

            } catch (ce: CancellationException) {
                AppLog.w(TAG, "Warmup cancelled", ce)
                _state.value = PrefetchState.Cancelled
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.java.simpleName
                AppLog.w(TAG, "Warmup crashed: $msg", t)
                _state.value = PrefetchState.Failed(msg)
            }
        }
    }

    /**
     * Wait until warmup reaches Initialized, then return the Model.
     *
     * Use this in repositories/services that must not run until the engine is ready.
     */
    suspend fun awaitInitializedModel(
        context: Context,
        timeoutMs: Long = 120_000L,
        configAssetName: String = DEFAULT_CONFIG_ASSET,
    ): Model {
        startWarmupIfConfigured(context, configAssetName = configAssetName)

        val ready = withTimeout(timeoutMs.coerceAtLeast(1L)) {
            state.first {
                it is PrefetchState.Initialized ||
                        it is PrefetchState.Failed ||
                        it is PrefetchState.Cancelled ||
                        it is PrefetchState.SkippedNotConfigured
            }
        }

        when (ready) {
            is PrefetchState.Initialized -> {
                return modelRef.get()
                    ?: throw IllegalStateException("Warmup state is Initialized but modelRef is null (bug).")
            }
            is PrefetchState.Failed -> throw IllegalStateException("Warmup failed: ${ready.message}")
            is PrefetchState.Cancelled -> throw CancellationException("Warmup cancelled")
            is PrefetchState.SkippedNotConfigured -> throw IllegalStateException("Warmup skipped: ${ready.reason}")
            else -> throw IllegalStateException("Unexpected warmup terminal state: ${ready::class.java.simpleName}")
        }
    }

    fun getInitializedModelOrNull(): Model? = modelRef.get()
    fun getModelFileOrNull(): File? = modelFileRef.get()

    private fun readOptionalBuildConfigString(fieldName: String): String? {
        return runCatching {
            val cls = BuildConfig::class.java
            val f = cls.getDeclaredField(fieldName)
            f.isAccessible = true
            f.get(null) as? String
        }.getOrNull()
    }

    /**
     * Trigger the "first decode" path to pre-compile GPU delegate/shaders.
     *
     * Implementation details:
     * - Runs a tiny inference.
     * - Instead of cancelling immediately on the first token, let it run briefly,
     *   then cancel to finish heavy initialization on more devices.
     * - Always resets the conversation afterwards to avoid polluting user context.
     */
    private suspend fun compileWarmupInference(model: Model) {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "compile warmup: begin model='${model.name}'")

        val done = CompletableDeferred<Unit>()
        val cancelIssued = AtomicBoolean(false)
        val firstDeltaSeen = AtomicBoolean(false)

        var delayedCancelJob: Job? = null
        var forceCancelJob: Job? = null

        try {
            // Safety: if TTFT is too slow (or stalled), force-cancel.
            forceCancelJob = scope.launch {
                delay(COMPILE_WARMUP_FORCE_CANCEL_MS)
                if (cancelIssued.compareAndSet(false, true)) {
                    AppLog.w(TAG, "compile warmup: force cancel after ${COMPILE_WARMUP_FORCE_CANCEL_MS}ms")
                    LiteRtLM.cancel(model)
                }
            }

            LiteRtLM.runInference(
                model = model,
                input = "Warmup.",
                resultListener = { delta, isDone ->
                    if (delta.isNotEmpty() && firstDeltaSeen.compareAndSet(false, true)) {
                        AppLog.d(TAG, "compile warmup: first delta received")

                        // Let it run a bit longer to increase the chance that GPU compilation completes.
                        delayedCancelJob = scope.launch {
                            delay(COMPILE_WARMUP_EXTRA_RUN_AFTER_FIRST_DELTA_MS)
                            if (cancelIssued.compareAndSet(false, true)) {
                                AppLog.d(TAG, "compile warmup: delayed cancel after first delta")
                                LiteRtLM.cancel(model)
                            }
                        }
                    }

                    if (isDone && !done.isCompleted) {
                        done.complete(Unit)
                    }
                },
                cleanUpListener = {
                    if (!done.isCompleted) done.complete(Unit)
                },
                onError = {
                    if (!done.isCompleted) done.complete(Unit)
                },
                images = emptyList(),
                audioClips = emptyList(),
                notifyCancelToOnError = false
            )

            withTimeout(COMPILE_WARMUP_TIMEOUT_MS) {
                done.await()
            }
        } finally {
            runCatching { delayedCancelJob?.cancel() }
            runCatching { forceCancelJob?.cancel() }

            // Ensure the next real request starts clean.
            runCatching {
                LiteRtLM.resetConversation(
                    model = model,
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList()
                )
            }

            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
            AppLog.i(TAG, "compile warmup: end elapsedMs=$elapsed firstDeltaSeen=${firstDeltaSeen.get()} cancelIssued=${cancelIssued.get()}")
        }
    }
}