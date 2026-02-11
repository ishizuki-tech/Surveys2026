/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: GitHubLogUploadManager.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import com.negi.surveys.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.util.Base64

/**
 * Uploads log bundles to GitHub via "Create or update file contents".
 *
 * Regular path:
 * - Called manually from UI (e.g., HomeScreen) to upload a "regular" bundle.
 *
 * Crash path:
 * - Crash files are persisted to logs/crash/pending/ by [CrashCapture].
 * - On next cold start, call [tryUploadPendingCrashesBlocking] to upload them.
 *
 * Security notes:
 * - DO NOT ship a personal access token in production builds.
 * - For production, use OAuth (device flow) or a backend broker.
 */
object GitHubLogUploadManager {
    private const val TAG = "GitHubLogUpload"
    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"

    /** Soft safety cap: keep log bundles reasonably small. */
    private const val SOFT_MAX_BYTES: Long = 50L * 1024L * 1024L // 50 MiB

    fun isConfigured(): Boolean {
        return BuildConfig.GITHUB_OWNER.isNotBlank() &&
                BuildConfig.GITHUB_REPO.isNotBlank() &&
                BuildConfig.GITHUB_BRANCH.isNotBlank() &&
                BuildConfig.GITHUB_TOKEN.isNotBlank()
    }

    /**
     * Uploads a "regular" log bundle (blocking).
     *
     * Call this on a background thread.
     *
     * @return Result.success(remotePath) on success
     */
    fun uploadRegularBlocking(context: android.content.Context, reason: String? = null): Result<String> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("GitHub upload is not configured (owner/repo/branch/token)."))
        }

        val install = LogFiles.installId(context)
        val zip = LogFiles.createZipBundle(
            context = context,
            kind = LogFiles.UploadKind.REGULAR,
            includeCrashFiles = emptyList(),
            reason = reason
        )

        val remotePath = buildRepoPath(
            kind = LogFiles.UploadKind.REGULAR,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(TAG, "uploadRegular: start path=$remotePath size=${zip.length()}")
        val result = uploadZipAsRepoContent(zipFile = zip, repoPath = remotePath, commitHint = "regular logs")
        runCatching { zip.delete() }

        return result.onSuccess {
            AppLog.i(TAG, "uploadRegular: ok path=$it")
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
            AppLog.w(TAG, "tryUploadPendingCrashes: GitHub not configured; skip")
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

        val remotePath = buildRepoPath(
            kind = LogFiles.UploadKind.CRASH,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(TAG, "uploadCrash: start path=$remotePath crashes=${pending.size} size=${zip.length()}")
        val result = uploadZipAsRepoContent(zipFile = zip, repoPath = remotePath, commitHint = "crash logs")
        runCatching { zip.delete() }

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
            AppLog.i(TAG, "uploadCrash: ok path=$it moved=$moved")
            pending.size
        }.onFailure { t ->
            AppLog.e(TAG, "uploadCrash: failed", t)
        }
    }

    private fun buildRepoPath(kind: LogFiles.UploadKind, installId: String, fileName: String): String {
        val prefix = BuildConfig.GITHUB_LOG_PREFIX.trim().trim('/').ifBlank { "surveyapp" }
        return "$prefix/${kind.prefix}/$installId/$fileName"
    }

    private fun uploadZipAsRepoContent(zipFile: File, repoPath: String, commitHint: String): Result<String> {
        if (!zipFile.exists() || !zipFile.isFile) {
            return Result.failure(IllegalArgumentException("zipFile not found: ${zipFile.absolutePath}"))
        }

        val size = zipFile.length()
        if (size > SOFT_MAX_BYTES) {
            return Result.failure(IllegalStateException("Bundle too large (${size} bytes). Reduce logs or switch to Release assets."))
        }

        val bytes = runCatching { zipFile.readBytes() }.getOrElse { return Result.failure(it) }
        val contentB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val owner = BuildConfig.GITHUB_OWNER.trim()
        val repo = BuildConfig.GITHUB_REPO.trim()
        val branch = BuildConfig.GITHUB_BRANCH.trim()
        val token = BuildConfig.GITHUB_TOKEN.trim()

        val endpoint = "$API_BASE/repos/$owner/$repo/contents/$repoPath"

        return runCatching {
            val existingSha = getExistingShaIfAny(endpoint = endpoint, branch = branch, token = token)

            val body = JSONObject().apply {
                put("message", buildCommitMessage(commitHint, repoPath))
                put("content", contentB64)
                put("branch", branch)
                // Optional: committer identity. You can remove this block if you prefer GitHub default.
                put(
                    "committer",
                    JSONObject().apply {
                        put("name", "SurveyApp Bot")
                        put("email", "noreply@users.noreply.github.com")
                    }
                )
                if (existingSha != null) {
                    put("sha", existingSha)
                }
            }

            httpPutJson(
                url = endpoint,
                token = token,
                bodyJson = body.toString()
            )

            repoPath
        }
    }

    private fun buildCommitMessage(hint: String, repoPath: String): String {
        val ts = LogFiles.utcCompactNow()
        val shortPath = repoPath.takeLast(180)
        return "logs: $hint ($ts) - $shortPath"
    }

    private fun getExistingShaIfAny(endpoint: String, branch: String, token: String): String? {
        val url = "$endpoint?ref=${encodeQuery(branch)}"
        val res = httpGetJson(url = url, token = token, allow404 = true) ?: return null
        val obj = JSONObject(res)
        return obj.optString("sha", null).takeIf { it.isNotBlank() }
    }

    private fun httpGetJson(url: String, token: String, allow404: Boolean): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "SurveyApp-LogUploader")
        }

        val code = conn.responseCode
        if (allow404 && code == 404) return null

        val ok = code in 200..299
        val resp = runCatching {
            val stream = if (ok) conn.inputStream else conn.errorStream
            stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

        if (!ok) {
            throw IOException("HTTP $code: ${resp.take(800)}")
        }
        return resp
    }

    private fun httpPutJson(url: String, token: String, bodyJson: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 25_000
            readTimeout = 40_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "SurveyApp-LogUploader")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        conn.outputStream.use { os ->
            os.write(bodyJson.toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        val ok = code in 200..299
        val resp = runCatching {
            val stream = if (ok) conn.inputStream else conn.errorStream
            stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

        if (!ok) {
            throw IOException("HTTP $code: ${resp.take(1200)}")
        }
    }

    private fun encodeQuery(s: String): String {
        // Minimal encoding for branch names; keep it tiny and dependency-free.
        return s.replace(" ", "%20")
    }
}
