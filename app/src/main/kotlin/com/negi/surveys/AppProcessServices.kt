/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Process Services)
 *  ---------------------------------------------------------------------
 *  File: AppProcessServices.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.content.Context
import com.negi.surveys.chat.FakeSlmRepository
import com.negi.surveys.chat.RepositoryI
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.slm.SlmRepository
import com.negi.surveys.slm.SlmWarmupController
import com.negi.surveys.utils.FakeWarmupController
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.utils.WarmupController
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-scoped service holder.
 *
 * Why:
 * - Activity/Compose recomposition can recreate objects.
 * - Repository / warmup / model-download controllers should be stable per process.
 *
 * Privacy:
 * - Never log tokens, URLs, file names, or any raw user/model content.
 * - Even exception messages may contain sensitive info; do not log exception.message.
 *
 * Thread-safety:
 * - Creation is guarded to prevent duplicate heavy instances under contention.
 * - "Swap then close" for replaceable services to keep callers fast.
 */
object AppProcessServices {

    private const val TAG = "AppProcessServices"

    enum class RepoMode {
        ON_DEVICE,
        FAKE,
    }

    // ---------------------------------------------------------------------
    // Repository (process scoped)
    // ---------------------------------------------------------------------

    private val repoRef = AtomicReference<RepositoryI?>(null)
    private val repoModeRef = AtomicReference<RepoMode?>(null)
    private val repoLock = Any()

    /**
     * Returns a single RepositoryI instance per process for the requested mode.
     *
     * IMPORTANT:
     * - FAKE mode never throws; it always falls back to FakeSlmRepository().
     */
    fun repository(
        context: Context,
        mode: RepoMode,
    ): RepositoryI {
        val appCtx = context.applicationContext

        // Fast path: mode matches and instance exists.
        val cur = repoRef.get()
        val curMode = repoModeRef.get()
        if (cur != null && curMode == mode) return cur

        synchronized(repoLock) {
            val insideCur = repoRef.get()
            val insideMode = repoModeRef.get()
            if (insideCur != null && insideMode == mode) return insideCur

            val created: RepositoryI = when (mode) {
                RepoMode.ON_DEVICE -> {
                    SlmRepository(
                        context = appCtx,
                        enableTwoStepEval = true,
                        debug = SlmRepository.DebugConfig(enabled = true, logFullText = true)
                    )
                }
                RepoMode.FAKE -> FakeSlmRepository()
            }

            repoRef.set(created)
            repoModeRef.set(mode)

            SafeLog.i(TAG, "repository: (re)created mode=$mode type=${created.javaClass.simpleName}")
            return created
        }
    }

    // ---------------------------------------------------------------------
    // WarmupController (process scoped)
    // ---------------------------------------------------------------------

    private val warmupRef = AtomicReference<WarmupController?>(null)
    private val warmupModeRef = AtomicReference<RepoMode?>(null)
    private val warmupLock = Any()

    /**
     * Returns a single WarmupController instance per process for the requested mode.
     *
     * IMPORTANT:
     * - FAKE mode never throws; it always falls back to FakeWarmupController().
     */
    fun warmupController(
        context: Context,
        mode: RepoMode,
    ): WarmupController {
        val appCtx = context.applicationContext

        val cur = warmupRef.get()
        val curMode = warmupModeRef.get()
        if (cur != null && curMode == mode) return cur

        synchronized(warmupLock) {
            val insideCur = warmupRef.get()
            val insideMode = warmupModeRef.get()
            if (insideCur != null && insideMode == mode) return insideCur

            val created: WarmupController = when (mode) {
                RepoMode.ON_DEVICE -> SlmWarmupController(appCtx)
                RepoMode.FAKE -> FakeWarmupController()
            }

            warmupRef.set(created)
            warmupModeRef.set(mode)

            SafeLog.i(TAG, "warmupController: (re)created mode=$mode type=${created.javaClass.simpleName}")
            return created
        }
    }

    // ---------------------------------------------------------------------
    // ModelDownloadController (process scoped per config)
    // ---------------------------------------------------------------------

    private val downloaderLock = Any()
    private var downloaderConfig: DownloaderConfig? = null
    private var downloader: ModelDownloadController? = null

    // Token can change independently; keep it dynamic.
    // IMPORTANT: Never log this token (PII/secret).
    private val hfTokenRef = AtomicReference<String?>(null)

    fun modelDownloader(context: Context, spec: ModelDownloadSpec?): ModelDownloadController {
        val appCtx = context.applicationContext

        hfTokenRef.set(spec?.hfToken)

        val cfg = DownloaderConfig(
            modelUrl = spec?.modelUrl,
            fileName = spec?.fileName,
            uiThrottleMs = spec?.uiThrottleMs ?: 200L,
            uiMinDeltaBytes = spec?.uiMinDeltaBytes ?: (512L * 1024L),
        )

        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig
            if (cur != null && curCfg == cfg) return cur
        }

        val created = ModelDownloadController(
            appContext = appCtx,
            modelUrl = cfg.modelUrl,
            fileName = cfg.fileName,
            hfTokenProvider = { hfTokenRef.get() },
            uiThrottleMs = cfg.uiThrottleMs,
            uiMinDeltaBytes = cfg.uiMinDeltaBytes,
        )

        var toClose: Any? = null
        var installed: ModelDownloadController? = null

        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig

            if (cur != null && curCfg == cfg) {
                toClose = created
                installed = cur
            } else {
                toClose = downloader
                downloader = created
                downloaderConfig = cfg
                installed = created

                SafeLog.i(
                    TAG,
                    "modelDownloader: (re)created controller for cfg=" +
                            "urlPresent=${cfg.modelUrl != null} fileNamePresent=${cfg.fileName != null} " +
                            "uiThrottleMs=${cfg.uiThrottleMs} uiMinDeltaBytes=${cfg.uiMinDeltaBytes}"
                )
            }
        }

        toClose?.let { closeIfSupportedSafely(it) }
        return requireNotNull(installed)
    }

    private data class DownloaderConfig(
        val modelUrl: String?,
        val fileName: String?,
        val uiThrottleMs: Long,
        val uiMinDeltaBytes: Long,
    )

    /**
     * Best-effort close helper.
     *
     * Notes:
     * - Never logs exception.message.
     */
    private fun closeIfSupportedSafely(any: Any) {
        if (any is AutoCloseable) {
            try {
                any.close()
            } catch (t: Throwable) {
                SafeLog.w(
                    TAG,
                    "closeIfSupportedSafely: failed type=${t::class.java.simpleName} target=${any::class.java.simpleName}"
                )
            }
        }
    }
}