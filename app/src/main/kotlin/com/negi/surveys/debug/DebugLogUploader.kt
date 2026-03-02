/*
 * =====================================================================
 *  IshizukiTech LLC — SurveyApp
 *  ---------------------------------------------------------------------
 *  File: DebugLogUploader.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.debug

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Base64
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Collects a debug log file on-device and uploads it (GitHub only).
 *
 * Notes:
 * - Never prints secret values.
 * - Always produces a file (even if logcat collection fails).
 * - Debug builds only are recommended (tokens may be embedded in BuildConfig on debug).
 */
object DebugLogUploader {

    private const val TAG = "DebugLogUploader"

    private const val DEFAULT_LOG_DIR = "logs"
    private const val DEFAULT_KEEP_FILES = 20
    private const val DEFAULT_LOGCAT_MAX_BYTES = 900_000
    private const val DEFAULT_GH_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_GH_READ_TIMEOUT_MS = 30_000

    private const val DEFAULT_PROC_WAIT_MS = 2_000L
    private const val DEFAULT_HTTP_MSG_TRUNCATE = 800

    data class UploadResult(
        val ok: Boolean,
        val destination: String,
        val message: String
    )

    /**
     * Collects a log file and uploads it.
     *
     * Behavior:
     * 1) Collect logcat for this process (best-effort).
     * 2) Write to filesDir/logs/...
     * 3) Upload to GitHub if configured; otherwise return local path.
     */
    suspend fun collectAndUpload(context: Context): UploadResult = withContext(Dispatchers.IO) {
        val file = runCatching { collectLogFile(context) }
            .getOrElse { e ->
                AppLog.w(TAG, "collectLogFile failed: ${e.message}")
                // Always produce *some* file to avoid "No log file found".
                writeFallbackFile(context, "collectLogFile failed: ${e.javaClass.simpleName}: ${e.message}")
            }

        val github = runCatching { uploadToGithubIfConfigured(file) }.getOrNull()
        if (github != null && github.ok) return@withContext github

        UploadResult(
            ok = false,
            destination = "local",
            message = "Saved locally: ${file.absolutePath} (GitHub upload not configured or failed)"
        )
    }

    /**
     * Collects logcat output and writes it to a file.
     *
     * Notes:
     * - Uses `logcat -d --pid=<pid>` when possible.
     * - Hard-limits captured bytes to avoid giant uploads.
     * - Prunes old log files to keep storage bounded.
     */
    private fun collectLogFile(context: Context): File {
        val dir = File(context.filesDir, DEFAULT_LOG_DIR).apply { mkdirs() }

        pruneOldLogs(dir, keep = DEFAULT_KEEP_FILES)

        val ts = safeTimestampForFilename(Instant.now().toString())
        val file = File(dir, "debug-log-$ts.txt")

        val header = buildHeader(context)
        val logcat = collectLogcatBestEffort(maxBytes = DEFAULT_LOGCAT_MAX_BYTES)

        val body = StringBuilder(header.length + logcat.length + 256)
            .append(header)
            .append("\n\n---- LOGCAT (best-effort) ----\n")
            .append(logcat)
            .append("\n\n---- END ----\n")
            .toString()

        file.writeText(body)

        AppLog.d(TAG, "Collected debug log file: ${file.absolutePath} (len=${file.length()})")
        return file
    }

    private fun buildHeader(context: Context): String {
        val now = Instant.now().toString()
        val pkg = context.packageName
        val pid = Process.myPid()
        val uid = Process.myUid()

        // IMPORTANT: Never include token strings in logs.
        val hasGhToken = BuildConfig.GH_TOKEN.isNotBlank() || BuildConfig.GITHUB_TOKEN.isNotBlank()

        return """
            # SurveyApp Debug Log
            timeUtc=$now
            package=$pkg
            pid=$pid
            uid=$uid

            # Build
            debug=${BuildConfig.DEBUG}
            versionName=${BuildConfig.VERSION_NAME}
            versionCode=${BuildConfig.VERSION_CODE}
            gitSha=${BuildConfig.GIT_SHA}
            gitDirty=${BuildConfig.GIT_DIRTY}
            buildTimeUtc=${BuildConfig.BUILD_TIME_UTC}

            # Device
            sdkInt=${Build.VERSION.SDK_INT}
            release=${Build.VERSION.RELEASE}
            manufacturer=${Build.MANUFACTURER}
            model=${Build.MODEL}
            abi=${Build.SUPPORTED_ABIS.joinToString(",")}

            # Integrations present (no secrets)
            githubTokenPresent=$hasGhToken
        """.trimIndent()
    }

    /**
     * Attempts to collect logcat output.
     *
     * Notes:
     * - Some devices may restrict logcat access. We still return useful info.
     */
    private fun collectLogcatBestEffort(maxBytes: Int): String {
        val pid = Process.myPid()
        val cap = max(0, maxBytes)

        // Primary: only this process.
        val candidates: List<List<String>> = listOf(
            listOf("logcat", "-d", "--pid=$pid", "-v", "threadtime"),
            // Fallback: no pid filter.
            listOf("logcat", "-d", "-v", "threadtime")
        )

        for (cmd in candidates) {
            val out = runCatching { runCommandLimited(cmd, cap) }.getOrNull()
            if (!out.isNullOrBlank()) {
                return out
            }
        }

        return "(logcat unavailable on this device / build; collected header only)"
    }

    private fun runCommandLimited(cmd: List<String>, maxBytes: Int): String {
        val cap = max(0, maxBytes)
        if (cap == 0) return ""

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val p = pb.start()

        try {
            BufferedInputStream(p.inputStream).use { input ->
                val buf = ByteArray(8 * 1024)
                var total = 0
                val baos = ByteArrayOutputStream()

                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break

                    val canWrite = (cap - total).coerceAtLeast(0)
                    if (canWrite <= 0) break

                    val n = minOf(read, canWrite)
                    baos.write(buf, 0, n)
                    total += n

                    if (total >= cap) break
                }

                return baos.toString(Charsets.UTF_8.name())
            }
        } finally {
            // Best-effort cleanup. Avoid leaving a stuck process around.
            runCatching {
                if (!p.waitFor(DEFAULT_PROC_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    p.destroy()
                    if (!p.waitFor(DEFAULT_PROC_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly()
                    }
                }
            }
        }
    }

    private fun pruneOldLogs(dir: File, keep: Int) {
        val k = keep.coerceAtLeast(0)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (files.size <= k) return
        for (f in files.drop(k)) {
            runCatching { f.delete() }
        }
    }

    private fun writeFallbackFile(context: Context, reason: String): File {
        val dir = File(context.filesDir, DEFAULT_LOG_DIR).apply { mkdirs() }
        val ts = safeTimestampForFilename(Instant.now().toString())
        val file = File(dir, "debug-fallback-$ts.txt")

        val body = buildString {
            append(buildHeader(context))
            append("\n\n---- FALLBACK ----\n")
            append(reason)
            append("\n---- END ----\n")
        }

        file.writeText(body)
        AppLog.w(TAG, "Wrote fallback log file: ${file.absolutePath}")
        return file
    }

    /**
     * Upload to GitHub Contents API when configured.
     *
     * PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}
     * JSON body: { message, content(base64), branch }
     */
    private fun uploadToGithubIfConfigured(file: File): UploadResult? {
        val owner = BuildConfig.GH_OWNER.trim()
        val repo = BuildConfig.GH_REPO.trim()
        val branch = BuildConfig.GH_BRANCH.trim().ifBlank { "main" }
        val prefix = BuildConfig.GH_PATH_PREFIX.trim()
        val token = (BuildConfig.GH_TOKEN.ifBlank { BuildConfig.GITHUB_TOKEN }).trim()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null

        val safePrefix = prefix.trim('/').let { if (it.isBlank()) "" else "$it/" }
        val path = "${safePrefix}logs/${file.name}"

        val endpoint = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection)

        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.connectTimeout = DEFAULT_GH_CONNECT_TIMEOUT_MS
        conn.readTimeout = DEFAULT_GH_READ_TIMEOUT_MS

        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("message", "Upload debug log ${file.name}")
            put("content", b64)
            put("branch", branch)
        }.toString()

        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val ok = code in 200..299
        val msg = readHttpMessage(conn)

        return if (ok) {
            val webUrl = "https://github.com/$owner/$repo/blob/$branch/$path"
            UploadResult(
                ok = true,
                destination = "github",
                message = "Uploaded to GitHub: $webUrl (HTTP $code)"
            )
        } else {
            AppLog.w(TAG, "GitHub upload failed: HTTP $code msg=$msg endpoint=$endpoint")
            UploadResult(
                ok = false,
                destination = "github",
                message = "GitHub upload failed: HTTP $code ($msg)"
            )
        }
    }

    private fun readHttpMessage(conn: HttpURLConnection): String {
        val stream = runCatching {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        }.getOrNull() ?: return ""

        return runCatching {
            stream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
            .take(DEFAULT_HTTP_MSG_TRUNCATE)
            .replace("\\s+".toRegex(), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun safeTimestampForFilename(raw: String): String {
        // Replace characters that commonly cause issues on filesystems or URLs.
        return raw
            .replace(":", "-")
            .replace("/", "-")
            .replace("\\", "-")
    }
}