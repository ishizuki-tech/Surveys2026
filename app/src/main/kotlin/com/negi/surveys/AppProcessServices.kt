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
import com.negi.surveys.config.InstalledSurveyConfigStore
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.resolveModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.slm.SlmRepository
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.SLMWarmupEngine
import com.negi.surveys.warmup.WarmupController
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped service holder.
 *
 * Why:
 * - Activity / Compose recomposition can recreate objects.
 * - Repository / warmup / model-download controllers should stay stable per process.
 *
 * Privacy:
 * - Never log tokens, URLs, file names, or raw user/model content.
 * - Even exception messages may contain sensitive info, so do not log exception.message.
 *
 * Thread-safety:
 * - Creation is guarded to prevent duplicate heavy instances under contention.
 * - Uses "swap then close" for replaceable services to keep callers fast.
 *
 * Warmup notes:
 * - WarmupController.Dependencies is removed.
 * - Warmup is driven by explicit WarmupController.Inputs injected via updateWarmupInputs().
 */
object AppProcessServices {

    private const val TAG = "AppProcessServices"
    private const val FORCE_FAKE_REPO: Boolean = false
    private const val TAG_WARMUP_LOG = "WarmupController"

    enum class RepoMode {
        ON_DEVICE,
        FAKE,
    }

    /**
     * Returns the configured process-wide repository mode.
     *
     * Notes:
     * - Keep the decision centralized here so Application and startup orchestration
     *   do not drift apart.
     * - Replace the hardcoded switch with BuildConfig, SurveyConfig, or a dev menu
     *   when this branch needs runtime control.
     */
    fun configuredRepoMode(): RepoMode {
        return if (FORCE_FAKE_REPO) {
            RepoMode.FAKE
        } else {
            RepoMode.ON_DEVICE
        }
    }

    private const val DEFAULT_MIN_LOCAL_MODEL_BYTES: Long = 128L * 1024L * 1024L

    private val allowedModelExtensions = setOf(
        ".litertlm",
        ".bin",
        ".task",
        ".gguf",
    )

    /**
     * Resolves a local model file using configured artifact hints only.
     *
     * Resolution order:
     * - Explicit [spec].fileName when provided
     * - Explicit [spec].modelUrl basename when provided
     * - Installed config resolved model spec file name
     * - Installed config resolved model spec URL basename
     * - Installed config modelDefaults.defaultFileName
     *
     * Notes:
     * - Never scans arbitrary files.
     * - Never falls back to "largest file wins".
     * - Never logs file paths, file names, or URLs.
     * - URL basename fallback exists to tolerate configuration drift between
     *   remote artifact names and persisted local file names.
     */
    fun resolveConfiguredLocalModelFileOrNull(
        context: Context,
        spec: ModelDownloadSpec? = null,
    ): File? {
        val appCtx = context.applicationContext
        val installed = InstalledSurveyConfigStore.getOrNull()
        val installedSpec = installed?.resolveModelDownloadSpec()

        val candidateNames =
            collectConfiguredLocalModelNames(
                explicitSpec = spec,
                installedSpec = installedSpec,
                installedDefaultFileName = installed?.modelDefaults?.defaultFileName,
            )

        if (candidateNames.isEmpty()) return null

        val roots = candidateModelRoots(appCtx)
        for (safeName in candidateNames) {
            for (root in roots) {
                val candidate = File(root, safeName)
                if (isUsableLocalModelFile(candidate)) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Returns true only when the file looks like a usable local model artifact.
     *
     * Validation:
     * - file exists
     * - file is regular
     * - extension is explicitly allowed
     * - size meets the threshold for that extension
     */
    fun isUsableLocalModelFile(file: File?): Boolean {
        if (file == null) return false

        return runCatching {
            if (!file.exists() || !file.isFile) return@runCatching false

            val extension = allowedModelExtensionOrNull(file.name) ?: return@runCatching false
            val minimumBytes = minimumLocalModelBytesForExtension(extension)

            file.length() >= minimumBytes
        }.getOrDefault(false)
    }

    /**
     * Returns the repository-supported app-private roots for configured model files.
     *
     * Notes:
     * - Keep this aligned with the runtime repository path contract.
     * - Avoid probing alternate directories that the repository cannot open later.
     */
    fun candidateModelRoots(context: Context): List<File> {
        val appCtx = context.applicationContext
        return listOf(appCtx.filesDir)
    }

    /**
     * Collects sanitized local model file name hints from explicit and installed configuration.
     *
     * Why:
     * - Some configurations persist a local artifact using an explicit file name.
     * - Others implicitly reuse the remote artifact basename.
     * - Startup should tolerate either style without scanning unrelated files.
     */
    private fun collectConfiguredLocalModelNames(
        explicitSpec: ModelDownloadSpec?,
        installedSpec: ModelDownloadSpec?,
        installedDefaultFileName: String?,
    ): List<String> {
        val names = LinkedHashSet<String>()

        explicitSpec?.fileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(names::add)

        explicitSpec?.modelUrl
            ?.let(::sanitizeUrlBasename)
            ?.let(names::add)

        installedSpec?.fileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(names::add)

        installedSpec?.modelUrl
            ?.let(::sanitizeUrlBasename)
            ?.let(names::add)

        installedDefaultFileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(names::add)

        return names.toList()
    }

    /**
     * Sanitizes a basename extracted from a configured URL.
     *
     * Notes:
     * - Query strings and fragments are removed first.
     * - Only a simple leaf file name is accepted.
     */
    private fun sanitizeUrlBasename(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null

        val noFragment = raw.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val basename = noQuery.substringAfterLast('/')

        return sanitizeSimpleFileName(basename)
    }

    /**
     * Sanitizes a simple file name to avoid traversal-like inputs.
     */
    private fun sanitizeSimpleFileName(name: String?): String? {
        val n = name?.trim().orEmpty()
        if (n.isBlank()) return null
        if (n.contains('/')) return null
        if (n.contains('\\')) return null
        if (n.contains("..")) return null
        if (n.length > 200) return null
        return n
    }

    /**
     * Returns the allowed extension for the provided file name, or null when unsupported.
     */
    private fun allowedModelExtensionOrNull(fileName: String): String? {
        val lowerName = fileName.lowercase()
        return allowedModelExtensions.firstOrNull { lowerName.endsWith(it) }
    }

    /**
     * Returns the minimum usable size for the given allowed model extension.
     *
     * Notes:
     * - Keep the current behavior conservative.
     * - Centralizing the threshold here allows future extension-specific tuning
     *   without rewriting the file validation contract.
     */
    private fun minimumLocalModelBytesForExtension(extension: String): Long {
        return when (extension) {
            ".litertlm",
            ".bin",
            ".task",
            ".gguf" -> DEFAULT_MIN_LOCAL_MODEL_BYTES
            else -> DEFAULT_MIN_LOCAL_MODEL_BYTES
        }
    }

    // ---------------------------------------------------------------------
    // Repository (process scoped)
    // ---------------------------------------------------------------------

    private val repoRef = AtomicReference<ChatValidation.RepositoryI?>(null)
    private val repoModeRef = AtomicReference<RepoMode?>(null)
    private val repoLock = Any()
    private val repoDebugOverridesRef = AtomicReference<RepoDebugOverrides?>(null)

    data class RepoDebugOverrides(
        val enableSlmDebugLogs: Boolean? = null,
        val streamEvalOutputToClient: Boolean? = null,
    )

    private data class RepoDebugOptions(
        val enableSlmDebugLogs: Boolean,
        val streamEvalOutputToClient: Boolean,
    )

    /**
     * Installs optional debug overrides for repository creation.
     *
     * Notes:
     * - Intended for dev menu / debug panels.
     * - Rebuilds the repository so the new debug options take effect.
     */
    fun setRepoDebugOverrides(overrides: RepoDebugOverrides?) {
        repoDebugOverridesRef.set(overrides)
        clearRepositoryForRebuild()
    }

    private fun resolveRepoDebugOptions(): RepoDebugOptions {
        val cfg = InstalledSurveyConfigStore.getOrNull()
        val cfgDebug = cfg?.debug
        val overrides = repoDebugOverridesRef.get()

        val enableLogsRequested =
            overrides?.enableSlmDebugLogs
                ?: cfgDebug?.enableSlmDebugLogs
                ?: BuildConfig.DEBUG

        val streamEvalRequested =
            overrides?.streamEvalOutputToClient
                ?: cfgDebug?.streamEvalOutputToClient
                ?: false

        return RepoDebugOptions(
            enableSlmDebugLogs = BuildConfig.DEBUG && enableLogsRequested,
            streamEvalOutputToClient = BuildConfig.DEBUG && streamEvalRequested,
        )
    }

    private fun clearRepositoryForRebuild() {
        var toClose: Any? = null

        synchronized(repoLock) {
            toClose = repoRef.get()
            repoRef.set(null)
            repoModeRef.set(null)
        }

        toClose?.let { closeIfSupportedSafely(it) }
        SafeLog.i(TAG, "repository: cleared for rebuild")
    }

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
                            /**
                             * IMPORTANT:
                             * - Avoid logging raw model outputs in production.
                             * - Step-1 Assessment streaming stays OFF by default unless
                             *   an explicit config or dev override enables it.
                             */
                            val twoStepAssessmentEnabled = true
                            val debugOptions = resolveRepoDebugOptions()

                            val dbg =
                                SlmRepository.DebugConfig(
                                    enabled = debugOptions.enableSlmDebugLogs,
                                    streamEvalOutputToClient = debugOptions.streamEvalOutputToClient,
                                )

                            SafeLog.i(
                                TAG,
                                "repository: creating SlmRepository " +
                                        "twoStepAssessment=$twoStepAssessmentEnabled " +
                                        "streamAssessment=${dbg.streamEvalOutputToClient} " +
                                        "dbgLogs=${dbg.enabled}",
                            )

                            SlmRepository(
                                context = appCtx,
                                // Legacy named argument retained intentionally for compatibility.
                                enableTwoStepEval = twoStepAssessmentEnabled,
                                debug = dbg,
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

        toClose?.let { closeIfSupportedSafely(it) }

        createdNow?.let { created ->
            SafeLog.i(TAG, "repository: created mode=$mode type=${created.javaClass.simpleName}")

            if (mode == RepoMode.ON_DEVICE) {
                rebindWarmupInputsToCurrentRepoBestEffort(repo = created)
            }
        }

        return installed
    }

    // ---------------------------------------------------------------------
    // Warmup Controller (process scoped)
    // ---------------------------------------------------------------------

    private val warmupRef = AtomicReference<WarmupController?>(null)
    private val warmupModeRef = AtomicReference<RepoMode?>(null)
    private val warmupLock = Any()

    private val warmupEngineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Cached inputs for ON_DEVICE warmup.
     *
     * Privacy:
     * - Never log file paths.
     *
     * Notes:
     * - The cached file/options are treated as durable hints.
     * - The repository inside cached inputs may become stale after a repo swap,
     *   so controller restore should always try to rebind to the current repo.
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

                if (mode == RepoMode.ON_DEVICE) {
                    created.updateInputs(resolveWarmupInputsForCurrentRepoOrNull())
                } else {
                    created.updateInputs(null)
                }

                created
            }

        toClose?.let { closeIfSupportedSafely(it) }

        createdNow?.let { created ->
            SafeLog.i(
                TAG,
                "warmupController: (re)created mode=$mode type=${created.javaClass.simpleName}",
            )
        }

        return installed
    }

    /**
     * Updates warmup inputs explicitly.
     *
     * Recommended usage:
     * - Call this from orchestration code once modelFile + warmup-capable repo are resolved.
     *
     * Privacy:
     * - Never logs file path or file name.
     */
    fun updateWarmupInputs(inputs: WarmupController.Inputs?) {
        warmupInputsRef.set(inputs)

        val controller = warmupRef.get()
        val mode = warmupModeRef.get()
        if (controller != null && mode == RepoMode.ON_DEVICE) {
            controller.updateInputs(inputs)
        }

        SafeLog.i(TAG, "warmupInputs: updated hasInputs=${inputs != null}")
    }

    /**
     * Convenience helper:
     * - Builds Inputs using the current process-scoped repository if it implements
     *   WarmupCapableRepository.
     * - Does not use reflection guessing.
     *
     * Notes:
     * - This is best-effort.
     * - If requirements are not met, it keeps the previous inputs instead of clearing them.
     * - The recommended path is still calling updateWarmupInputs() with explicit inputs.
     */
    fun updateWarmupInputsBestEffort(
        context: Context,
        mode: RepoMode,
        modelFile: File?,
        options: WarmupController.Options = WarmupController.Options(),
    ) {
        if (mode != RepoMode.ON_DEVICE) {
            SafeLog.d(TAG, "warmupInputs: skip best-effort update (mode=$mode)")
            return
        }

        if (!isUsableLocalModelFile(modelFile)) {
            SafeLog.w(TAG, "warmupInputs: file not ready; keeping previous inputs")
            return
        }

        val repo = repository(context, mode)
        val warmupRepo = repo as? WarmupController.WarmupCapableRepository
        if (warmupRepo == null) {
            SafeLog.w(
                TAG,
                "warmupInputs: repo is not WarmupCapableRepository type=${repo.javaClass.simpleName}",
            )
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
     * - Tries hard not to throw to callers.
     *
     * Strategy:
     * - FAKE mode uses a fake engine immediately.
     * - ON_DEVICE mode uses SLMWarmupEngine that waits for Inputs via updateInputs().
     * - If creation fails, fall back to a fake controller.
     */
    private fun createWarmupControllerBestEffort(
        appCtx: Context,
        mode: RepoMode,
    ): WarmupController {
        val warmupLogger: (String) -> Unit = { msg ->
            SafeLog.d(TAG_WARMUP_LOG, sanitizeWarmupLog(msg))
        }

        return runCatching {
            when (mode) {
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
        }.getOrElse { t ->
            SafeLog.e(
                TAG,
                "createWarmupControllerBestEffort: fallback to fake " +
                        "errType=${t::class.java.simpleName} at=${t.toStackHint()}",
            )

            WarmupController.createFake(
                context = appCtx,
                logger = warmupLogger,
            )
        }
    }

    /**
     * Resolves cached warmup inputs rebound to the current repository when possible.
     *
     * Returns null when:
     * - There are no cached inputs
     * - The cached file is no longer valid
     * - The current repo is missing or not warmup-capable
     *
     * Privacy:
     * - Never logs file paths or names.
     */
    private fun resolveWarmupInputsForCurrentRepoOrNull(): WarmupController.Inputs? {
        val cached = warmupInputsRef.get() ?: return null
        val file = cached.file

        if (!isUsableLocalModelFile(file)) {
            SafeLog.w(TAG, "warmupInputs: cached file not ready; skipping restore")
            return null
        }

        val repo = repoRef.get() ?: return null
        val repoMode = repoModeRef.get()
        if (repoMode != RepoMode.ON_DEVICE) return null

        val warmupRepo = repo as? WarmupController.WarmupCapableRepository
        if (warmupRepo == null) {
            SafeLog.w(TAG, "warmupInputs: current repo not warmup-capable; skipping restore")
            return null
        }

        return WarmupController.Inputs(
            file = file,
            repository = warmupRepo,
            options = cached.options,
        )
    }

    /**
     * Rebinds cached warmup inputs to the currently installed ON_DEVICE repo.
     *
     * Notes:
     * - This keeps cached inputs aligned after repository recreation.
     * - If rebinding fails, previous cached inputs are left untouched.
     */
    private fun rebindWarmupInputsToCurrentRepoBestEffort(
        repo: ChatValidation.RepositoryI,
    ) {
        val cached = warmupInputsRef.get() ?: return
        val file = cached.file

        if (!isUsableLocalModelFile(file)) {
            SafeLog.w(TAG, "warmupInputs: cached file not ready; skip repo rebind")
            return
        }

        val warmupRepo = repo as? WarmupController.WarmupCapableRepository
        if (warmupRepo == null) {
            SafeLog.w(TAG, "warmupInputs: recreated repo not warmup-capable; skip repo rebind")
            return
        }

        val rebound =
            WarmupController.Inputs(
                file = file,
                repository = warmupRepo,
                options = cached.options,
            )

        warmupInputsRef.set(rebound)

        val controller = warmupRef.get()
        val mode = warmupModeRef.get()
        if (controller != null && mode == RepoMode.ON_DEVICE) {
            controller.updateInputs(rebound)
        }

        SafeLog.i(TAG, "warmupInputs: rebound to current repository")
    }

    // ---------------------------------------------------------------------
    // Model Downloader (process scoped)
    // ---------------------------------------------------------------------

    private val downloaderLock = Any()
    private var downloaderConfig: DownloaderConfig? = null
    private var downloader: ModelDownloadController? = null

    private val hfTokenRef = AtomicReference<String?>(null)

    /**
     * Returns the process-scoped model downloader for the provided spec.
     *
     * Contract:
     * - [spec] is required and must represent the downloader that should be active now.
     * - Passing null is not supported; disabling the downloader must use [clearModelDownloader].
     * - This avoids accidental stale-spec reuse when configuration changes from
     *   remote-download mode to local-only mode.
     */
    fun modelDownloader(
        context: Context,
        spec: ModelDownloadSpec,
    ): ModelDownloadController {
        val appCtx = context.applicationContext

        hfTokenRef.set(spec.hfToken)

        val cfg =
            DownloaderConfig(
                modelUrl = spec.modelUrl,
                fileName = spec.fileName,
                uiThrottleMs = spec.uiThrottleMs,
                uiMinDeltaBytes = spec.uiMinDeltaBytes,
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

    /**
     * Explicitly disables the process-scoped downloader and clears its cached config.
     *
     * Why:
     * - A missing download spec means "no downloader should be active".
     * - Reusing the last non-null spec here would resurrect stale remote-download state.
     */
    fun clearModelDownloader() {
        var toClose: Any? = null
        var cleared = false

        synchronized(downloaderLock) {
            if (downloader != null || downloaderConfig != null) {
                toClose = downloader
                downloader = null
                downloaderConfig = null
                hfTokenRef.set(null)
                cleared = true
            }
        }

        toClose?.let { closeIfSupportedSafely(it) }

        if (cleared) {
            SafeLog.i(TAG, "modelDownloader: cleared")
        }
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

    private val closeMethodCache = ConcurrentHashMap<Class<*>, Method>()

    /**
     * Best-effort close helper.
     *
     * Rules:
     * - Never logs exception.message.
     * - Prefer type-safe close first (AutoCloseable / Closeable).
     * - Reflection is the last resort and is cached per class.
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
            val m =
                closeMethodCache[cls]
                    ?: cls.methods
                        .firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                        ?.also { found -> closeMethodCache[cls] = found }

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
     * - URLs
     * - file paths
     * - file names
     * - token-like substrings
     *
     * Notes:
     * - This is intentionally conservative.
     * - It may redact non-sensitive details, but strongly reduces accidental leakage.
     */
    private fun sanitizeWarmupLog(raw: String): String {
        var s = raw

        s = s.replace(Regex("""\bhf_[A-Za-z0-9]{10,}\b"""), "<token>")
        s = s.replace(Regex("""https?://\S+"""), "<url>")
        s = s.replace(Regex("""[A-Za-z]:\\[^\s]+"""), "<path>")
        s = s.replace(Regex("""(/[A-Za-z0-9._-]+)+"""), "<path>")

        s = s.replace(
            Regex(
                """\b[\w.-]+\.(bin|tflite|task|gguf|onnx|json|yaml|yml|zip)\b""",
                RegexOption.IGNORE_CASE,
            ),
            "<file>",
        )

        val trimmed = s.trim()
        return if (trimmed.length <= 220) trimmed else trimmed.take(220) + "…"
    }

    /**
     * Returns a stable stack hint without printing exception.message.
     */
    private fun Throwable.toStackHint(): String {
        val e = this.stackTrace.firstOrNull()
        return if (e != null) "${e.className}.${e.methodName}:${e.lineNumber}" else "unknown"
    }
}