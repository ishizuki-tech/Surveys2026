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
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.config.InstalledSurveyConfigStore
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.resolveModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * - Wait for the Application-installed SurveyConfig.
 * - Resolve model download spec and repo mode.
 * - Obtain process-scoped repository / warmup / downloader instances.
 * - Collect downloader + warmup states once and expose a single UI state.
 * - Own retry and startup orchestration.
 *
 * Non-responsibilities:
 * - Navigation
 * - Screen rendering
 * - Stream bridge ownership
 */
class AppStartupViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app = application.applicationContext

    private val firstFrameSeen = AtomicBoolean(false)
    private val startupRequested = AtomicBoolean(false)
    private val lastWarmupReadyFingerprint = AtomicReference<ModelReadyFingerprint?>(null)

    private var serviceCollectorsJob: Job? = null

    private val _uiState = MutableStateFlow(AppStartupUiState())
    val uiState: StateFlow<AppStartupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            awaitInstalledConfigAndBuildServices()
        }
    }

    override fun onCleared() {
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
     * Runs the unified retry pipeline for model + warmup.
     */
    fun retryAll(fromRaw: String) {
        val from = sanitizeLabel(fromRaw)

        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _uiState.value
            val downloader = snapshot.modelDownloader
            val warmup = snapshot.warmup
            val key = snapshot.modelSpecKey
            val onDeviceEnabled = snapshot.onDeviceEnabled

            SafeLog.w(
                TAG,
                "RetryAll requested from=$from " +
                        "onDevice=$onDeviceEnabled keyPresent=${key != null}",
            )

            if (onDeviceEnabled && downloader != null) {
                runCatching {
                    downloader.resetForRetry(reason = "uiRetry")
                }.onFailure { t ->
                    SafeLog.e(
                        TAG,
                        "RetryAll: model resetForRetry failed (non-fatal) " +
                                "type=${t::class.java.simpleName}",
                    )
                }

                if (key != null) {
                    runCatching {
                        downloader.ensureModelOnce(
                            timeoutMs = key.timeoutMs,
                            forceFresh = false,
                            reason = "uiRetry",
                        )
                    }.onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: model ensure failed (non-fatal) " +
                                    "type=${t::class.java.simpleName}",
                        )
                    }
                } else {
                    SafeLog.w(TAG, "RetryAll: skip model ensure (modelSpecKey missing)")
                }
            }

            if (warmup != null) {
                runCatching {
                    warmup.resetForRetry(reason = "uiRetry")
                }.onFailure { t ->
                    SafeLog.e(
                        TAG,
                        "RetryAll: warmup resetForRetry failed (non-fatal) " +
                                "type=${t::class.java.simpleName}",
                    )
                }

                if (onDeviceEnabled && key != null) {
                    runCatching {
                        warmup.requestCompileAfterPrefetch(reason = "uiRetry")
                    }.onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "RetryAll: warmup requestCompileAfterPrefetch failed (non-fatal) " +
                                    "type=${t::class.java.simpleName}",
                        )
                    }
                } else if (onDeviceEnabled) {
                    SafeLog.w(
                        TAG,
                        "RetryAll: skip warmup compile request (modelSpecKey missing)",
                    )
                }
            }
        }
    }

    /**
     * Waits briefly for the process-installed config and then builds process-scoped services.
     */
    private suspend fun awaitInstalledConfigAndBuildServices() {
        val t0 = SystemClock.elapsedRealtime()
        SafeLog.i(TAG, "Config: waiting for installed config (Application-owned)")

        val deadline = t0 + INSTALLED_CONFIG_WAIT_MS
        var installed: SurveyConfig? = null

        while (SystemClock.elapsedRealtime() < deadline && installed == null) {
            installed = InstalledSurveyConfigStore.getOrNull()
            if (installed != null) break
            delay(INSTALLED_CONFIG_POLL_MS)
        }

        if (installed == null) {
            val dt = SystemClock.elapsedRealtime() - t0
            SafeLog.e(TAG, "Config: missing (installed config not found) after ${dt}ms")
            _uiState.update {
                it.copy(
                    configState = StartupConfigState.Failed("InstalledConfigMissing"),
                    servicesReady = false,
                )
            }
            return
        }

        val dt = SystemClock.elapsedRealtime() - t0
        SafeLog.i(TAG, "Config: ready (installed) in ${dt}ms")

        val modelSpec = installed.resolveModelDownloadSpec()
        val modelSpecKey = buildModelSpecKey(modelSpec)
        val repoMode = computeRepoMode()
        val onDeviceEnabled = repoMode == AppProcessServices.RepoMode.ON_DEVICE

        _uiState.update {
            it.copy(
                configState = StartupConfigState.Ready(installed),
                modelSpec = modelSpec,
                modelSpecKey = modelSpecKey,
                repoMode = repoMode,
                onDeviceEnabled = onDeviceEnabled,
            )
        }

        rebuildServices(
            repoMode = repoMode,
            modelSpec = modelSpec,
            modelSpecKey = modelSpecKey,
        )
    }

    /**
     * Obtains process-scoped services and starts centralized state collection.
     *
     * Notes:
     * - Rebuild resets startup markers so a new service graph can bootstrap again.
     * - This keeps future config/service swaps from getting stuck behind stale flags.
     */
    private suspend fun rebuildServices(
        repoMode: AppProcessServices.RepoMode,
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ) {
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        val repo = AppProcessServices.repository(app, repoMode)
        val warmup = AppProcessServices.warmupController(app, repoMode)
        val downloader = AppProcessServices.modelDownloader(
            app,
            spec = modelSpecKey?.let { modelSpec },
        )

        SafeLog.i(
            TAG,
            "Services: obtained mode=$repoMode onDevice=${repoMode == AppProcessServices.RepoMode.ON_DEVICE} " +
                    "keyPresent=${modelSpecKey != null} specPresent=${modelSpec != null}",
        )

        _uiState.update {
            it.copy(
                repository = repo,
                warmup = warmup,
                modelDownloader = downloader,
                servicesReady = true,
                modelState = downloader.state.value,
                prefetchState = warmup.prefetchState.value,
                compileState = warmup.compileState.value,
            )
        }

        startCollectingServiceStates(
            repoMode = repoMode,
            warmup = warmup,
            downloader = downloader,
        )

        tryStartStartup(reason = "servicesReady")
    }

    /**
     * Collects process-scoped states exactly once at the root orchestration layer.
     */
    private fun startCollectingServiceStates(
        repoMode: AppProcessServices.RepoMode,
        warmup: WarmupController,
        downloader: ModelDownloadController,
    ) {
        serviceCollectorsJob?.cancel()

        serviceCollectorsJob =
            viewModelScope.launch(Dispatchers.Default) {
                launch {
                    downloader.state.collect { modelState ->
                        _uiState.update { it.copy(modelState = modelState) }

                        if (modelState is ModelDownloadController.ModelState.Ready) {
                            onModelReady(
                                repoMode = repoMode,
                                warmup = warmup,
                                file = modelState.file,
                            )
                        }
                    }
                }

                launch {
                    warmup.prefetchState.collect { prefetchState ->
                        _uiState.update { it.copy(prefetchState = prefetchState) }
                    }
                }

                launch {
                    warmup.compileState.collect { compileState ->
                        _uiState.update { it.copy(compileState = compileState) }
                    }
                }
            }
    }

    /**
     * Starts model download startup once the first frame and services are ready.
     */
    private suspend fun tryStartStartup(reason: String) {
        if (!firstFrameSeen.get()) return

        val snapshot = _uiState.value
        if (!snapshot.onDeviceEnabled) return

        val key = snapshot.modelSpecKey ?: return
        val downloader = snapshot.modelDownloader ?: return

        if (!startupRequested.compareAndSet(false, true)) {
            return
        }

        SafeLog.i(
            TAG,
            "Startup: begin reason=${sanitizeLabel(reason)} keyPresent=true",
        )

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
     * Wires warmup inputs once a concrete local model file is ready.
     *
     * Notes:
     * - This is intentionally idempotent.
     * - The in-memory fingerprint never stores or logs file paths.
     */
    private fun onModelReady(
        repoMode: AppProcessServices.RepoMode,
        warmup: WarmupController,
        file: File,
    ) {
        if (repoMode != AppProcessServices.RepoMode.ON_DEVICE) return

        val fingerprint = ModelReadyFingerprint.from(file)
        val previous = lastWarmupReadyFingerprint.get()
        if (previous == fingerprint) return
        lastWarmupReadyFingerprint.set(fingerprint)

        AppProcessServices.updateWarmupInputsBestEffort(
            context = app,
            mode = repoMode,
            modelFile = file,
            options = WarmupController.Options(),
        )

        runCatching {
            warmup.requestCompileAfterPrefetch(reason = "startupAfterModelReady")
        }.onFailure { t ->
            SafeLog.e(
                TAG,
                "Warmup: requestCompileAfterPrefetch failed (non-fatal) " +
                        "type=${t::class.java.simpleName}",
            )
        }
    }

    /**
     * Computes repo mode.
     *
     * This keeps the current branch behavior but moves the decision out of Compose.
     */
    private fun computeRepoMode(): AppProcessServices.RepoMode {
        return if (FORCE_FAKE_REPO) {
            AppProcessServices.RepoMode.FAKE
        } else {
            AppProcessServices.RepoMode.ON_DEVICE
        }
    }

    /**
     * Builds the stable downloader recreation key.
     */
    private fun buildModelSpecKey(
        modelSpec: ModelDownloadSpec?,
    ): ModelSpecKey? {
        val s = modelSpec ?: return null
        val url = s.modelUrl?.trim().orEmpty()
        val name = s.fileName.trim().orEmpty()
        if (url.isBlank() || name.isBlank()) return null

        return ModelSpecKey(
            url = url,
            fileName = name,
            timeoutMs = s.timeoutMs,
            forceFreshOnStart = false,
            uiThrottleMs = s.uiThrottleMs,
            uiMinDeltaBytes = s.uiMinDeltaBytes,
        )
    }

    /**
     * Sanitizes a small label for logs.
     */
    private fun sanitizeLabel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"

        val safe =
            buildString {
                for (c in trimmed) {
                    if (
                        c.isLetterOrDigit() ||
                        c == '_' ||
                        c == '-' ||
                        c == ':' ||
                        c == '.'
                    ) {
                        append(c)
                    }
                    if (length >= 32) break
                }
            }

        return safe.ifBlank { "unknown" }
    }

    companion object {
        private const val TAG = "AppStartupViewModel"

        /**
         * Hard guard for switching repository mode.
         *
         * TODO:
         * - Replace with BuildConfig / SurveyConfig / dev menu toggle if needed.
         */
        private const val FORCE_FAKE_REPO: Boolean = false

        private const val INSTALLED_CONFIG_WAIT_MS: Long = 1_500L
        private const val INSTALLED_CONFIG_POLL_MS: Long = 25L
        private const val STARTUP_AFTER_FIRST_FRAME_DELAY_MS: Long = 150L
    }
}

/**
 * Installed config readiness state for startup orchestration.
 */
sealed interface StartupConfigState {
    /**
     * The ViewModel is still waiting for Application-installed config.
     */
    data object Loading : StartupConfigState

    /**
     * The process-installed config is available.
     */
    data class Ready(
        val cfg: SurveyConfig,
    ) : StartupConfigState

    /**
     * A safe failure state.
     *
     * The reason must never contain exception.message.
     */
    data class Failed(
        val safeReason: String,
    ) : StartupConfigState
}

/**
 * Stable key that decides downloader recreation.
 */
data class ModelSpecKey(
    val url: String,
    val fileName: String,
    val timeoutMs: Long,
    val forceFreshOnStart: Boolean,
    val uiThrottleMs: Long,
    val uiMinDeltaBytes: Long,
)

/**
 * In-memory fingerprint for model-ready deduplication.
 *
 * Notes:
 * - Avoids storing file paths.
 * - Never intended for persistence or logging.
 */
private data class ModelReadyFingerprint(
    val lengthBytes: Long,
    val lastModifiedMs: Long,
    val nameHash: Int,
) {
    companion object {
        /**
         * Builds a path-free fingerprint for a ready local model file.
         */
        fun from(file: File): ModelReadyFingerprint {
            val lengthBytes = runCatching { file.length() }.getOrDefault(0L)
            val lastModifiedMs = runCatching { file.lastModified() }.getOrDefault(0L)
            val nameHash = runCatching { file.name.hashCode() }.getOrDefault(0)
            return ModelReadyFingerprint(
                lengthBytes = lengthBytes,
                lastModifiedMs = lastModifiedMs,
                nameHash = nameHash,
            )
        }
    }
}

/**
 * Root startup UI state exposed to Compose.
 */
data class AppStartupUiState(
    val configState: StartupConfigState = StartupConfigState.Loading,
    val modelSpec: ModelDownloadSpec? = null,
    val modelSpecKey: ModelSpecKey? = null,
    val repoMode: AppProcessServices.RepoMode = AppProcessServices.RepoMode.ON_DEVICE,
    val onDeviceEnabled: Boolean = true,
    val servicesReady: Boolean = false,
    val repository: ChatValidation.RepositoryI? = null,
    val warmup: WarmupController? = null,
    val modelDownloader: ModelDownloadController? = null,
    val modelState: ModelDownloadController.ModelState =
        ModelDownloadController.ModelState.Idle(elapsedMs = 0L),
    val prefetchState: WarmupController.PrefetchState =
        WarmupController.PrefetchState.Idle,
    val compileState: WarmupController.CompileState =
        WarmupController.CompileState.Idle,
)