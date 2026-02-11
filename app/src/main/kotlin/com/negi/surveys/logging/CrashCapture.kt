/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: CrashCapture.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures uncaught exceptions into an app-owned crash file.
 *
 * Why:
 * - If the process dies, Logcat is not guaranteed to be available to field users.
 * - A persisted crash report enables "upload on next launch".
 *
 * Notes:
 * - This is intentionally minimal; it does not attempt to do network work on crash.
 * - Upload happens on the next cold start via upload manager.
 * - Uses a re-entrancy guard to avoid infinite recursion if the handler itself crashes.
 * - Writes to a temp file first, then renames for best-effort atomicity.
 */
object CrashCapture {
    private const val TAG = "CrashCapture"

    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    /**
     * Installs a default uncaught exception handler that persists crash reports.
     *
     * Notes:
     * - Safe to call multiple times.
     * - Uses applicationContext to avoid leaking an Activity.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext

        // Ensure AppLog is ready so crash-path logging has a higher chance to land on disk.
        runCatching { AppLog.ensureReady(appContext) }

        previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Prevent recursion if our handler throws or if the process is already failing hard.
            val alreadyHandling = !handlingCrash.compareAndSet(false, true)
            if (alreadyHandling) {
                runCatching { previous?.uncaughtException(thread, throwable) }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                runCatching {
                    AppLog.e(TAG, "uncaught: thread=${thread.name} pid=${Process.myPid()}", throwable)
                }
                runCatching {
                    writeCrashFile(appContext, thread, throwable)
                }
            } finally {
                // Delegate to the original handler (system / crash reporter / etc.).
                runCatching { previous?.uncaughtException(thread, throwable) }
                // In practice the process will die, but keep state consistent for tests.
                handlingCrash.set(false)
            }
        }

        AppLog.i(TAG, "installed")
    }

    fun countPendingCrashes(context: Context): Int =
        LogFiles.listPendingCrashLogs(context.applicationContext).size

    fun pendingCrashFiles(context: Context): List<File> =
        LogFiles.listPendingCrashLogs(context.applicationContext)

    private fun writeCrashFile(context: Context, thread: Thread, t: Throwable) {
        val appContext = context.applicationContext

        val dir = LogFiles.crashPendingDir(appContext)
        if (!dir.exists()) runCatching { dir.mkdirs() }

        val utcCompact = LogFiles.utcCompactNow()
        val pid = Process.myPid()
        val name = "crash-$utcCompact-$pid.txt"

        val tmp = File(dir, "$name.tmp")
        val file = File(dir, name)

        val sw = StringWriter(8 * 1024)
        val pw = PrintWriter(sw)

        val pkg = safePackageName(appContext)
        val versionName = safeVersionName(appContext)
        val versionCode = safeVersionCode(appContext)

        pw.println("===== Survey App Crash Report =====")
        pw.println("utcCompact=$utcCompact")
        pw.println("thread=${thread.name}")
        pw.println("pid=$pid")
        pw.println("sdk=${Build.VERSION.SDK_INT}")
        pw.println("device=${Build.MANUFACTURER}/${Build.MODEL}")
        pw.println("appId=$pkg")
        pw.println("versionName=$versionName")
        pw.println("versionCode=$versionCode")
        pw.println("build=${LogFiles.buildLabelSafe()}")
        pw.println()

        pw.println("----- Exception -----")
        t.printStackTrace(pw)
        pw.flush()

        val bytes = sw.toString().toByteArray(Charsets.UTF_8)

        // Best-effort: write to tmp then rename into place.
        FileOutputStream(tmp, false).use { out ->
            out.write(bytes)
            out.flush()
        }

        // If rename fails (rare), fallback to direct write.
        if (!tmp.renameTo(file)) {
            runCatching { file.delete() }
            FileOutputStream(file, false).use { out ->
                out.write(bytes)
                out.flush()
            }
            runCatching { tmp.delete() }
        }
    }

    private fun safePackageName(context: Context): String =
        runCatching { context.packageName }.getOrDefault("unknown")

    private fun safeVersionName(context: Context): String {
        val pm = context.packageManager ?: return "unknown"
        val pkg = context.packageName
        return runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            pi.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun safeVersionCode(context: Context): Long {
        val pm = context.packageManager ?: return -1L
        val pkg = context.packageName
        return runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }.getOrDefault(-1L)
    }
}
