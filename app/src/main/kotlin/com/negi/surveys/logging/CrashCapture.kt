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
import android.os.Build
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
 * - Upload happens on the next cold start via [GitHubLogUploadManager.tryUploadPendingCrashesBlocking].
 */
object CrashCapture {
    private const val TAG = "CrashCapture"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e(TAG, "uncaught: thread=${thread.name}", throwable)
                writeCrashFile(appContext, thread, throwable)
            }

            // Delegate to the original handler (system / crash reporter / etc.).
            previous?.uncaughtException(thread, throwable)
        }

        AppLog.i(TAG, "installed")
    }

    fun countPendingCrashes(context: Context): Int =
        LogFiles.listPendingCrashLogs(context).size

    fun pendingCrashFiles(context: Context): List<File> =
        LogFiles.listPendingCrashLogs(context)

    private fun writeCrashFile(context: Context, thread: Thread, t: Throwable) {
        val dir = LogFiles.crashPendingDir(context)
        val name = "crash-${LogFiles.utcCompactNow()}-${android.os.Process.myPid()}.txt"
        val file = File(dir, name)

        val sw = StringWriter(4096)
        val pw = PrintWriter(sw)
        pw.println("===== Survey App Crash Report =====")
        pw.println("utc=${LogFiles.utcCompactNow()}")
        pw.println("thread=${thread.name}")
        pw.println("sdk=${Build.VERSION.SDK_INT}")
        pw.println("device=${Build.MANUFACTURER}/${Build.MODEL}")
        pw.println("build=${LogFiles.buildLabelSafe()}")
        pw.println()
        pw.println("----- Exception -----")
        t.printStackTrace(pw)
        pw.flush()

        FileOutputStream(file, false).use { out ->
            out.write(sw.toString().toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
