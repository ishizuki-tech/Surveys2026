/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SurveyAppRoot.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.RepositoryI
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.config.resolveModelDownloadSpec
import com.negi.surveys.logging.GitHubLogUploadManager
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
import com.negi.surveys.ui.chat.ChatQuestionScreen
import com.negi.surveys.ui.chat.LocalRepositoryI
import com.negi.surveys.utils.CompileState
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.utils.PrefetchState
import com.negi.surveys.utils.WarmupController
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SurveyAppRoot(modifier: Modifier = Modifier) {
    SurveyAppRootInternal.Render(modifier = modifier)
}

private object SurveyAppRootInternal {

    // ---------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------

    const val TAG: String = "SurveyAppRoot"
    const val CONFIG_ASSET_NAME: String = "survey.yaml"

    /**
     * Hard guard for switching repository mode.
     *
     * NOTE:
     * - Replace this with BuildConfig / SurveyConfig / dev menu toggle as needed.
     * - MUST NOT depend on secrets / tokens.
     */
    private const val FORCE_FAKE_REPO: Boolean = false

    // ---------------------------------------------------------------------
    // Models / Formatting
    // ---------------------------------------------------------------------

    sealed interface ConfigState {
        data object Loading : ConfigState
        data class Ready(val cfg: SurveyConfig) : ConfigState

        /**
         * Failure message MUST be safe (no exception.message).
         */
        data class Failed(val safeReason: String) : ConfigState
    }

    private data class WarmupUiFormat(
        val idleLabel: String,
        val prefetchRunningPrefix: String,
        val compileWaitingPrefix: String,
        val compileCompilingPrefix: String,
    )

    private val WARMUP_UI_FORMAT_HOME = WarmupUiFormat(
        idleLabel = "Idle",
        prefetchRunningPrefix = "Prefetch",
        compileWaitingPrefix = "WaitingForPrefetch",
        compileCompilingPrefix = "Compiling",
    )

    private val WARMUP_UI_FORMAT_GATE = WarmupUiFormat(
        idleLabel = "Preparing…",
        prefetchRunningPrefix = "Prefetch",
        compileWaitingPrefix = "Compile waiting…",
        compileCompilingPrefix = "Compiling",
    )

    private enum class GatePolicy {
        /** Block only on model file readiness. */
        MODEL_ONLY,

        /** Block on model + prefetch + compile busy. */
        MODEL_PREFETCH_COMPILE
    }

    /**
     * Stable key for deciding whether the downloader should be recreated.
     *
     * Why:
     * - ModelDownloadController is stateful (has a SupervisorJob scope).
     * - We MUST recreate it when effective config changes, otherwise it stays "NotConfigured".
     * - We also MUST close the previous instance to avoid scope leaks.
     */
    private data class ModelSpecKey(
        val url: String,
        val fileName: String,
        val timeoutMs: Long,
        val forceFreshOnStart: Boolean,
        val uiThrottleMs: Long,
        val uiMinDeltaBytes: Long,
    )

    // ---------------------------------------------------------------------
    // Entry Point (Render)
    // ---------------------------------------------------------------------

    @Composable
    fun Render(modifier: Modifier) {
        val appContext = LocalContext.current.applicationContext
        val scope = rememberCoroutineScope()

        // -------------------------------------------------------------
        // Navigation
        // -------------------------------------------------------------

        val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)
        val nav = remember(backStack) { AppNavigator(backStack) }

        val currentKey: NavKey by remember(backStack) {
            derivedStateOf { backStack.lastOrNull() ?: Home }
        }
        val canPop: Boolean by remember(backStack) {
            derivedStateOf { backStack.size > 1 }
        }

        BackHandler(enabled = canPop) {
            SafeLog.d(
                TAG,
                "BackHandler: pop requested stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}"
            )
            nav.pop()
        }

        LaunchedEffect(currentKey) {
            SafeLog.d(TAG, "Nav: current=${currentKey.javaClass.simpleName} stackSize=${backStack.size}")
        }

        // -------------------------------------------------------------
        // Session / streaming debug
        // -------------------------------------------------------------

        val sessionVm: SurveySessionViewModel = viewModel()
        val logs by sessionVm.logs.collectAsStateWithLifecycle()
        val exportText by sessionVm.exportJson.collectAsStateWithLifecycle()

        val prompts: Map<String, String> = remember {
            linkedMapOf(
                "Q1" to "Question prompt for Q1 (placeholder)",
                "Q2" to "Question prompt for Q2 (placeholder)"
            )
        }

        /**
         * Stream bridge logger MUST NOT output raw content.
         */
        val streamBridge: ChatStreamBridge = remember {
            ChatStreamBridge(
                logger = { msg ->
                    // Log only metadata to avoid leaking prompts/answers/deltas.
                    SafeLog.d("StreamBridge", "eventLen=${msg.length}")
                }
            )
        }
        val streamStats: ChatStreamBridge.StreamStats by streamBridge.stats.collectAsStateWithLifecycle()

        // -------------------------------------------------------------
        // Config + ModelSpec
        // -------------------------------------------------------------

        val configState: ConfigState by produceState<ConfigState>(
            initialValue = ConfigState.Loading,
            key1 = appContext
        ) {
            val t0 = SystemClock.elapsedRealtime()
            SafeLog.i(TAG, "Config: loading asset=$CONFIG_ASSET_NAME")

            val res = withContext(Dispatchers.IO) {
                runCatching { SurveyConfigLoader.fromAssetsValidated(appContext, CONFIG_ASSET_NAME) }
            }

            value = res.fold(
                onSuccess = {
                    SafeLog.i(TAG, "Config: ready in ${SystemClock.elapsedRealtime() - t0}ms")
                    SurveyConfigLoader.installProcessConfig(it)
                    ConfigState.Ready(it)
                },
                onFailure = { t ->
                    // NEVER log t.message; it can include sensitive content.
                    SafeLog.e(
                        TAG,
                        "Config: failed in ${SystemClock.elapsedRealtime() - t0}ms type=${t::class.java.simpleName}",
                        t
                    )
                    ConfigState.Failed(safeThrowableReason(t))
                }
            )
        }

        /**
         * IMPORTANT:
         * - We resolve ModelDownloadSpec whenever config is ready, regardless of repo mode.
         * - This enables "FAKEでも設定は読む" observability (url/file present in logs/UI).
         * - Actual download is still gated by onDeviceEnabled (see LaunchedEffect below).
         */
        val modelSpec: ModelDownloadSpec? = remember(configState) {
            when (val st = configState) {
                is ConfigState.Ready -> st.cfg.resolveModelDownloadSpec()
                else -> null
            }
        }

        /**
         * Build a stable "effective key" that decides downloader recreation.
         * Keep it strict: if URL/fileName changes, we MUST recreate.
         */
        val modelSpecKey: ModelSpecKey? = remember(modelSpec) {
            val s = modelSpec ?: return@remember null
            val url = s.modelUrl?.trim().orEmpty()
            val name = s.fileName.trim().orEmpty()
            if (url.isBlank() || name.isBlank()) return@remember null

            ModelSpecKey(
                url = url,
                fileName = name,
                timeoutMs = s.timeoutMs,
                forceFreshOnStart = false, // Keep false by default for resume friendliness.
                uiThrottleMs = s.uiThrottleMs,
                uiMinDeltaBytes = s.uiMinDeltaBytes,
            )
        }

        // -------------------------------------------------------------
        // Repo Mode Decision
        // -------------------------------------------------------------

        /**
         * Decide repository mode in one place.
         *
         * Assumption:
         * - In production you will replace this with a safe config/build flag.
         */
        val repoMode: AppProcessServices.RepoMode = remember(configState) {
            if (FORCE_FAKE_REPO) AppProcessServices.RepoMode.FAKE else AppProcessServices.RepoMode.ON_DEVICE
        }

        val onDeviceEnabled: Boolean = remember(repoMode) { repoMode == AppProcessServices.RepoMode.ON_DEVICE }

        LaunchedEffect(repoMode) {
            SafeLog.i(TAG, "RepoMode: $repoMode onDeviceEnabled=$onDeviceEnabled")
        }

        // -------------------------------------------------------------
        // Process-scoped services (Repo/Warmup/Downloader)
        // -------------------------------------------------------------

        val repo: RepositoryI = remember(appContext, repoMode) {
            AppProcessServices.repository(appContext, repoMode)
        }

        val warmup: WarmupController = remember(appContext, repoMode) {
            AppProcessServices.warmupController(appContext, repoMode)
        }

        /**
         * CRITICAL BEHAVIOR (requested):
         * - Even in FAKE mode, we create a "configured" ModelDownloadController if config has URL/fileName.
         * - This makes logs/UI reflect the real config ("FAKEでも設定は読む").
         * - We still NEVER start downloads unless onDeviceEnabled == true (startup effects below).
         */
        val modelDownloader: ModelDownloadController = remember(appContext, repoMode, modelSpecKey) {
            val specToUse: ModelDownloadSpec? = modelSpecKey?.let { modelSpec }

            AppProcessServices.modelDownloader(appContext, spec = specToUse).also {
                val key = modelSpecKey
                SafeLog.i(
                    TAG,
                    "ModelDownloader: created onDevice=$onDeviceEnabled " +
                            "cfgUrlPresent=${key != null} cfgFilePresent=${key != null} " +
                            "specPresent=${specToUse != null}"
                )
            }
        }

        DisposableEffect(modelDownloader) {
            onDispose {
                // Ensure no leaking scopes across recompositions / config swaps.
                runCatching { modelDownloader.close() }
                    .onFailure { t ->
                        SafeLog.e(
                            TAG,
                            "ModelDownloader: close failed (non-fatal) type=${t::class.java.simpleName}",
                            t
                        )
                    }
            }
        }

        // Collect state ONCE at root to avoid duplicate subscriptions.
        val rootPrefetchState: PrefetchState by warmup.prefetchState.collectAsStateWithLifecycle()
        val rootCompileState: CompileState by warmup.compileState.collectAsStateWithLifecycle()
        val rootModelState: ModelDownloadController.ModelState by modelDownloader.state.collectAsStateWithLifecycle()

        // Keep stable state containers for NavEntry lambdas (avoid entryProvider recreation).
        val rootModelStateState: State<ModelDownloadController.ModelState> = rememberUpdatedState(rootModelState)
        val rootPrefetchStateState: State<PrefetchState> = rememberUpdatedState(rootPrefetchState)
        val rootCompileStateState: State<CompileState> = rememberUpdatedState(rootCompileState)

        // -------------------------------------------------------------
        // Debug: log transitions (centralized)
        // -------------------------------------------------------------

        LogStateTransitions(
            modelState = rootModelState,
            prefetchState = rootPrefetchState,
            compileState = rootCompileState
        )

        // -------------------------------------------------------------
        // Retry (model + warmup) unified
        // -------------------------------------------------------------

        val launchRetryAll: (String) -> Unit = remember(
            scope,
            warmup,
            modelDownloader,
            modelSpecKey,
            onDeviceEnabled
        ) {
            { fromRaw ->
                val from = sanitizeLabel(fromRaw)

                scope.launch(Dispatchers.IO) {
                    val key = modelSpecKey
                    SafeLog.w(
                        TAG,
                        "RetryAll requested from=$from onDevice=$onDeviceEnabled keyPresent=${key != null}"
                    )

                    if (onDeviceEnabled) {
                        runCatching { modelDownloader.resetForRetry(reason = "uiRetry") }
                            .onFailure { t ->
                                SafeLog.e(
                                    TAG,
                                    "RetryAll: model resetForRetry failed (non-fatal) type=${t::class.java.simpleName}",
                                    t
                                )
                            }

                        if (key != null) {
                            runCatching {
                                modelDownloader.ensureModelOnce(
                                    timeoutMs = key.timeoutMs,
                                    forceFresh = false,
                                    reason = "uiRetry"
                                )
                            }.onFailure { t ->
                                SafeLog.e(
                                    TAG,
                                    "RetryAll: model ensure failed (non-fatal) type=${t::class.java.simpleName}",
                                    t
                                )
                            }
                        }
                    }

                    runCatching { warmup.resetForRetry(reason = "uiRetry") }
                        .onFailure { t ->
                            SafeLog.e(
                                TAG,
                                "RetryAll: warmup resetForRetry failed (non-fatal) type=${t::class.java.simpleName}",
                                t
                            )
                        }

                    if (onDeviceEnabled) {
                        runCatching { warmup.requestCompileAfterPrefetch(reason = "uiRetry") }
                            .onFailure { t ->
                                SafeLog.e(
                                    TAG,
                                    "RetryAll: warmup requestCompileAfterPrefetch failed (non-fatal) type=${t::class.java.simpleName}",
                                    t
                                )
                            }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Startup effects (single pipeline)
        // -------------------------------------------------------------

        /**
         * Start model download once config is ready and controller is created.
         *
         * Why:
         * - Ensures we do not attempt downloads in FAKE mode.
         * - Ensures we do not run using a stale "NotConfigured" controller.
         */
        LaunchedEffect(onDeviceEnabled, modelSpecKey, modelDownloader) {
            if (!onDeviceEnabled) return@LaunchedEffect
            val key = modelSpecKey ?: return@LaunchedEffect

            // Wait for the first frame to avoid blocking initial composition.
            withFrameNanos { /* first frame */ }
            delay(150L)

            runCatching {
                modelDownloader.ensureModelOnce(
                    timeoutMs = key.timeoutMs,
                    forceFresh = key.forceFreshOnStart,
                    reason = "startupAfterFirstFrame"
                )
            }.onFailure { t ->
                SafeLog.e(TAG, "Model ensure failed (non-fatal) type=${t::class.java.simpleName}", t)
            }
        }

        /**
         * Warmup should start after model is ready.
         */
        LaunchedEffect(rootModelState, onDeviceEnabled, warmup) {
            if (!onDeviceEnabled) return@LaunchedEffect
            if (rootModelState is ModelDownloadController.ModelState.Ready) {
                SafeLog.i(TAG, "Model Ready -> request warmup compileAfterPrefetch")
                runCatching {
                    warmup.requestCompileAfterPrefetch(reason = "modelReady")
                }.onFailure { t ->
                    SafeLog.e(TAG, "Warmup request failed (non-fatal) type=${t::class.java.simpleName}", t)
                }
            }
        }

        // -------------------------------------------------------------
        // Debug labels (home-level)
        // -------------------------------------------------------------

        val (prefetchUiLabel, compileUiLabel) = rememberWarmupUiLabels(
            prefetchState = rootPrefetchState,
            compileState = rootCompileState,
            tickIntervalMs = 500L,
            format = WARMUP_UI_FORMAT_HOME
        )

        val modelUiLabel: String = remember(rootModelState) { modelLabelForUi(rootModelState) }
        val buildLabel = remember { buildLabelSafe() }

        val cfgLabel = remember(configState) {
            when (val st = configState) {
                is ConfigState.Loading -> "Loading"
                is ConfigState.Ready -> "Ready"
                is ConfigState.Failed -> "Failed (${st.safeReason})"
            }
        }

        val debugInfo: DebugInfo = remember(
            currentKey,
            backStack.size,
            logs.size,
            exportText.length,
            buildLabel,
            cfgLabel,
            streamStats.activeSessionId,
            streamStats.droppedEvents,
            streamStats.ignoredDelta,
            streamStats.ignoredEnd,
            streamStats.ignoredError,
            streamStats.ignoredCancel,
            modelUiLabel,
            prefetchUiLabel,
            compileUiLabel,
            repoMode,
            onDeviceEnabled,
        ) {
            val ignoredTotal =
                streamStats.ignoredDelta + streamStats.ignoredEnd + streamStats.ignoredError + streamStats.ignoredCancel

            DebugInfo(
                currentRoute = titleFor(currentKey),
                backStackSize = backStack.size,
                buildLabel = buildLabel,
                extras = listOf(
                    DebugRow("SDK", Build.VERSION.SDK_INT.toString()),
                    DebugRow("Device", "${Build.MANUFACTURER}/${Build.MODEL}"),
                    DebugRow("Config", cfgLabel),
                    DebugRow("RepoMode", repoMode.name),
                    DebugRow("OnDevice", onDeviceEnabled.toString()),
                    DebugRow("Logs", logs.size.toString()),
                    DebugRow("ExportLen", exportText.length.toString()),
                    DebugRow("StreamActive", streamStats.activeSessionId.toString()),
                    DebugRow("StreamDropped", streamStats.droppedEvents.toString()),
                    DebugRow("StreamIgnored", ignoredTotal.toString()),
                    DebugRow("SLM Model", modelUiLabel),
                    DebugRow("SLM Prefetch", prefetchUiLabel),
                    DebugRow("SLM Compile", compileUiLabel),
                    DebugRow("RepoId", System.identityHashCode(repo).toString()),
                    DebugRow("RepoType", repo.javaClass.simpleName),
                )
            )
        }

        val debugInfoState: State<DebugInfo> = rememberUpdatedState(debugInfo)

        // -------------------------------------------------------------
        // Manual upload action (debug)
        // -------------------------------------------------------------

        var uploadStatus by remember { mutableStateOf<String?>(null) }
        val uploadStatusState: State<String?> = rememberUpdatedState(uploadStatus)

        val startManualUpload: (String) -> Unit = remember(appContext, scope) {
            { fromRaw ->
                val from = sanitizeLabel(fromRaw)

                scope.launch(Dispatchers.IO) {
                    val reason = "manual"
                    val gh = runCatching {
                        if (GitHubLogUploadManager.isConfigured()) {
                            GitHubLogUploadManager.uploadRegularBlocking(appContext, reason).getOrNull()
                        } else {
                            null
                        }
                    }.onFailure { t ->
                        SafeLog.e(TAG, "manual upload failed (non-fatal) type=${t::class.java.simpleName}", t)
                    }.getOrNull()

                    val summary = "gh=" + (gh ?: "fail")
                    SafeLog.i(TAG, "manual upload: $summary from=$from")

                    withContext(Dispatchers.Main) {
                        uploadStatus = summary
                        Toast.makeText(appContext, "Upload: $summary", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Nav3 entries (stable entryProvider + updated state refs)
        // -------------------------------------------------------------

        val entries: (NavKey) -> NavEntry<NavKey> = remember(
            nav,
            prompts,
            sessionVm,
            startManualUpload,
            debugInfoState,
            warmup,
            launchRetryAll,
            uploadStatusState,
            onDeviceEnabled,
            rootModelStateState,
            rootPrefetchStateState,
            rootCompileStateState,
        ) {
            entryProvider {
                entry<Home> {
                    HomeScreen(
                        onStartSurvey = { nav.startSurvey() },
                        onExport = { nav.goExport() },
                        debugInfo = debugInfoState.value
                    )
                }

                entry<SurveyStart> {
                    GateOrContent(
                        enabled = onDeviceEnabled,
                        policy = GatePolicy.MODEL_ONLY,
                        modelState = rootModelStateState.value,
                        prefetchState = rootPrefetchStateState.value,
                        compileState = rootCompileStateState.value,
                        onBack = { nav.pop() },
                        onRetryAll = { launchRetryAll("surveyStartGate") },
                    ) {
                        SurveyStartScreen(
                            onBegin = { nav.beginQuestions("Q1") },
                            onBack = { nav.pop() },
                            warmupController = warmup,
                            debugInfo = debugInfoState.value,
                        )
                    }
                }

                entry<Question> { key ->
                    val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                    GateOrContent(
                        enabled = onDeviceEnabled,
                        policy = GatePolicy.MODEL_PREFETCH_COMPILE,
                        modelState = rootModelStateState.value,
                        prefetchState = rootPrefetchStateState.value,
                        compileState = rootCompileStateState.value,
                        onBack = { nav.pop() },
                        onRetryAll = { launchRetryAll("questionGate") },
                    ) {
                        ChatQuestionScreen(
                            questionId = key.id,
                            prompt = prompt,
                            onNext = { log ->
                                sessionVm.upsertLog(log)
                                when (key.id) {
                                    "Q1" -> nav.goQuestion("Q2")
                                    "Q2" -> nav.goReview()
                                    else -> nav.goReview()
                                }
                            },
                            onBack = { nav.pop() }
                        )
                    }
                }

                entry<Review> {
                    val reviewLogs by sessionVm.logs.collectAsStateWithLifecycle()
                    ReviewScreen(
                        logs = reviewLogs,
                        onExport = { nav.goExport() },
                        onBack = { nav.pop() },
                        onUploadLogs = { startManualUpload("review") },
                        uploadStatusLine = uploadStatusState.value
                    )
                }

                entry<Export> {
                    val payload by sessionVm.exportJson.collectAsStateWithLifecycle()
                    ExportScreen(
                        exportText = payload,
                        onBack = { nav.pop() }
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // Scaffold
        // -------------------------------------------------------------

        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                CompactTopBar(
                    title = titleFor(currentKey),
                    canPop = canPop,
                    onBack = { nav.pop() }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                CompositionLocalProvider(
                    LocalChatStreamBridge provides streamBridge,
                    LocalRepositoryI provides repo,
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = entries,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Gate wrapper (dedup)
    // ---------------------------------------------------------------------

    @Composable
    private fun GateOrContent(
        enabled: Boolean,
        policy: GatePolicy,
        modelState: ModelDownloadController.ModelState,
        prefetchState: PrefetchState,
        compileState: CompileState,
        onBack: () -> Unit,
        onRetryAll: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        // Fake/Server mode MUST NOT block on local model readiness.
        if (!enabled) {
            content()
            return
        }

        val modelBlocking = modelState !is ModelDownloadController.ModelState.Ready
        val prefetchBusy = isPrefetchInProgress(prefetchState)
        val compileBusy = compileState is CompileState.WaitingForPrefetch || compileState is CompileState.Compiling

        val isBlocking = when (policy) {
            GatePolicy.MODEL_ONLY -> modelBlocking
            GatePolicy.MODEL_PREFETCH_COMPILE -> modelBlocking || prefetchBusy || compileBusy
        }

        if (isBlocking) {
            SlmGateScreen(
                modelState = modelState,
                prefetchState = prefetchState,
                compileState = compileState,
                onBack = onBack,
                onRetryAll = onRetryAll,
            )
        } else {
            content()
        }
    }

    // ---------------------------------------------------------------------
    // UI Components
    // ---------------------------------------------------------------------

    @Composable
    private fun SlmGateScreen(
        modelState: ModelDownloadController.ModelState,
        prefetchState: PrefetchState,
        compileState: CompileState,
        onBack: () -> Unit,
        onRetryAll: () -> Unit,
    ) {
        val prefetchInProgress = isPrefetchInProgress(prefetchState)
        val compileInProgress = compileState is CompileState.WaitingForPrefetch || compileState is CompileState.Compiling
        val modelInProgress =
            modelState is ModelDownloadController.ModelState.Checking ||
                    modelState is ModelDownloadController.ModelState.Downloading

        val nowMs = rememberWarmupUiNowMs(
            inProgress = modelInProgress || prefetchInProgress || compileInProgress,
            tickIntervalMs = 250L
        )

        val prefetchElapsedMs = rememberDynamicElapsedMs(
            reportedElapsedMs = prefetchState.elapsedMs,
            inProgress = prefetchInProgress,
            nowMs = nowMs
        )
        val compileElapsedMs = rememberDynamicElapsedMs(
            reportedElapsedMs = compileState.elapsedMs,
            inProgress = compileInProgress,
            nowMs = nowMs
        )

        val primary = remember(modelState, prefetchState, compileState, prefetchElapsedMs, compileElapsedMs) {
            when {
                modelState !is ModelDownloadController.ModelState.Ready -> modelLabelForUi(modelState)
                prefetchInProgress -> prefetchLabelForUi(
                    prefetchState,
                    elapsedMs = prefetchElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE
                )
                compileState is CompileState.WaitingForPrefetch -> compileLabelForUi(
                    compileState,
                    elapsedMs = compileElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE
                )
                compileState is CompileState.Compiling -> compileLabelForUi(
                    compileState,
                    elapsedMs = compileElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE
                )
                else -> WARMUP_UI_FORMAT_GATE.idleLabel
            }
        }

        val showRetryAll = remember(modelState, compileState) {
            modelState is ModelDownloadController.ModelState.Failed ||
                    modelState is ModelDownloadController.ModelState.Cancelled ||
                    compileState is CompileState.Failed ||
                    compileState is CompileState.Cancelled
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Preparing the on-device model…",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = primary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                val hint = remember(modelState, compileState) {
                    when (modelState) {
                        is ModelDownloadController.ModelState.Downloading ->
                            "Downloading the model file. Keep the app open (first run may take a while)."
                        is ModelDownloadController.ModelState.Checking ->
                            "Checking local model file…"
                        is ModelDownloadController.ModelState.NotConfigured ->
                            "Model URL is not configured in SurveyConfig."
                        is ModelDownloadController.ModelState.Failed ->
                            "Download failed. Check connectivity / credentials / configuration."
                        is ModelDownloadController.ModelState.Cancelled ->
                            "Download cancelled."
                        else -> when (compileState) {
                            is CompileState.WaitingForPrefetch ->
                                "Waiting for prefetch to complete before compilation."
                            is CompileState.Compiling ->
                                "Compiling/initializing the model. UI may stutter briefly."
                            is CompileState.Failed ->
                                "Warmup compile failed. Try retry."
                            is CompileState.Cancelled ->
                                "Warmup compile cancelled. Try retry."
                            else -> ""
                        }
                    }
                }

                if (hint.isNotBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }

                if (showRetryAll) {
                    OutlinedButton(onClick = onRetryAll) {
                        Text("Retry (model + warmup)")
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }
    }

    @Composable
    private fun CompactTopBar(
        title: String,
        canPop: Boolean,
        onBack: () -> Unit
    ) {
        val barHeight = 44.dp

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    if (canPop) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(40.dp))
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .weight(1f)
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Debug helpers
    // ---------------------------------------------------------------------

    @Composable
    private fun LogStateTransitions(
        modelState: ModelDownloadController.ModelState,
        prefetchState: PrefetchState,
        compileState: CompileState,
    ) {
        var prevModel by remember { mutableStateOf<String?>(null) }
        var prevPrefetch by remember { mutableStateOf<String?>(null) }
        var prevCompile by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(modelState) {
            val now = modelState.javaClass.simpleName
            if (prevModel != now) {
                SafeLog.d(TAG, "State: model=$now")
                prevModel = now
            }
        }
        LaunchedEffect(prefetchState) {
            val now = prefetchState.javaClass.simpleName
            if (prevPrefetch != now) {
                SafeLog.d(TAG, "State: prefetch=$now elapsedMs=${prefetchState.elapsedMs}")
                prevPrefetch = now
            }
        }
        LaunchedEffect(compileState) {
            val now = compileState.javaClass.simpleName
            if (prevCompile != now) {
                SafeLog.d(TAG, "State: compile=$now elapsedMs=${compileState.elapsedMs}")
                prevCompile = now
            }
        }
    }

    // ---------------------------------------------------------------------
    // String helpers
    // ---------------------------------------------------------------------

    private fun titleFor(key: NavKey): String {
        return when (key) {
            is Home -> "Home"
            is SurveyStart -> "Survey Start"
            is Question -> "Question: ${key.id}"
            is Review -> "Review"
            is Export -> "Export"
            else -> "Survey App"
        }
    }

    private fun buildLabelSafe(): String {
        return runCatching {
            val name = BuildConfig.VERSION_NAME
            val code = BuildConfig.VERSION_CODE
            val type = BuildConfig.BUILD_TYPE
            val dbg = BuildConfig.DEBUG
            "v$name ($code) $type dbg=$dbg"
        }.getOrElse { "debug" }
    }

    // ---------------------------------------------------------------------
    // Warmup/model label helpers
    // ---------------------------------------------------------------------

    private fun isPrefetchInProgress(state: PrefetchState): Boolean {
        return state is PrefetchState.Running
    }

    private fun modelLabelForUi(state: ModelDownloadController.ModelState): String {
        return when (state) {
            is ModelDownloadController.ModelState.NotConfigured -> "Model not configured"
            is ModelDownloadController.ModelState.Idle -> "Idle"
            is ModelDownloadController.ModelState.Checking -> "Checking… ${formatElapsed(state.elapsedMs)}"
            is ModelDownloadController.ModelState.Downloading -> {
                val total = state.total
                if (total != null && total > 0L) {
                    val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                        .toInt()
                        .coerceIn(0, 100)
                    "Downloading ${pct}% ${formatElapsed(state.elapsedMs)}"
                } else {
                    "Downloading ${formatElapsed(state.elapsedMs)}"
                }
            }
            is ModelDownloadController.ModelState.Ready -> "Ready"
            is ModelDownloadController.ModelState.Failed -> "Failed"
            is ModelDownloadController.ModelState.Cancelled -> "Cancelled"
        }
    }

    private fun prefetchLabelForUi(
        state: PrefetchState,
        elapsedMs: Long,
        format: WarmupUiFormat,
    ): String {
        val name = state.javaClass.simpleName
        val elapsed = formatElapsed(elapsedMs)
        return when {
            isPrefetchInProgress(state) -> "${format.prefetchRunningPrefix} ($name) $elapsed"
            name.equals("Idle", ignoreCase = true) -> format.idleLabel
            else -> "$name $elapsed"
        }
    }

    private fun compileLabelForUi(
        state: CompileState,
        elapsedMs: Long,
        format: WarmupUiFormat,
    ): String {
        val elapsed = formatElapsed(elapsedMs)
        return when (state) {
            is CompileState.WaitingForPrefetch -> "${format.compileWaitingPrefix} $elapsed"
            is CompileState.Compiling -> "${format.compileCompilingPrefix} $elapsed"
            is CompileState.Idle -> format.idleLabel
            else -> "${state.javaClass.simpleName} $elapsed"
        }
    }

    // ---------------------------------------------------------------------
    // Shared "Ticking" Composables
    // ---------------------------------------------------------------------

    @Composable
    private fun rememberWarmupUiNowMs(
        inProgress: Boolean,
        tickIntervalMs: Long,
    ): Long {
        var uiNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

        LaunchedEffect(inProgress, tickIntervalMs) {
            if (!inProgress) return@LaunchedEffect
            while (isActive) {
                uiNowMs = SystemClock.elapsedRealtime()
                delay(tickIntervalMs)
            }
        }
        return uiNowMs
    }

    /**
     * Produces a smoothly increasing elapsedMs for UI even if the underlying state updates are sparse.
     *
     * How:
     * - Treat reportedElapsedMs as a baseline at the moment we last observed it.
     * - While inProgress, add (nowMs - baselineNowMs).
     */
    @Composable
    private fun rememberDynamicElapsedMs(
        reportedElapsedMs: Long,
        inProgress: Boolean,
        nowMs: Long,
    ): Long {
        var baseElapsedMs by remember { mutableLongStateOf(reportedElapsedMs) }
        var baseNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

        LaunchedEffect(reportedElapsedMs, inProgress) {
            baseElapsedMs = reportedElapsedMs
            baseNowMs = SystemClock.elapsedRealtime()
        }

        return if (!inProgress) {
            reportedElapsedMs
        } else {
            val live = baseElapsedMs + (nowMs - baseNowMs)
            if (live < 0L) 0L else live
        }
    }

    @Composable
    private fun rememberWarmupUiLabels(
        prefetchState: PrefetchState,
        compileState: CompileState,
        tickIntervalMs: Long,
        format: WarmupUiFormat,
    ): Pair<String, String> {
        val prefetchInProgress = isPrefetchInProgress(prefetchState)
        val compileInProgress =
            compileState is CompileState.WaitingForPrefetch || compileState is CompileState.Compiling

        val inProgress = prefetchInProgress || compileInProgress
        val nowMs = rememberWarmupUiNowMs(inProgress = inProgress, tickIntervalMs = tickIntervalMs)

        val prefetchElapsedMs = rememberDynamicElapsedMs(
            reportedElapsedMs = prefetchState.elapsedMs,
            inProgress = prefetchInProgress,
            nowMs = nowMs
        )
        val compileElapsedMs = rememberDynamicElapsedMs(
            reportedElapsedMs = compileState.elapsedMs,
            inProgress = compileInProgress,
            nowMs = nowMs
        )

        val prefetchLabel = remember(prefetchState, prefetchElapsedMs, format) {
            prefetchLabelForUi(prefetchState, elapsedMs = prefetchElapsedMs, format = format)
        }
        val compileLabel = remember(compileState, compileElapsedMs, format) {
            compileLabelForUi(compileState, elapsedMs = compileElapsedMs, format = format)
        }
        return prefetchLabel to compileLabel
    }

    private fun formatElapsed(ms: Long): String {
        if (ms < 1_000L) return "${ms}ms"
        val sec = ms / 1_000.0
        if (sec < 60.0) return String.format(Locale.US, "%.1fs", sec)
        val m = (ms / 60_000L)
        val s = (ms / 1_000L) % 60
        return String.format(Locale.US, "%dm %02ds", m, s)
    }

    /**
     * Returns a safe, non-sensitive failure reason.
     *
     * IMPORTANT:
     * - Do NOT include exception.message.
     */
    private fun safeThrowableReason(t: Throwable): String {
        return t::class.java.simpleName.ifBlank { "Error" }
    }

    /**
     * Sanitizes a short label for logs/metrics.
     *
     * Notes:
     * - Avoids leaking arbitrary user-derived strings into logs.
     */
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