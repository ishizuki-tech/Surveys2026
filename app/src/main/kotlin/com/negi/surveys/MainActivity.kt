/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.CrashCapture
import com.negi.surveys.logging.GitHubLogUploadManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Main entry activity for the Nav3 frame prototype.
 *
 * Responsibilities (kept intentionally small):
 * - Configure window behavior (edge-to-edge) so insets are handled consistently.
 * - Provide a stable Material3 theme surface for the Compose tree.
 * - Install crash capture and kick off "upload-on-next-launch" for pending crash logs.
 *
 * Notes:
 * - Avoid putting survey/session state in the Activity; rely on ViewModels instead.
 * - Crash uploads are best-effort and run off the main thread.
 * - For maximum reliability across process lifecycle, prefer WorkManager.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext

        // Keep diagnostics initialization process-scoped and non-fatal.
        AppBootstrap.ensureInitialized(appContext)

        // Enable edge-to-edge layout so the app shell can correctly handle system insets.
        enableEdgeToEdge()

        val startup = buildStartupLog(savedInstanceState)
        if (BuildConfig.DEBUG) Log.d(TAG, startup)
        AppLog.i(TAG, startup)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SurveyAppRoot()
                }
            }
        }
    }
}

private const val TAG = "MainActivity"
private const val BOOT_TAG = "AppBootstrap"
private const val THREAD_NAME = "PendingCrashUpload"
private const val ERR_MSG_MAX = 220

/**
 * Process-scoped bootstrap to keep startup stable.
 *
 * Why:
 * - Activities can be recreated; initialization should not run multiple times.
 * - Diagnostics code must never prevent the app from showing UI.
 */
private object AppBootstrap {
    private val started = AtomicBoolean(false)

    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext
        if (!started.compareAndSet(false, true)) {
            // Already initialized in this process.
            AppLog.d(BOOT_TAG, "bootstrap: already initialized (skip)")
            return
        }

        val t0 = SystemClock.elapsedRealtime()

        // Initialize file logger early so crash capture can write minimal traces too.
        runCatching {
            AppLog.init(appCtx)
        }.onFailure { t ->
            // Fall back to Logcat only; do not crash.
            Log.w(BOOT_TAG, "AppLog.init failed (non-fatal): ${t.javaClass.simpleName}", t)
        }

        // Install crash capture as early as possible (still best-effort).
        runCatching {
            CrashCapture.install(appCtx)
        }.onFailure { t ->
            Log.w(BOOT_TAG, "CrashCapture.install failed (non-fatal): ${t.javaClass.simpleName}", t)
            // If AppLog is partially available, record it too.
            runCatching { AppLog.w(BOOT_TAG, "CrashCapture.install failed (non-fatal): ${t.javaClass.simpleName}") }
        }

        // Attempt to upload any pending crash logs from a previous session (best-effort).
        PendingCrashUploadKickoff.tryStart(appCtx)

        val dt = SystemClock.elapsedRealtime() - t0
        runCatching { AppLog.i(BOOT_TAG, "bootstrap: done in ${dt}ms pid=${Process.myPid()} uid=${Process.myUid()}") }
        if (BuildConfig.DEBUG) Log.d(BOOT_TAG, "bootstrap: done in ${dt}ms pid=${Process.myPid()} uid=${Process.myUid()}")
    }
}

/**
 * Process-scoped guard to prevent starting multiple upload threads due to Activity recreation.
 *
 * Why:
 * - Activity can be recreated (rotation, multi-window, task restore).
 * - Upload is best-effort; doing it twice adds noise and can race "move pending files".
 */
private object PendingCrashUploadKickoff {
    private val started = AtomicBoolean(false)

    fun tryStart(context: Context) {
        val appCtx = context.applicationContext
        if (!started.compareAndSet(false, true)) {
            AppLog.d(TAG, "pending crash upload: already started in this process (skip)")
            return
        }
        kickOffPendingCrashUpload(appCtx)
    }
}

/**
 * Kicks off a best-effort upload for pending crash logs.
 *
 * Design:
 * - Never blocks the main thread.
 * - Safe to call on every launch; no-ops if there is nothing to upload.
 * - Only attempts upload when GitHub is configured (reduces noisy failures).
 */
private fun kickOffPendingCrashUpload(context: Context) {
    runCatching {
        thread(
            start = true,
            name = THREAD_NAME,
            isDaemon = true
        ) {
            val appCtx = context.applicationContext
            val t0 = SystemClock.elapsedRealtime()

            try {
                // Snapshot configuration early for deterministic logs.
                val ghConfigured = runCatching { GitHubLogUploadManager.isConfigured() }.getOrDefault(false)
                val pending0 = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                AppLog.i(
                    TAG,
                    "pending crash upload: start pending=$pending0 " +
                            "ghConfigured=$ghConfigured " +
                            "pid=${Process.myPid()} " +
                            "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
                )

                if (pending0 <= 0) {
                    AppLog.i(TAG, "pending crash upload: none")
                    return@thread
                }

                if (!ghConfigured) {
                    AppLog.w(TAG, "pending crash upload: GitHub not configured; skip")
                    return@thread
                }

                runCatching {
                    val pendingBefore = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                    val r = GitHubLogUploadManager.tryUploadPendingCrashesBlocking(appCtx)

                    val err = r.exceptionOrNull()
                    val errMsg = err?.message?.replace('\n', ' ')?.take(ERR_MSG_MAX).orEmpty()

                    AppLog.i(
                        TAG,
                        "pending crash upload (GitHub): pendingBefore=$pendingBefore ok=${r.isSuccess} " +
                                "result=${r.getOrNull()} err=${err?.javaClass?.simpleName} msg=${if (errMsg.isBlank()) "-" else errMsg}"
                    )

                    if (err != null && BuildConfig.DEBUG) {
                        Log.e(TAG, "pending crash upload (GitHub) failed", err)
                    }
                }.onFailure { t ->
                    if (BuildConfig.DEBUG) Log.e(TAG, "pending crash upload (GitHub) failed", t)
                    AppLog.e(TAG, "pending crash upload (GitHub) failed", t)
                }

                val pendingAfter = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)
                AppLog.i(TAG, "pending crash upload: end pendingAfter=$pendingAfter")
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.e(TAG, "kickOffPendingCrashUpload: unexpected failure", t)
                AppLog.e(TAG, "kickOffPendingCrashUpload: unexpected failure", t)
            } finally {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLog.i(TAG, "pending crash upload: done in ${dt}ms")
            }
        }
    }.onFailure { t ->
        if (BuildConfig.DEBUG) Log.w(TAG, "Failed to start PendingCrashUpload thread (non-fatal)", t)
        AppLog.w(TAG, "Failed to start PendingCrashUpload thread (non-fatal): ${t.javaClass.simpleName}")
    }
}

/**
 * Builds a concise startup log message.
 *
 * Why:
 * - Helps detect whether we're in a cold start vs recreation (savedInstanceState != null).
 * - Captures device/runtime info useful for debugging.
 */
private fun buildStartupLog(savedInstanceState: Bundle?): String {
    val recreation = savedInstanceState != null

    // Keep log content non-sensitive.
    val buildLabel =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"

    return "onCreate: sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "pid=${Process.myPid()} uid=${Process.myUid()} " +
            "uptimeMs=${SystemClock.uptimeMillis()} " +
            "recreated=$recreation " +
            "build=$buildLabel"
}