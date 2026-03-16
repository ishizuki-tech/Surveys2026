/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Application Bootstrap)
 *  ---------------------------------------------------------------------
 *  File: SurveyApplication.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.config.InstalledSurveyConfigStore
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.SafeLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Process entry point for app-wide initialization.
 *
 * Responsibilities:
 * - Run bootstrap exactly once per process, before any UI.
 * - Install SurveyConfig as a single source of truth for the process.
 * - Avoid redundant config installs in non-main processes.
 *
 * Non-responsibilities:
 * - Repository creation
 * - Warmup controller creation
 * - Model downloader creation
 * - Warmup input injection
 * - Startup retry / rebuild policy
 *
 * Startup policy:
 * - Application owns lightweight bootstrap and config installation only.
 * - Startup service activation is deferred to the root startup pipeline
 *   after the first frame.
 *
 * Privacy:
 * - Never log tokens, URLs, file paths, file names, or raw user/model content.
 * - Even exception messages may contain sensitive info; do not log exception.message.
 */
class SurveyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val appContext = applicationContext
        val t0 = SystemClock.elapsedRealtime()

        SafeLog.i(
            TAG,
            "onCreate: pid=${Process.myPid()} uid=${Process.myUid()} " +
                    "process=${ProcessGuardsEntry.currentProcessNameCached(appContext)} " +
                    "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}",
        )

        /**
         * 1) Bootstrap shared process infrastructure.
         */
        runCatching {
            AppBootstrap.ensureInitialized(appContext)
        }.onFailure { t ->
            SafeLog.e(TAG, "AppBootstrap failed (non-fatal) ${t.safeTypeAndHint()}")
        }

        /**
         * 2) Guard non-main processes.
         *
         * Notes:
         * - Keep secondary processes free from redundant config installation work.
         * - Fail-open behavior remains inside ProcessGuardsEntry.
         */
        if (!ProcessGuardsEntry.isMainProcess(appContext)) {
            SafeLog.i(
                TAG,
                "Config install: skipped (non-main process) " +
                        "process=${ProcessGuardsEntry.currentProcessNameCached(appContext)}",
            )
            SafeLog.i(TAG, "startup: done in ${SystemClock.elapsedRealtime() - t0}ms (non-main)")
            return
        }

        /**
         * 3) Install SurveyConfig once per process.
         *
         * Notes:
         * - Keep ownership here so StartupCoordinator can remain wait-only.
         * - This is still synchronous for now to preserve startup determinism.
         * - Repository/warmup activation is intentionally NOT performed here.
         */
        installSurveyConfigOnce(appContext)

        SafeLog.i(TAG, "startup: done in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    /**
     * Installs the validated SurveyConfig once for the current process.
     *
     * Contract:
     * - First successful install wins.
     * - Duplicate install attempts are ignored.
     * - Parsing is skipped when a config is already installed.
     *
     * Privacy:
     * - Do not log asset names or file names.
     * - Do not log exception.message.
     */
    private fun installSurveyConfigOnce(appContext: Context) {
        val tCfg = SystemClock.elapsedRealtime()

        val existing = InstalledSurveyConfigStore.getOrNull()
        if (existing != null) {
            SafeLog.w(
                TAG,
                "Config install: skipped (already installed) " +
                        "dt=${SystemClock.elapsedRealtime() - tCfg}ms",
            )
            return
        }

        runCatching {
            SurveyConfigLoader.installProcessConfigFromAssetsValidated(
                context = appContext,
                fileName = CONFIG_ASSET_NAME,
            )
        }.onSuccess {
            SafeLog.i(
                TAG,
                "Config install: success dt=${SystemClock.elapsedRealtime() - tCfg}ms",
            )
        }.onFailure { t ->
            SafeLog.e(TAG, "Config install: failed (non-fatal) ${t.safeTypeAndHint()}")
        }
    }

    private companion object {
        private const val TAG = "SurveyApplication"
        private const val CONFIG_ASSET_NAME = "survey.yaml"
    }

    /**
     * Minimal process guards for Application entry.
     *
     * Notes:
     * - Keep this small and dependency-light.
     * - Avoid disk IO here.
     * - Use ApplicationInfo.processName as the declared main-process name so
     *   custom android:process values remain correct.
     */
    private object ProcessGuardsEntry {

        private val cachedCurrentProcessName = AtomicReference<String?>(null)

        fun currentProcessNameCached(context: Context): String {
            val cur = cachedCurrentProcessName.get()
            if (!cur.isNullOrBlank()) return cur

            val appCtx = context.applicationContext
            val resolved = getCurrentProcessName(appCtx)?.takeIf { it.isNotBlank() }
            if (resolved != null) {
                cachedCurrentProcessName.set(resolved)
                return resolved
            }

            return "unknown"
        }

        fun isMainProcess(context: Context): Boolean {
            val appCtx = context.applicationContext
            val mainProcessName = getMainProcessName(appCtx) ?: appCtx.packageName

            val current = getCurrentProcessName(appCtx)
            if (current.isNullOrBlank()) {
                SafeLog.w(TAG, "process name unavailable; fail-open (treat as main)")
                return true
            }

            cachedCurrentProcessName.set(current)
            return current == mainProcessName
        }

        /**
         * Returns the main process name declared by the app manifest.
         */
        private fun getMainProcessName(context: Context): String? {
            return runCatching { context.applicationInfo.processName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

        /**
         * Returns the current process name using in-memory or platform sources only.
         */
        private fun getCurrentProcessName(context: Context): String? {
            if (Build.VERSION.SDK_INT >= 28) {
                val name = runCatching { Application.getProcessName() }.getOrNull()
                if (!name.isNullOrBlank()) return name
            }

            val am = runCatching {
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            }.getOrNull()

            if (am != null) {
                val pid = Process.myPid()
                val name = runCatching {
                    am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
                }.getOrNull()
                if (!name.isNullOrBlank()) return name
            }

            return null
        }
    }

    /**
     * Returns a safe string that never includes exception.message.
     */
    private fun Throwable.safeTypeAndHint(): String {
        val e = stackTrace.firstOrNull()
        val hint = if (e != null) "${e.className}.${e.methodName}:${e.lineNumber}" else "unknown"
        return "type=${this::class.java.simpleName} at=$hint"
    }
}