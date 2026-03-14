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
import com.negi.surveys.warmup.WarmupController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point for app-wide initialization.
 *
 * Goals:
 * - Run bootstrap exactly once per process, before any UI.
 * - Install SurveyConfig as a single source of truth (process-scoped).
 * - Avoid duplicate config loads from UI/repositories.
 *
 * Warmup design:
 * - WarmupController is created process-scoped by AppProcessServices.
 * - WarmupController receives Inputs (file + WarmupCapableRepository + options)
 *   via AppProcessServices.updateWarmupInputs().
 * - This Application performs only best-effort early Inputs injection when it can do so safely.
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

        // 1) Bootstrap: logging/crash/infra/etc.
        runCatching {
            AppBootstrap.ensureInitialized(appContext)
        }.onFailure { t ->
            // Important: Do not log exception.message.
            SafeLog.e(TAG, "AppBootstrap failed (non-fatal) ${t.safeTypeAndHint()}")
        }

        // 2) Main-process guard (avoid redundant IO/installs in secondary processes).
        if (!ProcessGuardsEntry.isMainProcess(appContext)) {
            SafeLog.i(
                TAG,
                "Config install: skipped (non-main process) " +
                        "process=${ProcessGuardsEntry.currentProcessNameCached(appContext)}",
            )
            SafeLog.i(TAG, "startup: done in ${SystemClock.elapsedRealtime() - t0}ms (non-main)")
            return
        }

        // 3) Install SurveyConfig once per process (single source of truth).
        installSurveyConfigOnce(appContext)

        // 4) Warmup Inputs (best-effort):
        // - Do not use reflection to guess warmup entrypoints.
        // - Trust only config-named local model files that pass shared validation.
        // - Never blocks the main thread.
        // - Skip quietly when startup is remote-capable but no usable local model exists yet.
        WarmupInputsBootstrap.tryInjectOnceBestEffort(appContext)

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
                "Config install: skipped (already installed) dt=${SystemClock.elapsedRealtime() - tCfg}ms",
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
            // Important: Do not log exception.message.
            SafeLog.e(TAG, "Config install: failed (non-fatal) ${t.safeTypeAndHint()}")
        }
    }

    private companion object {
        private const val TAG = "SurveyApplication"
        private const val CONFIG_ASSET_NAME = "survey.yaml"
    }

    /**
     * Best-effort early warmup inputs injection.
     *
     * Rationale:
     * - In some flows, the model file may already exist at cold start.
     * - If we can determine (file + WarmupCapableRepository), we can install Inputs early.
     *
     * Important:
     * - Never blocks the caller.
     * - Never logs file paths/names.
     * - Never clears existing Inputs; only installs when confident.
     *
     * Policy:
     * - This path exists only to accelerate local-existing-model startup.
     * - It must not retry/warn during normal remote-download startup when no usable
     *   local model exists yet.
     */
    private object WarmupInputsBootstrap {

        private const val TAG = "SurveyApplication"

        private val attemptedOnce = AtomicBoolean(false)

        private val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun tryInjectOnceBestEffort(appContext: Context) {
            if (!attemptedOnce.compareAndSet(false, true)) return

            scope.launch {
                val repoMode = AppProcessServices.configuredRepoMode()
                if (repoMode != AppProcessServices.RepoMode.ON_DEVICE) {
                    SafeLog.i(
                        TAG,
                        "warmupInputs: earlyInject skipped (repoMode=$repoMode) pid=${Process.myPid()}",
                    )
                    return@launch
                }

                val file =
                    AppProcessServices.resolveConfiguredLocalModelFileOrNull(
                        appContext.applicationContext,
                    )

                if (!AppProcessServices.isUsableLocalModelFile(file)) {
                    SafeLog.i(
                        TAG,
                        "warmupInputs: earlyInject skipped (no usable local model) pid=${Process.myPid()}",
                    )
                    return@launch
                }

                val repo =
                    runCatching {
                        AppProcessServices.repository(
                            appContext.applicationContext,
                            repoMode,
                        )
                    }.onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "warmupInputs: earlyInject repo lookup failed (non-fatal)",
                        )
                    }.getOrNull() ?: return@launch

                val warmupRepo = repo as? WarmupController.WarmupCapableRepository
                if (warmupRepo == null) {
                    SafeLog.i(
                        TAG,
                        "warmupInputs: earlyInject skipped (repo not warmup-capable) " +
                                "repoType=${repo.javaClass.simpleName} pid=${Process.myPid()}",
                    )
                    return@launch
                }

                val inputs =
                    WarmupController.Inputs(
                        file = file,
                        repository = warmupRepo,
                        options = WarmupController.Options(),
                    )

                runCatching {
                    AppProcessServices.updateWarmupInputs(inputs)
                }.onSuccess {
                    SafeLog.i(
                        TAG,
                        "warmupInputs: earlyInject installed hasInputs=true " +
                                "pid=${Process.myPid()} repoType=${repo.javaClass.simpleName}",
                    )
                }.onFailure { t ->
                    SafeLog.e(
                        TAG,
                        "warmupInputs: earlyInject install failed (non-fatal) " +
                                "pid=${Process.myPid()} repoType=${repo.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * Minimal process guards for Application entry.
     *
     * Notes:
     * - Keep this small and dependency-light.
     * - Avoid disk IO here.
     * - Use ApplicationInfo.processName as the declared main-process name,
     *   so custom android:process values remain correct.
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
                // Fail-open for safety, but do not pretend the current name is known.
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
         * Returns the current process name using in-memory/platform sources only.
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