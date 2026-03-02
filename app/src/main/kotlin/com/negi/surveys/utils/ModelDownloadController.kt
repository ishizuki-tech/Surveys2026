package com.negi.surveys.utils

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.logging.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controller responsible for ensuring a model file exists locally.
 *
 * Notes:
 * - Uses a process-scoped controller in typical usage.
 * - Exposes [state] as a StateFlow to drive Compose/UI.
 * - This controller is cancellation-safe and avoids excessive UI updates.
 *
 * Threading:
 * - Work runs on Dispatchers.Default but delegates heavy IO/network to HeavyInitializer (which uses Dispatchers.IO).
 * - StateFlow updates are safe from any thread, but callers typically observe on Main.
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

    // Supervisor scope so child failures do not cancel the whole scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Prevent concurrent duplicate starts. Unlike the old behavior, we allow re-run after completion
    // (success/failure/cancel), but still prohibit overlapping active jobs.
    private val startedGuard = AtomicBoolean(false)

    private val jobRef = AtomicReference<Job?>(null)

    private val lastUiAtMs = AtomicLong(0L)
    private val lastUiBytes = AtomicLong(0L)

    private val lastProgressBytes = AtomicLong(0L)

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<ModelState>(ModelState.Idle(elapsedMs = 0L))
    val state: kotlinx.coroutines.flow.StateFlow<ModelState> = _state.asStateFlow()

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

        data class Failed(
            val message: String,
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : ModelState

        data class Cancelled(
            val startedAtMs: Long?,
            override val elapsedMs: Long,
        ) : ModelState
    }

    /**
     * Ensures model exactly once per *active job* (single-flight within this controller).
     *
     * Behavior:
     * - If already running: ignored.
     * - If a previous run completed (success/failure/cancel): allowed to run again.
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
                elapsedMs = 0L
            )
            AppLog.w(TAG, "ensureModelOnce: NotConfigured (urlBlank=${url.isBlank()} nameBlank=${name.isBlank()})")
            return
        }

        // If a job is active, do not start another.
        val existing = jobRef.get()
        if (existing != null && existing.isActive) {
            AppLog.d(TAG, "ensureModelOnce: ignored (job active) reason='$reason'")
            return
        }

        // Use a guard to avoid racing starts.
        if (!startedGuard.compareAndSet(false, true)) {
            AppLog.d(TAG, "ensureModelOnce: ignored (start guard) reason='$reason'")
            return
        }

        val dst = resolveSafeFileUnder(app.filesDir, name)
        val startedAt = SystemClock.elapsedRealtime()

        _state.value = ModelState.Checking(
            file = dst,
            startedAtMs = startedAt,
            elapsedMs = 0L
        )

        lastUiAtMs.set(0L)
        lastUiBytes.set(0L)
        lastProgressBytes.set(0L)

        AppLog.d(
            TAG,
            "ensureModelOnce: begin pid=${Process.myPid()} reason='$reason' " +
                    "urlHost='${safeHost(url)}' fileName='${dst.name}' " +
                    "uiThrottleMs=$uiThrottleMs uiMinDeltaBytes=$uiMinDeltaBytes timeoutMs=$timeoutMs forceFresh=$forceFresh"
        )

        val job = scope.launch(Dispatchers.Default) {
            try {
                // Fast-path: local file is already ready.
                if (!forceFresh && dst.exists() && dst.isFile && dst.length() > 0L) {
                    val end = SystemClock.elapsedRealtime()
                    _state.value = ModelState.Ready(
                        file = dst,
                        sizeBytes = dst.length(),
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt
                    )
                    AppLog.i(TAG, "ensureModelOnce: local ready size=${dst.length()}B elapsedMs=${end - startedAt}")
                    return@launch
                }

                val token = hfTokenProvider().takeIf { !it.isNullOrBlank() }

                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = url,
                    hfToken = token,
                    fileName = name,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh,
                    onProgress = { downloaded, total ->
                        val now = SystemClock.elapsedRealtime()

                        // Ensure monotonic downloaded bytes (defensive against strange callbacks).
                        val prev = lastProgressBytes.get()
                        if (downloaded < prev) return@ensureInitialized
                        lastProgressBytes.set(downloaded)

                        if (!shouldEmitUi(downloadedBytes = downloaded, nowMs = now)) {
                            return@ensureInitialized
                        }

                        _state.value = ModelState.Downloading(
                            file = dst,
                            startedAtMs = startedAt,
                            downloaded = downloaded,
                            total = total,
                            elapsedMs = now - startedAt
                        )
                    }
                )

                val end = SystemClock.elapsedRealtime()

                result.onSuccess { file ->
                    _state.value = ModelState.Ready(
                        file = file,
                        sizeBytes = file.length(),
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt
                    )
                    AppLog.i(TAG, "ensureModelOnce: success elapsedMs=${end - startedAt} size=${file.length()}B")
                }.onFailure { t ->
                    _state.value = ModelState.Failed(
                        message = "${t.javaClass.simpleName}(${t.message})",
                        startedAtMs = startedAt,
                        elapsedMs = end - startedAt
                    )
                    AppLog.w(TAG, "ensureModelOnce: failed elapsedMs=${end - startedAt} (call resetForRetry to rerun)", t)
                }
            } catch (ce: CancellationException) {
                val end = SystemClock.elapsedRealtime()
                _state.value = ModelState.Cancelled(
                    startedAtMs = startedAt,
                    elapsedMs = end - startedAt
                )
                AppLog.w(TAG, "ensureModelOnce: cancelled elapsedMs=${end - startedAt}", ce)
                throw ce
            } catch (t: Throwable) {
                val end = SystemClock.elapsedRealtime()
                _state.value = ModelState.Failed(
                    message = "${t.javaClass.simpleName}(${t.message})",
                    startedAtMs = startedAt,
                    elapsedMs = end - startedAt
                )
                AppLog.w(TAG, "ensureModelOnce: crashed", t)
            } finally {
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
        AppLog.w(TAG, "cancel: reason='$reason'")
        HeavyInitializer.cancel()
        jobRef.getAndSet(null)?.cancel(CancellationException("cancel:$reason"))
        startedGuard.set(false)
    }

    fun resetForRetry(reason: String = "resetForRetry") {
        AppLog.w(TAG, "resetForRetry: reason='$reason'")
        cancel(reason = "resetForRetry:$reason")
        HeavyInitializer.resetForDebug()
        _state.value = ModelState.Idle(elapsedMs = 0L)
    }

    /**
     * Releases controller-owned resources.
     *
     * Note:
     * - This controller may be replaced when "effective config" changes.
     * - Calling close() prevents background scope leaks.
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

    companion object {
        private const val TAG = "ModelDownloader"
    }
}