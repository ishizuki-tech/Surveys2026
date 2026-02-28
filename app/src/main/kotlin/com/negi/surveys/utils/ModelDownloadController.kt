package com.negi.surveys.utils

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.negi.surveys.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ModelDownloadController(
    appContext: Context,
    private val modelUrl: String?,
    private val fileName: String?,
    private val hfTokenProvider: () -> String? = { null },
    private val uiThrottleMs: Long = 200L,
    private val uiMinDeltaBytes: Long = 512L * 1024L,
) {

    private val app = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val requestedOnce = AtomicBoolean(false)
    private val jobRef = AtomicReference<Job?>(null)

    private val lastUiAtMs = AtomicLong(0L)
    private val lastUiBytes = AtomicLong(0L)

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

        if (!requestedOnce.compareAndSet(false, true)) {
            AppLog.d(TAG, "ensureModelOnce: ignored (already requested) reason='$reason'")
            return
        }

        val existing = jobRef.get()
        if (existing != null && existing.isActive) {
            AppLog.d(TAG, "ensureModelOnce: ignored (job active) reason='$reason'")
            return
        }

        val dst = File(app.filesDir, name)
        val startedAt = SystemClock.elapsedRealtime()

        _state.value = ModelState.Checking(
            file = dst,
            startedAtMs = startedAt,
            elapsedMs = 0L
        )

        AppLog.d(
            TAG,
            "ensureModelOnce: begin pid=${Process.myPid()} reason='$reason' url='$url' file='${dst.absolutePath}' " +
                    "uiThrottleMs=$uiThrottleMs uiMinDeltaBytes=$uiMinDeltaBytes timeoutMs=$timeoutMs"
        )

        lastUiAtMs.set(0L)
        lastUiBytes.set(0L)

        val ticker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(250L)
                val now = SystemClock.elapsedRealtime()
                val st = _state.value
                when (st) {
                    is ModelState.Checking -> _state.value = st.copy(elapsedMs = now - st.startedAtMs)
                    is ModelState.Downloading -> _state.value = st.copy(elapsedMs = now - st.startedAtMs)
                    else -> break
                }
            }
        }

        val job = scope.launch(Dispatchers.Default) {
            try {
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
                    AppLog.w(TAG, "ensureModelOnce: failed elapsedMs=${end - startedAt}", t)
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
                runCatching { ticker.cancel() }
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
    }

    fun resetForRetry(reason: String = "resetForRetry") {
        AppLog.w(TAG, "resetForRetry: reason='$reason'")
        cancel(reason = "resetForRetry:$reason")
        HeavyInitializer.resetForDebug()
        requestedOnce.set(false)
        _state.value = ModelState.Idle(elapsedMs = 0L)
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}