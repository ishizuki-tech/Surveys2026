/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: AppLog.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * App-scoped file logger (PII-safe).
 *
 * Notes:
 * - Mirrors android.util.Log.* so migration is easy.
 * - Writes are synchronous to survive crashes.
 * - This does NOT dump system logcat; it only records what the app chooses to log.
 * - Provides helper APIs so upload code can ALWAYS obtain a log file path.
 *
 * Hardening:
 * - Buffers pre-init lines in memory, flushes after init().
 * - Performs lightweight secret/PII redaction best-effort (including JSON-like logs).
 * - Avoids rotate filename collisions (unique suffix).
 */
object AppLog {
    private const val INTERNAL_TAG = "AppLog"

    /** Rotate if app.log exceeds this size. */
    private const val MAX_FILE_BYTES: Long = 1024L * 1024L // 1 MB

    /** Keep at most this many rotated files (app-*.log). */
    private const val MAX_ROTATED_FILES: Int = 8

    /** Keep at most this many lines before init() is called. */
    private const val PREINIT_BUFFER_LINES: Int = 256

    /** Truncate one log message (after sanitization). */
    private const val MAX_MSG_CHARS: Int = 4096

    /** Truncate throwable message (after sanitization). */
    private const val MAX_THROWABLE_MSG_CHARS: Int = 512

    private val installed = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var currentFile: File? = null

    /** Pre-init ring buffer (best-effort). */
    private val preInitLines: ArrayDeque<String> = ArrayDeque(PREINIT_BUFFER_LINES)

    /** Ensure rotate filenames are unique even if timestamps collide. */
    private val rotateSeq = AtomicLong(0L)

    private val ts: SimpleDateFormat = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ----------------------------
    // Redaction regexes (compiled once)
    // ----------------------------

    /** "Authorization: Bearer <token>" style header. */
    private val reAuthBearerHeader = Regex(
        "(?i)\\bauthorization\\b\\s*:\\s*bearer\\s+[^\\s]+"
    )

    /** JSON-ish: "authorization": "Bearer <token>" */
    private val reAuthBearerJson = Regex(
        "(?i)\"authorization\"\\s*:\\s*\"\\s*bearer\\s+[^\"]+\""
    )

    /** key[:=]value patterns (keeps separator). */
    private val reKeyValue = Regex(
        "(?i)\\b(token|secret|apikey|api_key|password|access_token|refresh_token)\\b\\s*([:=])\\s*([^\\s\"']+)"
    )

    /** JSON-ish: "key": "value" for sensitive keys. */
    private val reKeyValueJson = Regex(
        "(?i)\"\\s*(token|secret|apikey|api_key|password|access_token|refresh_token)\\s*\"\\s*:\\s*\"\\s*[^\"]+\\s*\""
    )

    /** GitHub tokens. */
    private val reGitHubClassic = Regex("\\bghp_[A-Za-z0-9]{30,}\\b")
    private val reGitHubPat = Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b")

    /** Google API keys. */
    private val reGcpApiKey = Regex("\\bAIza[0-9A-Za-z\\-_]{35}\\b")

    /** Email addresses (common PII). */
    private val reEmail = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")

    /**
     * Initializes the logger. Call early (e.g., Application.onCreate).
     *
     * Notes:
     * - Safe to call multiple times; only first call "installs".
     * - Uses applicationContext to avoid leaking Activity.
     * - Initialization is synchronized to avoid races with concurrent log writes.
     */
    fun init(context: Context) {
        val app = context.applicationContext

        val dirPath: String
        val safeInstallId: String

        synchronized(lock) {
            if (installed.get()) return

            val dir = LogFiles.appLogDir(app)
            logDir = dir
            currentFile = File(dir, "app.log")

            ensureFileExistsLocked()
            rotateIfNeededLocked()
            flushPreInitLocked()

            installed.set(true)

            dirPath = dir.absolutePath
            safeInstallId = runCatching { LogFiles.installId(app).take(8) }.getOrDefault("unknown")
        }

        i(
            INTERNAL_TAG,
            "init: dir=$dirPath build=${LogFiles.buildLabelSafe()} installId=$safeInstallId"
        )
    }

    /**
     * Ensures the logger is ready for file operations.
     *
     * Notes:
     * - This is intended for upload/debug tooling, not for normal logging call sites.
     * - If init() wasn't called, this will call init() and create app.log.
     */
    fun ensureReady(context: Context) {
        if (!installed.get()) {
            init(context)
            return
        }

        if (logDir != null && currentFile != null) return

        val app = context.applicationContext
        synchronized(lock) {
            val dir = logDir ?: LogFiles.appLogDir(app).also { logDir = it }
            currentFile = currentFile ?: File(dir, "app.log")
            ensureFileExistsLocked()
            rotateIfNeededLocked()
            flushPreInitLocked()
        }
    }

    /**
     * Returns the current log file (app.log), creating it if needed.
     *
     * Notes:
     * - Always returns a File that exists (best-effort).
     * - Does NOT expose secret values; it may write a minimal marker line.
     */
    fun getOrCreateCurrentLogFile(context: Context): File {
        ensureReady(context)
        synchronized(lock) {
            ensureFileExistsLocked()
            rotateIfNeededLocked()

            val dir = logDir ?: LogFiles.appLogDir(context.applicationContext).also { logDir = it }
            val file = currentFile ?: File(dir, "app.log").also { currentFile = it }

            ensureFileExistsLocked()
            return file
        }
    }

    /**
     * Lists log files suitable for upload.
     *
     * Ordering:
     * - Most recent first
     * - Includes "app.log" and rotated "app-*.log"
     *
     * Notes:
     * - If init() wasn't called, this will call ensureReady() and create app.log.
     */
    fun getLogFilesForUpload(context: Context): List<File> {
        ensureReady(context)

        val dir = logDir ?: return listOf(getOrCreateCurrentLogFile(context))
        val files = dir.listFiles()
            ?.filter {
                it.isFile && (it.name == "app.log" || (it.name.startsWith("app-") && it.name.endsWith(".log")))
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isNotEmpty()) return files
        return listOf(getOrCreateCurrentLogFile(context))
    }

    /**
     * Writes a lightweight marker line that is safe for upload/debug.
     *
     * Notes:
     * - Useful to ensure the log file is non-empty right before uploading.
     * - Message is sanitized and length-limited.
     */
    fun mark(context: Context, tag: String, msg: String) {
        ensureReady(context)
        i(tag, "MARK: ${msg.take(1024)}")
    }

    fun v(tag: String, msg: String): Int = log(Log.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String): Int = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String): Int = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String): Int = log(Log.WARN, tag, msg, null)
    fun e(tag: String, msg: String): Int = log(Log.ERROR, tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable): Int = log(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable): Int = log(Log.ERROR, tag, msg, t)

    private fun log(level: Int, tag: String, msg: String, t: Throwable?): Int {
        val rc = when (level) {
            Log.VERBOSE -> Log.v(tag, msg, t)
            Log.DEBUG -> Log.d(tag, msg, t)
            Log.INFO -> Log.i(tag, msg, t)
            Log.WARN -> Log.w(tag, msg, t)
            Log.ERROR -> Log.e(tag, msg, t)
            else -> Log.d(tag, msg, t)
        }

        runCatching { writeLineOrBuffer(level, tag, msg, t) }
            .onFailure { ex -> Log.w(INTERNAL_TAG, "file write failed: ${ex.javaClass.simpleName}") }

        return rc
    }

    private fun writeLineOrBuffer(level: Int, tag: String, msg: String, t: Throwable?) {
        synchronized(lock) {
            val line = buildLineLocked(level, tag, msg, t)

            val dir = logDir
            if (dir == null) {
                // Pre-init: keep a small best-effort buffer.
                if (preInitLines.size >= PREINIT_BUFFER_LINES) {
                    preInitLines.removeFirst()
                }
                preInitLines.addLast(line)
                return
            }

            ensureFileExistsLocked()
            rotateIfNeededLocked()

            val file = currentFile ?: File(dir, "app.log").also { currentFile = it }
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    private fun buildLineLocked(level: Int, tag: String, msg: String, t: Throwable?): String {
        val now = ts.format(Date())
        val lvl = when (level) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'D'
        }

        val cleanMsg = sanitize(msg).replace('\n', ' ').take(MAX_MSG_CHARS)
        val extra = if (t != null) {
            val m = sanitize(t.message.orEmpty()).replace('\n', ' ').take(MAX_THROWABLE_MSG_CHARS)
            " | ${t.javaClass.simpleName}${if (m.isBlank()) "" else ": $m"}"
        } else {
            ""
        }

        return "$now $lvl/${tag.take(64)}: $cleanMsg$extra\n"
    }

    /**
     * Best-effort redaction for secrets/PII-like values.
     *
     * Notes:
     * - This is intentionally conservative; it won't catch everything.
     * - The primary protection is still: "do not log sensitive data at call sites".
     */
    private fun sanitize(input: String): String {
        var s = input

        // Authorization headers: "Authorization: Bearer <token>"
        s = s.replace(reAuthBearerHeader, "Authorization: Bearer <REDACTED>")

        // JSON-ish: "authorization": "Bearer <token>"
        s = s.replace(reAuthBearerJson, "\"authorization\":\"Bearer <REDACTED>\"")

        // key[:=]value patterns, keep the separator (":" or "=").
        s = s.replace(reKeyValue) { m ->
            val key = m.groupValues[1]
            val sep = m.groupValues[2]
            "$key$sep<REDACTED>"
        }

        // JSON-ish: "token": "value" for sensitive keys.
        s = s.replace(reKeyValueJson) { m ->
            // Preserve the original key spelling found in the match as much as possible.
            val key = Regex("(?i)\"\\s*([^\"]+)\\s*\"\\s*:").find(m.value)?.groupValues?.getOrNull(1) ?: "token"
            "\"$key\":\"<REDACTED>\""
        }

        // GitHub tokens.
        s = s.replace(reGitHubClassic, "<REDACTED_GH_TOKEN>")
        s = s.replace(reGitHubPat, "<REDACTED_GH_TOKEN>")

        // Google API keys.
        s = s.replace(reGcpApiKey, "<REDACTED_GCP_KEY>")

        // Email addresses.
        s = s.replace(reEmail, "<REDACTED_EMAIL>")

        return s
    }

    private fun ensureFileExistsLocked() {
        val dir = logDir ?: return
        if (!dir.exists()) runCatching { dir.mkdirs() }

        val file = currentFile ?: File(dir, "app.log").also { currentFile = it }
        if (!file.exists()) {
            runCatching { FileOutputStream(file, true).use { } }
        }
    }

    private fun flushPreInitLocked() {
        if (preInitLines.isEmpty()) return

        val dir = logDir ?: return
        val file = currentFile ?: File(dir, "app.log").also { currentFile = it }

        runCatching {
            FileOutputStream(file, true).use { out ->
                while (preInitLines.isNotEmpty()) {
                    out.write(preInitLines.removeFirst().toByteArray(Charsets.UTF_8))
                }
                out.flush()
            }
        }.onFailure {
            // If flushing fails, keep buffer bounded and continue; avoid throwing.
            while (preInitLines.size > PREINIT_BUFFER_LINES) preInitLines.removeFirst()
        }
    }

    private fun rotateIfNeededLocked() {
        val dir = logDir ?: return
        val file = currentFile ?: File(dir, "app.log").also { currentFile = it }
        if (!file.exists()) return
        if (file.length() < MAX_FILE_BYTES) return

        val rotated = nextRotatedFileLocked(dir)

        val renamed = runCatching { file.renameTo(rotated) }.getOrDefault(false)
        if (!renamed) {
            // Fallback: copy then truncate original (best-effort).
            runCatching {
                file.inputStream().use { input ->
                    FileOutputStream(rotated, false).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                // Truncate the original file so future writes stay small.
                FileOutputStream(file, false).use { /* truncate */ }
            }
        }

        // Ensure app.log exists for new writes.
        currentFile = File(dir, "app.log").also { f ->
            if (!f.exists()) {
                runCatching { FileOutputStream(f, true).use { } }
            }
        }

        // Prune old rotated files.
        val rotatedFiles = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (rotatedFiles.size <= MAX_ROTATED_FILES) return
        rotatedFiles.drop(MAX_ROTATED_FILES).forEach { runCatching { it.delete() } }
    }

    /**
     * Generate a unique rotated filename to avoid collisions even under rapid rotations.
     */
    private fun nextRotatedFileLocked(dir: File): File {
        val stamp = LogFiles.utcCompactNow()
        val seq = rotateSeq.incrementAndGet()
        val candidate = File(dir, "app-$stamp-$seq.log")
        if (!candidate.exists()) return candidate

        // Extremely defensive fallback (should be rare).
        for (i in 1..64) {
            val f = File(dir, "app-$stamp-$seq-$i.log")
            if (!f.exists()) return f
        }
        return File(dir, "app-$stamp-$seq-${System.nanoTime().toString(16)}.log")
    }
}