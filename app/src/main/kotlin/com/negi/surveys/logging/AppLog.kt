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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-scoped file logger (PII-safe).
 *
 * Notes:
 * - Mirrors android.util.Log.* so migration is easy.
 * - Writes are synchronous to survive crashes.
 * - This does NOT dump system logcat; it only records what the app chooses to log.
 */
object AppLog {
    private const val INTERNAL_TAG = "AppLog"

    /** Rotate if app.log exceeds this size. */
    private const val MAX_FILE_BYTES: Long = 1024L * 1024L // 1 MB

    /** Keep at most this many rotated files (app-*.log). */
    private const val MAX_ROTATED_FILES: Int = 8

    private val installed = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var currentFile: File? = null

    private val ts: SimpleDateFormat = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * Initializes the logger. Call early (e.g., Activity.onCreate).
     */
    fun init(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val dir = LogFiles.appLogDir(context)
        logDir = dir
        currentFile = File(dir, "app.log")
        rotateIfNeededLocked()
        i(INTERNAL_TAG, "init: dir=${dir.absolutePath} build=${LogFiles.buildLabelSafe()} installId=${LogFiles.installId(context)}")
    }

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

        runCatching { writeLine(level, tag, msg, t) }
            .onFailure { ex -> Log.w(INTERNAL_TAG, "file write failed: ${ex.javaClass.simpleName}") }

        return rc
    }

    private fun writeLine(level: Int, tag: String, msg: String, t: Throwable?) {
        val dir = logDir ?: return
        synchronized(lock) {
            rotateIfNeededLocked()

            val file = currentFile ?: File(dir, "app.log").also { currentFile = it }
            val now = ts.format(Date())
            val lvl = when (level) {
                Log.VERBOSE -> 'V'
                Log.DEBUG -> 'D'
                Log.INFO -> 'I'
                Log.WARN -> 'W'
                Log.ERROR -> 'E'
                else -> 'D'
            }

            val cleanMsg = msg.replace('\n', ' ').take(4096)
            val extra = if (t != null) {
                val m = t.message?.replace('\n', ' ')?.take(512).orEmpty()
                " | ${t.javaClass.simpleName}${if (m.isBlank()) "" else ": $m"}"
            } else {
                ""
            }

            val line = "$now $lvl/${tag.take(64)}: $cleanMsg$extra\n"
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    private fun rotateIfNeededLocked() {
        val dir = logDir ?: return
        val file = currentFile ?: return
        if (!file.exists()) return
        if (file.length() < MAX_FILE_BYTES) return

        val rotated = File(dir, "app-${LogFiles.utcCompactNow()}.log")
        runCatching { file.renameTo(rotated) }

        currentFile = File(dir, "app.log").also { FileOutputStream(it, true).use { } }

        // Prune old rotated files.
        val rotatedFiles = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (rotatedFiles.size <= MAX_ROTATED_FILES) return
        rotatedFiles.drop(MAX_ROTATED_FILES).forEach { runCatching { it.delete() } }
    }
}
