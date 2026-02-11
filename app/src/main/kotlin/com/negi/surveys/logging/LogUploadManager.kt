/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: LogUploadManager.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import com.negi.surveys.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads log bundles to Supabase Storage using plain HttpURLConnection.
 *
 * Regular path:
 * - Called manually from UI (e.g., HomeScreen) to upload a "regular" bundle.
 *
 * Crash path:
 * - Crash files are persisted to logs/crash/pending/ by [CrashCapture].
 * - On next cold start, call [tryUploadPendingCrashesBlocking] to upload them.
 *
 * Security notes:
 * - NEVER embed a Supabase service_role key in the app.
 * - For production, prefer Signed Upload URLs or an Edge Function.
 * - This client uses the anon key (or user JWT) and requires appropriate Storage policies.
 */
object LogUploadManager {
    private const val TAG = "LogUpload"

    /**
     * Returns true if the BuildConfig contains non-blank Supabase settings.
     */
    fun isConfigured(): Boolean {
//        return BuildConfig.SUPABASE_URL.isNotBlank() &&
//                BuildConfig.SUPABASE_ANON_KEY.isNotBlank() &&
//                BuildConfig.SUPABASE_LOG_BUCKET.isNotBlank()
        return false
    }

    /**
     * Uploads a "regular" log bundle (blocking).
     *
     * Call this on a background thread.
     *
     * @return Result.success(remoteObjectPath) on success
     */
    fun uploadRegularBlocking(context: android.content.Context, reason: String? = null): Result<String> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("Supabase is not configured (SUPABASE_URL / SUPABASE_ANON_KEY / SUPABASE_LOG_BUCKET)."))
        }

        val install = LogFiles.installId(context)
        val zip = LogFiles.createZipBundle(
            context = context,
            kind = LogFiles.UploadKind.REGULAR,
            includeCrashFiles = emptyList(),
            reason = reason
        )

        val objectPath = buildObjectPath(kind = LogFiles.UploadKind.REGULAR, installId = install, fileName = zip.name)

        AppLog.i(TAG, "uploadRegular: start objectPath=$objectPath size=${zip.length()}")
        val result = uploadZipToSupabase(zip, objectPath)
        zip.delete()

        return result.onSuccess {
            AppLog.i(TAG, "uploadRegular: ok objectPath=$it")
        }.onFailure { t ->
            AppLog.e(TAG, "uploadRegular: failed", t)
        }
    }

    /**
     * Uploads all pending crash logs as a single bundle (blocking).
     *
     * Call this on a background thread. Intended to be invoked at app start.
     *
     * @return Result.success(uploadedCrashCount) on success
     */
    fun tryUploadPendingCrashesBlocking(context: android.content.Context): Result<Int> {
        if (!isConfigured()) {
            // If not configured, do not treat as fatal.
            AppLog.w(TAG, "tryUploadPendingCrashes: Supabase not configured; skip")
            return Result.success(0)
        }

        val pending = CrashCapture.pendingCrashFiles(context)
        if (pending.isEmpty()) return Result.success(0)

        val install = LogFiles.installId(context)
        val zip = LogFiles.createZipBundle(
            context = context,
            kind = LogFiles.UploadKind.CRASH,
            includeCrashFiles = pending,
            reason = "auto:pending_crash"
        )

        val objectPath = buildObjectPath(kind = LogFiles.UploadKind.CRASH, installId = install, fileName = zip.name)

        AppLog.i(TAG, "uploadCrash: start objectPath=$objectPath crashes=${pending.size} size=${zip.length()}")
        val result = uploadZipToSupabase(zip, objectPath)
        zip.delete()

        return result.map {
            // Move crash files to uploaded/ only if upload succeeded.
            val uploadedDir = LogFiles.crashUploadedDir(context)
            var moved = 0
            for (f in pending) {
                val dst = File(uploadedDir, f.name)
                if (f.exists()) {
                    if (runCatching { f.renameTo(dst) }.getOrDefault(false)) moved++
                }
            }
            AppLog.i(TAG, "uploadCrash: ok objectPath=$it moved=$moved")
            pending.size
        }.onFailure { t ->
            AppLog.e(TAG, "uploadCrash: failed", t)
        }
    }

    private fun buildObjectPath(kind: LogFiles.UploadKind, installId: String, fileName: String): String {
        val prefix = BuildConfig.SUPABASE_LOG_PREFIX.trim().trim('/').ifBlank { "surveyapp" }
        return "$prefix/${kind.prefix}/$installId/$fileName"
    }

    private fun uploadZipToSupabase(zipFile: File, objectPath: String): Result<String> {
        val urlBase = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim().trim('/')

        val uploadUrl = "$urlBase/storage/v1/object/$bucket/$objectPath"
        val bytes = runCatching { zipFile.readBytes() }
            .getOrElse { return Result.failure(it) }

        return httpUpload(
            url = uploadUrl,
            anonKey = BuildConfig.SUPABASE_ANON_KEY.trim(),
            contentType = "application/zip",
            body = bytes
        ).map { objectPath }
    }

    /**
     * Performs a standard upload to Supabase Storage via HTTP.
     *
     * Docs:
     * - Standard uploads and overwrite options (x-upsert). See Supabase docs.
     */
    private fun httpUpload(
        url: String,
        anonKey: String,
        contentType: String,
        body: ByteArray
    ): Result<Unit> {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("x-upsert", "true")
            }

            conn.outputStream.use { os ->
                os.write(body)
                os.flush()
            }

            val code = conn.responseCode
            val ok = code in 200..299
            val resp = runCatching {
                val stream = if (ok) conn.inputStream else conn.errorStream
                stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            }.getOrDefault("")

            if (!ok) {
                throw IOException("HTTP $code: ${resp.take(512)}")
            }
        }
    }
}
