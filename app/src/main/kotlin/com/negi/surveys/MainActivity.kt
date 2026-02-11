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
import com.negi.surveys.logging.LogUploadManager

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
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext

        // English comment: Initialize file logger early so crash capture can write minimal traces too.
        AppLog.init(appContext)

        // English comment: Install crash capture as early as possible.
        CrashCapture.install(appContext)

        // English comment: Attempt to upload any pending crash logs from a previous session (best-effort).
        kickOffPendingCrashUpload(appContext)

        // Enable edge-to-edge layout so the app shell can correctly handle system insets.
        enableEdgeToEdge()

        val startup = buildStartupLog(savedInstanceState)
        Log.d(TAG, startup)
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

/**
 * Kicks off a best-effort upload for pending crash logs.
 *
 * Design:
 * - Never blocks the main thread.
 * - Safe to call on every launch; no-ops if there is nothing to upload.
 * - Only attempts providers that are configured (reduces noisy failures).
 */
private fun kickOffPendingCrashUpload(context: Context) {
    runCatching {
        Thread {
            val t0 = SystemClock.elapsedRealtime()

            // English comment: Snapshot configuration early for deterministic logs.
            val ghConfigured = runCatching { GitHubLogUploadManager.isConfigured() }.getOrDefault(false)
            val sbConfigured = runCatching { LogUploadManager.isConfigured() }.getOrDefault(false)

            try {
                val pending = runCatching { CrashCapture.countPendingCrashes(context) }.getOrDefault(-1)

                AppLog.i(
                    TAG,
                    "pending crash upload: start pending=$pending " +
                            "ghConfigured=$ghConfigured sbConfigured=$sbConfigured " +
                            "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
                )

                if (pending <= 0) {
                    // English comment: Nothing to do. Keep logs minimal and deterministic.
                    AppLog.i(TAG, "pending crash upload: none")
                    return@Thread
                }

                // Provider: GitHub
                if (ghConfigured) {
                    runCatching {
                        val r = GitHubLogUploadManager.tryUploadPendingCrashesBlocking(context)
                        AppLog.i(
                            TAG,
                            "pending crash upload (GitHub): ok=${r.isSuccess} " +
                                    "result=${r.getOrNull()} err=${r.exceptionOrNull()?.javaClass?.simpleName}"
                        )
                    }.onFailure { t ->
                        AppLog.e(TAG, "pending crash upload (GitHub) failed", t)
                    }
                } else {
                    AppLog.w(TAG, "pending crash upload (GitHub) skipped: not configured")
                }

                // Provider: Supabase
                if (sbConfigured) {
                    runCatching {
                        val r = LogUploadManager.tryUploadPendingCrashesBlocking(context)
                        AppLog.i(
                            TAG,
                            "pending crash upload (Supabase): ok=${r.isSuccess} " +
                                    "result=${r.getOrNull()} err=${r.exceptionOrNull()?.javaClass?.simpleName}"
                        )
                    }.onFailure { t ->
                        AppLog.e(TAG, "pending crash upload (Supabase) failed", t)
                    }
                } else {
                    AppLog.w(TAG, "pending crash upload (Supabase) skipped: not configured")
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "kickOffPendingCrashUpload: unexpected failure", t)
            } finally {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLog.i(TAG, "pending crash upload: done in ${dt}ms")
            }
        }.apply {
            name = "PendingCrashUpload"
            // English comment: Daemon is fine for "best-effort" background work.
            // English comment: If you want maximum reliability, set isDaemon=false or use WorkManager.
            isDaemon = true
        }.start()
    }.onFailure { t ->
        Log.w(TAG, "Failed to start PendingCrashUpload thread (non-fatal)", t)
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

    // English comment: Keep log content non-sensitive.
    val buildLabel =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"

    return "onCreate: sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "recreated=$recreation " +
            "build=$buildLabel"
}
