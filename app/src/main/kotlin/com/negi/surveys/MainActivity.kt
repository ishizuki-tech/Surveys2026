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

import android.os.Build
import android.os.Bundle
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
 * - Crash uploads are best-effort and run on a background thread.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContext = applicationContext

        // Initialize file logger early so crash capture can also write minimal traces.
        AppLog.init(appContext)

        // Install crash capture as early as possible (within current architecture).
        CrashCapture.install(appContext)

        // Attempt to upload any pending crash logs from a previous session.
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
 * - GitHub path requires BuildConfig.GITHUB_* + token (debug only by default).
 * - Supabase path currently is disabled by LogUploadManager.isConfigured() hard-false.
 */
private fun kickOffPendingCrashUpload(context: android.content.Context) {
    runCatching {
        Thread {
            try {
                val pending = CrashCapture.countPendingCrashes(context)
                if (pending > 0) {
                    AppLog.i(TAG, "pending crash logs detected: count=$pending")
                }

                // GitHub upload (Create/Update contents API).
                runCatching {
                    GitHubLogUploadManager.tryUploadPendingCrashesBlocking(context)
                }.onFailure { t ->
                    AppLog.e(TAG, "pending crash upload (GitHub) failed", t)
                }

                // Supabase upload (currently disabled by isConfigured()).
                runCatching {
                    LogUploadManager.tryUploadPendingCrashesBlocking(context)
                }.onFailure { t ->
                    AppLog.e(TAG, "pending crash upload (Supabase) failed", t)
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "kickOffPendingCrashUpload: unexpected failure", t)
            }
        }.apply {
            name = "PendingCrashUpload"
            isDaemon = true
        }.start()
    }.onFailure { t ->
        Log.w(TAG, "Failed to start PendingCrashUpload thread (non-fatal)", t)
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

    // Notes:
    // - BuildConfig is generated under android.namespace.
    // - Keep log content non-sensitive.
    val buildLabel =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"

    return "onCreate: sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "recreated=$recreation " +
            "build=$buildLabel"
}
