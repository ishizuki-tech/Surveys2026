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
 *  - Resume (safe):
 *      Partial files are preserved across cancellations/timeouts and many IO failures
 *      when forceFresh=false, but only if meta matches the current URL/expected length.
 *  - Integrity:
 *      If Content-Length (or meta expectedLen) is known, final size must match exactly.
 *  - Replacement:
 *      Uses rename within the same directory when possible; falls back to
 *      a stream copy if rename fails.
 * =====================================================================
 */

package com.negi.surveys.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
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
 *
 * Threading contract:
 * - All disk/network IO runs on Dispatchers.IO.
 * - Progress callbacks are marshalled to the main thread to be Compose/UI safe.
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

    /** Debug watchdog (does not cancel; improves observability). */
    private const val WATCHDOG_MS = 30_000L

    private val nextRunId = AtomicLong(1L)

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
        // Fast path: join existing in-flight work.
        inFlight.get()?.let { return it.await() }

        val deferred = CompletableDeferred<Result<File>>()
        if (!inFlight.compareAndSet(null, deferred)) {
            val existing = inFlight.get()
            return (existing ?: deferred).await()
        }

        val runId = nextRunId.getAndIncrement()
        val ownerJob = currentCoroutineContext()[Job]
        runningJob = ownerJob

        val app = context.applicationContext
        val token = hfToken?.takeIf { it.isNotBlank() }

        val dir = app.filesDir
        val finalFile = resolveSafeFileUnder(dir, fileName)

        val tmpFile = resolveSafeFileUnder(dir, "$fileName.tmp")
        val tmpPartFile = File(tmpFile.parentFile, tmpFile.name + ".part")
        val tmpMetaFile = File(tmpFile.parentFile, tmpPartFile.name + ".meta")

        val phaseRef = AtomicReference(Phase.INIT)
        val mainProgress = MainThreadProgress(onProgress)

        val watchdog = startWatchdog(runId, phaseRef)

        try {
            currentCoroutineContext().ensureActive()
            phaseRef.set(Phase.PREPARE)

            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
                runCatching { finalFile.delete() }
            }

            // Read meta first (best-effort) to validate resume safety.
            val meta = readMetaIfPresent(tmpMetaFile)
            if (!forceFresh) {
                // If meta exists but does not match the current URL, discard partial.
                if (meta != null && meta.url != modelUrl) {
                    AppLog.w(TAG, "run=$runId meta url mismatch -> discard part/meta")
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
            }

            phaseRef.set(Phase.HEAD_PROBE)

            // HEAD probe (best-effort). If it fails, fall back to meta expectedLen.
            val remoteLen: Long? = withContext(Dispatchers.IO) {
                runCatching { headContentLength(modelUrl, token) }
                    .onFailure { e -> AppLog.w(TAG, "run=$runId HEAD content-length failed (non-fatal)", e) }
                    .getOrNull()
                    ?.takeIf { it > 0L }
            }

            val meta2 = readMetaIfPresent(tmpMetaFile)
            val expectedLen: Long? = (remoteLen ?: meta2?.expectedLen)?.takeIf { it > 0L }

            phaseRef.set(Phase.CHECK_EXISTING)

            if (!forceFresh && finalFile.exists() && finalFile.length() > 0L) {
                val ok = when {
                    expectedLen != null -> finalFile.length() == expectedLen
                    else -> true
                }
                if (ok) {
                    val len = finalFile.length()
                    mainProgress.emit(len, expectedLen ?: len)
                    deferred.complete(Result.success(finalFile))
                    return deferred.await()
                }
            }

            if (!forceFresh && tmpPartFile.exists() && tmpPartFile.length() > 0L) {
                if (expectedLen != null && tmpPartFile.length() > expectedLen) {
                    AppLog.w(TAG, "run=$runId part > expectedLen -> discard part/meta")
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
            }

            val existingPartial = tmpPartFile.takeIf { it.exists() }?.length() ?: 0L

            phaseRef.set(Phase.CHECK_SPACE)

            if (expectedLen != null) {
                val remaining = max(0L, expectedLen - existingPartial)
                val needed = remaining + FREE_SPACE_MARGIN_BYTES
                if (dir.usableSpace < needed) {
                    val msg =
                        "Not enough free space. needed=${needed}B (remaining=$remaining + margin), usable=${dir.usableSpace}B"
                    deferred.complete(Result.failure(IOException(msg)))
                    return deferred.await()
                }
            }

            AppLog.i(
                TAG,
                "run=$runId start forceFresh=$forceFresh expectedLen=${expectedLen ?: -1L} existingPartial=$existingPartial"
            )

            val runDownload: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()

                // Write meta that binds the partial to this URL and expected length.
                runCatching { writeMeta(tmpMetaFile, modelUrl, expectedLen) }

                phaseRef.set(Phase.DOWNLOAD)

                downloadToPartFile(
                    url = modelUrl,
                    hfToken = token,
                    partFile = tmpPartFile,
                    expectedTotalLen = expectedLen,
                    onProgress = { cur, total -> mainProgress.emit(cur, total) }
                )

                currentCoroutineContext().ensureActive()
                phaseRef.set(Phase.PROMOTE_TMP)

                if (tmpFile.exists()) runCatching { tmpFile.delete() }

                if (!tmpPartFile.renameTo(tmpFile)) {
                    // Copy fallback with best-effort fsync.
                    tmpPartFile.inputStream().use { input ->
                        FileOutputStream(tmpFile, false).use { fos ->
                            input.copyTo(fos, BUFFER_BYTES)
                            fos.flush()
                            trySync(fos)
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

            phaseRef.set(Phase.VERIFY_TMP)

            if (expectedLen != null) {
                val got = tmpFile.length()
                if (got != expectedLen) {
                    throw IOException("Downloaded size mismatch. expected=$expectedLen, got=$got")
                }
            }

            phaseRef.set(Phase.REPLACE_FINAL)

            replaceFinalAtomic(tmpFile, finalFile)
            runCatching { tmpMetaFile.delete() }

            val outLen = finalFile.length()
            mainProgress.emit(outLen, expectedLen ?: outLen)

            phaseRef.set(Phase.DONE_OK)
            deferred.complete(Result.success(finalFile))

        } catch (ce: CancellationException) {
            AppLog.w(TAG, "run=$runId ensureInitialized: cancelled", ce)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            // Keep partial/meta for resume when forceFresh=false.
            deferred.complete(Result.failure(IOException("Canceled", ce)))

        } catch (ie: InterruptedIOException) {
            AppLog.w(TAG, "run=$runId ensureInitialized: interrupted", ie)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Canceled", ie)))

        } catch (te: TimeoutCancellationException) {
            AppLog.w(TAG, "run=$runId ensureInitialized: timeout after ${timeoutMs}ms", te)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            }
            // Keep partial/meta for resume when forceFresh=false.
            deferred.complete(Result.failure(IOException("Timeout ($timeoutMs ms)", te)))

        } catch (t: Throwable) {
            val msg = userFriendlyMessage(t)
            AppLog.w(TAG, "run=$runId Initialization error: $msg", t)

            val keepPartial = !forceFresh && shouldKeepPartialForResume(t)

            // If we want to resume, keep .part and .meta; otherwise purge.
            runCatching { tmpFile.delete() }

            if (!keepPartial) {
                runCatching { tmpPartFile.delete() }
                runCatching { tmpMetaFile.delete() }
            } else {
                AppLog.w(TAG, "run=$runId Keeping partial for resume (forceFresh=false)")
            }

            deferred.complete(Result.failure(IOException(msg, t)))

        } finally {
            stopWatchdog(watchdog)

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
        val cur = inFlight.get()
        cur?.complete(Result.failure(IOException("resetForDebug")))
        inFlight.compareAndSet(cur, null)

        runningJob?.cancel(CancellationException("resetForDebug"))
        runningJob = null
        AppLog.w(TAG, "resetForDebug(): cleared in-flight state")
    }

    // ---------------------------------------------------------------------
    // Phase + watchdog (debug/observability)
    // ---------------------------------------------------------------------

    private enum class Phase {
        INIT,
        PREPARE,
        HEAD_PROBE,
        CHECK_EXISTING,
        CHECK_SPACE,
        DOWNLOAD,
        PROMOTE_TMP,
        VERIFY_TMP,
        REPLACE_FINAL,
        DONE_OK
    }

    private data class WatchdogHandle(
        val thread: Thread
    )

    private fun startWatchdog(runId: Long, phaseRef: AtomicReference<Phase>): WatchdogHandle {
        val t0 = SystemClock.elapsedRealtime()
        val th = Thread.currentThread()

        val wd = Thread(
            {
                try {
                    Thread.sleep(WATCHDOG_MS)
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLog.w(
                        TAG,
                        "run=$runId watchdog timeout after ${WATCHDOG_MS}ms elapsed=${dt}ms phase=${phaseRef.get()} state=${th.state}"
                    )
                } catch (_: InterruptedException) {
                    // Stop silently.
                }
            },
            "HeavyInit-Watchdog"
        ).apply { isDaemon = true }

        wd.start()
        return WatchdogHandle(wd)
    }

    private fun stopWatchdog(handle: WatchdogHandle) {
        runCatching { handle.thread.interrupt() }
    }

    // ---------------------------------------------------------------------
    // Resume keep/discard policy
    // ---------------------------------------------------------------------

    /**
     * Decide whether we should keep the partial file on failure to allow resume.
     *
     * Policy:
     * - Keep on transient/network IO errors.
     * - Discard on "integrity" errors (size mismatch, resume refused, etc.).
     */
    private fun shouldKeepPartialForResume(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        if (t is InterruptedIOException) return true

        // Integrity-ish failures -> discard to avoid reusing corrupt partial.
        if ("size mismatch" in msg) return false
        if ("downloaded size mismatch" in msg) return false
        if ("resume failed" in msg) return false
        if ("refused range" in msg) return false
        if ("416" in msg) return false

        // HTTP failures can be transient; keep partial (resume later) unless integrity-specific.
        if (t is IOException) return true

        return false
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
    // Meta (bind partial to URL + expectedLen)
    // ---------------------------------------------------------------------

    private data class Meta(
        val url: String,
        val expectedLen: Long?
    )

    private fun readMetaIfPresent(metaFile: File): Meta? {
        if (!metaFile.exists() || !metaFile.isFile) return null
        return runCatching {
            val text = metaFile.readText()
            var url: String? = null
            var expectedLen: Long? = null

            val lines = text.split('\n')
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val idx = t.indexOf('=')
                if (idx <= 0) continue
                val k = t.substring(0, idx).trim()
                val v = t.substring(idx + 1).trim()

                when (k) {
                    "url" -> url = v
                    "expectedLen" -> expectedLen = v.toLongOrNull()
                }
            }

            val u = url?.takeIf { it.isNotBlank() } ?: return@runCatching null
            Meta(url = u, expectedLen = expectedLen?.takeIf { it > 0L })
        }.getOrNull()
    }

    private fun writeMeta(metaFile: File, url: String, expectedLen: Long?) {
        metaFile.parentFile?.mkdirs()
        val lines = buildString {
            append("url=").append(url).append("\n")
            append("expectedLen=").append(expectedLen?.toString() ?: "").append("\n")
            append("debug=").append(BuildConfig.DEBUG.toString()).append("\n")
            append("sdk=").append(Build.VERSION.SDK_INT.toString()).append("\n")
        }
        metaFile.writeText(lines)
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
                    // Server refused the range. Discard partial to allow a clean restart next time.
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
                        trySync(fos)
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

    private fun trySync(fos: FileOutputStream) {
        runCatching { fos.fd.sync() }
    }

    // ---------------------------------------------------------------------
    // Progress marshalling (UI-safe)
    // ---------------------------------------------------------------------

    private class MainThreadProgress(
        private val onProgress: (Long, Long?) -> Unit
    ) {
        private val mainHandler: Handler? =
            if (Looper.getMainLooper() != null) Handler(Looper.getMainLooper()) else null

        /** Emit progress on the main thread to keep Compose/UI updates safe. */
        fun emit(downloaded: Long, total: Long?) {
            val h = mainHandler
            if (h == null || Looper.myLooper() == Looper.getMainLooper()) {
                runCatching { onProgress(downloaded, total) }
                return
            }
            h.post {
                runCatching { onProgress(downloaded, total) }
            }
        }
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
            "content-range" in s || "416" in s || "refused range" in s -> "Resume failed (server refused range)"
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
                FileOutputStream(dst, false).use { fos ->
                    input.copyTo(fos, BUFFER_BYTES)
                    fos.flush()
                    trySync(fos)
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