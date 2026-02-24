/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: LogFiles.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import android.content.Context
import android.os.Build
import android.os.Process
import com.negi.surveys.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Log file utilities (paths + bundling).
 *
 * Storage (internal):
 * - filesDir/logs/app/
 * - filesDir/logs/crash/pending/
 * - filesDir/logs/crash/uploaded/
 *
 * Bundles (cache):
 * - cacheDir/{kind}-{utc}-{pid}.zip
 */
object LogFiles {
    private const val ROOT_DIR = "logs"
    private const val SUBDIR_APP = "app"
    private const val SUBDIR_CRASH = "crash"
    private const val SUBDIR_PENDING = "pending"
    private const val SUBDIR_UPLOADED = "uploaded"

    private const val PREFS = "survey_logs"
    private const val KEY_INSTALL_ID = "install_id"

    /**
     * Defaults tuned for GitHub Contents API + Base64-in-JSON stability.
     *
     * Notes:
     * - Zipped output may be smaller than raw, but we still keep conservative defaults.
     */
    private const val DEFAULT_MAX_APP_LOGS = 12
    private const val DEFAULT_MAX_CRASH_LOGS = 12
    private const val DEFAULT_MAX_TOTAL_BYTES: Long = 12L * 1024L * 1024L // 12 MiB

    private const val ZIP_BUFFER_BYTES = 8 * 1024

    /** Thread-local UTC timestamp formatter (SimpleDateFormat is not thread-safe). */
    private val utcCompact: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /** Returns current UTC time in compact format. */
    fun utcCompactNow(): String {
        // ThreadLocal#get is seen as nullable in Kotlin; guard and self-heal deterministically.
        val fmt = utcCompact.get() ?: SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.also { utcCompact.set(it) }

        return fmt.format(Date())
    }

    /** Root logs directory: filesDir/logs/ */
    fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR).apply { mkdirs() }

    /** App logs directory: filesDir/logs/app/ */
    fun appLogDir(context: Context): File =
        File(rootDir(context), SUBDIR_APP).apply { mkdirs() }

    /** Crash pending directory: filesDir/logs/crash/pending/ */
    fun crashPendingDir(context: Context): File =
        File(File(rootDir(context), SUBDIR_CRASH), SUBDIR_PENDING).apply { mkdirs() }

    /** Crash uploaded directory: filesDir/logs/crash/uploaded/ */
    fun crashUploadedDir(context: Context): File =
        File(File(rootDir(context), SUBDIR_CRASH), SUBDIR_UPLOADED).apply { mkdirs() }

    /**
     * Canonical app log file path (created by AppLog).
     *
     * Notes:
     * - This is a convenience for callers that want to ensure existence.
     */
    fun appLogFile(context: Context): File =
        File(appLogDir(context), "app.log")

    /**
     * Ensures the canonical app log file exists.
     *
     * Why:
     * - Some flows check "is there any log file" before the first AppLog write happens.
     * - Creating an empty file is safe and removes ambiguity ("no file" vs "empty file").
     */
    fun ensureAppLogFile(context: Context): File {
        val f = appLogFile(context)
        if (!f.exists()) {
            runCatching {
                f.parentFile?.mkdirs()
                FileOutputStream(f, true).use { /* touch */ }
            }
        }
        return f
    }

    /**
     * Stable install ID.
     *
     * This ID survives app restarts but is cleared on app data clear/uninstall.
     */
    fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_ID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, newId).apply()
        return newId
    }

    /** Short, human-friendly install ID marker (non-secret). */
    fun installIdShort(context: Context): String =
        installId(context).replace("-", "").take(8).ifBlank { "unknown" }

    /** Hashed install ID for PII-safe diagnostics correlation. */
    fun installIdHash(context: Context): String =
        sha256Hex(installId(context)).take(16).ifBlank { "unknown" }

    /** Lists app logs (*.log) in newest-first order. */
    fun listAppLogs(context: Context): List<File> =
        appLogDir(context).listFiles()
            ?.filter { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Lists pending crash logs (*.txt) in newest-first order. */
    fun listPendingCrashLogs(context: Context): List<File> =
        crashPendingDir(context).listFiles()
            ?.filter { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun createZipBundle(
        context: Context,
        kind: UploadKind,
        includeCrashFiles: List<File> = emptyList(),
        reason: String? = null,
        maxAppLogs: Int = DEFAULT_MAX_APP_LOGS,
        maxCrashLogs: Int = DEFAULT_MAX_CRASH_LOGS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ): File {
        val appContext = context.applicationContext

        val safeMaxApp = maxAppLogs.coerceAtLeast(0)
        val safeMaxCrash = maxCrashLogs.coerceAtLeast(0)
        val safeMaxBytes = maxTotalBytes.coerceAtLeast(0L)

        // Ensure a canonical file exists so callers don't get "no log file found" due to timing.
        ensureAppLogFile(appContext)

        val cache = appContext.cacheDir
        val zipName = "${kind.prefix}-${utcCompactNow()}-${Process.myPid()}.zip"
        val zipFile = File(cache, zipName)

        // Candidates (newest-first).
        val appCandidates = listAppLogs(appContext)
        val crashCandidates = includeCrashFiles
            .filter { it.exists() && it.isFile }
            .sortedByDescending { it.lastModified() }

        val selected = selectFilesWithinBudget(
            appFiles = appCandidates,
            crashFiles = crashCandidates,
            maxAppLogs = safeMaxApp,
            maxCrashLogs = safeMaxCrash,
            maxTotalBytes = safeMaxBytes
        )

        val metaJson = buildMetaJson(
            context = appContext,
            kind = kind,
            reason = reason,
            appFiles = selected.appFiles,
            crashFiles = selected.crashFiles,
            maxTotalBytes = safeMaxBytes
        )

        // If nothing is selected, include a small notice file to make debugging self-contained.
        val needNotice = selected.appFiles.isEmpty() && selected.crashFiles.isEmpty()
        val noticeText = if (needNotice) buildNoticeText(appContext, kind, reason) else null

        runCatching {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("meta.json"))
                zos.write(metaJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (noticeText != null) {
                    zos.putNextEntry(ZipEntry("notice.txt"))
                    zos.write(noticeText.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                // Keep deterministic ordering inside zip.
                for (f in selected.appFiles.sortedBy { it.name }) {
                    zipAddFile(zos, f, entryName = "app/${f.name}")
                }
                for (f in selected.crashFiles.sortedBy { it.name }) {
                    zipAddFile(zos, f, entryName = "crash/${f.name}")
                }
            }
        }.getOrElse { t ->
            runCatching { zipFile.delete() }
            throw t
        }

        return zipFile
    }

    /**
     * Moves crash files from pending to uploaded.
     *
     * Call this after a successful upload, to prevent re-upload loops.
     *
     * @return List of destination files that exist after move/copy.
     */
    fun moveCrashFilesToUploaded(context: Context, files: List<File>): List<File> {
        val dstDir = crashUploadedDir(context)
        val out = ArrayList<File>(files.size)

        val batchTs = utcCompactNow()
        val pid = Process.myPid()

        files.forEachIndexed { idx, src ->
            if (!src.exists() || !src.isFile) return@forEachIndexed

            val safeName = src.name.replace(Regex("""[^\w.\-]"""), "_")
            val dst = File(dstDir, "${batchTs}-${pid}-${idx}-$safeName")

            val moved = runCatching { src.renameTo(dst) }.getOrDefault(false)
            if (moved && dst.exists()) {
                out.add(dst)
                return@forEachIndexed
            }

            val copied = runCatching {
                copyFile(src, dst)
                true
            }.getOrDefault(false)

            if (copied && dst.exists()) {
                runCatching { src.delete() }
                out.add(dst)
            }
        }

        return out
    }

    /**
     * Builds a human-readable build label without hard dependency on custom BuildConfig fields.
     *
     * Note: Direct references like BuildConfig.GIT_SHA will not compile if the field doesn't exist.
     * We use reflection so the file remains buildable across variants.
     */
    fun buildLabelSafe(): String {
        val sha = getBuildConfigString("GIT_SHA") ?: "nogit"
        val dirty = getBuildConfigBoolean("GIT_DIRTY") ?: false
        val time = getBuildConfigString("BUILD_TIME_UTC") ?: "unknown"

        return "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "type=${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG} " +
                "sha=$sha dirty=$dirty time=$time"
    }

    /**
     * Adds a file to ZIP stream.
     *
     * @return Number of bytes written to the zip entry payload.
     */
    private fun zipAddFile(zos: ZipOutputStream, file: File, entryName: String): Long {
        if (!file.exists() || !file.isFile) return 0L

        val safeEntry = sanitizeZipEntryName(entryName)
        val entry = ZipEntry(safeEntry).apply {
            time = file.lastModified()
        }

        zos.putNextEntry(entry)
        var written = 0L

        BufferedInputStream(FileInputStream(file)).use { input ->
            val buf = ByteArray(ZIP_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zos.write(buf, 0, n)
                written += n.toLong()
            }
        }

        zos.closeEntry()
        return written
    }

    /**
     * Builds meta.json for the bundle.
     *
     * Keep it small and stable; prefer additive fields.
     */
    private fun buildMetaJson(
        context: Context,
        kind: UploadKind,
        reason: String?,
        appFiles: List<File>,
        crashFiles: List<File>,
        maxTotalBytes: Long
    ): String {
        val safeReason = (reason ?: "")
            .replace("\n", " ")
            .replace("\r", " ")
            .take(256)

        val installShort = installIdShort(context)
        val installHash = installIdHash(context)
        val build = buildLabelSafe()

        val appBytes = appFiles.sumOf { it.length().coerceAtLeast(0L) }
        val crashBytes = crashFiles.sumOf { it.length().coerceAtLeast(0L) }
        val totalBytes = appBytes + crashBytes

        val abis = runCatching { Build.SUPPORTED_ABIS?.joinToString(",") ?: "" }.getOrDefault("")
        val fingerprintHash = sha256Hex(Build.FINGERPRINT ?: "").take(16)

        return buildString(2048) {
            append("{")
            append("\"kind\":\"").append(escapeJson(kind.prefix)).append("\",")
            append("\"createdUtc\":\"").append(utcCompactNow()).append("\",")

            // PII-safe install identifiers (avoid full installId in the bundle).
            append("\"installIdShort\":\"").append(escapeJson(installShort)).append("\",")
            append("\"installIdHash\":\"").append(escapeJson(installHash)).append("\",")

            append("\"appId\":\"").append(escapeJson(BuildConfig.APPLICATION_ID)).append("\",")
            append("\"build\":\"").append(escapeJson(build)).append("\",")
            append("\"pid\":").append(Process.myPid()).append(",")
            append("\"sdk\":").append(Build.VERSION.SDK_INT).append(",")
            append("\"release\":\"").append(escapeJson(Build.VERSION.RELEASE ?: "")).append("\",")
            append("\"device\":\"").append(escapeJson("${Build.MANUFACTURER}/${Build.MODEL}")).append("\",")
            append("\"fingerprintHash\":\"").append(escapeJson(fingerprintHash)).append("\",")
            append("\"abis\":\"").append(escapeJson(abis)).append("\",")

            append("\"limits\":{")
            append("\"maxTotalBytes\":").append(maxTotalBytes).append(",")
            append("\"selectedTotalBytes\":").append(totalBytes)
            append("},")

            append("\"counts\":{")
            append("\"app\":").append(appFiles.size).append(",")
            append("\"crash\":").append(crashFiles.size)
            append("},")

            append("\"bytes\":{")
            append("\"app\":").append(appBytes).append(",")
            append("\"crash\":").append(crashBytes)
            append("},")

            append("\"reason\":\"").append(escapeJson(safeReason)).append("\",")

            append("\"paths\":{")
            append("\"appDir\":\"").append(escapeJson(appLogDir(context).absolutePath)).append("\",")
            append("\"crashPendingDir\":\"").append(escapeJson(crashPendingDir(context).absolutePath)).append("\"")
            append("},")

            append("\"files\":{")
            append("\"app\":").append(filesArrayJson(appFiles)).append(",")
            append("\"crash\":").append(filesArrayJson(crashFiles))
            append("}")

            append("}")
        }
    }

    private fun buildNoticeText(context: Context, kind: UploadKind, reason: String?): String {
        val r = (reason ?: "").take(256)
        val appDir = appLogDir(context).absolutePath
        val crashDir = crashPendingDir(context).absolutePath
        val appLog = appLogFile(context).absolutePath
        val build = buildLabelSafe()
        return buildString {
            appendLine("No log files were selected for this bundle.")
            appendLine("kind=${kind.prefix} reason=$r")
            appendLine("build=$build")
            appendLine()
            appendLine("Checked directories:")
            appendLine("- appDir=$appDir")
            appendLine("- crashPendingDir=$crashDir")
            appendLine()
            appendLine("Canonical app log file:")
            appendLine("- appLog=$appLog")
            appendLine()
            appendLine("Likely causes:")
            appendLine("- AppLog.init(context) was never called on startup.")
            appendLine("- Most logs use android.util.Log.* instead of AppLog.* (file logger only records AppLog calls).")
            appendLine("- No crash logs exist in pending/ (expected unless a crash happened).")
        }
    }

    /** Represents selected file lists under a shared budget. */
    private data class SelectedFiles(
        val appFiles: List<File>,
        val crashFiles: List<File>
    )

    /**
     * Selects files within total byte budget with basic prioritization:
     * - Prefer newer files.
     * - Prefer crash files first.
     */
    private fun selectFilesWithinBudget(
        appFiles: List<File>,
        crashFiles: List<File>,
        maxAppLogs: Int,
        maxCrashLogs: Int,
        maxTotalBytes: Long
    ): SelectedFiles {
        var budget = maxTotalBytes.coerceAtLeast(0L)

        val selectedCrash = ArrayList<File>(maxCrashLogs)
        for (f in crashFiles.take(maxCrashLogs)) {
            val size = f.length().coerceAtLeast(0L)
            if (size <= budget) {
                selectedCrash.add(f)
                budget -= size
            }
        }

        val selectedApp = ArrayList<File>(maxAppLogs)
        for (f in appFiles.take(maxAppLogs)) {
            val size = f.length().coerceAtLeast(0L)
            if (size <= budget) {
                selectedApp.add(f)
                budget -= size
            }
        }

        return SelectedFiles(appFiles = selectedApp, crashFiles = selectedCrash)
    }

    /** Copies file bytes, overwriting destination if needed. */
    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(ZIP_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
    }

    /**
     * Sanitizes entry name to avoid zip-slip patterns.
     *
     * We do not allow:
     * - absolute paths
     * - "../"
     */
    private fun sanitizeZipEntryName(name: String): String {
        var s = name.replace('\\', '/')
        while (s.startsWith("/")) s = s.removePrefix("/")
        while (s.contains("../")) s = s.replace("../", "")
        // Additional guard: collapse "//"
        while (s.contains("//")) s = s.replace("//", "/")
        return s.ifBlank { "file" }
    }

    /** Encodes list of files as JSON array with minimal fields. */
    private fun filesArrayJson(files: List<File>): String {
        val sb = StringBuilder(files.size * 64)
        sb.append("[")
        files.forEachIndexed { idx, f ->
            if (idx > 0) sb.append(",")
            sb.append("{")
            sb.append("\"name\":\"").append(escapeJson(f.name)).append("\",")
            sb.append("\"bytes\":").append(f.length().coerceAtLeast(0L)).append(",")
            sb.append("\"lastModifiedMs\":").append(f.lastModified().coerceAtLeast(0L))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    /** JSON escaping for safe embedding into string values. */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** Reflection-based optional BuildConfig string field. */
    private fun getBuildConfigString(fieldName: String): String? {
        return runCatching {
            val f = BuildConfig::class.java.getDeclaredField(fieldName)
            f.isAccessible = true
            (f.get(null) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Reflection-based optional BuildConfig boolean field. */
    private fun getBuildConfigBoolean(fieldName: String): Boolean? {
        return runCatching {
            val f = BuildConfig::class.java.getDeclaredField(fieldName)
            f.isAccessible = true
            when (val v = f.get(null)) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true)
                else -> null
            }
        }.getOrNull()
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

    /** Upload bundle kind. */
    enum class UploadKind(val prefix: String) {
        REGULAR("regular"),
        CRASH("crash")
    }
}