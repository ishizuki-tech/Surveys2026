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
 * - Run initialization exactly once per process.
 * - Avoid duplicated heavy work in non-main processes.
 * - Never prevent the app from showing UI (best-effort + non-fatal).
 */
object AppBootstrap {
    private const val TAG_BOOT = "AppBootstrap"
    private val started = AtomicBoolean(false)

    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext

        // Avoid duplicated heavy work in non-main processes (WorkManager/remote services, etc.).
        if (!ProcessGuards.isMainProcess(appCtx)) {
            SafeLog.i(
                TAG_BOOT,
                "bootstrap: non-main process detected (skip) pid=${Process.myPid()} process=${ProcessGuards.currentProcessNameCached()}"
            )
            return
        }

        if (!started.compareAndSet(false, true)) {
            SafeLog.d(TAG_BOOT, "bootstrap: already initialized (skip)")
            return
        }

        val t0 = SystemClock.elapsedRealtime()

        runCatching { AppLog.init(appCtx) }.onFailure { t ->
            SafeLog.w(TAG_BOOT, "AppLog.init failed (non-fatal): ${t.javaClass.simpleName}", t)
        }

        runCatching { CrashCapture.install(appCtx) }.onFailure { t ->
            SafeLog.w(TAG_BOOT, "CrashCapture.install failed (non-fatal): ${t.javaClass.simpleName}", t)
        }

        PendingCrashUploadKickoff.tryStart(appCtx)

        val dt = SystemClock.elapsedRealtime() - t0
        SafeLog.i(
            TAG_BOOT,
            "bootstrap: done in ${dt}ms pid=${Process.myPid()} uid=${Process.myUid()} " +
                    "process=${ProcessGuards.currentProcessNameCached()} " +
                    "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
        )
    }
}

/**
 * Process-scoped guard to prevent starting multiple upload threads.
 */
private object PendingCrashUploadKickoff {
    private const val TAG_UPLOAD = "PendingCrashUpload"
    private const val THREAD_NAME = "PendingCrashUpload"
    private const val ERR_MSG_MAX = 220
    private const val UPLOAD_WATCHDOG_MS = 25_000L

    private val started = AtomicBoolean(false)

    fun tryStart(context: Context) {
        val appCtx = context.applicationContext
        if (!started.compareAndSet(false, true)) {
            SafeLog.d(TAG_UPLOAD, "pending crash upload: already started in this process (skip)")
            return
        }
        kickOffPendingCrashUpload(appCtx)
    }

    private enum class UploadPhase {
        INIT,
        COUNT_PENDING_INITIAL,
        CHECK_GH_CONFIG,
        UPLOAD_BLOCKING,
        COUNT_PENDING_AFTER,
        DONE
    }

    private fun kickOffPendingCrashUpload(context: Context) {
        runCatching {
            thread(
                start = true,
                name = THREAD_NAME,
                // Do NOT daemonize: we want a higher chance of completing early uploads.
                isDaemon = false
            ) {
                val appCtx = context.applicationContext
                val t0 = SystemClock.elapsedRealtime()
                val phaseRef = AtomicReference(UploadPhase.INIT)

                Thread.currentThread().uncaughtExceptionHandler =
                    Thread.UncaughtExceptionHandler { _, e ->
                        SafeLog.e(TAG_UPLOAD, "pending crash upload: uncaught exception", e)
                    }

                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }

                // Watchdog: if the blocking upload hangs, leave an observable breadcrumb.
                val workerThread = Thread.currentThread()
                val watchdog = thread(
                    start = true,
                    name = "${THREAD_NAME}-Watchdog",
                    isDaemon = true
                ) {
                    runCatching {
                        Thread.sleep(UPLOAD_WATCHDOG_MS)
                        val elapsed = SystemClock.elapsedRealtime() - t0
                        SafeLog.w(
                            TAG_UPLOAD,
                            "pending crash upload: watchdog timeout after ${UPLOAD_WATCHDOG_MS}ms " +
                                    "elapsed=${elapsed}ms phase=${phaseRef.get()} state=${workerThread.state} " +
                                    "process=${ProcessGuards.currentProcessNameCached()}"
                        )
                    }
                }

                try {
                    phaseRef.set(UploadPhase.COUNT_PENDING_INITIAL)
                    val pending0 = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                    phaseRef.set(UploadPhase.CHECK_GH_CONFIG)
                    val ghConfigured = runCatching { GitHubLogUploadManager.isConfigured() }.getOrDefault(false)

                    SafeLog.i(
                        TAG_UPLOAD,
                        "pending crash upload: start pending=$pending0 ghConfigured=$ghConfigured " +
                                "pid=${Process.myPid()} process=${ProcessGuards.currentProcessNameCached()} " +
                                "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
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
                    val pendingBefore = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                    val r = runCatching { GitHubLogUploadManager.tryUploadPendingCrashesBlocking(appCtx) }

                    val err = r.exceptionOrNull()
                    val errMsg = err?.message?.replace('\n', ' ')?.take(ERR_MSG_MAX).orEmpty()

                    SafeLog.i(
                        TAG_UPLOAD,
                        "pending crash upload (GitHub): pendingBefore=$pendingBefore ok=${r.isSuccess} " +
                                "result=${r.getOrNull()} err=${err?.javaClass?.simpleName} " +
                                "msg=${if (errMsg.isBlank()) "-" else errMsg}"
                    )

                    if (err != null) {
                        SafeLog.e(TAG_UPLOAD, "pending crash upload (GitHub) failed", err)
                    }

                    phaseRef.set(UploadPhase.COUNT_PENDING_AFTER)
                    val pendingAfter = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)
                    SafeLog.i(TAG_UPLOAD, "pending crash upload: end pendingAfter=$pendingAfter")
                } catch (t: Throwable) {
                    SafeLog.e(TAG_UPLOAD, "kickOffPendingCrashUpload: unexpected failure", t)
                } finally {
                    phaseRef.set(UploadPhase.DONE)
                    runCatching { watchdog.interrupt() }

                    val dt = SystemClock.elapsedRealtime() - t0
                    SafeLog.i(TAG_UPLOAD, "pending crash upload: done in ${dt}ms")
                }
            }
        }.onFailure { t ->
            SafeLog.w(
                TAG_UPLOAD,
                "Failed to start PendingCrashUpload thread (non-fatal): ${t.javaClass.simpleName}",
                t
            )
        }
    }
}

/**
 * Process-related guards.
 */
private object ProcessGuards {

    private const val TAG_BOOT = "AppBootstrap"
    private val cachedProcessName = AtomicReference<String?>(null)

    fun currentProcessNameCached(): String {
        return cachedProcessName.get() ?: "unknown"
    }

    fun isMainProcess(context: Context): Boolean {
        val appCtx = context.applicationContext
        val packageName = appCtx.packageName

        val current = getCurrentProcessName(appCtx)
        if (current.isNullOrBlank()) {
            // Fail-open for safety, but leave a breadcrumb for diagnosis.
            SafeLog.w(TAG_BOOT, "process name unavailable; fail-open (treat as main)")
            return true
        }

        cachedProcessName.set(current)
        return current == packageName
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

        // Last resort: /proc/self/cmdline (small read, but still IO).
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