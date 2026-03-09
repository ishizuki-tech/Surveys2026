/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Startup ViewModel)
 *  ---------------------------------------------------------------------
 *  File: AppStartupViewModel.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.StartupConfigState
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Startup orchestration ViewModel.
 *
 * Responsibilities:
 * - Hold root startup UI state.
 * - Expose startup entry points to the UI.
 * - Bridge process-scoped downloader / warmup state into UI state.
 *
 * Non-responsibilities:
 * - Config install / recovery policy
 * - Service graph construction details
 * - Local model warmup wiring policy
 */
class AppStartupViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application.applicationContext

    private val firstFrameSeen = AtomicBoolean(false)
    private val startupRequested = AtomicBoolean(false)
    private val lastWarmupReadyFingerprint = AtomicReference<StartupModelWiring.ModelReadyFingerprint?>(null)

    private val modelWiring = StartupModelWiring(app)
    private val startupCoordinator = StartupCoordinator(app)

    private var serviceCollectorsJob: Job? = null
    private var startupPipelineJob: Job? = null

    private val _uiState = MutableStateFlow(AppStartupUiState())
    val uiState: StateFlow<AppStartupUiState> = _uiState.asStateFlow()

    init {
        startStartupPipeline(reason = "init")
    }

    override fun onCleared() {
        startupPipelineJob?.cancel()
        serviceCollectorsJob?.cancel()
        super.onCleared()
    }

    /**
     * Signals that the UI has drawn its first frame.
     *
     * Design:
     * - The root composable should call this after the first frame callback.
     * - Actual startup work still lives here, not in the composable.
     */
    fun onFirstFrameRendered() {
        if (!firstFrameSeen.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            delay(STARTUP_AFTER_FIRST_FRAME_DELAY_MS)
            tryStartStartup(reason = "firstFrame")
        }
    }

    /**
     * Runs the unified retry pipeline for model + warmup + startup config.
     *
     * Behavior:
     * - If startup is currently failed or services are not ready, restart the whole startup pipeline.
     * - Otherwise retry downloader / warmup in-place.
     */
    fun retryAll(fromRaw: String) {
        val from = sanitizeLabel(fromRaw)
        val snapshot = _uiState.value
        val needsPipelineRestart =
            snapshot.configState is StartupConfigState.Failed ||
                    snapshot.repository == null ||
                    !snapshot.servicesReady

        if (needsPipelineRestart) {
            SafeLog.w(TAG, "RetryAll: restart startup pipeline from=$from")
            startStartupPipeline(reason = "retry:$from")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val current = _uiState.value
            val downloader = current.modelDownloader
            val warmup = current.warmup
            val key = current.modelSpecKey
            val onDeviceEnabled = current.onDeviceEnabled

            SafeLog.w(
                TAG,
                "RetryAll requested from=$from onDevice=$onDeviceEnabled keyPresent=${key != null}",
            )

            /**
             * Allow the same local file to re-trigger warmup wiring after retry.
             *
             * Why:
             * - retryAll may hit the same local file path / same bytes again.
             * - Without clearing this fingerprint, onModelReady() can be skipped.
             */
            lastWarmupReadyFingerprint.set(null)

            if (onDeviceEnabled && key == null) {
                val localFile = modelWiring.resolveExistingLocalModelFileOrNull(current.modelSpec)
                if (localFile != null && warmup != null) {
                    _uiState.update {
                        it.copy(
                            modelState = modelWiring.localReadyModelState(localFile),
                            servicesReady = false,
                        )
                    }

                    val localWarmupReady =
                        modelWiring.onModelReady(
                            repoMode = current.repoMode,
                            warmup = warmup,
                            file = localFile,
                            lastReadyFingerprintRef = lastWarmupReadyFingerprint,
                        )

                    _uiState.update {
                        it.copy(
                            servicesReady = modelWiring.deriveServicesReady(
                                onDeviceEnabled = it.onDeviceEnabled,
                                downloader = it.modelDownloader,
                                localWarmupReady = localWarmupReady,
                            ),
                        )
                    }

                    if (!localWarmupReady) {
                        SafeLog.w(
                            TAG,
                            "RetryAll: local-only warmup wiring did not complete; leaving servicesReady=false",
                        )
                    }

                    return@launch
                }

                SafeLog.w(TAG, "RetryAll: local-only retry could not resolve a model file")
                startStartupPipeline(reason = "retry:$from")
                return@launch
            }

            if (onDeviceEnabled && downloader != null) {
                runCatching { downloader.resetForRetry(reason = "uiRetry") }
                    .onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: model resetForRetry failed (non-fatal) type=${t::class.java.simpleName}",
                        )
                    }

                if (key != null) {
                    runCatching {
                        downloader.ensureModelOnce(
                            timeoutMs = key.timeoutMs,
                            forceFresh = key.forceFreshOnStart,
                            reason = "uiRetry",
                        )
                    }.onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: model ensure failed (non-fatal) type=${t::class.java.simpleName}",
                        )
                    }
                }
            }

            if (warmup != null) {
                runCatching { warmup.resetForRetry(reason = "uiRetry") }
                    .onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: warmup resetForRetry failed (non-fatal) type=${t::class.java.simpleName}",
                        )
                    }

                if (onDeviceEnabled && key != null) {
                    runCatching {
                        warmup.requestCompileAfterPrefetch(reason = "uiRetry")
                    }.onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: warmup requestCompileAfterPrefetch failed (non-fatal) type=${t::class.java.simpleName}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Starts the startup pipeline from a clean root-level orchestration state.
     */
    private fun startStartupPipeline(reason: String) {
        val safeReason = sanitizeLabel(reason)
        val repoMode = computeRepoMode()

        startupPipelineJob?.cancel()
        serviceCollectorsJob?.cancel()
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        _uiState.update {
            it.copy(
                configState = StartupConfigState.Loading,
                modelSpec = null,
                modelSpecKey = null,
                repoMode = repoMode,
                onDeviceEnabled = repoMode == AppProcessServices.RepoMode.ON_DEVICE,
                servicesReady = false,
                repository = null,
                warmup = null,
                modelDownloader = null,
                modelState = ModelDownloadController.ModelState.Idle(elapsedMs = 0L),
                prefetchState = WarmupController.PrefetchState.Idle,
                compileState = WarmupController.CompileState.Idle,
            )
        }

        startupPipelineJob = viewModelScope.launch(Dispatchers.Default) {
            SafeLog.i(TAG, "Startup pipeline: begin reason=$safeReason")

            when (val result = startupCoordinator.prepareStartup(::computeRepoMode, modelWiring)) {
                is StartupCoordinator.StartupPreparationResult.Failed -> {
                    failStartup(
                        safeReason = result.safeReason,
                        repoMode = result.repoMode,
                        modelSpec = result.modelSpec,
                        modelSpecKey = result.modelSpecKey,
                    )
                }

                is StartupCoordinator.StartupPreparationResult.Ready -> {
                    val prepared = result.prepared

                    _uiState.update {
                        it.copy(
                            configState = StartupConfigState.Ready(prepared.installedConfig),
                            modelSpec = prepared.modelSpec,
                            modelSpecKey = prepared.modelSpecKey,
                            repoMode = prepared.repoMode,
                            onDeviceEnabled = prepared.onDeviceEnabled,
                        )
                    }

                    activatePreparedServices(prepared)
                }
            }
        }
    }

    /**
     * Activates process-scoped services for a prepared startup configuration.
     */
    private suspend fun activatePreparedServices(
        prepared: StartupCoordinator.StartupPreparedConfig,
    ) {
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        val services =
            startupCoordinator.buildServices(
                repoMode = prepared.repoMode,
                modelSpec = prepared.modelSpec,
                modelSpecKey = prepared.modelSpecKey,
            )

        if (services == null) {
            failStartup(
                safeReason = "ServiceInitFailed",
                repoMode = prepared.repoMode,
                modelSpec = prepared.modelSpec,
                modelSpecKey = prepared.modelSpecKey,
            )
            return
        }

        SafeLog.i(
            TAG,
            "Services: obtained mode=${prepared.repoMode} onDevice=${prepared.onDeviceEnabled} " +
                    "keyPresent=${prepared.modelSpecKey != null} specPresent=${prepared.modelSpec != null} " +
                    "downloaderPresent=${services.downloader != null} localModelPresent=${prepared.initialLocalModelFile != null}",
        )

        val initialModelState =
            when {
                services.downloader != null -> services.downloader.state.value
                prepared.initialLocalModelFile != null -> {
                    modelWiring.localReadyModelState(prepared.initialLocalModelFile)
                }
                else -> ModelDownloadController.ModelState.Idle(elapsedMs = 0L)
            }

        _uiState.update {
            it.copy(
                repository = services.repository,
                warmup = services.warmup,
                modelDownloader = services.downloader,
                servicesReady = modelWiring.deriveServicesReady(
                    onDeviceEnabled = it.onDeviceEnabled,
                    downloader = services.downloader,
                    localWarmupReady = false,
                ),
                modelState = initialModelState,
                prefetchState = services.warmup.prefetchState.value,
                compileState = services.warmup.compileState.value,
            )
        }

        startCollectingServiceStates(
            repoMode = prepared.repoMode,
            warmup = services.warmup,
            downloader = services.downloader,
        )

        if (prepared.initialLocalModelFile != null) {
            val localWarmupReady =
                modelWiring.onModelReady(
                    repoMode = prepared.repoMode,
                    warmup = services.warmup,
                    file = prepared.initialLocalModelFile,
                    lastReadyFingerprintRef = lastWarmupReadyFingerprint,
                )

            _uiState.update {
                it.copy(
                    servicesReady = modelWiring.deriveServicesReady(
                        onDeviceEnabled = it.onDeviceEnabled,
                        downloader = it.modelDownloader,
                        localWarmupReady = localWarmupReady,
                    ),
                )
            }

            if (!localWarmupReady) {
                SafeLog.w(
                    TAG,
                    "Services: local model found but warmup wiring did not complete; servicesReady stays false",
                )
            }
        }

        tryStartStartup(reason = "servicesReady")
    }

    /**
     * Collects process-scoped states exactly once at the root orchestration layer.
     */
    private fun startCollectingServiceStates(
        repoMode: AppProcessServices.RepoMode,
        warmup: WarmupController,
        downloader: ModelDownloadController?,
    ) {
        serviceCollectorsJob?.cancel()
        serviceCollectorsJob = viewModelScope.launch(Dispatchers.Default) {
            if (downloader != null) {
                launch {
                    try {
                        downloader.state.collect { modelState ->
                            _uiState.update { it.copy(modelState = modelState) }
                            if (modelState is ModelDownloadController.ModelState.Ready) {
                                modelWiring.onModelReady(
                                    repoMode = repoMode,
                                    warmup = warmup,
                                    file = modelState.file,
                                    lastReadyFingerprintRef = lastWarmupReadyFingerprint,
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        handleServiceCollectorFailure(
                            safeReason = "ModelStateCollectorFailed",
                            repoMode = repoMode,
                            t = t,
                        )
                    }
                }
            }

            launch {
                try {
                    warmup.prefetchState.collect { prefetchState ->
                        _uiState.update { it.copy(prefetchState = prefetchState) }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    handleServiceCollectorFailure(
                        safeReason = "PrefetchStateCollectorFailed",
                        repoMode = repoMode,
                        t = t,
                    )
                }
            }

            launch {
                try {
                    warmup.compileState.collect { compileState ->
                        _uiState.update { it.copy(compileState = compileState) }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    handleServiceCollectorFailure(
                        safeReason = "CompileStateCollectorFailed",
                        repoMode = repoMode,
                        t = t,
                    )
                }
            }
        }
    }

    /**
     * Starts model download startup once the first frame and services are ready.
     *
     * Notes:
     * - Local-only startup is supported by warmup input injection and local file probing.
     * - Remote downloader startup only runs when a valid remote model spec key exists.
     */
    private suspend fun tryStartStartup(reason: String) {
        if (!firstFrameSeen.get()) return

        val snapshot = _uiState.value
        if (!snapshot.onDeviceEnabled) return

        val key = snapshot.modelSpecKey ?: return
        val downloader = snapshot.modelDownloader ?: return

        if (!startupRequested.compareAndSet(false, true)) return

        SafeLog.i(TAG, "Startup: begin reason=${sanitizeLabel(reason)} keyPresent=true")

        runCatching {
            downloader.ensureModelOnce(
                timeoutMs = key.timeoutMs,
                forceFresh = key.forceFreshOnStart,
                reason = "startup",
            )
        }.onFailure { t ->
            startupRequested.set(false)
            SafeLog.e(
                TAG,
                "Startup: ensureModelOnce failed (non-fatal) type=${t::class.java.simpleName}",
            )
        }
    }

    /**
     * Handles a collector failure as a safe startup failure.
     */
    private fun handleServiceCollectorFailure(
        safeReason: String,
        repoMode: AppProcessServices.RepoMode,
        t: Throwable,
    ) {
        SafeLog.e(
            TAG,
            "Services: collector failed reason=${sanitizeLabel(safeReason)} type=${t::class.java.simpleName}",
        )
        failStartup(
            safeReason = safeReason,
            repoMode = repoMode,
            modelSpec = _uiState.value.modelSpec,
            modelSpecKey = _uiState.value.modelSpecKey,
        )
    }

    /**
     * Resets UI-visible startup state into a safe failure mode.
     *
     * Notes:
     * - The reason must never contain exception.message.
     * - Existing process-scoped singletons are left intact; only root orchestration state is reset.
     */
    private fun failStartup(
        safeReason: String,
        repoMode: AppProcessServices.RepoMode,
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ) {
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        _uiState.update {
            it.copy(
                configState = StartupConfigState.Failed(safeReason),
                modelSpec = modelSpec,
                modelSpecKey = modelSpecKey,
                repoMode = repoMode,
                onDeviceEnabled = repoMode == AppProcessServices.RepoMode.ON_DEVICE,
                servicesReady = false,
                repository = null,
                warmup = null,
                modelDownloader = null,
                modelState = ModelDownloadController.ModelState.Idle(elapsedMs = 0L),
                prefetchState = WarmupController.PrefetchState.Idle,
                compileState = WarmupController.CompileState.Idle,
            )
        }

        serviceCollectorsJob?.cancel()
    }

    /**
     * Computes repo mode from the centralized process-wide selector.
     */
    private fun computeRepoMode(): AppProcessServices.RepoMode {
        return AppProcessServices.configuredRepoMode()
    }

    /**
     * Sanitizes a small label for logs.
     */
    private fun sanitizeLabel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"

        val safe = buildString {
            for (c in trimmed) {
                if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') {
                    append(c)
                }
                if (length >= 32) break
            }
        }

        return safe.ifBlank { "unknown" }
    }

    companion object {
        private const val TAG = "AppStartupViewModel"
        private const val STARTUP_AFTER_FIRST_FRAME_DELAY_MS: Long = 150L
    }
}