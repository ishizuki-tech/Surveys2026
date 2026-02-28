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

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.negi.surveys.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import org.json.JSONObject

/**
 * Uploads log bundles to GitHub via "Create or update file contents".
 *
 * Regular path:
 * - Called manually from UI (e.g., HomeScreen) to upload a "regular" bundle.
 *
 * Crash path:
 * - Crash files are persisted to logs/crash/pending/ by CrashCapture.
 * - On next cold start, call tryUploadPendingCrashesBlocking to upload them.
 *
 * Security notes:
 * - DO NOT ship a personal access token in production builds.
 * - For production, use OAuth (device flow) or a backend broker.
 *
 * Hardening:
 * - BuildConfig fields are resolved via reflection (supports both GH_* and GITHUB_* without compile-time coupling).
 * - Size cap is conservative to reduce memory spikes (base64 + JSON).
 * - Retries for transient failures (5xx/429), and conflict retry (409) by re-fetching sha.
 * - Retry-After header support (best-effort) for 429/secondary rate limiting.
 */
object GitHubLogUploadManager {
    private const val TAG = "GitHubLogUpload"
    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"

    private const val CONNECT_TIMEOUT_MS: Int = 25_000
    private const val READ_TIMEOUT_MS: Int = 45_000

    /**
     * Soft safety cap.
     *
     * Rationale:
     * - Contents API requires Base64 in JSON; memory footprint grows quickly (raw bytes + base64 string + JSON).
     * - Keep this conservative for field stability; raise only if you truly need it.
     */
    private const val SOFT_MAX_BYTES: Long = 16L * 1024L * 1024L // 16 MiB

    /**
     * Additional sanity cap on Base64 character count (roughly 4/3 expansion).
     *
     * Notes:
     * - Helps avoid large transient allocations from Base64 + JSON + UTF-8 encoding.
     */
    private const val SOFT_MAX_B64_CHARS: Int = 24 * 1024 * 1024 // 24M chars

    /** Retry count for transient HTTP failures. */
    private const val MAX_RETRIES: Int = 3

    /** Base backoff (ms). */
    private const val BACKOFF_BASE_MS: Long = 700L

    private data class GitHubCfg(
        val owner: String,
        val repo: String,
        val branch: String,
        val token: String,
        val logPrefix: String
    )

    /**
     * Resolves configuration from BuildConfig.
     *
     * Notes:
     * - Supports both legacy GH_* and newer GITHUB_* fields via reflection.
     * - Token must be present for upload to run.
     */
    private fun cfgOrNull(): GitHubCfg? {
        val owner = firstNonBlank(
            buildConfigString("GITHUB_OWNER"),
            buildConfigString("GH_OWNER")
        )
        val repo = firstNonBlank(
            buildConfigString("GITHUB_REPO"),
            buildConfigString("GH_REPO")
        )
        val branch = firstNonBlank(
            buildConfigString("GITHUB_BRANCH"),
            buildConfigString("GH_BRANCH")
        )
        val token = firstNonBlank(
            buildConfigString("GITHUB_TOKEN"),
            buildConfigString("GH_TOKEN")
        )
        val logPrefix = firstNonBlank(
            buildConfigString("GITHUB_LOG_PREFIX"),
            buildConfigString("GH_LOG_PREFIX"),
            "surveyapp"
        )

        if (owner.isBlank() || repo.isBlank() || branch.isBlank() || token.isBlank()) return null
        return GitHubCfg(
            owner = owner.trim(),
            repo = repo.trim(),
            branch = branch.trim(),
            token = token.trim(),
            logPrefix = logPrefix.trim()
        )
    }

    fun isConfigured(): Boolean = cfgOrNull() != null

    /**
     * Uploads a "regular" log bundle (blocking).
     *
     * Call this on a background thread.
     *
     * @return Result.success(remotePath) on success
     */
    fun uploadRegularBlocking(context: Context, reason: String? = null): Result<String> {
        val cfg = cfgOrNull()
            ?: return Result.failure(IllegalStateException("GitHub upload is not configured (owner/repo/branch/token)."))

        val install = LogFiles.installId(context)
        val zip = LogFiles.createZipBundle(
            context = context,
            kind = LogFiles.UploadKind.REGULAR,
            includeCrashFiles = emptyList(),
            reason = reason
        )

        val remotePath = buildRepoPath(
            cfg = cfg,
            kind = LogFiles.UploadKind.REGULAR,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(TAG, "uploadRegular: start path=$remotePath size=${zip.length()}")
        val result = uploadZipAsRepoContent(cfg = cfg, zipFile = zip, repoPath = remotePath, commitHint = "regular logs")
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
     * @return Result.success(movedCrashCount) on success
     */
    fun tryUploadPendingCrashesBlocking(context: Context): Result<Int> {
        val cfg = cfgOrNull()
        if (cfg == null) {
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
            cfg = cfg,
            kind = LogFiles.UploadKind.CRASH,
            installId = install,
            fileName = zip.name
        )

        AppLog.i(TAG, "uploadCrash: start path=$remotePath crashes=${pending.size} size=${zip.length()}")
        val result = uploadZipAsRepoContent(cfg = cfg, zipFile = zip, repoPath = remotePath, commitHint = "crash logs")
        runCatching { zip.delete() }

        return result.map { uploadedRepoPath ->
            // Move crash files to uploaded/ only if upload succeeded.
            val uploadedDir = LogFiles.crashUploadedDir(context)
            if (!uploadedDir.exists()) runCatching { uploadedDir.mkdirs() }

            var moved = 0
            for (f in pending) {
                if (!f.exists()) continue
                val dst = File(uploadedDir, f.name)

                val okRename = runCatching { f.renameTo(dst) }.getOrDefault(false)
                if (okRename) {
                    moved++
                    continue
                }

                // Fallback: copy then delete.
                val okCopy = runCatching {
                    f.inputStream().use { input ->
                        FileOutputStream(dst, false).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    true
                }.getOrDefault(false)

                if (okCopy) {
                    runCatching { f.delete() }
                    moved++
                }
            }

            AppLog.i(TAG, "uploadCrash: ok path=$uploadedRepoPath pending=${pending.size} moved=$moved")
            moved
        }.onFailure { t ->
            AppLog.e(TAG, "uploadCrash: failed", t)
        }
    }

    private fun buildRepoPath(cfg: GitHubCfg, kind: LogFiles.UploadKind, installId: String, fileName: String): String {
        val prefix = cfg.logPrefix.trim().trim('/').ifBlank { "surveyapp" }
        return "$prefix/${kind.prefix}/$installId/$fileName"
    }

    private fun uploadZipAsRepoContent(cfg: GitHubCfg, zipFile: File, repoPath: String, commitHint: String): Result<String> {
        if (!zipFile.exists() || !zipFile.isFile) {
            return Result.failure(IllegalArgumentException("zipFile not found: ${zipFile.absolutePath}"))
        }

        val size = zipFile.length()
        if (size > SOFT_MAX_BYTES) {
            return Result.failure(
                IllegalStateException(
                    "Bundle too large (${size} bytes). " +
                            "Reduce bundle size or lower verbosity; contents API uses Base64-in-JSON and becomes unstable at large sizes."
                )
            )
        }

        val uploadStartMs = SystemClock.elapsedRealtime()

        // Read bytes (kept conservative by SOFT_MAX_BYTES).
        val bytes = runCatching { zipFile.readBytes() }.getOrElse { return Result.failure(it) }

        // Base64 expands payload; keep an additional sanity check.
        val estimatedB64Chars = estimateBase64Chars(bytes.size)
        if (estimatedB64Chars <= 0) {
            return Result.failure(IllegalStateException("Invalid Base64 size estimation."))
        }
        if (estimatedB64Chars > SOFT_MAX_B64_CHARS) {
            return Result.failure(
                IllegalStateException(
                    "Bundle too large after Base64 expansion (estimatedB64Chars=$estimatedB64Chars). " +
                            "Reduce bundle size; Contents API JSON payload becomes unstable."
                )
            )
        }

        val contentB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        // Encode path safely for GitHub Contents API.
        val encodedPath = encodePathSegments(repoPath)
        val endpoint = "$API_BASE/repos/${cfg.owner}/${cfg.repo}/contents/$encodedPath"

        return runCatching {
            val uploadId = LogFiles.utcCompactNow()
            AppLog.i(
                TAG,
                "upload: begin uploadId=$uploadId path=$repoPath rawBytes=${bytes.size} b64Chars=${contentB64.length} branch=${cfg.branch}"
            )

            // Try to get SHA (non-fatal if missing).
            var existingSha: String? = runCatching {
                getExistingShaIfAny(endpoint = endpoint, branch = cfg.branch, token = cfg.token)
            }.getOrNull()

            if (existingSha != null) {
                AppLog.d(TAG, "upload: existing sha detected uploadId=$uploadId path=$repoPath")
            } else {
                AppLog.d(TAG, "upload: no existing sha (new file) uploadId=$uploadId path=$repoPath")
            }

            var lastError: Throwable? = null
            for (attempt in 0 until MAX_RETRIES) {
                val attemptNo = attempt + 1
                try {
                    AppLog.d(TAG, "upload: attempt=$attemptNo/$MAX_RETRIES uploadId=$uploadId path=$repoPath")

                    val body = JSONObject().apply {
                        put("message", buildCommitMessage(commitHint, repoPath))
                        put("content", contentB64)
                        put("branch", cfg.branch)
                        put(
                            "committer",
                            JSONObject().apply {
                                put("name", "SurveyApp Bot")
                                put("email", "noreply@users.noreply.github.com")
                            }
                        )
                        if (existingSha != null) put("sha", existingSha)
                    }.toString()

                    httpPutJson(
                        url = endpoint,
                        token = cfg.token,
                        bodyJson = body
                    )

                    val elapsed = SystemClock.elapsedRealtime() - uploadStartMs
                    AppLog.i(TAG, "upload: success uploadId=$uploadId path=$repoPath elapsedMs=$elapsed")
                    return@runCatching repoPath
                } catch (e: GitHubHttpException) {
                    lastError = e

                    // 409 often indicates sha mismatch / conflict; refetch sha and retry.
                    if (e.code == 409 && attempt < MAX_RETRIES - 1) {
                        AppLog.w(TAG, "upload: conflict(409) refetch sha uploadId=$uploadId path=$repoPath")
                        existingSha = runCatching {
                            getExistingShaIfAny(endpoint = endpoint, branch = cfg.branch, token = cfg.token)
                        }.getOrNull()
                        sleepBackoffMs(
                            attempt = attempt,
                            retryAfterMs = e.retryAfterMs,
                            hint = "409-conflict"
                        )
                        continue
                    }

                    // Retry transient issues only.
                    if (isRetryable(code = e.code, body = e.body) && attempt < MAX_RETRIES - 1) {
                        AppLog.w(
                            TAG,
                            "upload: retryable http=${e.code} uploadId=$uploadId path=$repoPath " +
                                    "retryAfterMs=${e.retryAfterMs ?: -1} rateRemaining=${e.rateLimitRemaining ?: "?"}"
                        )
                        sleepBackoffMs(
                            attempt = attempt,
                            retryAfterMs = e.retryAfterMs,
                            hint = "http-${e.code}"
                        )
                        continue
                    }

                    throw IOException(
                        "HTTP ${e.code}: ${summarizeGitHubError(e.body)}" +
                                (e.rateLimitRemaining?.let { " | rateRemaining=$it" } ?: "")
                    )
                } catch (e: IOException) {
                    lastError = e
                    if (attempt < MAX_RETRIES - 1) {
                        AppLog.w(TAG, "upload: io retry uploadId=$uploadId path=$repoPath err=${e.message}")
                        sleepBackoffMs(attempt = attempt, retryAfterMs = null, hint = "io")
                        continue
                    }
                    throw e
                }
            }

            throw (lastError ?: IOException("Upload failed: unknown error"))
        }
    }

    private fun buildCommitMessage(hint: String, repoPath: String): String {
        val ts = LogFiles.utcCompactNow()
        val shortPath = repoPath.takeLast(180)
        return "logs: $hint ($ts) - $shortPath"
    }

    private fun getExistingShaIfAny(endpoint: String, branch: String, token: String): String? {
        val url = "$endpoint?ref=${encodeQueryParam(branch)}"
        val res = httpGetJson(url = url, token = token, allow404 = true) ?: return null
        val obj = JSONObject(res)
        return obj.optString("sha", "").takeIf { !it.isNullOrBlank() }
    }

    private fun httpGetJson(url: String, token: String, allow404: Boolean): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("User-Agent", "SurveyApp-LogUploader")

            val code = conn.responseCode
            if (allow404 && code == 404) return null

            val ok = code in 200..299
            val resp = readResponseBody(conn, ok)

            if (!ok) {
                throw GitHubHttpException(
                    code = code,
                    body = resp,
                    retryAfterMs = parseRetryAfterMs(conn),
                    rateLimitRemaining = conn.getHeaderField("X-RateLimit-Remaining"),
                    rateLimitReset = conn.getHeaderField("X-RateLimit-Reset")
                )
            }
            return resp
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun httpPutJson(url: String, token: String, bodyJson: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("User-Agent", "SurveyApp-LogUploader")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            // Best-effort: avoid internal buffering of the entire request body in some stacks.
            runCatching { conn.setFixedLengthStreamingMode(bodyJson.toByteArray(Charsets.UTF_8).size) }

            conn.outputStream.use { os ->
                os.write(bodyJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val ok = code in 200..299
            val resp = readResponseBody(conn, ok)

            if (!ok) {
                throw GitHubHttpException(
                    code = code,
                    body = resp,
                    retryAfterMs = parseRetryAfterMs(conn),
                    rateLimitRemaining = conn.getHeaderField("X-RateLimit-Remaining"),
                    rateLimitReset = conn.getHeaderField("X-RateLimit-Reset")
                )
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, ok: Boolean): String {
        val stream = if (ok) conn.inputStream else conn.errorStream
        return runCatching {
            stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
    }

    /**
     * Encodes a query parameter value (UTF-8) and normalizes spaces to %20.
     */
    private fun encodeQueryParam(s: String): String {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    /**
     * Encodes path segments for GitHub Contents API.
     *
     * Notes:
     * - GitHub expects the path portion to be URL-encoded.
     * - We encode each segment to avoid turning '/' into %2F.
     */
    private fun encodePathSegments(path: String): String {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.split("/").joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * Summarizes GitHub JSON errors to a compact message.
     */
    private fun summarizeGitHubError(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "(empty response)"
        return runCatching {
            val o = JSONObject(s)
            val msg = o.optString("message", "").trim()
            val doc = o.optString("documentation_url", "").trim()
            val err = o.optJSONArray("errors")
            val e0 = if (err != null && err.length() > 0) err.opt(0)?.toString()?.take(300) else ""
            buildString {
                if (msg.isNotBlank()) append(msg) else append(s.take(600))
                if (e0!!.isNotBlank()) append(" | errors[0]=").append(e0)
                if (doc.isNotBlank()) append(" | doc=").append(doc)
            }.take(900)
        }.getOrElse {
            s.take(900)
        }
    }

    private fun isRetryable(code: Int, body: String): Boolean {
        // 429: rate/secondary limit; 5xx: transient server errors.
        if (code == 429) return true
        if (code in 500..599) return true

        // GitHub may return 403 for secondary rate limits in some cases.
        if (code == 403) {
            val b = body.lowercase()
            if (b.contains("rate limit") || b.contains("secondary rate") || b.contains("abuse detection")) {
                return true
            }
        }
        return false
    }

    private fun parseRetryAfterMs(conn: HttpURLConnection): Long? {
        val raw = conn.getHeaderField("Retry-After")?.trim().orEmpty()
        val sec = raw.toLongOrNull() ?: return null
        if (sec <= 0L) return null
        return sec * 1000L
    }

    private fun sleepBackoffMs(attempt: Int, retryAfterMs: Long?, hint: String) {
        val jitter = ThreadLocalRandom.current().nextLong(0L, 250L)
        val exp = (attempt + 1).toLong()
        val base = BACKOFF_BASE_MS * exp * exp
        val ms = (retryAfterMs ?: base) + jitter
        val clamped = ms.coerceIn(200L, 30_000L)
        AppLog.d(TAG, "backoff: hint=$hint attempt=${attempt + 1} sleepMs=$clamped")
        runCatching { Thread.sleep(clamped) }
    }

    private fun estimateBase64Chars(byteCount: Int): Int {
        if (byteCount <= 0) return 0
        // Base64: 4 chars per 3 bytes, rounded up.
        val groups = (byteCount + 2) / 3
        val chars = groups * 4
        return min(chars, Int.MAX_VALUE)
    }

    private fun firstNonBlank(vararg xs: String): String {
        for (x in xs) if (x.isNotBlank()) return x
        return ""
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
            val f = BuildConfig::class.java.getField(fieldName)
            val v = f.get(null)
            (v as? String).orEmpty()
        }.getOrDefault("")
    }

    private class GitHubHttpException(
        val code: Int,
        val body: String,
        val retryAfterMs: Long?,
        val rateLimitRemaining: String?,
        val rateLimitReset: String?
    ) : IOException("HTTP $code")
}