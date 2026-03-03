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
 *  Process-wide, keyed single-flight initializer for heavy assets.
 *
 *  Guarantees:
 *  - Single-flight per key (default key: modelUrl + fileName).
 *  - begin(...) returns a Handle immediately + a Deferred<Result<File>>.
 *  - Targeted cancellation: cancel(handle) removes only that caller.
 *  - Underlying job is cancelled only when no handles remain.
 *
 *  Privacy / Safety:
 *  - Never log tokens, full URLs, file paths, or exception.message.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object HeavyInitializer {

    private const val TAG = "HeavyInitializer"

    /** Safety buffer to reduce out-of-space risk during metadata updates or temporary allocations. */
    private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L // 64 MiB

    /** Upper bound for manual redirect following. */
    private const val MAX_REDIRECTS = 10

    /** Network timeouts (per request). */
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Copy buffer. */
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Stall watchdog:
     * - Logs only when we see no "beat" for this long while still running.
     * - Does not cancel; it is observability only.
     */
    private const val STALL_THRESHOLD_MS = 30_000L
    private const val STALL_CHECK_INTERVAL_MS = 5_000L

    /** Progress throttling per listener (protect Compose/UI). */
    private const val PROGRESS_MIN_INTERVAL_MS = 200L
    private const val PROGRESS_MIN_DELTA_BYTES = 1L * 1024L * 1024L // 1 MiB

    private val nextRunId = AtomicLong(1L)
    private val nextGeneration = AtomicLong(1L)
    private val nextHandleId = AtomicLong(1L)

    /** Key -> in-flight operation */
    private val flights = ConcurrentHashMap<String, InFlight>()

    /** Process-wide scope for heavy work (IO). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-caller handle used for targeted cancellation.
     */
    class Handle internal constructor(
        val key: String,
        internal val handleId: Long,
        internal val generation: Long,
        internal val runId: Long,
    ) {
        override fun toString(): String = "HeavyInitHandle(key=$key, hid=$handleId, gen=$generation, run=$runId)"
    }

    /**
     * Returned by begin(): handle + shared deferred.
     */
    data class Operation(
        val handle: Handle,
        val deferred: CompletableDeferred<Result<File>>,
    )

    /**
     * Returns true if a keyed in-flight operation exists.
     */
    @JvmStatic
    fun isInFlight(key: String): Boolean = flights.containsKey(key)

    /**
     * Start or join a keyed single-flight initialization.
     *
     * Returns immediately:
     * - a per-caller [Handle] (for targeted cancellation)
     * - a shared [deferred] that completes with Result<File>
     */
    fun begin(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
        key: String? = null,
    ): Operation {
        val app = context.applicationContext
        val token = hfToken?.takeIf { it.isNotBlank() }
        val stableKey = key ?: defaultKey(modelUrl = modelUrl, fileName = fileName)

        // Join existing flight if present.
        flights[stableKey]?.let { existing ->
            val handle = existing.newHandle(onProgress)
            return Operation(handle = handle, deferred = existing.deferred)
        }

        // Create new flight record.
        val runId = nextRunId.getAndIncrement()
        val gen = nextGeneration.getAndIncrement()
        val deferred = CompletableDeferred<Result<File>>()
        val ownerJob = SupervisorJob()
        val phaseRef = AtomicReference(Phase.INIT)
        val lastBeatMs = AtomicLong(SystemClock.elapsedRealtime())

        val created = InFlight(
            key = stableKey,
            runId = runId,
            generation = gen,
            deferred = deferred,
            ownerJob = ownerJob,
            phaseRef = phaseRef,
            lastBeatMs = lastBeatMs,
        )

        // Race-safe install.
        val prev = flights.putIfAbsent(stableKey, created)
        if (prev != null) {
            val handle = prev.newHandle(onProgress)
            return Operation(handle = handle, deferred = prev.deferred)
        }

        // Owner also gets a handle (registered listener).
        val ownerHandle = created.newHandle(onProgress)

        // Launch heavy work now.
        scope.launch(ownerJob) {
            val dir = app.filesDir
            val finalFile = resolveSafeFileUnder(dir, fileName)

            val tmpFile = resolveSafeFileUnder(dir, "$fileName.tmp")
            val tmpPartFile = File(tmpFile.parentFile, tmpFile.name + ".part")
            val tmpMetaFile = File(tmpFile.parentFile, tmpPartFile.name + ".meta")

            val watchdogJob = startStallWatchdog(runId, gen, stableKey, phaseRef, lastBeatMs, ownerJob)

            try {
                currentCoroutineContext().ensureActive()
                phaseRef.set(Phase.PREPARE)

                if (forceFresh) {
                    runCatching { tmpFile.delete() }
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                    runCatching { finalFile.delete() }
                }

                // Validate resume safety using meta.
                val meta = readMetaIfPresent(tmpMetaFile)
                if (!forceFresh) {
                    if (meta != null && meta.url != modelUrl) {
                        AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' meta url mismatch -> discard part/meta")
                        runCatching { tmpPartFile.delete() }
                        runCatching { tmpMetaFile.delete() }
                    }
                }

                phaseRef.set(Phase.HEAD_PROBE)

                // HEAD probe (best-effort).
                val remoteLen: Long? = withContext(Dispatchers.IO) {
                    runCatching { headContentLength(modelUrl, token) }.getOrNull()?.takeIf { it > 0L }
                }

                val meta2 = readMetaIfPresent(tmpMetaFile)
                val expectedLen: Long? = (remoteLen ?: meta2?.expectedLen)?.takeIf { it > 0L }

                phaseRef.set(Phase.CHECK_EXISTING)

                // Final already exists and is acceptable.
                if (!forceFresh && finalFile.exists() && finalFile.isFile && finalFile.length() > 0L) {
                    val ok = when {
                        expectedLen != null -> finalFile.length() == expectedLen
                        else -> true
                    }
                    if (ok) {
                        val len = finalFile.length()
                        created.emitProgress(len, expectedLen ?: len)
                        deferred.complete(Result.success(finalFile))
                        return@launch
                    }
                }

                // Part sanity.
                if (!forceFresh && tmpPartFile.exists() && tmpPartFile.length() > 0L) {
                    if (expectedLen != null && tmpPartFile.length() > expectedLen) {
                        AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' part > expectedLen -> discard part/meta")
                        runCatching { tmpPartFile.delete() }
                        runCatching { tmpMetaFile.delete() }
                    }
                }

                val existingPartial = tmpPartFile.takeIf { it.exists() }?.length() ?: 0L

                phaseRef.set(Phase.CHECK_SPACE)

                // Space check (only when expectedLen known).
                if (expectedLen != null) {
                    val remaining = max(0L, expectedLen - existingPartial)
                    val needed = remaining + FREE_SPACE_MARGIN_BYTES
                    if (dir.usableSpace < needed) {
                        deferred.complete(Result.failure(IOException("Not enough free space")))
                        return@launch
                    }
                }

                AppLog.i(
                    TAG,
                    "run=$runId gen=$gen key='$stableKey' start forceFresh=$forceFresh expectedLen=${expectedLen ?: -1L} existingPartial=$existingPartial"
                )

                val runDownload: suspend () -> Unit = {
                    currentCoroutineContext().ensureActive()

                    // Bind partial to url/expectedLen.
                    runCatching { writeMeta(tmpMetaFile, modelUrl, expectedLen) }

                    phaseRef.set(Phase.DOWNLOAD)

                    downloadToPartFile(
                        url = modelUrl,
                        hfToken = token,
                        partFile = tmpPartFile,
                        expectedTotalLen = expectedLen,
                        onProgress = { cur, total -> created.emitProgress(cur, total) }
                    )

                    currentCoroutineContext().ensureActive()
                    phaseRef.set(Phase.PROMOTE_TMP)

                    if (tmpFile.exists()) runCatching { tmpFile.delete() }

                    if (!tmpPartFile.renameTo(tmpFile)) {
                        tmpPartFile.inputStream().use { input ->
                            FileOutputStream(tmpFile, false).use { fos ->
                                input.copyTo(fos, BUFFER_BYTES)
                                fos.flush()
                                trySync(fos)
                            }
                        }
                        runCatching { tmpPartFile.delete() }
                    }
                }

                if (timeoutMs > 0L) {
                    withTimeout(timeoutMs) { runDownload() }
                } else {
                    runDownload()
                }

                phaseRef.set(Phase.VERIFY_TMP)

                if (expectedLen != null) {
                    val got = tmpFile.length()
                    if (got != expectedLen) throw IOException("Downloaded size mismatch")
                }

                phaseRef.set(Phase.REPLACE_FINAL)

                replaceFinalAtomic(tmpFile, finalFile)
                runCatching { tmpMetaFile.delete() }

                val outLen = finalFile.length()
                created.emitProgress(outLen, expectedLen ?: outLen)

                phaseRef.set(Phase.DONE_OK)
                deferred.complete(Result.success(finalFile))

            } catch (ce: CancellationException) {
                AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' cancelled type=${ce::class.java.simpleName}")
                if (forceFresh) {
                    runCatching { tmpFile.delete() }
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
                deferred.complete(Result.failure(IOException("Canceled", ce)))

            } catch (ie: InterruptedIOException) {
                AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' interrupted type=${ie::class.java.simpleName}")
                if (forceFresh) {
                    runCatching { tmpFile.delete() }
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
                deferred.complete(Result.failure(IOException("Canceled", ie)))

            } catch (te: TimeoutCancellationException) {
                AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' timeout after ${timeoutMs}ms")
                if (forceFresh) {
                    runCatching { tmpFile.delete() }
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                }
                deferred.complete(Result.failure(IOException("Timeout", te)))

            } catch (t: Throwable) {
                val safe = userFriendlyMessage(t)
                AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' error: $safe type=${t::class.java.simpleName}")

                val keepPartial = !forceFresh && shouldKeepPartialForResume(t)
                runCatching { tmpFile.delete() }

                if (!keepPartial) {
                    runCatching { tmpPartFile.delete() }
                    runCatching { tmpMetaFile.delete() }
                } else {
                    AppLog.w(TAG, "run=$runId gen=$gen key='$stableKey' keeping partial for resume")
                }

                deferred.complete(Result.failure(IOException(safe, t)))

            } finally {
                watchdogJob.cancel()
                // Remove the flight record if still current.
                flights[stableKey]?.let { cur ->
                    if (cur.generation == gen && cur.deferred === deferred) {
                        flights.remove(stableKey, cur)
                    }
                }
                ownerJob.cancel()
            }
        }

        return Operation(handle = ownerHandle, deferred = deferred)
    }

    /**
     * Targeted cancel: cancels only this handle's participation.
     * Underlying job is cancelled only when no handles remain.
     */
    fun cancel(handle: Handle, reason: String = "cancel") {
        val f = flights[handle.key]
        if (f == null) {
            AppLog.d(TAG, "cancel: no flight hid=${handle.handleId} reason='${sanitizeLabel(reason)}'")
            return
        }
        if (f.generation != handle.generation) {
            AppLog.d(TAG, "cancel: stale handle hid=${handle.handleId} activeGen=${f.generation} reason='${sanitizeLabel(reason)}'")
            return
        }

        // Remove progress listener first.
        f.removeListener(handle.handleId)

        val remaining = f.activeHandles.decrementAndGet().coerceAtLeast(0)
        AppLog.d(
            TAG,
            "cancel: hid=${handle.handleId} run=${handle.runId} gen=${handle.generation} key='${handle.key}' reason='${sanitizeLabel(reason)}' remaining=$remaining phase=${f.phaseRef.get()}"
        )

        if (remaining == 0) {
            f.ownerJob.cancel(CancellationException("canceled"))
        }
    }

    // ---------------------------------------------------------------------
    // InFlight (internal)
    // ---------------------------------------------------------------------

    private class InFlight(
        val key: String,
        val runId: Long,
        val generation: Long,
        val deferred: CompletableDeferred<Result<File>>,
        val ownerJob: Job,
        val phaseRef: AtomicReference<Phase>,
        val lastBeatMs: AtomicLong,
    ) {
        val activeHandles = java.util.concurrent.atomic.AtomicInteger(0)
        private val listeners = ConcurrentHashMap<Long, MainThreadProgress>()

        fun newHandle(onProgress: (Long, Long?) -> Unit): Handle {
            val hid = nextHandleId.getAndIncrement()
            activeHandles.incrementAndGet()

            listeners[hid] = MainThreadProgress(
                onProgress = onProgress,
                lastBeatMs = lastBeatMs,
                minIntervalMs = PROGRESS_MIN_INTERVAL_MS,
                minDeltaBytes = PROGRESS_MIN_DELTA_BYTES
            )

            return Handle(
                key = key,
                handleId = hid,
                generation = generation,
                runId = runId,
            )
        }

        fun removeListener(handleId: Long) {
            listeners.remove(handleId)
        }

        fun emitProgress(downloaded: Long, total: Long?) {
            // Beat must be updated on every progress signal (independent of UI throttling).
            lastBeatMs.set(SystemClock.elapsedRealtime())
            for (p in listeners.values) {
                p.emit(downloaded, total)
            }
        }
    }

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

    private fun defaultKey(modelUrl: String, fileName: String): String {
        return "heavy|" + modelUrl.trim() + "|" + fileName.trim()
    }

    // ---------------------------------------------------------------------
    // Stall watchdog (observability)
    // ---------------------------------------------------------------------

    private fun startStallWatchdog(
        runId: Long,
        gen: Long,
        key: String,
        phaseRef: AtomicReference<Phase>,
        lastBeatMs: AtomicLong,
        ownerJob: Job,
    ): Job {
        return scope.launch(Dispatchers.Default) {
            var warnedAtMs = 0L
            while (ownerJob.isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                val phase = phaseRef.get()
                val now = SystemClock.elapsedRealtime()
                val since = now - lastBeatMs.get()

                val downloading = (phase == Phase.DOWNLOAD)
                val stalled = downloading && since >= STALL_THRESHOLD_MS

                if (stalled) {
                    // Avoid spamming; warn at most once per threshold window.
                    if (warnedAtMs == 0L || (now - warnedAtMs) >= STALL_THRESHOLD_MS) {
                        warnedAtMs = now
                        AppLog.w(TAG, "run=$runId gen=$gen key='$key' stall phase=$phase noBeatFor=${since}ms")
                    }
                } else {
                    warnedAtMs = 0L
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Resume keep/discard policy
    // ---------------------------------------------------------------------

    /**
     * Decide whether we should keep the partial file on failure to allow resume.
     */
    private fun shouldKeepPartialForResume(t: Throwable): Boolean {
        if (t is InterruptedIOException) return true
        if (t is TimeoutCancellationException) return true
        if (t is CancellationException) return true

        val msg = (t.message ?: "").lowercase()

        // Integrity-ish failures -> discard.
        if ("size mismatch" in msg) return false
        if ("downloaded size mismatch" in msg) return false
        if ("resume failed" in msg) return false
        if ("refused range" in msg) return false
        if ("416" in msg) return false

        // IO failures often transient -> keep.
        return t is IOException
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

    private fun headContentLength(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl
        var redirects = 0

        while (true) {
            if (redirects++ >= MAX_REDIRECTS) return null

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
                    continue
                }

                if (code !in 200..299) return null

                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }
            } finally {
                conn.disconnect()
            }
        }
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
            val existingLen = partFile.takeIf { it.exists() }?.length() ?: 0L

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
                if (existingLen > 0L) {
                    setRequestProperty("Range", "bytes=$existingLen-")
                }
            }

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    if (redirects++ >= MAX_REDIRECTS) throw IOException("Too many redirects")
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect with no Location header")
                    current = URL(u, loc).toString()
                    continue
                }

                if (code == 416) {
                    runCatching { partFile.delete() }
                    throw IOException("Resume failed (server refused range)")
                }

                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }

                // If we requested Range but server returned 200, treat it as full restart (truncate).
                val resumeHonored = (existingLen > 0L && code == 206)
                val already = if (resumeHonored) existingLen else 0L

                val totalFromContentRange = parseTotalFromContentRange(conn.getHeaderField("Content-Range"))
                val contentLen = conn.contentLengthLong.takeIf { it > 0L }

                val total = expectedTotalLen ?: totalFromContentRange ?: run {
                    if (code == 206 && contentLen != null) already + contentLen else contentLen
                }

                partFile.parentFile?.mkdirs()

                val append = resumeHonored
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
                        throw IOException("Downloaded size mismatch")
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
    // Progress marshalling + throttling
    // ---------------------------------------------------------------------

    private class MainThreadProgress(
        private val onProgress: (Long, Long?) -> Unit,
        private val lastBeatMs: AtomicLong,
        private val minIntervalMs: Long,
        private val minDeltaBytes: Long,
    ) {
        private val mainHandler: Handler? =
            if (Looper.getMainLooper() != null) Handler(Looper.getMainLooper()) else null

        private val lastUiAt = AtomicLong(0L)
        private val lastUiBytes = AtomicLong(0L)

        /**
         * Emit progress on the main thread with throttling.
         *
         * Important:
         * - Beat update must NOT be throttled (stall watchdog depends on it).
         */
        fun emit(downloaded: Long, total: Long?) {
            lastBeatMs.set(SystemClock.elapsedRealtime())

            val now = SystemClock.elapsedRealtime()
            val prevAt = lastUiAt.get()
            val prevB = lastUiBytes.get()

            val timeOk = (now - prevAt) >= minIntervalMs
            val bytesOk = (downloaded - prevB) >= minDeltaBytes

            if (!(timeOk || bytesOk || prevAt == 0L)) return

            lastUiAt.set(now)
            lastUiBytes.set(downloaded)

            val h = mainHandler
            if (h == null || Looper.myLooper() == Looper.getMainLooper()) {
                runCatching { onProgress(downloaded, total) }
                return
            }
            h.post { runCatching { onProgress(downloaded, total) } }
        }
    }

    // ---------------------------------------------------------------------
    // Error messaging (safe)
    // ---------------------------------------------------------------------

    private fun userFriendlyMessage(t: Throwable): String {
        // Never return exception.message directly (it may contain URLs).
        val klass = t::class.java.simpleName.ifBlank { "Error" }
        val raw = (t.message ?: "").lowercase()

        return when {
            "unauthorized" in raw || "401" in raw -> "Authorization failed (HF token?)"
            "forbidden" in raw || "403" in raw -> "Access denied (token/permissions?)"
            "timeout" in raw -> "Network timeout"
            "space" in raw -> "Not enough free space"
            "content-range" in raw || "416" in raw || "refused range" in raw -> "Resume failed (server refused range)"
            "unknown host" in raw || "dns" in raw -> "Unknown host (check connectivity)"
            "http 4" in raw || "http 5" in raw -> "HTTP error"
            else -> klass
        }
    }

    // ---------------------------------------------------------------------
    // Replacement
    // ---------------------------------------------------------------------

    private fun replaceFinalAtomic(tmp: File, dst: File) {
        if (!tmp.exists() || tmp.length() <= 0L) {
            throw IOException("Temp file missing or empty")
        }

        dst.parentFile?.mkdirs()

        if (dst.exists() && !dst.delete()) {
            AppLog.w(TAG, "replaceFinalAtomic: failed to delete existing destination")
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
            throw IOException("replaceFinalAtomic: destination invalid after copy")
        }
    }

    private fun sanitizeLabel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"
        val safe = buildString {
            for (c in trimmed) {
                if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') append(c)
                if (length >= 32) break
            }
        }
        return if (safe.isNotBlank()) safe else "unknown"
    }
}