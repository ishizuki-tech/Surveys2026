/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Process Bootstrap)
 *  ---------------------------------------------------------------------
 *  File: AppBootstrap.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.CrashCapture
import com.negi.surveys.logging.GitHubLogUploadManager
import com.negi.surveys.logging.SafeLog
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Process-scoped bootstrap to keep startup stable.
 *
 * Goals:
 * - Run heavy initialization exactly once in the main process.
 * - Install crash capture in every process.
 * - Avoid duplicated heavy work in non-main processes.
 * - Never prevent the app from showing UI (best-effort + non-fatal).
 *
 * Privacy:
 * - Never log tokens/urls/file names/raw content.
 * - Never log exception.message (it can contain sensitive info).
 *
 * Note:
 * - To enforce the privacy rule robustly, this file never passes Throwable to the logger.
 *   (Some logger implementations print `Throwable.toString()`, which includes message.)
 */
object AppBootstrap {

    private const val TAG_BOOT = "AppBootstrap"
    private val started = AtomicBoolean(false)

    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext

        /**
         * Crash capture must be installed in every process.
         *
         * Why:
         * - UncaughtExceptionHandler is process-local.
         * - Non-main processes can still crash (WorkManager, remote services, etc.).
         * - CrashCapture itself is intentionally lightweight and does not perform network work here.
         */
        runCatching { CrashCapture.install(appCtx) }
            .onFailure { t ->
                SafeLog.w(TAG_BOOT, "CrashCapture.install failed (non-fatal) type=${t.javaClass.simpleName}")
            }

        // Avoid duplicated heavy work in non-main processes (WorkManager/remote services, etc.).
        val isMainProcess = ProcessGuards.isMainProcess(appCtx)
        val processName = ProcessGuards.currentProcessNameCached(appCtx)
        if (!isMainProcess) {
            SafeLog.i(
                TAG_BOOT,
                "bootstrap: non-main process detected (heavy init skipped) " +
                        "pid=${Process.myPid()} process=$processName",
            )
            return
        }

        if (!started.compareAndSet(false, true)) {
            SafeLog.d(TAG_BOOT, "bootstrap: already initialized (skip)")
            return
        }

        val t0 = SystemClock.elapsedRealtime()

        runCatching { AppLog.init(appCtx) }
            .onFailure { t ->
                // Do not pass Throwable to logger (may print exception.message).
                SafeLog.w(TAG_BOOT, "AppLog.init failed (non-fatal) type=${t.javaClass.simpleName}")
            }

        PendingCrashUploadKickoff.tryStart(appCtx)

        val dt = SystemClock.elapsedRealtime() - t0
        SafeLog.i(
            TAG_BOOT,
            "bootstrap: done in ${dt}ms pid=${Process.myPid()} uid=${Process.myUid()} " +
                    "process=$processName build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}",
        )
    }

    /**
     * Process-scoped guard to prevent starting multiple upload threads.
     *
     * Notes:
     * - Uses a background thread intentionally (best-effort early upload).
     * - Watchdog logs only state breadcrumbs (no sensitive payloads).
     * - Blocking upload is additionally wrapped with a hard timeout to avoid hanging forever.
     */
    private object PendingCrashUploadKickoff {
        private const val TAG_UPLOAD = "PendingCrashUpload"
        private const val THREAD_NAME = "PendingCrashUpload"

        private const val UPLOAD_WATCHDOG_MS = 25_000L
        private const val UPLOAD_HARD_TIMEOUT_MS = 23_000L

        private val started = AtomicBoolean(false)

        fun tryStart(context: Context) {
            val appCtx = context.applicationContext
            if (!started.compareAndSet(false, true)) {
                SafeLog.d(TAG_UPLOAD, "pending crash upload: already started in this process (skip)")
                return
            }

            val launchOk = kickOffPendingCrashUpload(appCtx)
            if (!launchOk) {
                started.set(false)
            }
        }

        private enum class UploadPhase {
            INIT,
            COUNT_PENDING_INITIAL,
            CHECK_GH_CONFIG,
            UPLOAD_BLOCKING,
            COUNT_PENDING_AFTER,
            DONE,
        }

        private data class UploadCallMeta(
            val finished: Boolean,
            val ok: Boolean,
            val resultType: String?,
            val errType: String?,
        )

        private fun kickOffPendingCrashUpload(context: Context): Boolean {
            return runCatching {
                thread(
                    start = true,
                    name = THREAD_NAME,
                    // Do NOT daemonize this outer thread: it provides early best-effort upload.
                    // The actual blocking upload call is run on a daemon sub-thread with a hard timeout.
                    isDaemon = false,
                ) {
                    val appCtx = context.applicationContext
                    val t0 = SystemClock.elapsedRealtime()
                    val phaseRef = AtomicReference(UploadPhase.INIT)
                    val finishedRef = AtomicBoolean(false)

                    Thread.currentThread().uncaughtExceptionHandler =
                        Thread.UncaughtExceptionHandler { _, e ->
                            // Do not pass Throwable to logger (may print exception.message).
                            SafeLog.e(TAG_UPLOAD, "pending crash upload: uncaught exception type=${e.javaClass.simpleName}")
                        }

                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

                    // Watchdog: if the blocking upload hangs, leave an observable breadcrumb.
                    val workerThread = Thread.currentThread()
                    val watchdog = thread(
                        start = true,
                        name = "${THREAD_NAME}-Watchdog",
                        isDaemon = true,
                    ) {
                        runCatching {
                            Thread.sleep(UPLOAD_WATCHDOG_MS)
                            if (finishedRef.get()) return@thread

                            val elapsed = SystemClock.elapsedRealtime() - t0
                            SafeLog.w(
                                TAG_UPLOAD,
                                "pending crash upload: watchdog timeout after ${UPLOAD_WATCHDOG_MS}ms " +
                                        "elapsed=${elapsed}ms phase=${phaseRef.get()} state=${workerThread.state} " +
                                        "process=${ProcessGuards.currentProcessNameCached(appCtx)}",
                            )
                        }
                    }

                    try {
                        phaseRef.set(UploadPhase.COUNT_PENDING_INITIAL)
                        val pending0 = runCatching { com.negi.surveys.logging.CrashCapture.countPendingCrashes(appCtx) }
                            .getOrDefault(-1)

                        phaseRef.set(UploadPhase.CHECK_GH_CONFIG)
                        val ghConfigured = runCatching { GitHubLogUploadManager.isConfigured() }
                            .getOrDefault(false)

                        SafeLog.i(
                            TAG_UPLOAD,
                            "pending crash upload: start pending=$pending0 ghConfigured=$ghConfigured " +
                                    "pid=${Process.myPid()} process=${ProcessGuards.currentProcessNameCached(appCtx)} " +
                                    "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}",
                        )

                        if (pending0 == 0) {
                            SafeLog.i(TAG_UPLOAD, "pending crash upload: none")
                            return@thread
                        }

                        if (!ghConfigured) {
                            SafeLog.w(TAG_UPLOAD, "pending crash upload: GitHub not configured; skip")
                            return@thread
                        }

                        phaseRef.set(UploadPhase.UPLOAD_BLOCKING)
                        val pendingBefore = runCatching { com.negi.surveys.logging.CrashCapture.countPendingCrashes(appCtx) }
                            .getOrDefault(-1)

                        val meta = tryUploadPendingCrashesWithHardTimeout(appCtx, UPLOAD_HARD_TIMEOUT_MS)

                        SafeLog.i(
                            TAG_UPLOAD,
                            "pending crash upload (GitHub): pendingBefore=$pendingBefore finished=${meta.finished} " +
                                    "ok=${meta.ok} resultType=${meta.resultType ?: "-"} errType=${meta.errType ?: "-"}",
                        )

                        if (!meta.finished) {
                            SafeLog.w(TAG_UPLOAD, "pending crash upload (GitHub): hard timeout reached; continuing")
                        } else if (!meta.ok && meta.errType != null) {
                            // Do not pass Throwable to logger (may print exception.message).
                            SafeLog.e(TAG_UPLOAD, "pending crash upload (GitHub) failed errType=${meta.errType}")
                        }

                        phaseRef.set(UploadPhase.COUNT_PENDING_AFTER)
                        val pendingAfter = runCatching { com.negi.surveys.logging.CrashCapture.countPendingCrashes(appCtx) }
                            .getOrDefault(-1)

                        SafeLog.i(TAG_UPLOAD, "pending crash upload: end pendingAfter=$pendingAfter")
                    } catch (t: Throwable) {
                        // Do not pass Throwable to logger (may print exception.message).
                        SafeLog.e(TAG_UPLOAD, "kickOffPendingCrashUpload: unexpected failure type=${t.javaClass.simpleName}")
                    } finally {
                        phaseRef.set(UploadPhase.DONE)
                        finishedRef.set(true)
                        runCatching { watchdog.interrupt() }

                        val dt = SystemClock.elapsedRealtime() - t0
                        SafeLog.i(TAG_UPLOAD, "pending crash upload: done in ${dt}ms")
                    }
                }
                true
            }.getOrElse { t ->
                // Do not pass Throwable to logger (may print exception.message).
                SafeLog.w(
                    TAG_UPLOAD,
                    "Failed to start PendingCrashUpload thread (non-fatal) type=${t.javaClass.simpleName}",
                )
                false
            }
        }

        /**
         * Runs the blocking upload on a daemon sub-thread and joins with a hard timeout.
         *
         * Why:
         * - A network stack can hang indefinitely in rare cases.
         * - We do not want a non-daemon bootstrap thread to stick around forever.
         */
        private fun tryUploadPendingCrashesWithHardTimeout(
            appCtx: Context,
            timeoutMs: Long,
        ): UploadCallMeta {
            data class UploadCallOutcome(
                val ok: Boolean,
                val resultType: String?,
                val errType: String?,
            )

            val outcomeRef = AtomicReference<UploadCallOutcome?>(null)

            val callThread = thread(
                start = true,
                name = "${THREAD_NAME}-Call",
                isDaemon = true,
            ) {
                val outcome = runCatching {
                    GitHubLogUploadManager.tryUploadPendingCrashesBlocking(appCtx)
                }.fold(
                    onSuccess = { value ->
                        UploadCallOutcome(
                            ok = true,
                            resultType = value.javaClass.simpleName,
                            errType = null,
                        )
                    },
                    onFailure = { err ->
                        UploadCallOutcome(
                            ok = false,
                            resultType = null,
                            errType = err.javaClass.simpleName,
                        )
                    },
                )
                outcomeRef.set(outcome)
            }

            runCatching { callThread.join(timeoutMs) }

            val outcome = outcomeRef.get()
            return if (outcome != null) {
                UploadCallMeta(
                    finished = true,
                    ok = outcome.ok,
                    resultType = outcome.resultType,
                    errType = outcome.errType,
                )
            } else {
                // Best-effort interrupt; network stacks may ignore it.
                runCatching { callThread.interrupt() }
                UploadCallMeta(
                    finished = false,
                    ok = false,
                    resultType = null,
                    errType = "Timeout",
                )
            }
        }
    }

    /**
     * Process-related guards.
     *
     * Notes:
     * - Prefer API 28+ getProcessName fast path.
     * - Fallback order: ActivityManager -> /proc/self/cmdline.
     * - Use ApplicationInfo.processName as the main-process name (handles custom android:process).
     */
    private object ProcessGuards {

        private val cachedProcessName = AtomicReference<String?>(null)

        fun currentProcessNameCached(context: Context): String {
            val cur = cachedProcessName.get()
            if (!cur.isNullOrBlank()) return cur

            val appCtx = context.applicationContext
            val resolved = getCurrentProcessName(appCtx)?.takeIf { it.isNotBlank() }
            if (resolved != null) {
                cachedProcessName.set(resolved)
                return resolved
            }

            return getMainProcessName(appCtx) ?: "unknown"
        }

        fun isMainProcess(context: Context): Boolean {
            val appCtx = context.applicationContext
            val mainProcessName = getMainProcessName(appCtx) ?: appCtx.packageName

            val current = getCurrentProcessName(appCtx)
            if (current.isNullOrBlank()) {
                // Fail-open for safety, but do not cache the fallback as if it were the true current name.
                SafeLog.w(TAG_BOOT, "process name unavailable; fail-open (treat as main)")
                return true
            }

            cachedProcessName.set(current)
            return current == mainProcessName
        }

        /**
         * Returns the main process name declared by the app.
         *
         * This is safer than assuming it equals packageName, because android:process can override it.
         */
        private fun getMainProcessName(context: Context): String? {
            return runCatching { context.applicationInfo.processName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

        private fun getCurrentProcessName(context: Context): String? {
            // API 28+: reliable fast path.
            if (Build.VERSION.SDK_INT >= 28) {
                val name = runCatching { android.app.Application.getProcessName() }.getOrNull()
                if (!name.isNullOrBlank()) return name
            }

            // Fallback: ActivityManager scan.
            val am = runCatching {
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            }.getOrNull()

            if (am != null) {
                val pid = Process.myPid()
                val name = runCatching {
                    am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
                }.getOrNull()
                if (!name.isNullOrBlank()) return name
            }

            // Last resort: /proc/self/cmdline.
            return readProcCmdline()
        }

        private fun readProcCmdline(): String? {
            return runCatching {
                FileInputStream("/proc/self/cmdline").use { fis ->
                    val bytes = ByteArray(256)
                    val n = fis.read(bytes)
                    if (n <= 0) return@runCatching null
                    val raw = String(bytes, 0, n)
                    raw.trim { it <= ' ' || it == '\u0000' }.ifBlank { null }
                }
            }.getOrNull()
        }
    }
}