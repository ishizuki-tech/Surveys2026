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
 * - Duplicate warmup-input installation is deduplicated centrally here.
 */
object AppProcessServices {

    private const val TAG = "AppProcessServices"
    private const val FORCE_FAKE_REPO: Boolean = false
    private const val TAG_WARMUP_LOG = "WarmupController"
    private const val MODEL_DIR_NAME = "models"

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

    private const val MIN_LOCAL_MODEL_BYTES: Long = 128L * 1024L * 1024L

    private val allowedModelExtensions = setOf(
        ".litertlm",
        ".bin",
        ".task",
        ".gguf",
    )

    /**
     * Resolves a local model file using configured file names only.
     *
     * Resolution order:
     * - Explicit [spec].fileName when provided
     * - Installed config resolved model spec file name
     * - Installed config modelDefaults.defaultFileName
     *
     * Root order:
     * - Preferred no-backup model root
     * - Legacy filesDir root (migration compatibility)
     *
     * Notes:
     * - Never scans arbitrary files.
     * - Never falls back to "largest file wins".
     * - Never logs file paths or file names.
     */
    fun resolveConfiguredLocalModelFileOrNull(
        context: Context,
        spec: ModelDownloadSpec? = null,
    ): File? {
        val appCtx = context.applicationContext
        val installed = InstalledSurveyConfigStore.getOrNull()

        val candidateNames = LinkedHashSet<String>()

        spec?.fileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(candidateNames::add)

        installed?.resolveModelDownloadSpec()
            ?.fileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(candidateNames::add)

        installed?.modelDefaults
            ?.defaultFileName
            ?.let(::sanitizeSimpleFileName)
            ?.let(candidateNames::add)

        if (candidateNames.isEmpty()) return null

        for (safeName in candidateNames) {
            for (root in candidateModelRoots(appCtx)) {
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
     * - size meets the shared minimum threshold
     */
    fun isUsableLocalModelFile(file: File?): Boolean {
        if (file == null) return false

        return runCatching {
            if (!file.exists() || !file.isFile) return@runCatching false

            val lowerName = file.name.lowercase()
            if (allowedModelExtensions.none { lowerName.endsWith(it) }) {
                return@runCatching false
            }

            file.length() >= MIN_LOCAL_MODEL_BYTES
        }.getOrDefault(false)
    }

    /**
     * Returns the preferred app-private root for model artifacts.
     *
     * Contract:
     * - Preferred storage is no-backup app storage under a dedicated "models" directory.
     * - The directory is created on demand.
     *
     * Notes:
     * - Returns null when the preferred root cannot be prepared.
     * - Callers should fall back to legacy roots only for compatibility.
     */
    fun preferredModelStorageRootOrNull(context: Context): File? {
        val appCtx = context.applicationContext

        return runCatching {
            val root = File(appCtx.noBackupFilesDir, MODEL_DIR_NAME)
            if (root.isDirectory || root.mkdirs()) {
                root.canonicalFile
            } else {
                null
            }
        }.getOrElse { t ->
            SafeLog.w(
                TAG,
                "preferredModelStorageRootOrNull: unavailable errType=${t::class.java.simpleName} at=${t.toStackHint()}",
            )
            null
        }
    }

    /**
     * Returns the legacy app-private root used before no-backup storage migration.
     *
     * Notes:
     * - This exists only for compatibility with already-downloaded local models.
     */
    fun legacyModelStorageRoot(context: Context): File {
        val appCtx = context.applicationContext
        return runCatching { appCtx.filesDir.canonicalFile }.getOrDefault(appCtx.filesDir)
    }

    /**
     * Returns the repository-supported app-private roots for configured model files.
     *
     * Order:
     * - Preferred no-backup root first
     * - Legacy filesDir root second
     *
     * Notes:
     * - Keep this aligned with the downloader/runtime repository path contract.
     * - Avoid probing unrelated directories.
     */
    fun candidateModelRoots(context: Context): List<File> {
        val appCtx = context.applicationContext
        val roots = ArrayList<File>(2)

        preferredModelStorageRootOrNull(appCtx)?.let { preferred ->
            roots.add(preferred)
        }

        val legacy = legacyModelStorageRoot(appCtx)
        if (roots.none { sameCanonicalFile(it, legacy) }) {
            roots.add(legacy)
        }

        return roots
    }

    /**
     * Sanitizes a simple file name to avoid traversal-like inputs.
     *
     * Notes:
     * - This function intentionally accepts only a single file name segment.
     * - Directory layout is controlled by repository/downloader roots, not by config.
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

    private fun sameCanonicalFile(a: File, b: File): Boolean {
        return runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)
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
        clearStartupGraphForRebuild(
            reason = "repoDebugOverrides",
            clearWarmupInputs = false,
            clearDownloader = false,
            clearRepository = true,
            clearWarmup = true,
        )
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

    private fun clearRepositoryForRebuild(
        reason: String,
        detachWarmupInputsFromActiveController: Boolean,
    ) {
        if (detachWarmupInputsFromActiveController) {
            detachWarmupInputsFromActiveControllerBestEffort(reason = reason)
        }

        var toClose: Any? = null
        var cleared = false

        synchronized(repoLock) {
            if (repoRef.get() != null || repoModeRef.get() != null) {
                toClose = repoRef.get()
                repoRef.set(null)
                repoModeRef.set(null)
                cleared = true
            }
        }

        toClose?.let { closeIfSupportedSafely(it) }

        if (cleared) {
            SafeLog.i(TAG, "repository: cleared for rebuild reason=${sanitizeReason(reason)}")
        }
    }

    private fun detachWarmupInputsFromActiveControllerBestEffort(reason: String) {
        val controller = warmupRef.get() ?: return
        val mode = warmupModeRef.get()
        if (mode != RepoMode.ON_DEVICE) return

        runCatching {
            controller.updateInputs(null)
        }.onFailure { t ->
            SafeLog.w(
                TAG,
                "warmupInputs: detach active controller failed " +
                        "reason=${sanitizeReason(reason)} errType=${t::class.java.simpleName} at=${t.toStackHint()}",
            )
        }
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

        createdNow?.let { created ->
            SafeLog.i(TAG, "repository: created mode=$mode type=${created.javaClass.simpleName}")

            if (mode == RepoMode.ON_DEVICE) {
                rebindWarmupInputsToCurrentRepoBestEffort(
                    repo = created,
                )
            }
        }

        toClose?.let { closeIfSupportedSafely(it) }

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
     * - Duplicate equivalent inputs are ignored centrally to avoid redundant
     *   updateInputs() calls from early-inject + startup wiring.
     */
    private val warmupInputsRef = AtomicReference<WarmupController.Inputs?>(null)

    /**
     * Path-free signature used to detect equivalent warmup inputs.
     *
     * Design:
     * - File is identified by length/mtime/nameHash rather than full path.
     * - Repository is identified by object identity, so repo recreation still rebinds.
     * - Options are reduced to the fields relevant for warmup behavior.
     */
    private data class WarmupInputsSignature(
        val fileLengthBytes: Long,
        val fileLastModifiedMs: Long,
        val fileNameHash: Int,
        val repositoryIdentity: Int,
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val toolCount: Int,
        val hasSystemMessage: Boolean,
    ) {
        companion object {
            fun from(inputs: WarmupController.Inputs): WarmupInputsSignature {
                val file = inputs.file!!
                val options = inputs.options
                return WarmupInputsSignature(
                    fileLengthBytes = runCatching { file.length() }.getOrDefault(0L),
                    fileLastModifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
                    fileNameHash = runCatching { file.name.hashCode() }.getOrDefault(0),
                    repositoryIdentity = System.identityHashCode(inputs.repository),
                    supportImage = options.supportImage,
                    supportAudio = options.supportAudio,
                    toolCount = options.tools.size,
                    hasSystemMessage = options.systemMessage != null,
                )
            }
        }
    }

    private fun warmupInputsSignatureOrNull(
        inputs: WarmupController.Inputs?,
    ): WarmupInputsSignature? {
        val safeInputs = inputs ?: return null
        return WarmupInputsSignature.from(safeInputs)
    }

    private fun areEquivalentWarmupInputs(
        a: WarmupController.Inputs?,
        b: WarmupController.Inputs?,
    ): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return warmupInputsSignatureOrNull(a) == warmupInputsSignatureOrNull(b)
    }

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
                    created.updateInputs(
                        resolveWarmupInputsForCurrentRepoOrNull(
                            context = appCtx,
                        ),
                    )
                } else {
                    created.updateInputs(null)
                }

                created
            }

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
     * - Call this from orchestration code once modelFile + warmup-capable repo are resolved.
     *
     * Privacy:
     * - Never logs file path or file name.
     *
     * Deduplication:
     * - Equivalent inputs are ignored to avoid redundant controller updates.
     * - This intentionally absorbs overlap between SurveyApplication early-inject
     *   and later startup onModelReady wiring.
     */
    fun updateWarmupInputs(inputs: WarmupController.Inputs?) {
        val previous = warmupInputsRef.get()
        if (areEquivalentWarmupInputs(previous, inputs)) {
            return
        }

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

        val repo = repoRef.get()
        val repoMode = repoModeRef.get()
        if (repo == null || repoMode != mode) {
            SafeLog.d(TAG, "warmupInputs: skip best-effort update (repo not ready)")
            return
        }

        val warmupRepo = repo as? WarmupController.WarmupCapableRepository
        if (warmupRepo == null) {
            SafeLog.w(TAG, "warmupInputs: repo is not WarmupCapableRepository type=${repo.javaClass.simpleName}")
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
    private fun resolveWarmupInputsForCurrentRepoOrNull(
        context: Context,
    ): WarmupController.Inputs? {
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

        updateWarmupInputs(rebound)
    }

    /**
     * Clears the process-scoped warmup controller.
     *
     * Contract:
     * - Active controller inputs are detached before the previous instance is closed.
     * - Cached durable inputs are preserved by default so a future repository rebuild
     *   can rebind them safely.
     */
    fun clearWarmupControllerForRebuild(
        reason: String,
        clearWarmupInputs: Boolean = false,
    ) {
        var toClose: WarmupController? = null
        var cleared = false

        synchronized(warmupLock) {
            val current = warmupRef.get()
            if (current != null || warmupModeRef.get() != null) {
                toClose = current
                warmupRef.set(null)
                warmupModeRef.set(null)
                cleared = true
            }
        }

        toClose?.let { controller ->
            runCatching { controller.updateInputs(null) }
            closeIfSupportedSafely(controller)
        }

        if (clearWarmupInputs) {
            clearWarmupInputsCache(reason = reason)
        }

        if (cleared) {
            SafeLog.i(
                TAG,
                "warmupController: cleared for rebuild " +
                        "reason=${sanitizeReason(reason)} keepCachedInputs=${!clearWarmupInputs}",
            )
        }
    }

    /**
     * Clears the process-scoped startup graph.
     *
     * Why:
     * - Full startup restart must invalidate stale singleton instances, not only UI state.
     * - The clear path is intentionally destroy-only; recreation remains the caller's job.
     */
    fun clearStartupGraphForRebuild(
        reason: String,
        clearWarmupInputs: Boolean = false,
        clearDownloader: Boolean = true,
        clearRepository: Boolean = true,
        clearWarmup: Boolean = true,
    ) {
        val safeReason = sanitizeReason(reason)

        if (clearDownloader) {
            clearModelDownloaderForRebuild(reason = safeReason)
        }

        if (clearWarmup) {
            clearWarmupControllerForRebuild(
                reason = safeReason,
                clearWarmupInputs = clearWarmupInputs,
            )
        } else if (clearWarmupInputs) {
            clearWarmupInputsCache(reason = safeReason)
        }

        if (clearRepository) {
            clearRepositoryForRebuild(
                reason = safeReason,
                detachWarmupInputsFromActiveController = !clearWarmup,
            )
        }

        SafeLog.i(
            TAG,
            "startupGraph: cleared " +
                    "reason=$safeReason clearDownloader=$clearDownloader clearRepository=$clearRepository " +
                    "clearWarmup=$clearWarmup clearWarmupInputs=$clearWarmupInputs",
        )
    }

    private fun clearWarmupInputsCache(reason: String) {
        val hadInputs = warmupInputsRef.getAndSet(null) != null
        if (hadInputs) {
            SafeLog.i(TAG, "warmupInputs: cache cleared reason=${sanitizeReason(reason)}")
        }
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
        clearModelDownloaderForRebuild(reason = "manual")
    }

    private fun clearModelDownloaderForRebuild(reason: String) {
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
            SafeLog.i(TAG, "modelDownloader: cleared reason=${sanitizeReason(reason)}")
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
    private fun sanitizeReason(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"

        val safe =
            buildString {
                for (c in trimmed) {
                    if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') {
                        append(c)
                    }
                    if (length >= 48) break
                }
            }

        return safe.ifBlank { "unknown" }
    }

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