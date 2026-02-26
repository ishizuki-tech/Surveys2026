/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: HeavyInitializer.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  One-shot, single-flight initializer for large model files or heavy assets.
 *  Handles download, resume, integrity check, and atomic replacement with
 *  coroutine cancellation propagation and friendly error reporting.
 *
 *  Key properties:
 *  ---------------------------------------------------------------------
 *  - Single-flight:
 *      Concurrent callers share the same in-flight Deferred<Result<File>>.
 *  - Resume:
 *      Partial files are preserved across cancellations/timeouts when forceFresh=false.
 *  - Integrity:
 *      If Content-Length is known, final size must match exactly.
 *  - Replacement:
 *      Uses rename within the same directory when possible; falls back to
 *      a stream copy if rename fails.
 * =====================================================================
 */

package com.negi.surveys.utils

import android.content.Context
import android.os.Looper
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Manages single-flight initialization for heavy assets (e.g., model download).
 *
 * This object is designed to be called from ViewModels or other lifecycle-aware layers.
 * The "owner" caller performs the work; all other callers wait on the same [CompletableDeferred].
 */
object HeavyInitializer {

    private const val TAG = "HeavyInitializer"

    /** Safety buffer to reduce out-of-space risk during metadata updates or temporary allocations. */
    private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L // 64 MiB

    /** Upper bound for manual redirect following. */
    private const val MAX_REDIRECTS = 10

    /** Network timeouts (per request). */
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Copy buffer. */
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Tracks a single in-flight initialization for all concurrent callers.
     */
    private val inFlight = AtomicReference<CompletableDeferred<Result<File>>?>(null)

    /**
     * The Job of the "owner" coroutine currently performing the initialization.
     *
     * cancel() will cancel this Job.
     */
    @Volatile
    private var runningJob: Job? = null

    /**
     * Returns true if a download/initialization is currently in progress.
     */
    @JvmStatic
    fun isInFlight(): Boolean = inFlight.get() != null

    /**
     * Checks if a valid file already exists and (when possible) matches remote size.
     *
     * NOTE:
     * - This is synchronous best-effort. If called on the main thread, it will NOT block on network;
     *   it falls back to "local file exists" behavior.
     */
    fun isAlreadyComplete(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String
    ): Boolean {
        val dst = resolveSafeFileUnder(context.applicationContext.filesDir, fileName)
        if (!dst.exists() || !dst.isFile || dst.length() <= 0L) return false

        val isMain = Looper.myLooper() == Looper.getMainLooper()
        if (isMain) return true

        val token = hfToken?.takeIf { it.isNotBlank() }
        val remoteLen = runCatching { headContentLengthForVerify(modelUrl, token) }.getOrNull()

        return when {
            remoteLen != null -> remoteLen == dst.length()
            else -> true
        }
    }

    /**
     * Ensure that the model or asset is initialized, downloading it if needed.
     *
     * @param timeoutMs <= 0 means "no timeout".
     */
    suspend fun ensureInitialized(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Result<File> {
        inFlight.get()?.let { return it.await() }

        val deferred = CompletableDeferred<Result<File>>()
        if (!inFlight.compareAndSet(null, deferred)) {
            return inFlight.get()!!.await()
        }

        val ownerJob = currentCoroutineContext()[Job]
        runningJob = ownerJob

        val app = context.applicationContext
        val token = hfToken?.takeIf { it.isNotBlank() }

        val dir = app.filesDir
        val finalFile = resolveSafeFileUnder(dir, fileName)

        val tmpFile = resolveSafeFileUnder(dir, "$fileName.tmp")
        val tmpPartFile = File(tmpFile.parentFile, tmpFile.name + ".part")
        val tmpMetaFile = File(tmpFile.parentFile, tmpPartFile.name + ".meta")

        try {
            currentCoroutineContext().ensureActive()

            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
                runCatching { finalFile.delete() }
            }

            val remoteLen: Long? = withContext(Dispatchers.IO) {
                runCatching { headContentLength(modelUrl, token) }
                    .onFailure { e -> AppLog.w(TAG, "HEAD content-length failed (non-fatal)", e) }
                    .getOrNull()
                    ?.takeIf { it > 0L }
            }

            if (!forceFresh && finalFile.exists() && finalFile.length() > 0L) {
                val ok = when {
                    remoteLen != null -> finalFile.length() == remoteLen
                    else -> true
                }
                if (ok) {
                    val len = finalFile.length()
                    runCatching { onProgress(len, remoteLen ?: len) }
                    deferred.complete(Result.success(finalFile))
                    return deferred.await()
                }
            }

            if (!forceFresh && tmpPartFile.exists() && tmpPartFile.length() > 0L) {
                if (remoteLen != null && tmpPartFile.length() > remoteLen) {
                    AppLog.w(
                        TAG,
                        "part is larger than remoteLen -> discarding part (part=${tmpPartFile.length()}, remote=$remoteLen)"
                    )
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
            }

            val existingPartial = tmpPartFile.takeIf { it.exists() }?.length() ?: 0L

            if (remoteLen != null) {
                val remaining = max(0L, remoteLen - existingPartial)
                val needed = remaining + FREE_SPACE_MARGIN_BYTES
                if (dir.usableSpace < needed) {
                    val msg =
                        "Not enough free space. needed=${needed}B (remaining=$remaining + margin), usable=${dir.usableSpace}B"
                    deferred.complete(Result.failure(IOException(msg)))
                    return deferred.await()
                }
            }

            val safeProgressBridge: (Long, Long?) -> Unit = { cur, total ->
                if (ownerJob?.isActive != false) {
                    runCatching { onProgress(cur, total) }
                }
            }

            val runDownload: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()

                runCatching { writeMeta(tmpMetaFile, modelUrl, remoteLen) }

                downloadToPartFile(
                    url = modelUrl,
                    hfToken = token,
                    partFile = tmpPartFile,
                    expectedTotalLen = remoteLen,
                    onProgress = safeProgressBridge
                )

                currentCoroutineContext().ensureActive()

                if (tmpFile.exists()) runCatching { tmpFile.delete() }
                if (!tmpPartFile.renameTo(tmpFile)) {
                    tmpPartFile.inputStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output, BUFFER_BYTES)
                        }
                    }
                    runCatching { tmpPartFile.delete() }
                }

                currentCoroutineContext().ensureActive()
            }

            if (timeoutMs > 0L) {
                withTimeout(timeoutMs) { runDownload() }
            } else {
                runDownload()
            }

            if (remoteLen != null) {
                val got = tmpFile.length()
                if (got != remoteLen) {
                    throw IOException("Downloaded size mismatch. expected=$remoteLen, got=$got")
                }
            }

            replaceFinalAtomic(tmpFile, finalFile)
            runCatching { tmpMetaFile.delete() }

            val outLen = finalFile.length()
            runCatching { onProgress(outLen, remoteLen ?: outLen) }

            deferred.complete(Result.success(finalFile))

        } catch (ce: CancellationException) {
            AppLog.w(TAG, "ensureInitialized: cancelled", ce)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Canceled", ce)))

        } catch (ie: InterruptedIOException) {
            AppLog.w(TAG, "ensureInitialized: interrupted", ie)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Canceled", ie)))

        } catch (te: TimeoutCancellationException) {
            AppLog.w(TAG, "ensureInitialized: timeout after ${timeoutMs}ms", te)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Timeout ($timeoutMs ms)", te)))

        } catch (t: Throwable) {
            val msg = userFriendlyMessage(t)
            AppLog.w(TAG, "Initialization error: $msg", t)

            runCatching { tmpFile.delete() }
            runCatching { tmpPartFile.delete() }
            runCatching { tmpMetaFile.delete() }

            deferred.complete(Result.failure(IOException(msg, t)))

        } finally {
            if (inFlight.get() === deferred) {
                inFlight.set(null)
            }
            runningJob = null
        }

        return deferred.await()
    }

    /**
     * Cancels any running initialization.
     */
    fun cancel() {
        runningJob?.cancel(CancellationException("canceled by user"))
        AppLog.w(TAG, "Initialization cancel requested.")
    }

    /**
     * Clears all in-flight and debug state.
     */
    fun resetForDebug() {
        inFlight.get()?.complete(Result.failure(IOException("resetForDebug")))
        runningJob?.cancel(CancellationException("resetForDebug"))
        runningJob = null
        AppLog.w(TAG, "resetForDebug(): cleared in-flight state")
    }

    // ---------------------------------------------------------------------
    // Safe file resolution
    // ---------------------------------------------------------------------

    private fun resolveSafeFileUnder(baseDir: File, relativePath: String): File {
        val p = relativePath.trim()
        require(p.isNotEmpty()) { "fileName must not be empty" }
        require(!p.startsWith("/")) { "absolute paths are not allowed: $p" }

        val segments = p.split('/', '\\')
        require(segments.none { it == ".." }) { "path traversal is not allowed: $p" }

        val base = baseDir.canonicalFile
        val f = File(baseDir, p)
        val canon = f.canonicalFile

        val basePath = base.path
        val canonPath = canon.path
        val ok = canonPath == basePath || canonPath.startsWith(basePath + File.separator)
        require(ok) { "resolved path escapes baseDir: $p" }

        return canon
    }

    // ---------------------------------------------------------------------
    // HEAD probe
    // ---------------------------------------------------------------------

    private fun headContentLength(srcUrl: String, hfToken: String?): Long? =
        headContentLengthForVerify(srcUrl, hfToken)

    private fun headContentLengthForVerify(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl

        repeat(MAX_REDIRECTS) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (
                    (u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) &&
                    !hfToken.isNullOrBlank()
                ) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }

                if (code !in 200..299) return null

                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }
            } finally {
                conn.disconnect()
            }
        }

        return null
    }

    // ---------------------------------------------------------------------
    // Download (resume + redirects)
    // ---------------------------------------------------------------------

    private suspend fun downloadToPartFile(
        url: String,
        hfToken: String?,
        partFile: File,
        expectedTotalLen: Long?,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            val u = URL(current)
            val already = partFile.takeIf { it.exists() }?.length() ?: 0L

            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (
                    (u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) &&
                    !hfToken.isNullOrBlank()
                ) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
                if (already > 0L) {
                    setRequestProperty("Range", "bytes=$already-")
                }
            }

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many redirects.")
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect with no Location header.")
                    current = URL(u, loc).toString()
                    continue
                }

                if (code == 416) {
                    runCatching { partFile.delete() }
                    throw IOException("Resume failed (server refused range).")
                }

                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }

                val totalFromContentRange = parseTotalFromContentRange(conn.getHeaderField("Content-Range"))
                val contentLen = conn.contentLengthLong.takeIf { it > 0L }
                val total = expectedTotalLen ?: totalFromContentRange ?: run {
                    if (code == 206 && contentLen != null) already + contentLen else contentLen
                }

                partFile.parentFile?.mkdirs()

                // Append only if we requested Range and server honored it (206).
                val append = already > 0L && code == 206
                val startWritten = if (append) already else 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { fos ->
                        val buf = ByteArray(BUFFER_BYTES)
                        var written = startWritten
                        onProgressSafe(onProgress, written, total)

                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val r = input.read(buf)
                            if (r < 0) break
                            fos.write(buf, 0, r)
                            written += r
                            onProgressSafe(onProgress, written, total)
                        }
                        fos.flush()
                    }
                }

                if (total != null) {
                    val got = partFile.length()
                    if (got != total) {
                        throw IOException("Downloaded size mismatch. expected=$total, got=$got")
                    }
                }

                return@withContext
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun onProgressSafe(
        onProgress: (Long, Long?) -> Unit,
        cur: Long,
        total: Long?
    ) {
        runCatching { onProgress(cur, total) }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        val cr = contentRange ?: return null
        val idx = cr.lastIndexOf('/')
        if (idx < 0 || idx == cr.lastIndex) return null
        val tail = cr.substring(idx + 1).trim()
        return tail.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun writeMeta(metaFile: File, url: String, expectedLen: Long?) {
        metaFile.parentFile?.mkdirs()
        val lines = buildString {
            append("url=").append(url).append("\n")
            append("expectedLen=").append(expectedLen?.toString() ?: "").append("\n")
            append("debug=").append(BuildConfig.DEBUG.toString()).append("\n")
        }
        metaFile.writeText(lines)
    }

    // ---------------------------------------------------------------------
    // Error messaging
    // ---------------------------------------------------------------------

    private fun userFriendlyMessage(t: Throwable): String {
        val raw = t.message ?: t::class.java.simpleName
        val s = raw.lowercase()

        return when {
            "unauthorized" in s || "401" in s -> "Authorization failed (HF token?)"
            "forbidden" in s || "403" in s -> "Access denied (token/permissions?)"
            "timeout" in s -> "Network timeout"
            "space" in s -> "Not enough free space"
            "content-range" in s || "416" in s || "range" in s -> "Resume failed (server refused range)"
            "unknown host" in s || "dns" in s -> "Unknown host (check connectivity)"
            else -> raw
        }
    }

    // ---------------------------------------------------------------------
    // Replacement
    // ---------------------------------------------------------------------

    private fun replaceFinalAtomic(tmp: File, dst: File) {
        if (!tmp.exists() || tmp.length() <= 0L) {
            throw IOException("Temp file missing or empty: ${tmp.absolutePath}")
        }

        dst.parentFile?.mkdirs()

        if (dst.exists() && !dst.delete()) {
            AppLog.w(TAG, "replaceFinalAtomic: failed to delete existing ${dst.absolutePath}")
        }

        if (tmp.renameTo(dst)) {
            return
        }

        AppLog.w(TAG, "replaceFinalAtomic: rename failed, falling back to copy")

        try {
            tmp.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output, BUFFER_BYTES)
                }
            }
        } catch (e: IOException) {
            throw IOException("replaceFinalAtomic: copy fallback failed", e)
        } finally {
            runCatching { tmp.delete() }
        }

        if (!dst.exists() || dst.length() <= 0L) {
            throw IOException("replaceFinalAtomic: destination invalid after copy: ${dst.absolutePath}")
        }
    }
}