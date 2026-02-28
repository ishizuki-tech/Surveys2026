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
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.slm.SlmWarmupController
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.utils.WarmupController
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-scoped service holder.
 *
 * Why:
 * - Activity/Compose recomposition can recreate objects.
 * - Warmup and model-download controllers should be stable per process to avoid duplicated work.
 *
 * Notes:
 * - Uses applicationContext only (no Activity leak).
 * - Model downloader is recreated only when "effective config" changes.
 */
object AppProcessServices {

    private val warmupRef = AtomicReference<WarmupController?>(null)

    private val downloaderLock = Any()
    private var downloaderConfig: DownloaderConfig? = null
    private var downloader: ModelDownloadController? = null
    private val hfTokenRef = AtomicReference<String?>(null)

    /**
     * Returns a single WarmupController instance per process.
     */
    fun warmupController(context: Context): WarmupController {
        val appCtx = context.applicationContext
        warmupRef.get()?.let { return it }

        val created = SlmWarmupController(appCtx)
        return if (warmupRef.compareAndSet(null, created)) {
            created
        } else {
            warmupRef.get() ?: created
        }
    }

    /**
     * Returns a single ModelDownloadController instance per process for the current config.
     *
     * Behavior:
     * - If spec is null, we still create a controller with null URL/file; it should surface "NotConfigured".
     * - If spec fields change (url/fileName/ui throttles), a new controller is created.
     * - hfToken is updated dynamically without requiring recreation.
     */
    fun modelDownloader(context: Context, spec: ModelDownloadSpec?): ModelDownloadController {
        val appCtx = context.applicationContext

        // Token can change independently; keep it dynamic.
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

            if (cur != null && curCfg == cfg) {
                return cur
            }

            val created = ModelDownloadController(
                appContext = appCtx,
                modelUrl = cfg.modelUrl,
                fileName = cfg.fileName,
                hfTokenProvider = { hfTokenRef.get() },
                uiThrottleMs = cfg.uiThrottleMs,
                uiMinDeltaBytes = cfg.uiMinDeltaBytes,
            )

            downloader = created
            downloaderConfig = cfg
            return created
        }
    }

    private data class DownloaderConfig(
        val modelUrl: String?,
        val fileName: String?,
        val uiThrottleMs: Long,
        val uiMinDeltaBytes: Long,
    )
}