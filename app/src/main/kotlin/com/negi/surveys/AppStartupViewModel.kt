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
                val localFile = resolveExistingLocalModelFileOrNull(current.modelSpec)
                if (localFile != null && warmup != null) {
                    _uiState.update {
                        it.copy(
                            modelState = localReadyModelState(localFile),
                            servicesReady = true,
                        )
                    }
                    onModelReady(
                        repoMode = current.repoMode,
                        warmup = warmup,
                        file = localFile,
                    )
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

        startupPipelineJob?.cancel()
        serviceCollectorsJob?.cancel()
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        _uiState.update {
            it.copy(
                configState = StartupConfigState.Loading,
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
            awaitInstalledConfigAndBuildServices()
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

        val initialLocalModelFile =
            if (onDeviceEnabled && modelSpecKey == null) {
                resolveExistingLocalModelFileOrNull(modelSpec)
            } else {
                null
            }

        if (onDeviceEnabled && modelSpecKey == null && initialLocalModelFile == null) {
            SafeLog.e(
                TAG,
                "Config: on-device model unavailable (no download URL and no local file)",
            )
            _uiState.update {
                it.copy(
                    configState = StartupConfigState.Failed("ModelUnavailable"),
                    modelSpec = modelSpec,
                    modelSpecKey = null,
                    repoMode = repoMode,
                    onDeviceEnabled = true,
                    servicesReady = false,
                    repository = null,
                    warmup = null,
                    modelDownloader = null,
                    modelState = ModelDownloadController.ModelState.Idle(elapsedMs = 0L),
                    prefetchState = WarmupController.PrefetchState.Idle,
                    compileState = WarmupController.CompileState.Idle,
                )
            }
            return
        }

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
            initialLocalModelFile = initialLocalModelFile,
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
        initialLocalModelFile: File?,
    ) {
        startupRequested.set(false)
        lastWarmupReadyFingerprint.set(null)

        val repo = AppProcessServices.repository(app, repoMode)
        val warmup = AppProcessServices.warmupController(app, repoMode)

        /**
         * Important:
         * - Do not pass null to AppProcessServices.modelDownloader() as a way to mean "disabled".
         * - That API intentionally reuses the last non-null spec when spec == null.
         * - Creating no downloader here avoids stale-spec resurrection.
         */
        val downloader =
            if (
                repoMode == AppProcessServices.RepoMode.ON_DEVICE &&
                modelSpec != null &&
                modelSpecKey != null
            ) {
                AppProcessServices.modelDownloader(app, spec = modelSpec)
            } else {
                null
            }

        SafeLog.i(
            TAG,
            "Services: obtained mode=$repoMode onDevice=${repoMode == AppProcessServices.RepoMode.ON_DEVICE} " +
                    "keyPresent=${modelSpecKey != null} specPresent=${modelSpec != null} " +
                    "downloaderPresent=${downloader != null} localModelPresent=${initialLocalModelFile != null}",
        )

        val initialModelState =
            when {
                downloader != null -> downloader.state.value
                initialLocalModelFile != null -> localReadyModelState(initialLocalModelFile)
                else -> ModelDownloadController.ModelState.Idle(elapsedMs = 0L)
            }

        _uiState.update {
            it.copy(
                repository = repo,
                warmup = warmup,
                modelDownloader = downloader,
                servicesReady = !it.onDeviceEnabled || downloader != null || initialLocalModelFile != null,
                modelState = initialModelState,
                prefetchState = warmup.prefetchState.value,
                compileState = warmup.compileState.value,
            )
        }

        startCollectingServiceStates(
            repoMode = repoMode,
            warmup = warmup,
            downloader = downloader,
        )

        if (initialLocalModelFile != null) {
            onModelReady(
                repoMode = repoMode,
                warmup = warmup,
                file = initialLocalModelFile,
            )
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
                "Warmup: requestCompileAfterPrefetch failed (non-fatal) type=${t::class.java.simpleName}",
            )
        }
    }

    /**
     * Computes repo mode from the centralized process-wide selector.
     */
    private fun computeRepoMode(): AppProcessServices.RepoMode {
        return AppProcessServices.configuredRepoMode()
    }

    /**
     * Builds the stable downloader recreation key.
     *
     * Notes:
     * - This key is only for remote-download capable startup.
     * - Local-only startup should not fail just because this key is absent.
     */
    private fun buildModelSpecKey(modelSpec: ModelDownloadSpec?): ModelSpecKey? {
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
     * Best-effort local model resolution aligned with Application bootstrap.
     *
     * Strategy:
     * - Prefer the configured file name under known app-private roots.
     * - Fallback to a bounded scan for the largest plausible model file.
     * - Never logs file paths or file names.
     */
    private fun resolveExistingLocalModelFileOrNull(modelSpec: ModelDownloadSpec?): File? {
        val spec = modelSpec ?: return null

        val preferred =
            sanitizeSimpleFileName(spec.fileName)?.let { safeName ->
                candidateRoots()
                    .asSequence()
                    .map { File(it, safeName) }
                    .firstOrNull { candidate ->
                        runCatching {
                            candidate.exists() && candidate.isFile && candidate.length() > 0L
                        }.getOrDefault(false)
                    }
            }

        if (preferred != null) return preferred

        var best: File? = null
        var bestLen = 0L

        for (dir in candidateRoots()) {
            val list = runCatching { dir.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
            for (file in list) {
                if (!file.isFile) continue

                val name = file.name.lowercase()
                if (ALLOWED_MODEL_EXTENSIONS.none { name.endsWith(it) }) continue

                val len = runCatching { file.length() }.getOrDefault(0L)
                if (len < MIN_LOCAL_MODEL_BYTES) continue

                if (len > bestLen) {
                    best = file
                    bestLen = len
                }
            }
        }

        return best
    }

    /**
     * Returns small app-private roots that may contain model files.
     */
    private fun candidateRoots(): List<File> {
        return listOfNotNull(
            app.filesDir,
            File(app.filesDir, "models"),
            File(app.filesDir, "slm"),
            File(app.filesDir, "litert"),
            app.noBackupFilesDir,
            app.cacheDir,
        ).distinct()
    }

    /**
     * Sanitizes a simple file name to avoid traversal-like inputs.
     */
    private fun sanitizeSimpleFileName(name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        if (n.contains('/')) return null
        if (n.contains('\\')) return null
        if (n.contains("..")) return null
        if (n.length > 200) return null
        return n
    }

    /**
     * Builds a synthetic ready state for local-only startup.
     */
    private fun localReadyModelState(file: File): ModelDownloadController.ModelState.Ready {
        val size = runCatching { file.length() }.getOrDefault(0L)
        return ModelDownloadController.ModelState.Ready(
            file = file,
            sizeBytes = size,
            startedAtMs = 0L,
            elapsedMs = 0L,
        )
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
        private const val INSTALLED_CONFIG_WAIT_MS: Long = 1_500L
        private const val INSTALLED_CONFIG_POLL_MS: Long = 25L
        private const val STARTUP_AFTER_FIRST_FRAME_DELAY_MS: Long = 150L

        private const val MIN_LOCAL_MODEL_BYTES: Long = 128L * 1024L * 1024L
        private val ALLOWED_MODEL_EXTENSIONS = setOf(
            ".litertlm",
            ".bin",
            ".task",
            ".gguf",
        )
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
    val prefetchState: WarmupController.PrefetchState = WarmupController.PrefetchState.Idle,
    val compileState: WarmupController.CompileState = WarmupController.CompileState.Idle,
)