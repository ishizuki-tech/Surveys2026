/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Startup Model Wiring)
 *  ---------------------------------------------------------------------
 *  File: StartupModelWiring.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.content.Context
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
 * Model-related startup policy and warmup wiring.
 *
 * Responsibilities:
 * - Build a stable downloader key from config.
 * - Resolve the configured local model file.
 * - Convert a local file into a synthetic ready UI state.
 * - Wire warmup inputs when a concrete model file becomes ready.
 * - Derive service readiness for local-only vs remote-download startup.
 */
internal class StartupModelWiring(
    appContext: Context,
) {
    private val app = appContext.applicationContext

    /**
     * Builds the stable downloader recreation key.
     *
     * Notes:
     * - This key is only for remote-download capable startup.
     * - Local-only startup should not fail just because this key is absent.
     */
    fun buildModelSpecKey(modelSpec: ModelDownloadSpec?): ModelSpecKey? {
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
     * Strict local model resolution aligned with Application bootstrap.
     *
     * Strategy:
     * - Trust only config-named local model files.
     * - Reuse the shared process-scoped validation rules.
     * - Never guess by extension, size, or "largest file wins".
     * - Never logs file paths or file names.
     */
    fun resolveExistingLocalModelFileOrNull(modelSpec: ModelDownloadSpec?): File? {
        return AppProcessServices.resolveConfiguredLocalModelFileOrNull(
            context = app,
            spec = modelSpec,
        )
    }

    /**
     * Builds a synthetic ready state for local-only startup.
     */
    fun localReadyModelState(file: File): ModelDownloadController.ModelState.Ready {
        val size = runCatching { file.length() }.getOrDefault(0L)
        return ModelDownloadController.ModelState.Ready(
            file = file,
            sizeBytes = size,
            startedAtMs = 0L,
            elapsedMs = 0L,
        )
    }

    /**
     * Derives whether startup services are truly ready for the current mode.
     *
     * Notes:
     * - Remote on-device mode is ready once the downloader exists.
     * - Local-only on-device mode is ready only after warmup wiring succeeds.
     * - Non-on-device mode is ready once the repository graph is built.
     */
    fun deriveServicesReady(
        onDeviceEnabled: Boolean,
        downloader: ModelDownloadController?,
        localWarmupReady: Boolean,
    ): Boolean {
        if (!onDeviceEnabled) return true
        if (downloader != null) return true
        return localWarmupReady
    }

    /**
     * Wires warmup inputs once a concrete local model file is ready.
     *
     * Returns:
     * - true when warmup wiring is already satisfied or newly completed.
     * - false when input injection or compile scheduling did not complete.
     *
     * Notes:
     * - This is intentionally idempotent.
     * - The in-memory fingerprint never stores or logs file paths.
     * - Fingerprint is committed only after warmup wiring succeeds.
     */
    fun onModelReady(
        repoMode: AppProcessServices.RepoMode,
        warmup: WarmupController,
        file: File,
        lastReadyFingerprintRef: AtomicReference<ModelReadyFingerprint?>,
    ): Boolean {
        if (repoMode != AppProcessServices.RepoMode.ON_DEVICE) return false

        val fingerprint = ModelReadyFingerprint.from(file)
        val previous = lastReadyFingerprintRef.get()
        if (previous == fingerprint) return true

        val inputsUpdated =
            runCatching {
                AppProcessServices.updateWarmupInputsBestEffort(
                    context = app,
                    mode = repoMode,
                    modelFile = file,
                    options = WarmupController.Options(),
                )
            }.onFailure { t ->
                SafeLog.e(
                    TAG,
                    "Warmup: update inputs failed (non-fatal) type=${t::class.java.simpleName}",
                )
            }.isSuccess

        if (!inputsUpdated) return false

        val compileRequested =
            runCatching {
                warmup.requestCompileAfterPrefetch(reason = "startupAfterModelReady")
            }.onFailure { t ->
                SafeLog.e(
                    TAG,
                    "Warmup: requestCompileAfterPrefetch failed (non-fatal) type=${t::class.java.simpleName}",
                )
            }.isSuccess

        if (compileRequested) {
            lastReadyFingerprintRef.set(fingerprint)
        }

        return compileRequested
    }

    /**
     * In-memory fingerprint for model-ready deduplication.
     *
     * Notes:
     * - Avoids storing file paths.
     * - Never intended for persistence or logging.
     */
    data class ModelReadyFingerprint(
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

    companion object {
        private const val TAG = "StartupModelWiring"
    }
}