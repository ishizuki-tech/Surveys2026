/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Model Download Controller)
 *  ---------------------------------------------------------------------
 *  File: ModelDownloadController.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys.utils

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.AppProcessServices
import com.negi.surveys.logging.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controller responsible for ensuring a model file exists locally.
 *
 * Production goals:
 * - At most one active download job per controller instance.
 * - Targeted cancellation via HeavyInitializer.Handle.
 * - UI updates are throttled to avoid recomposition storms.
 *
 * Privacy / Safety:
 * - Never expose raw URLs, tokens, file paths, or exception messages in state/logs.
 */
class ModelDownloadController(
    appContext: Context,
    private val modelUrl: String?,
    private val fileName: String?,
    private val hfTokenProvider: () -> String? = { null },
    private val uiThrottleMs: Long = 200L,
    private val uiMinDeltaBytes: Long = 512L * 1024L,
) : AutoCloseable {

    private val app = appContext.applicationContext

    /** Supervisor scope so child failures do not cancel the whole scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Prevent overlapping active jobs. Allows re-run after completion. */
    private val startedGuard = AtomicBoolean(false)

    private val jobRef = AtomicReference<Job?>(null)

    private val lastUiAtMs = AtomicLong(0L)
    private val lastUiBytes = AtomicLong(0L)
    private val lastProgressBytes = AtomicLong(0L)

    /** Handle returned by HeavyInitializer.begin() for targeted cancellation. */
    private val handleRef = AtomicReference<HeavyInitializer.Handle?>(null)

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle(elapsedMs = 0L))
    val state: StateFlow<ModelState> = _state.asStateFlow()

    sealed interface ModelState {
        val elapsedMs: Long

        data class Idle(override val elapsedMs: Long) : ModelState

        data class NotConfigured(
            val reason: String,
            override val elapsedMs: Long,
        ) : ModelState

        data class Checking(
            val file: File,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : ModelState

        data class Downloading(
            val file: File,
            val startedAtMs: Long,
            val downloaded: Long,
            val total: Long?,
            override val elapsedMs: Long,
        ) : ModelState

        data class Ready(
            val file: File,
            val sizeBytes: Long,
            val startedAtMs: Long,
            override val elapsedMs: Long,
        ) : ModelState

        /**
         * Failure message MUST be safe:
         * - Do not include exception.message (may contain URLs / secrets).
         */
        data class Failed(
            val safeReason: String,
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : ModelState

        data class Cancelled(
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : ModelState
    }

    /**
     * Ensures the model file, starting at most one active job per controller.
     *
     * Behavior:
     * - If already running: ignored.
     * - If a previous run completed: allowed to run again.
     */
    fun ensureModelOnce(
        timeoutMs: Long,
        forceFresh: Boolean = false,
        reason: String = "ensureModelOnce",
    ) {
        val url = modelUrl?.trim().orEmpty()
        val name = fileName?.trim().orEmpty()

        if (url.isBlank() || name.isBlank()) {
            _state.value = ModelState.NotConfigured(
                reason = "modelUrl/fileName is blank",
                elapsedMs = 0L,
            )
            AppLog.w(TAG, "ensureModelOnce: NotConfigured (urlBlank=${url.isBlank()} nameBlank=${name.isBlank()})")
            return
        }

        // If a job is active, do not start another.
        val existing = jobRef.get()
        if (existing != null && existing.isActive) {
            AppLog.d(TAG, "ensureModelOnce: ignored (job active) reason='${sanitizeLabel(reason)}'")
            return
        }

        // Guard to avoid racing starts.
        if (!startedGuard.compareAndSet(false, true)) {
            AppLog.d(TAG, "ensureModelOnce: ignored (start guard) reason='${sanitizeLabel(reason)}'")
            return
        }

        val dst = resolveSafeFileUnder(app.filesDir, name)
        val startedAt = SystemClock.elapsedRealtime()

        _state.value = ModelState.Checking(
            file = dst,
            startedAtMs = startedAt,
            elapsedMs = 0L,
        )

        lastUiAtMs.set(0L)
        lastUiBytes.set(0L)
        lastProgressBytes.set(0L)

        // Clear old handle (defensive).
        handleRef.set(null)

        AppLog.d(
            TAG,
            "ensureModelOnce: begin pid=${Process.myPid()} reason='${sanitizeLabel(reason)}' " +
                    "urlHost='${safeHost(url)}' fileName='${dst.name}' " +
                    "uiThrottleMs=$uiThrottleMs uiMinDeltaBytes=$uiMinDeltaBytes timeoutMs=$timeoutMs forceFresh=$forceFresh",
        )

        val job = scope.launch(Dispatchers.Default) {
            try {
                // Fast-path: local file already ready under the shared process-wide validation rules.
                if (!forceFresh && AppProcessServices.isUsableLocalModelFile(dst)) {
                    val end = SystemClock.elapsedRealtime()
                    _state.value = ModelState.Ready(
                        file = dst,
                        sizeBytes = dst.length(),
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt,
                    )
                    AppLog.i(TAG, "ensureModelOnce: local ready size=${dst.length()}B elapsedMs=${end - startedAt}")
                    return@launch
                }

                if (!forceFresh && dst.exists() && dst.isFile) {
                    AppLog.w(
                        TAG,
                        "ensureModelOnce: local file present but unusable; redownload required size=${safeLength(dst)}B",
                    )
                }

                val token = hfTokenProvider().takeIf { !it.isNullOrBlank() }

                val op = HeavyInitializer.begin(
                    context = app,
                    modelUrl = url,
                    hfToken = token,
                    fileName = name,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh,
                    onProgress = progress@{ downloaded, total ->
                        val now = SystemClock.elapsedRealtime()

                        // Ensure monotonic downloaded bytes (defensive).
                        val prev = lastProgressBytes.get()
                        if (downloaded < prev) return@progress
                        lastProgressBytes.set(downloaded)

                        if (!shouldEmitUi(downloadedBytes = downloaded, nowMs = now)) return@progress

                        _state.value = ModelState.Downloading(
                            file = dst,
                            startedAtMs = startedAt,
                            downloaded = downloaded,
                            total = total,
                            elapsedMs = now - startedAt,
                        )
                    },
                )

                handleRef.set(op.handle)

                val result = op.deferred.await()
                val end = SystemClock.elapsedRealtime()

                result.onSuccess { file ->
                    if (!AppProcessServices.isUsableLocalModelFile(file)) {
                        _state.value = ModelState.Failed(
                            safeReason = DOWNLOADED_FILE_NOT_USABLE,
                            startedAtMs = startedAt,
                            elapsedMs = end - startedAt,
                        )
                        AppLog.w(
                            TAG,
                            "ensureModelOnce: downloaded file unusable elapsedMs=${end - startedAt} size=${safeLength(file)}B",
                        )
                        return@onSuccess
                    }

                    _state.value = ModelState.Ready(
                        file = file,
                        sizeBytes = file.length(),
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt,
                    )
                    AppLog.i(TAG, "ensureModelOnce: success elapsedMs=${end - startedAt} size=${file.length()}B")
                }.onFailure { t ->
                    _state.value = ModelState.Failed(
                        safeReason = safeFailureReason(t),
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt,
                    )
                    // Do not log exception.message; it may contain sensitive info.
                    AppLog.w(TAG, "ensureModelOnce: failed elapsedMs=${end - startedAt} type=${t::class.java.simpleName}")
                }
            } catch (ce: CancellationException) {
                val end = SystemClock.elapsedRealtime()
                _state.value = ModelState.Cancelled(
                    startedAtMs = startedAt,
                    elapsedMs = end - startedAt,
                )
                AppLog.w(TAG, "ensureModelOnce: cancelled elapsedMs=${end - startedAt} type=${ce::class.java.simpleName}")
                throw ce
            } catch (t: Throwable) {
                val end = SystemClock.elapsedRealtime()
                _state.value = ModelState.Failed(
                    safeReason = safeFailureReason(t),
                    startedAtMs = startedAt,
                    elapsedMs = end - startedAt,
                )
                AppLog.w(TAG, "ensureModelOnce: crashed type=${t::class.java.simpleName}")
            } finally {
                handleRef.set(null)
                startedGuard.set(false)
            }
        }

        jobRef.set(job)
    }

    private fun shouldEmitUi(downloadedBytes: Long, nowMs: Long): Boolean {
        val lastAt = lastUiAtMs.get()
        val lastB = lastUiBytes.get()

        val timeOk = (nowMs - lastAt) >= uiThrottleMs
        val bytesOk = (downloadedBytes - lastB) >= uiMinDeltaBytes

        if (timeOk || bytesOk || lastAt == 0L) {
            lastUiAtMs.set(nowMs)
            lastUiBytes.set(downloadedBytes)
            return true
        }
        return false
    }

    fun cancel(reason: String = "cancel") {
        val activeJob = jobRef.get()
        val handle = handleRef.getAndSet(null)

        // Keep logs quiet when nothing is active.
        val isActuallyActive = (activeJob != null && activeJob.isActive) || handle != null
        if (!isActuallyActive) return

        AppLog.w(TAG, "cancel: reason='${sanitizeLabel(reason)}'")

        handle?.let {
            HeavyInitializer.cancel(it, reason = sanitizeLabel(reason))
        }

        jobRef.getAndSet(null)?.cancel(CancellationException("cancel:${sanitizeLabel(reason)}"))
        startedGuard.set(false)
    }

    fun resetForRetry(reason: String = "resetForRetry") {
        AppLog.w(TAG, "resetForRetry: reason='${sanitizeLabel(reason)}'")
        cancel(reason = "resetForRetry:${sanitizeLabel(reason)}")
        _state.value = ModelState.Idle(elapsedMs = 0L)
    }

    /**
     * Releases controller-owned resources.
     *
     * Notes:
     * - Calling close() prevents background scope leaks.
     * - close() is quiet if no active work exists.
     */
    override fun close() {
        runCatching { cancel(reason = "close") }
        runCatching { scope.coroutineContext[Job]?.cancel(CancellationException("close")) }
    }

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

    private fun safeHost(url: String): String {
        return runCatching { java.net.URL(url).host }.getOrNull()?.ifBlank { "unknown" } ?: "unknown"
    }

    private fun safeFailureReason(t: Throwable): String {
        // Do not use t.message here. Keep it intentionally generic.
        return t::class.java.simpleName.ifBlank { "Error" }
    }

    private fun safeLength(file: File?): Long {
        return runCatching { file?.length() ?: -1L }.getOrDefault(-1L)
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
        return safe.ifBlank { "unknown" }
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val DOWNLOADED_FILE_NOT_USABLE = "DownloadedFileNotUsable"
    }
}
