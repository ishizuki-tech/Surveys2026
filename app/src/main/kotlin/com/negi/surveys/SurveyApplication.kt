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

import android.app.Application
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.SafeLog

/**
 * Process entry point for app-wide initialization.
 *
 * Goals:
 * - Run bootstrap exactly once per process, before any UI.
 * - Install SurveyConfig as a single source of truth (process-scoped).
 * - Avoid duplicate config loads from UI/repositories.
 */
class SurveyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Always use applicationContext to avoid leaking Activity contexts.
        val appContext = applicationContext
        val t0 = SystemClock.elapsedRealtime()

        // Emit a single, early breadcrumb for launch diagnostics.
        SafeLog.i(
            TAG,
            "onCreate: pid=${Process.myPid()} uid=${Process.myUid()} " +
                    "process=${ProcessGuardsEntry.currentProcessNameCached(appContext as Application)} " +
                    "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
        )

        // 1) Bootstrap: logging/crash/infra/etc.
        runCatching {
            AppBootstrap.ensureInitialized(appContext)
        }.onFailure { t ->
            // Do NOT crash the process here unless you explicitly want fail-fast.
            SafeLog.e(TAG, "AppBootstrap failed (non-fatal) type=${t::class.java.simpleName}", t)
        }

        // 2) Install SurveyConfig ONCE per process (single source of truth).
        // IMPORTANT:
        // - Prefer installing only in main process to avoid redundant IO in secondary processes.
        // - After install, all other components must call getInstalledConfigOrNull().
        if (!ProcessGuardsEntry.isMainProcess(appContext)) {
            SafeLog.i(
                TAG,
                "Config install: skipped (non-main process) process=${ProcessGuardsEntry.currentProcessNameCached(appContext)}"
            )
            SafeLog.i(TAG, "startup: done in ${SystemClock.elapsedRealtime() - t0}ms (non-main)")
            return
        }

        val tCfg = SystemClock.elapsedRealtime()
        runCatching {
            val cfg = SurveyConfigLoader.fromAssetsValidated(appContext, CONFIG_ASSET_NAME)
            SurveyConfigLoader.installProcessConfig(cfg)
            SafeLog.i(
                TAG,
                "Config install: success dt=${SystemClock.elapsedRealtime() - tCfg}ms asset=$CONFIG_ASSET_NAME"
            )
        }.onFailure { t ->
            // Keep message safe (don't log t.message).
            SafeLog.e(TAG, "Config install: failed type=${t::class.java.simpleName}", t)
        }

        SafeLog.i(TAG, "startup: done in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    private companion object {
        private const val TAG = "SurveyApplication"
        private const val CONFIG_ASSET_NAME = "survey.yaml"
    }
}

/**
 * Minimal process guards for Application entry.
 *
 * Note:
 * - Keep this small and dependency-free.
 * - AppBootstrap has its own guards, but config install should be guarded too.
 */
private object ProcessGuardsEntry {

    @Volatile
    private var cachedName: String? = null

    fun currentProcessNameCached(context: Application): String {
        return cachedName ?: runCatching {
            // API 28+ fast path
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                // Fallback: best-effort to avoid IO here.
                context.packageName
            }
        }.getOrNull().also { name ->
            if (!name.isNullOrBlank()) cachedName = name
        } ?: "unknown"
    }

    fun isMainProcess(context: Application): Boolean {
        val name = currentProcessNameCached(context)
        val pkg = context.packageName
        // Fail-open: if we cannot determine, treat as main to keep behavior consistent.
        return name.isBlank() || name == pkg
    }
}