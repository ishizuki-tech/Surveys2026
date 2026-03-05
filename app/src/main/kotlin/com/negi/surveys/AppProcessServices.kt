/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Process Services)
 *  ---------------------------------------------------------------------
 *  File: AppProcessServices.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.content.Context
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.chat.FakeSlmRepository
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.slm.SlmRepository
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.SLMWarmupEngine
import com.negi.surveys.warmup.WarmupController
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped service holder.
 *
 * Why:
 * - Activity/Compose recomposition can recreate objects.
 * - Repository / warmup / model-download controllers should be stable per process.
 *
 * Privacy:
 * - Never log tokens, URLs, file names, or any raw user/model content.
 * - Even exception messages may contain sensitive info; do not log exception.message.
 *
 * Thread-safety:
 * - Creation is guarded to prevent duplicate heavy instances under contention.
 * - "Swap then close" for replaceable services to keep callers fast.
 *
 * Key change:
 * - WarmupController.Dependencies is removed.
 * - Warmup is driven by explicit WarmupController.Inputs injected via updateWarmupInputs().
 */
object AppProcessServices {

    private const val TAG = "AppProcessServices"
    private const val TAG_WARMUP_LOG = "WarmupController"

    enum class RepoMode {
        ON_DEVICE,
        FAKE,
    }

    // ---------------------------------------------------------------------
    // Repository (process scoped)
    // ---------------------------------------------------------------------

    private val repoRef = AtomicReference<ChatValidation.RepositoryI?>(null)
    private val repoModeRef = AtomicReference<RepoMode?>(null)
    private val repoLock = Any()

    fun repository(
        context: Context,
        mode: RepoMode,
    ): ChatValidation.RepositoryI {
        val appCtx = context.applicationContext

        val cur = repoRef.get()
        val curMode = repoModeRef.get()
        if (cur != null && curMode == mode) return cur

        var toClose: Any? = null
        var createdNow: ChatValidation.RepositoryI? = null

        val installed: ChatValidation.RepositoryI =
            synchronized(repoLock) {
                val insideCur = repoRef.get()
                val insideMode = repoModeRef.get()
                if (insideCur != null && insideMode == mode) return insideCur

                val created: ChatValidation.RepositoryI =
                    when (mode) {
                        RepoMode.ON_DEVICE -> {
                            // IMPORTANT: Avoid logging raw model outputs in production.
                            SlmRepository(
                                context = appCtx,
                                enableTwoStepEval = true,
                            )
                        }
                        RepoMode.FAKE -> {
                            FakeSlmRepository()
                        }
                    }

                toClose = repoRef.get()
                createdNow = created

                repoRef.set(created)
                repoModeRef.set(mode)

                created
            }

        // Close outside lock (best-effort).
        toClose?.let { closeIfSupportedSafely(it) }

        createdNow?.let { created ->
            SafeLog.i(TAG, "repository: (re)created mode=$mode type=${created.javaClass.simpleName}")
        }

        return installed
    }

    // ---------------------------------------------------------------------
    // Warmup Controller (process scoped)
    // ---------------------------------------------------------------------

    private val warmupRef = AtomicReference<WarmupController?>(null)
    private val warmupModeRef = AtomicReference<RepoMode?>(null)
    private val warmupLock = Any()

    // External scope for warmup engine work (never use Main).
    private val warmupEngineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Cached inputs for ON_DEVICE warmup.
     *
     * Privacy:
     * - Never log file paths.
     */
    private val warmupInputsRef = AtomicReference<WarmupController.Inputs?>(null)

    fun warmupController(
        context: Context,
        mode: RepoMode,
    ): WarmupController {
        val appCtx = context.applicationContext

        val cur = warmupRef.get()
        val curMode = warmupModeRef.get()
        if (cur != null && curMode == mode) return cur

        var toClose: Any? = null
        var createdNow: WarmupController? = null

        val installed: WarmupController =
            synchronized(warmupLock) {
                val insideCur = warmupRef.get()
                val insideMode = warmupModeRef.get()
                if (insideCur != null && insideMode == mode) return insideCur

                val created = createWarmupControllerBestEffort(appCtx, mode)

                toClose = warmupRef.get()
                createdNow = created

                warmupRef.set(created)
                warmupModeRef.set(mode)

                // Apply cached inputs if we have them (ON_DEVICE only).
                if (mode == RepoMode.ON_DEVICE) {
                    created.updateInputs(warmupInputsRef.get())
                } else {
                    created.updateInputs(null)
                }

                created
            }

        // Close outside lock (best-effort).
        toClose?.let { closeIfSupportedSafely(it) }

        createdNow?.let { created ->
            SafeLog.i(TAG, "warmupController: (re)created mode=$mode type=${created.javaClass.simpleName}")
        }

        return installed
    }

    /**
     * Updates warmup inputs explicitly.
     *
     * Recommended usage:
     * - Call this from SurveyAppRoot once modelFile + warmup-capable repo are resolved.
     *
     * Privacy:
     * - Never logs file path / file name.
     */
    fun updateWarmupInputs(inputs: WarmupController.Inputs?) {
        warmupInputsRef.set(inputs)

        // Update existing controller if it exists and is ON_DEVICE.
        val controller = warmupRef.get()
        val mode = warmupModeRef.get()
        if (controller != null && mode == RepoMode.ON_DEVICE) {
            controller.updateInputs(inputs)
        }

        SafeLog.i(TAG, "warmupInputs: updated hasInputs=${inputs != null}")
    }

    /**
     * Convenience helper:
     * - Builds Inputs using the current process-scoped repository IF it implements WarmupCapableRepository.
     * - Does NOT use reflection guessing. If not supported, it installs null inputs.
     *
     * Notes:
     * - This is best-effort; recommended is calling updateWarmupInputs() with an explicit warmup repo.
     */
    fun updateWarmupInputsBestEffort(
        context: Context,
        mode: RepoMode,
        modelFile: File?,
        options: WarmupController.Options = WarmupController.Options(),
    ) {
        if (mode != RepoMode.ON_DEVICE) {
            updateWarmupInputs(null)
            return
        }

        val fileOk = modelFile != null && modelFile.exists() && modelFile.isFile && modelFile.length() > 0L
        if (!fileOk) {
            SafeLog.w(TAG, "warmupInputs: file not ready; installing null inputs")
            updateWarmupInputs(null)
            return
        }

        val repo = repository(context, mode)
        val warmupRepo = repo as? WarmupController.WarmupCapableRepository
        if (warmupRepo == null) {
            SafeLog.w(TAG, "warmupInputs: repo is not WarmupCapableRepository type=${repo.javaClass.simpleName}")
            updateWarmupInputs(null)
            return
        }

        updateWarmupInputs(
            WarmupController.Inputs(
                file = modelFile,
                repository = warmupRepo,
                options = options,
            ),
        )
    }

    /**
     * Creates WarmupController in a way that:
     * - Never blocks on environment resolution at creation time.
     * - Never throws.
     *
     * Strategy:
     * - FAKE mode uses Fake engine immediately.
     * - ON_DEVICE mode uses SLMWarmupEngine that waits for Inputs via updateInputs().
     */
    private fun createWarmupControllerBestEffort(appCtx: Context, mode: RepoMode): WarmupController {
        val warmupLogger: (String) -> Unit = { msg ->
            // Sanitize any engine message to avoid leaking URLs/paths/tokens/filenames.
            SafeLog.d(TAG_WARMUP_LOG, sanitizeWarmupLog(msg))
        }

        return when (mode) {
            RepoMode.FAKE -> {
                WarmupController.createFake(
                    context = appCtx,
                    logger = warmupLogger,
                )
            }

            RepoMode.ON_DEVICE -> {
                val engine: WarmupController.Engine =
                    SLMWarmupEngine(
                        logger = warmupLogger,
                        externalScope = warmupEngineScope,
                    )

                WarmupController.createDefault(
                    context = appCtx,
                    engine = engine,
                    logger = warmupLogger,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Model Downloader (process scoped)
    // ---------------------------------------------------------------------

    private val downloaderLock = Any()
    private var downloaderConfig: DownloaderConfig? = null
    private var downloader: ModelDownloadController? = null

    // Token can change independently; keep it dynamic.
    // IMPORTANT: Never log this token (PII/secret).
    private val hfTokenRef = AtomicReference<String?>(null)

    // Keep the last non-null spec so later callers can pass null without clobbering config.
    private val lastModelSpecRef = AtomicReference<ModelDownloadSpec?>(null)

    fun modelDownloader(context: Context, spec: ModelDownloadSpec?): ModelDownloadController {
        val appCtx = context.applicationContext

        val effectiveSpec =
            if (spec != null) {
                lastModelSpecRef.set(spec)
                spec
            } else {
                lastModelSpecRef.get()
            }

        hfTokenRef.set(effectiveSpec?.hfToken)

        val cfg =
            DownloaderConfig(
                modelUrl = effectiveSpec?.modelUrl,
                fileName = effectiveSpec?.fileName,
                uiThrottleMs = effectiveSpec?.uiThrottleMs ?: 200L,
                uiMinDeltaBytes = effectiveSpec?.uiMinDeltaBytes ?: (512L * 1024L),
            )

        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig
            if (cur != null && curCfg == cfg) return cur
        }

        val created =
            ModelDownloadController(
                appContext = appCtx,
                modelUrl = cfg.modelUrl,
                fileName = cfg.fileName,
                hfTokenProvider = { hfTokenRef.get() },
                uiThrottleMs = cfg.uiThrottleMs,
                uiMinDeltaBytes = cfg.uiMinDeltaBytes,
            )

        var toClose: Any? = null
        var installed: ModelDownloadController? = null

        synchronized(downloaderLock) {
            val cur = downloader
            val curCfg = downloaderConfig

            if (cur != null && curCfg == cfg) {
                toClose = created
                installed = cur
            } else {
                toClose = downloader
                downloader = created
                downloaderConfig = cfg
                installed = created

                SafeLog.i(
                    TAG,
                    "modelDownloader: (re)created controller for cfg=" +
                            "urlPresent=${cfg.modelUrl != null} fileNamePresent=${cfg.fileName != null} " +
                            "uiThrottleMs=${cfg.uiThrottleMs} uiMinDeltaBytes=${cfg.uiMinDeltaBytes}",
                )
            }
        }

        toClose?.let { closeIfSupportedSafely(it) }
        return requireNotNull(installed)
    }

    private data class DownloaderConfig(
        val modelUrl: String?,
        val fileName: String?,
        val uiThrottleMs: Long,
        val uiMinDeltaBytes: Long,
    )

    // ---------------------------------------------------------------------
    // Close helpers (best-effort, privacy-safe)
    // ---------------------------------------------------------------------

    private val closeMethodCache = ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()

    /**
     * Best-effort close helper.
     *
     * Rules:
     * - Never logs exception.message.
     * - Prefer type-safe close (AutoCloseable/Closeable).
     * - Reflection is last resort and cached per class.
     */
    private fun closeIfSupportedSafely(target: Any) {
        runCatching {
            when (target) {
                is AutoCloseable -> {
                    target.close()
                    return
                }
                is Closeable -> {
                    target.close()
                    return
                }
            }

            val cls = target.javaClass
            val cached = closeMethodCache[cls]
            val m =
                cached ?: run {
                    val found = cls.methods.firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                    closeMethodCache[cls] = found
                    found
                }

            m?.invoke(target)
        }.onFailure { t ->
            SafeLog.w(
                TAG,
                "closeIfSupportedSafely: failed errType=${t::class.java.simpleName} at=${t.toStackHint()}",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Log sanitization
    // ---------------------------------------------------------------------

    /**
     * Sanitizes a warmup-engine log message to avoid leaking:
     * - URLs, file paths, filenames, and token-like substrings.
     *
     * This is intentionally conservative: it may redact non-sensitive details,
     * but it strongly reduces accidental leakage from engine-side logs.
     */
    private fun sanitizeWarmupLog(raw: String): String {
        var s = raw

        // Redact common Hugging Face token pattern.
        s = s.replace(Regex("""\bhf_[A-Za-z0-9]{10,}\b"""), "<token>")

        // Redact URLs.
        s = s.replace(Regex("""https?://\S+"""), "<url>")

        // Redact Windows paths.
        s = s.replace(Regex("""[A-Za-z]:\\[^\s]+"""), "<path>")

        // Redact Unix-like absolute paths.
        s = s.replace(Regex("""(/[A-Za-z0-9._-]+)+"""), "<path>")

        // Redact common model/config filenames.
        s = s.replace(
            Regex(
                """\b[\w.-]+\.(bin|tflite|task|gguf|onnx|json|yaml|yml|zip)\b""",
                RegexOption.IGNORE_CASE,
            ),
            "<file>",
        )

        // Keep logs short and stable.
        val trimmed = s.trim()
        return if (trimmed.length <= 220) trimmed else trimmed.take(220) + "…"
    }

    /**
     * Returns a stable "stack hint" without printing exception.message.
     */
    private fun Throwable.toStackHint(): String {
        val e = this.stackTrace.firstOrNull()
        return if (e != null) "${e.className}.${e.methodName}:${e.lineNumber}" else "unknown"
    }
}