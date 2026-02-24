/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SupabaseLogUploadManager.kt
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

object SupabaseLogUploadManager {
    private const val TAG = "SupabaseLogUpload"

    fun isConfigured(): Boolean {
        return BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank() &&
                BuildConfig.SUPABASE_LOG_BUCKET.isNotBlank()
    }

    fun uploadRegularBlocking(
        context: android.content.Context,
        reason: String? = null,
        userJwt: String? = null
    ): Result<String> {
        if (!isConfigured()) {
            return Result.failure(
                IllegalStateException("Supabase is not configured (SUPABASE_URL / SUPABASE_ANON_KEY / SUPABASE_LOG_BUCKET).")
            )
        }

        val install = LogFiles.installId(context)
        val zip = LogFiles.createZipBundle(
            context = context,
            kind = LogFiles.UploadKind.REGULAR,
            includeCrashFiles = emptyList(),
            reason = reason
        )

        val objectPath = buildObjectPath(
            kind = LogFiles.UploadKind.REGULAR,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(TAG, "uploadRegular: start objectPath=$objectPath size=${zip.length()} file=${zip.name}")

        val result = uploadZipToSupabase(zip, objectPath, userJwt)

        return result.onSuccess {
            runCatching { zip.delete() }
            AppLog.i(TAG, "uploadRegular: ok objectPath=$it")
        }.onFailure { t ->
            AppLog.e(TAG, "uploadRegular: failed (zip kept) file=${zip.absolutePath}", t)
        }
    }

    fun tryUploadPendingCrashesBlocking(
        context: android.content.Context,
        userJwt: String? = null,
        moveOnSuccess: Boolean = true
    ): Result<Int> {
        if (!isConfigured()) {
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

        val objectPath = buildObjectPath(
            kind = LogFiles.UploadKind.CRASH,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(
            TAG,
            "uploadCrash: start objectPath=$objectPath crashes=${pending.size} size=${zip.length()} file=${zip.name} moveOnSuccess=$moveOnSuccess"
        )

        val result = uploadZipToSupabase(zip, objectPath, userJwt)

        return result.map {
            val movedCount = if (moveOnSuccess) {
                val uploaded = LogFiles.moveCrashFilesToUploaded(context, pending)
                uploaded.size
            } else {
                0
            }

            AppLog.i(TAG, "uploadCrash: ok objectPath=$it moved=$movedCount")
            runCatching { zip.delete() }
            pending.size
        }.onFailure { t ->
            AppLog.e(TAG, "uploadCrash: failed (zip kept) file=${zip.absolutePath}", t)
        }
    }

    private fun buildObjectPath(kind: LogFiles.UploadKind, installId: String, fileName: String): String {
        val prefix = BuildConfig.SUPABASE_LOG_PREFIX.trim().trim('/').ifBlank { "surveyapp" }
        return "$prefix/${kind.prefix}/$installId/$fileName"
    }

    private fun uploadZipToSupabase(zipFile: File, objectPath: String, userJwt: String?): Result<String> {
        val urlBase = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim().trim('/')

        val uploadUrl = "$urlBase/storage/v1/object/$bucket/$objectPath"
        val bytes = runCatching { zipFile.readBytes() }.getOrElse { return Result.failure(it) }

        val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
        val authBearer = userJwt?.trim().takeUnless { it.isNullOrBlank() } ?: anonKey

        return httpUploadWithFallback(
            url = uploadUrl,
            apikey = anonKey,
            authorizationBearer = authBearer,
            contentType = "application/zip",
            body = bytes
        ).map { objectPath }
    }

    /**
     * Tries POST first, and retries with PUT only when the server explicitly rejects the method (405).
     *
     * Why:
     * - Supabase Storage is typically POST-friendly, but some deployments / proxies may require PUT.
     * - We keep the retry narrow to avoid unintended double uploads on other errors.
     */
    private fun httpUploadWithFallback(
        url: String,
        apikey: String,
        authorizationBearer: String,
        contentType: String,
        body: ByteArray
    ): Result<Unit> {
        val first = httpUploadOnce(
            method = "POST",
            url = url,
            apikey = apikey,
            authorizationBearer = authorizationBearer,
            contentType = contentType,
            body = body
        )

        val ex = first.exceptionOrNull()
        if (ex is HttpStatusException && ex.code == 405) {
            AppLog.w(TAG, "httpUpload: POST rejected (405); retrying with PUT")
            return httpUploadOnce(
                method = "PUT",
                url = url,
                apikey = apikey,
                authorizationBearer = authorizationBearer,
                contentType = contentType,
                body = body
            )
        }

        return first
    }

    private fun httpUploadOnce(
        method: String,
        url: String,
        apikey: String,
        authorizationBearer: String,
        contentType: String,
        body: ByteArray
    ): Result<Unit> {
        return runCatching {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    doOutput = true
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $authorizationBearer")
                    setRequestProperty("apikey", apikey)
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
                    throw HttpStatusException(
                        code = code,
                        method = method,
                        endpoint = url,
                        bodySnippet = resp.take(900)
                    )
                }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /**
     * HTTP failure with status code and a short response body snippet.
     *
     * This makes Logcat/AppLog actionable for policy issues (401/403), routing (404),
     * or method mismatch (405).
     */
    private class HttpStatusException(
        val code: Int,
        val method: String,
        val endpoint: String,
        val bodySnippet: String
    ) : IOException(buildMessage(code, method, endpoint, bodySnippet)) {
        companion object {
            private fun buildMessage(code: Int, method: String, endpoint: String, body: String): String {
                val b = body.replace('\n', ' ').replace('\r', ' ')
                return "HTTP $code ($method): ${b.take(900)} endpoint=$endpoint"
            }
        }
    }
}
