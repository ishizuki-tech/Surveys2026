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
import com.negi.surveys.logging.SupabaseLogUploadManager
import java.net.URI
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

        // Initialize file logger early so crash capture can write minimal traces too.
        AppLog.init(appContext)

        // Install crash capture as early as possible.
        CrashCapture.install(appContext)

        // Attempt to upload any pending crash logs from a previous session (best-effort).
        PendingCrashUploadKickoff.tryStart(appContext)

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
private const val THREAD_NAME = "PendingCrashUpload"
private const val ERR_MSG_MAX = 220

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
 * - Only attempts providers that are configured (reduces noisy failures).
 *
 * Critical ordering:
 * - Run Supabase first to avoid "GitHub moved pending -> Supabase sees 0" behavior.
 * - When both providers are enabled, only the last provider should move crash files.
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
                val sbConfigured = runCatching { SupabaseLogUploadManager.isConfigured() }.getOrDefault(false)

                val pending0 = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                AppLog.i(
                    TAG,
                    "pending crash upload: start pending=$pending0 " +
                            "ghConfigured=$ghConfigured sbConfigured=$sbConfigured " +
                            "pid=${Process.myPid()} " +
                            "build=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
                )

                if (pending0 <= 0) {
                    AppLog.i(TAG, "pending crash upload: none")
                    return@thread
                }

                if (!ghConfigured && !sbConfigured) {
                    AppLog.w(TAG, "pending crash upload: no providers configured; skip")
                    return@thread
                }

                // Emit a small Supabase config trace (PII-safe: host/bucket/prefix only).
                runCatching {
                    val rawUrl = buildConfigString("SUPABASE_URL").trim()
                    val urlHost = runCatching { if (rawUrl.isBlank()) "" else URI(rawUrl).host.orEmpty() }
                        .getOrDefault("")

                    val bucket = buildConfigString("SUPABASE_LOG_BUCKET").trim()
                    val prefix = buildConfigString("SUPABASE_LOG_PREFIX").trim().trim('/').ifBlank { "surveyapp" }

                    AppLog.i(
                        TAG,
                        "crash upload config: ghConfigured=$ghConfigured sbConfigured=$sbConfigured " +
                                "supabaseHost=${urlHost.ifBlank { "(blank)" }} bucket=${bucket.ifBlank { "(blank)" }} prefix=$prefix"
                    )
                }.onFailure {
                    AppLog.w(TAG, "crash upload config: failed to summarize (non-fatal): ${it.javaClass.simpleName}")
                }

                /**
                 * When both are enabled:
                 * - Supabase should NOT move crash files on success (otherwise GitHub sees 0).
                 * - GitHub runs after and performs the move (or whatever its implementation does).
                 */
                val sbMoveOnSuccess = !ghConfigured

                // Provider: Supabase (run first).
                if (sbConfigured) {
                    runCatching {
                        val pendingBefore = runCatching { CrashCapture.countPendingCrashes(appCtx) }.getOrDefault(-1)

                        val r = SupabaseLogUploadManager.tryUploadPendingCrashesBlocking(
                            context = appCtx,
                            userJwt = null,
                            moveOnSuccess = sbMoveOnSuccess
                        )

                        val err = r.exceptionOrNull()
                        val errMsg = err?.message?.replace('\n', ' ')?.take(ERR_MSG_MAX).orEmpty()

                        AppLog.i(
                            TAG,
                            "pending crash upload (Supabase): pendingBefore=$pendingBefore ok=${r.isSuccess} " +
                                    "result=${r.getOrNull()} moveOnSuccess=$sbMoveOnSuccess " +
                                    "err=${err?.javaClass?.simpleName} msg=${if (errMsg.isBlank()) "-" else errMsg}"
                        )

                        if (err != null && BuildConfig.DEBUG) {
                            Log.e(TAG, "pending crash upload (Supabase) failed", err)
                        }
                    }.onFailure { t ->
                        if (BuildConfig.DEBUG) Log.e(TAG, "pending crash upload (Supabase) failed", t)
                        AppLog.e(TAG, "pending crash upload (Supabase) failed", t)
                    }
                } else {
                    AppLog.w(TAG, "pending crash upload (Supabase) skipped: not configured")
                }

                // Provider: GitHub (run second).
                if (ghConfigured) {
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
                } else {
                    AppLog.w(TAG, "pending crash upload (GitHub) skipped: not configured")
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
            "pid=${Process.myPid()} " +
            "recreated=$recreation " +
            "build=$buildLabel"
}

/**
 * Reads a String field from BuildConfig via reflection.
 *
 * Notes:
 * - Avoids compile-time dependency on optional fields.
 * - Returns "" if missing or not a String.
 */
private fun buildConfigString(fieldName: String): String {
    return runCatching {
        val f = BuildConfig::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        val v = f.get(null)
        (v as? String).orEmpty()
    }.getOrDefault("")
}