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
import com.negi.surveys.logging.SafeLog
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

    private const val TAG = "AppProcessServices"

    private val warmupRef = AtomicReference<WarmupController?>(null)

    private val downloaderLock = Any()
    private var downloaderConfig: DownloaderConfig? = null
    private var downloader: ModelDownloadController? = null

    // Token can change independently; keep it dynamic.
    // IMPORTANT: Never log this token (PII/secret).
    private val hfTokenRef = AtomicReference<String?>(null)

    /**
     * Returns a single WarmupController instance per process.
     *
     * Thread-safety:
     * - Ensures the controller is created at most once per process.
     * - Avoids installing duplicates under contention.
     */
    fun warmupController(context: Context): WarmupController {
        val appCtx = context.applicationContext

        warmupRef.get()?.let { return it }

        while (true) {
            val cur = warmupRef.get()
            if (cur != null) return cur

            // Create only when we have a chance to install it.
            val created = SlmWarmupController(appCtx)
            if (warmupRef.compareAndSet(null, created)) {
                return created
            }

            // CAS lost; another thread installed the instance.
            // English comments: Best-effort cleanup for the instance we created but did not install.
            // Avoid `as?` to prevent JVM signature / platform-type pitfalls.
            runCatching { closeIfSupported(created) }
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

        // Fast path: avoid locking if current matches.
        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig
            if (cur != null && curCfg == cfg) {
                return cur
            }
        }

        // Create outside lock to minimize lock hold time (constructor may do IO/heavy setup).
        val created = ModelDownloadController(
            appContext = appCtx,
            modelUrl = cfg.modelUrl,
            fileName = cfg.fileName,
            hfTokenProvider = { hfTokenRef.get() },
            uiThrottleMs = cfg.uiThrottleMs,
            uiMinDeltaBytes = cfg.uiMinDeltaBytes,
        )

        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig

            if (cur != null && curCfg == cfg) {
                // Someone else already created the correct instance; discard ours.
                runCatching { closeIfSupported(created) }
                return cur
            }

            // Replace and best-effort close old instance if supported.
            val old = downloader
            downloader = created
            downloaderConfig = cfg

            if (old != null && old !== created) {
                runCatching { closeIfSupported(old) }
            }

            SafeLog.i(
                TAG,
                "modelDownloader: (re)created controller for cfg=" +
                        "url=${cfg.modelUrl != null} fileName=${cfg.fileName != null} " +
                        "uiThrottleMs=${cfg.uiThrottleMs} uiMinDeltaBytes=${cfg.uiMinDeltaBytes}"
            )

            return created
        }
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
     * English comments:
     * - This avoids `(x as? AutoCloseable)` casts.
     * - `is AutoCloseable` is a safe runtime check and does not require call-site changes.
     * - Idempotency is the responsibility of the underlying implementation.
     */
    private fun closeIfSupported(any: Any) {
        if (any is AutoCloseable) {
            any.close()
        }
    }
}