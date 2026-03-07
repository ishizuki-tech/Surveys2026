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
import com.negi.surveys.config.resolveModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        // - If a model file is already present, inject Inputs once to enable early warmup later.
        // - Never blocks the main thread.
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
            val cfg = SurveyConfigLoader.fromAssetsValidated(appContext, CONFIG_ASSET_NAME)
            when (InstalledSurveyConfigStore.installOnce(cfg)) {
                InstalledSurveyConfigStore.InstallResult.INSTALLED -> {
                    SafeLog.i(
                        TAG,
                        "Config install: success dt=${SystemClock.elapsedRealtime() - tCfg}ms",
                    )
                }

                InstalledSurveyConfigStore.InstallResult.ALREADY_INSTALLED -> {
                    SafeLog.w(
                        TAG,
                        "Config install: ignored duplicate install dt=${SystemClock.elapsedRealtime() - tCfg}ms",
                    )
                }
            }
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
     */
    private object WarmupInputsBootstrap {

        private const val TAG = "SurveyApplication"

        private val attemptedOnce = AtomicBoolean(false)

        private val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val INITIAL_DELAY_MS: Long = 150L
        private const val RETRY_INTERVAL_MS: Long = 250L
        private const val MAX_TOTAL_RETRY_MS: Long = 5_000L

        fun tryInjectOnceBestEffort(appContext: Context) {
            if (!attemptedOnce.compareAndSet(false, true)) return

            scope.launch {
                // Small delay so process bootstrap/config install can settle.
                delay(INITIAL_DELAY_MS)

                val deadlineAt = SystemClock.elapsedRealtime() + MAX_TOTAL_RETRY_MS
                var attempts = 0
                var loggedRepoNotCapable = false

                while (SystemClock.elapsedRealtime() < deadlineAt) {
                    attempts += 1

                    val file = ModelFileProbe.findModelFileOrNull(appContext.applicationContext)
                    val fileReady = file != null && file.exists() && file.isFile && file.length() > 0L
                    if (!fileReady) {
                        delay(RETRY_INTERVAL_MS)
                        continue
                    }

                    val repo =
                        runCatching {
                            AppProcessServices.repository(
                                appContext.applicationContext,
                                AppProcessServices.RepoMode.ON_DEVICE,
                            )
                        }.getOrNull()

                    val warmupRepo = repo as? WarmupController.WarmupCapableRepository
                    if (warmupRepo == null) {
                        if (!loggedRepoNotCapable) {
                            loggedRepoNotCapable = true
                            SafeLog.w(
                                TAG,
                                "warmupInputs: earlyInject retry " +
                                        "(repo not WarmupCapableRepository) " +
                                        "repoType=${repo?.javaClass?.simpleName ?: "null"} " +
                                        "pid=${Process.myPid()}",
                            )
                        }
                        delay(RETRY_INTERVAL_MS)
                        continue
                    }

                    val inputs =
                        WarmupController.Inputs(
                            file = file,
                            repository = warmupRepo,
                            options = WarmupController.Options(),
                        )

                    AppProcessServices.updateWarmupInputs(inputs)

                    SafeLog.i(
                        TAG,
                        "warmupInputs: earlyInject installed hasInputs=true " +
                                "pid=${Process.myPid()} repoType=${repo.javaClass.simpleName} " +
                                "attempts=$attempts",
                    )
                    return@launch
                }

                SafeLog.w(
                    TAG,
                    "warmupInputs: earlyInject gave up (best-effort) " +
                            "pid=${Process.myPid()} attempts=$attempts",
                )
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
     * Best-effort model file probe without depending on ModelDownloadController internals.
     *
     * Privacy:
     * - Never logs file paths.
     *
     * Strategy:
     * - Prefer config-resolved file name if installed config is available.
     * - Fallback to modelDefaults.defaultFileName for backward compatibility.
     * - Otherwise, scan a small set of app-private directories.
     * - Pick the largest plausible model file by extension and minimum size threshold.
     *
     * Notes:
     * - Keep this fast and rate-limited.
     * - Never assume the model lives only in filesDir root.
     */
    private object ModelFileProbe {

        private const val TAG = "SurveyApplication"

        private val lastScanAtMs = AtomicLong(0L)
        private val cachedFile = AtomicReference<File?>(null)

        private const val SCAN_INTERVAL_READY_MS: Long = 30_000L
        private const val SCAN_INTERVAL_EMPTY_MS: Long = 1_000L
        private const val MIN_BYTES: Long = 128L * 1024L * 1024L

        private val allowedExtensions = setOf(
            ".litertlm",
            ".bin",
            ".task",
            ".gguf",
        )

        fun findModelFileOrNull(appContext: Context): File? {
            // 1) Prefer config-resolved file name if available.
            val preferred = resolvePreferredFromInstalledConfig(appContext)
            if (preferred != null) {
                cachedFile.set(preferred)
                return preferred
            }

            // 2) Validate cached file before using it.
            val cached = cachedFile.get()
            if (cached != null) {
                val cachedOk = runCatching {
                    cached.exists() && cached.isFile && cached.length() >= MIN_BYTES
                }.getOrDefault(false)

                if (cachedOk) {
                    return cached
                }

                cachedFile.compareAndSet(cached, null)
            }

            // 3) Rate-limit scans.
            val now = SystemClock.elapsedRealtime()
            val last = lastScanAtMs.get()

            val interval =
                if (cachedFile.get() == null) {
                    SCAN_INTERVAL_EMPTY_MS
                } else {
                    SCAN_INTERVAL_READY_MS
                }

            if (now - last < interval) {
                return cachedFile.get()
            }
            if (!lastScanAtMs.compareAndSet(last, now)) {
                return cachedFile.get()
            }

            var best: File? = null
            var bestLen = 0L

            for (dir in candidateRoots(appContext)) {
                val list = runCatching { dir.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
                for (f in list) {
                    if (!f.isFile) continue

                    val name = f.name.lowercase()
                    if (allowedExtensions.none { name.endsWith(it) }) continue

                    val len = runCatching { f.length() }.getOrDefault(0L)
                    if (len < MIN_BYTES) continue

                    if (len > bestLen) {
                        best = f
                        bestLen = len
                    }
                }
            }

            cachedFile.set(best)

            if (best != null) {
                SafeLog.d(TAG, "warmupProbe: best-effort file found size=$bestLen pid=${Process.myPid()}")
            } else {
                SafeLog.d(TAG, "warmupProbe: no candidate found pid=${Process.myPid()}")
            }

            return best
        }

        /**
         * Resolves a preferred model file using installed config without directory scans.
         *
         * Notes:
         * - This does not log any file name.
         * - It checks a small set of known app-private roots to stay aligned with the
         *   general scan strategy.
         */
        private fun resolvePreferredFromInstalledConfig(appContext: Context): File? {
            val cfg = InstalledSurveyConfigStore.getOrNull() ?: return null

            val candidates = LinkedHashSet<String>()

            cfg.resolveModelDownloadSpec()
                .fileName
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it) }

            cfg.modelDefaults.defaultFileName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates.add(it) }

            for (raw in candidates) {
                val safeName = sanitizeSimpleFileName(raw) ?: continue
                for (dir in candidateRoots(appContext)) {
                    val f = File(dir, safeName)
                    val ok = runCatching {
                        f.exists() && f.isFile && f.length() > 0L
                    }.getOrDefault(false)
                    if (ok) return f
                }
            }

            return null
        }

        /**
         * Returns small app-private roots that may contain model files.
         */
        private fun candidateRoots(appContext: Context): List<File> {
            return listOfNotNull(
                appContext.filesDir,
                File(appContext.filesDir, "models"),
                File(appContext.filesDir, "slm"),
                File(appContext.filesDir, "litert"),
                appContext.noBackupFilesDir,
                appContext.cacheDir,
            ).distinct()
        }

        /**
         * Simple file-name sanitizer to avoid traversal-like inputs.
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