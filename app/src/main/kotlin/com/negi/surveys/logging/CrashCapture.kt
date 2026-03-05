/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: CrashCapture.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures uncaught exceptions into an app-owned crash file.
 *
 * Why:
 * - If the process dies, Logcat is not guaranteed to be available to field users.
 * - A persisted crash report enables "upload on next launch".
 *
 * Notes:
 * - Intentionally minimal; never attempts network work on crash.
 * - Upload happens on the next cold start via upload manager.
 * - Uses a re-entrancy guard to avoid infinite recursion if the handler itself crashes.
 * - Writes to a temp file first, then renames for best-effort atomicity.
 *
 * Hardening:
 * - Unique crash filenames even if timestamps collide.
 * - Best-effort redaction for secrets/PII-like values.
 * - Size cap to avoid huge crash files / accidental OOM.
 * - Prunes old pending crash files to a fixed maximum.
 */
object CrashCapture {
    private const val TAG = "CrashCapture"

    /** Max crash report size written to disk (bytes). */
    private const val MAX_REPORT_BYTES: Long = 512L * 1024L // 512 KB

    /** Keep at most this many pending crash reports. */
    private const val MAX_PENDING_CRASH_FILES: Int = 16

    /** Delete stale tmp crash files older than this. */
    private const val STALE_TMP_AGE_MS: Long = 24L * 60L * 60L * 1000L // 24h

    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** Ensures filename uniqueness even under rapid repeated crashes in the same millisecond. */
    private val crashSeq = AtomicLong(0L)

    // ----------------------------
    // Redaction regexes (compiled once)
    // ----------------------------

    /** "Authorization: Bearer <token>" style header. */
    private val reAuthBearerHeader = Regex("(?i)\\bauthorization\\b\\s*:\\s*bearer\\s+[^\\s]+")

    /** JSON-ish: "authorization": "Bearer <token>" */
    private val reAuthBearerJson = Regex("(?i)\"authorization\"\\s*:\\s*\"\\s*bearer\\s+[^\"]+\"")

    /** key[:=]value patterns (keeps separator). */
    private val reKeyValue = Regex(
        "(?i)\\b(token|secret|apikey|api_key|password|access_token|refresh_token|session|cookie)\\b\\s*([:=])\\s*([^\\s\"']+)"
    )

    /** JSON-ish: "key": "value" for sensitive keys. */
    private val reKeyValueJson = Regex(
        "(?i)\"\\s*(token|secret|apikey|api_key|password|access_token|refresh_token|session|cookie)\\s*\"\\s*:\\s*\"\\s*[^\"]+\\s*\""
    )

    /** GitHub tokens. */
    private val reGitHubClassic = Regex("\\bghp_[A-Za-z0-9]{30,}\\b")
    private val reGitHubPat = Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b")

    /** Google API keys. */
    private val reGcpApiKey = Regex("\\bAIza[0-9A-Za-z\\-_]{35}\\b")

    /** JWT-like token (best-effort). */
    private val reJwt = Regex("\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\b")

    /** Email addresses (common PII). */
    private val reEmail = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")

    /**
     * Installs a default uncaught exception handler that persists crash reports.
     *
     * Notes:
     * - Safe to call multiple times.
     * - Uses applicationContext to avoid leaking an Activity.
     * - MUST NOT throw; crash path must never block process termination.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext

        // Best-effort: make file logger ready so non-crash logs can land on disk.
        runCatching { AppLog.ensureReady(appContext) }

        previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Prevent recursion if our handler throws or if the process is already failing hard.
            val alreadyHandling = !handlingCrash.compareAndSet(false, true)
            if (alreadyHandling) {
                runCatching { previous?.uncaughtException(thread, throwable) }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                // IMPORTANT: On crash path, avoid AppLog/SafeLog to prevent extra IO/locks.
                runCatching {
                    val tid = safeThreadId(thread)
                    Log.e(TAG, "uncaught: thread=${thread.name} tid=$tid pid=${Process.myPid()}", throwable)
                }

                runCatching {
                    writeCrashFile(appContext, thread, throwable)
                }
            } finally {
                // Delegate to the original handler (system / crash reporter / etc.).
                runCatching { previous?.uncaughtException(thread, throwable) }
                // In practice the process will die, but keep state consistent for tests.
                handlingCrash.set(false)
            }
        }

        // Normal path log is fine; SafeLog is okay here.
        SafeLog.i(TAG, "installed")
    }

    fun countPendingCrashes(context: Context): Int =
        LogFiles.listPendingCrashLogs(context.applicationContext).size

    fun pendingCrashFiles(context: Context): List<File> =
        LogFiles.listPendingCrashLogs(context.applicationContext)

    private fun writeCrashFile(context: Context, thread: Thread, t: Throwable) {
        val appContext = context.applicationContext

        val dir = LogFiles.crashPendingDir(appContext)
        if (!dir.exists()) runCatching { dir.mkdirs() }

        // Prevent unbounded growth (including tmp leftovers).
        pruneOldPendingCrashes(dir)

        val utcCompact = LogFiles.utcCompactNow()
        val pid = Process.myPid()
        val seq = crashSeq.incrementAndGet()

        val name = "crash-$utcCompact-$pid-$seq.txt"
        val tmp = File(dir, "$name.tmp")
        val file = File(dir, name)

        val pkg = safePackageName(appContext)
        val versionName = safeVersionName(appContext)
        val versionCode = safeVersionCode(appContext)
        val installShort = runCatching { LogFiles.installIdShort(appContext) }.getOrDefault("unknown")
        val installHash = runCatching { LogFiles.installIdHash(appContext) }.getOrDefault("unknown")

        val uptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(-1L)
        val tid = safeThreadId(thread)

        val fingerprintHash = sha256Hex(Build.FINGERPRINT).take(16).ifBlank { "unknown" }
        val abis = runCatching { (Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(",") }.getOrDefault("")

        // Best-effort: write to tmp then rename into place.
        val wroteTmp = runCatching {
            FileOutputStream(tmp, false).use { fos ->
                writeReport(
                    out = fos,
                    utcCompact = utcCompact,
                    thread = thread,
                    tid = tid,
                    pid = pid,
                    uptimeMs = uptimeMs,
                    pkg = pkg,
                    versionName = versionName,
                    versionCode = versionCode,
                    buildLabel = LogFiles.buildLabelSafe(),
                    installShort = installShort,
                    installHash = installHash,
                    sdk = Build.VERSION.SDK_INT,
                    device = "${Build.MANUFACTURER}/${Build.MODEL}",
                    fingerprintHash = fingerprintHash,
                    abis = abis,
                    throwable = t,
                )

                // Best-effort fsync (helps on sudden process death).
                runCatching { fos.fd.sync() }
            }
            true
        }.getOrDefault(false)

        if (!wroteTmp) {
            runCatching { tmp.delete() }
            return
        }

        // If rename fails (rare), fallback to direct write.
        if (!tmp.renameTo(file)) {
            runCatching { file.delete() }
            runCatching {
                FileOutputStream(file, false).use { fos ->
                    writeReport(
                        out = fos,
                        utcCompact = utcCompact,
                        thread = thread,
                        tid = tid,
                        pid = pid,
                        uptimeMs = uptimeMs,
                        pkg = pkg,
                        versionName = versionName,
                        versionCode = versionCode,
                        buildLabel = LogFiles.buildLabelSafe(),
                        installShort = installShort,
                        installHash = installHash,
                        sdk = Build.VERSION.SDK_INT,
                        device = "${Build.MANUFACTURER}/${Build.MODEL}",
                        fingerprintHash = fingerprintHash,
                        abis = abis,
                        throwable = t,
                    )
                    runCatching { fos.fd.sync() }
                }
            }
            runCatching { tmp.delete() }
        } else {
            runCatching { tmp.delete() }
        }
    }

    private fun writeReport(
        out: OutputStream,
        utcCompact: String,
        thread: Thread,
        tid: Long,
        pid: Int,
        uptimeMs: Long,
        pkg: String,
        versionName: String,
        versionCode: Long,
        buildLabel: String,
        installShort: String,
        installHash: String,
        sdk: Int,
        device: String,
        fingerprintHash: String,
        abis: String,
        throwable: Throwable,
    ) {
        val limited = LimitedOutputStream(out, MAX_REPORT_BYTES)
        val osw = OutputStreamWriter(limited, Charsets.UTF_8)

        SanitizingLineWriter(osw) { line -> sanitizeLine(line) }.use { writer ->
            PrintWriter(writer, true).use { pw ->
                pw.println("===== Survey App Crash Report =====")
                pw.println("utcCompact=$utcCompact")
                pw.println("thread=${thread.name}")
                pw.println("threadId=$tid")
                pw.println("pid=$pid")
                pw.println("uptimeMs=$uptimeMs")
                pw.println("sdk=$sdk")
                pw.println("device=$device")
                pw.println("fingerprintHash=$fingerprintHash")
                pw.println("abis=$abis")
                pw.println("appId=$pkg")
                pw.println("versionName=$versionName")
                pw.println("versionCode=$versionCode")
                pw.println("build=$buildLabel")
                pw.println("installIdShort=$installShort")
                pw.println("installIdHash=$installHash")
                pw.println()

                pw.println("----- Exception -----")
                throwable.printStackTrace(pw)
                pw.flush()
            }
        }
    }

    /**
     * Best-effort redaction for secrets/PII-like values.
     *
     * Notes:
     * - Conservative; it won't catch everything.
     * - The primary protection is still: "do not log sensitive data at call sites".
     */
    private fun sanitizeLine(input: String): String {
        var s = input

        s = s.replace(reAuthBearerHeader, "Authorization: Bearer <REDACTED>")
        s = s.replace(reAuthBearerJson, "\"authorization\":\"Bearer <REDACTED>\"")

        s = s.replace(reKeyValue) { m ->
            val key = m.groupValues[1]
            val sep = m.groupValues[2]
            "$key$sep<REDACTED>"
        }

        s = s.replace(reKeyValueJson) { m ->
            val key = Regex("(?i)\"\\s*([^\"]+)\\s*\"\\s*:").find(m.value)?.groupValues?.getOrNull(1) ?: "token"
            "\"$key\":\"<REDACTED>\""
        }

        s = s.replace(reGitHubClassic, "<REDACTED_GH_TOKEN>")
        s = s.replace(reGitHubPat, "<REDACTED_GH_TOKEN>")
        s = s.replace(reGcpApiKey, "<REDACTED_GCP_KEY>")
        s = s.replace(reJwt, "<REDACTED_JWT>")
        s = s.replace(reEmail, "<REDACTED_EMAIL>")

        return s
    }

    private fun pruneOldPendingCrashes(dir: File) {
        val now = System.currentTimeMillis()

        // 1) Remove stale tmp leftovers.
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".tmp") }
            ?.forEach { f ->
                val age = now - (f.lastModified().takeIf { it > 0L } ?: now)
                if (age >= STALE_TMP_AGE_MS) {
                    runCatching { f.delete() }
                }
            }

        // 2) Keep only the newest N crash reports.
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()

        if (files.size <= MAX_PENDING_CRASH_FILES) return
        files.drop(MAX_PENDING_CRASH_FILES).forEach { runCatching { it.delete() } }
    }

    private fun safePackageName(context: Context): String =
        runCatching { context.packageName }.getOrDefault("unknown")

    private fun safeVersionName(context: Context): String {
        val pm = context.packageManager ?: return "unknown"
        val pkg = context.packageName
        return runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            pi.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun safeVersionCode(context: Context): Long {
        val pm = context.packageManager ?: return -1L
        val pkg = context.packageName
        return runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }.getOrDefault(-1L)
    }

    /**
     * Returns a stable thread identifier without relying on newer APIs.
     *
     * Notes:
     * - thread.id exists widely (though it may be deprecated); it is safe in crash paths.
     */
    private fun safeThreadId(thread: Thread): Long {
        return runCatching { thread.id }.getOrDefault(-1L)
    }

    /**
     * OutputStream wrapper that stops writing after reaching a size limit.
     *
     * Notes:
     * - Does NOT close the underlying stream on close(); caller manages lifecycle.
     * - Once the limit is hit, it writes a truncation marker (best-effort) and ignores further writes.
     */
    private class LimitedOutputStream(
        private val base: OutputStream,
        private val limitBytes: Long
    ) : OutputStream() {

        private var written: Long = 0L
        private var truncated: Boolean = false

        override fun write(b: Int) {
            if (truncated) return
            if (written + 1 > limitBytes) {
                writeTruncatedMarker()
                truncated = true
                return
            }
            base.write(b)
            written += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (truncated) return
            if (len <= 0) return

            val remaining = limitBytes - written
            if (remaining <= 0) {
                writeTruncatedMarker()
                truncated = true
                return
            }

            val toWrite = if (len.toLong() > remaining) remaining.toInt() else len
            base.write(b, off, toWrite)
            written += toWrite.toLong()

            if (toWrite < len) {
                writeTruncatedMarker()
                truncated = true
            }
        }

        override fun flush() {
            runCatching { base.flush() }
        }

        override fun close() {
            // Intentionally do not close base.
            flush()
        }

        private fun writeTruncatedMarker() {
            if (truncated) return
            val marker = "\n--- TRUNCATED: report exceeded limit (${limitBytes} bytes) ---\n"
            val bytes = marker.toByteArray(Charsets.UTF_8)
            val remaining = limitBytes - written
            if (remaining <= 0) return
            val toWrite = if (bytes.size.toLong() > remaining) remaining.toInt() else bytes.size
            if (toWrite <= 0) return
            runCatching {
                base.write(bytes, 0, toWrite)
                written += toWrite.toLong()
                base.flush()
            }
        }
    }

    /**
     * Writer that sanitizes output line-by-line.
     *
     * Notes:
     * - Buffers until newline, then applies sanitizer and forwards.
     * - Keeps memory bounded even for large stack traces.
     */
    private class SanitizingLineWriter(
        private val base: Writer,
        private val sanitizer: (String) -> String
    ) : Writer() {

        private val buf = StringBuilder()

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (len <= 0) return
            for (i in off until (off + len)) {
                val c = cbuf[i]
                if (c == '\n') {
                    flushLine(withNewline = true)
                } else {
                    buf.append(c)
                }
            }
        }

        override fun flush() {
            flushLine(withNewline = false)
            base.flush()
        }

        override fun close() {
            flush()
            base.close()
        }

        private fun flushLine(withNewline: Boolean) {
            if (buf.isEmpty() && !withNewline) return

            val raw = buf.toString()
            buf.setLength(0)

            val clean = runCatching { sanitizer(raw) }.getOrDefault(raw)
            base.write(clean)
            if (withNewline) base.write("\n")
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** SHA-256 hex (lowercase). */
    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        var i = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }
}