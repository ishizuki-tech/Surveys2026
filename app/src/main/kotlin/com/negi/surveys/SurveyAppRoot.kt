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
import com.negi.surveys.slm.ProvideSlmWarmupController
import com.negi.surveys.ui.ChatQuestionScreen
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
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

/**
 * Lightweight config loading state for the app root.
 *
 * Notes:
 * - Keep this at file scope (Kotlin disallows local interfaces).
 */
private sealed interface ConfigState {
    data object Loading : ConfigState
    data class Ready(val cfg: SurveyConfig) : ConfigState
    data class Failed(val message: String) : ConfigState
}

@Composable
fun StartupPrefetchEffect(
    prefetch: suspend () -> Unit,
    delayMsAfterFirstFrame: Long = 150L,
    onRequested: (() -> Unit)? = null,
) {
    val prefetchLatest by rememberUpdatedState(prefetch)
    val onRequestedLatest by rememberUpdatedState(onRequested)

    LaunchedEffect(Unit) {
        withFrameNanos { /* first frame */ }
        if (delayMsAfterFirstFrame > 0L) delay(delayMsAfterFirstFrame)

        runCatching { onRequestedLatest?.invoke() }
        runCatching { prefetchLatest() }
            .onFailure { t -> SafeLog.e(TAG, "StartupPrefetchEffect: prefetch failed (non-fatal)", t) }
    }
}

@Composable
fun SurveyAppRoot(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)
    val nav = remember(backStack) { AppNavigator(backStack) }

    val currentKey: NavKey by remember(backStack) {
        derivedStateOf { backStack.lastOrNull() ?: Home }
    }
    val canPop: Boolean by remember(backStack) {
        derivedStateOf { backStack.size > 1 }
    }

    val currentKeyState: State<NavKey> = rememberUpdatedState(currentKey)
    val backStackSizeState: State<Int> = rememberUpdatedState(backStack.size)

    BackHandler(enabled = canPop) {
        SafeLog.d(
            TAG,
            "BackHandler: pop requested stackSize=${backStackSizeState.value} current=${currentKeyState.value.javaClass.simpleName}"
        )
        nav.pop()
    }

    LaunchedEffect(currentKey) {
        SafeLog.d(
            TAG,
            "Nav: current=${currentKey.javaClass.simpleName} stackSize=${backStack.size}"
        )
    }

    // ---------------------------------------------------------------------
    // Session / streaming debug
    // ---------------------------------------------------------------------

    val sessionVm: SurveySessionViewModel = viewModel()
    val logs by sessionVm.logs.collectAsStateWithLifecycle()
    val exportText by sessionVm.exportJson.collectAsStateWithLifecycle()

    val prompts: Map<String, String> = remember {
        linkedMapOf(
            "Q1" to "Question prompt for Q1 (placeholder)",
            "Q2" to "Question prompt for Q2 (placeholder)"
        )
    }

    val streamBridge: ChatStreamBridge = remember {
        ChatStreamBridge(logger = { msg -> SafeLog.d("StreamBridge", msg) })
    }
    val streamStats: ChatStreamBridge.StreamStats by streamBridge.stats.collectAsStateWithLifecycle()

    // ---------------------------------------------------------------------
    // Load SurveyConfig and resolve ModelDownloadSpec
    // ---------------------------------------------------------------------

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
                ConfigState.Ready(it)
            },
            onFailure = {
                SafeLog.e(TAG, "Config: failed in ${SystemClock.elapsedRealtime() - t0}ms", it)
                ConfigState.Failed("${it.javaClass.simpleName}(${it.message})")
            }
        )
    }

    val modelSpec: ModelDownloadSpec? = remember(configState) {
        when (val st = configState) {
            is ConfigState.Ready -> st.cfg.resolveModelDownloadSpec()
            else -> null
        }
    }

    LaunchedEffect(modelSpec) {
        val spec = modelSpec
        if (spec == null) {
            SafeLog.w(TAG, "ModelSpec: null (config not ready or not configured)")
            return@LaunchedEffect
        }
        // Do not log tokens. Keep it non-sensitive.
        SafeLog.i(TAG, "ModelSpec: file=${spec.fileName} throttleMs=${spec.uiThrottleMs} minDelta=${spec.uiMinDeltaBytes}")
    }

    // ---------------------------------------------------------------------
    // Process-scoped controllers
    // ---------------------------------------------------------------------

    val warmup: WarmupController = remember(appContext) { AppProcessServices.warmupController(appContext) }
    val modelDownloader: ModelDownloadController = remember(appContext, modelSpec) {
        AppProcessServices.modelDownloader(appContext, modelSpec)
    }

    val rootPrefetchState: PrefetchState by warmup.prefetchState.collectAsStateWithLifecycle()
    val rootCompileState: CompileState by warmup.compileState.collectAsStateWithLifecycle()
    val modelState: ModelDownloadController.ModelState by modelDownloader.state.collectAsStateWithLifecycle()

    // ---------------------------------------------------------------------
    // Startup effects: ensure model + schedule warmup
    // ---------------------------------------------------------------------

    StartupPrefetchEffect(
        prefetch = {
            val spec = modelSpec
            if (spec != null) {
                runCatching {
                    modelDownloader.ensureModelOnce(timeoutMs = spec.timeoutMs, reason = "rootStartup")
                }.onFailure { t ->
                    SafeLog.e(TAG, "Model ensure failed (rootStartup, non-fatal)", t)
                }
            } else {
                SafeLog.w(TAG, "Model ensure skipped: modelSpec is null (config not ready)")
            }
        },
        delayMsAfterFirstFrame = 150L,
        onRequested = { SafeLog.d(TAG, "Model ensure requested (startup)") }
    )

    LaunchedEffect(modelSpec) {
        val spec = modelSpec ?: return@LaunchedEffect
        runCatching {
            modelDownloader.ensureModelOnce(timeoutMs = spec.timeoutMs, reason = "configReady")
        }.onFailure { t ->
            SafeLog.e(TAG, "Model ensure failed (configReady, non-fatal)", t)
        }
    }

    LaunchedEffect(modelState) {
        if (modelState is ModelDownloadController.ModelState.Ready) {
            SafeLog.i(TAG, "Model Ready -> request warmup compileAfterPrefetch")
            runCatching {
                warmup.requestCompileAfterPrefetch(reason = "modelReady")
            }.onFailure { t ->
                SafeLog.e(TAG, "Warmup request failed (non-fatal)", t)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Debug labels (home-level)
    // ---------------------------------------------------------------------

    val (prefetchUiLabel, compileUiLabel) = rememberWarmupUiLabels(
        prefetchState = rootPrefetchState,
        compileState = rootCompileState,
        tickIntervalMs = 500L,
        format = WARMUP_UI_FORMAT_HOME
    )

    val modelUiLabel: String = remember(modelState) { modelLabelForUi(modelState) }
    val buildLabel = remember { buildLabelSafe() }

    val cfgLabel = remember(configState) {
        when (val st = configState) {
            is ConfigState.Loading -> "Loading"
            is ConfigState.Ready -> "Ready"
            is ConfigState.Failed -> "Failed (${st.message})"
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
        compileUiLabel
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
                DebugRow("Logs", logs.size.toString()),
                DebugRow("ExportLen", exportText.length.toString()),
                DebugRow("StreamActive", streamStats.activeSessionId.toString()),
                DebugRow("StreamDropped", streamStats.droppedEvents.toString()),
                DebugRow("StreamIgnored", ignoredTotal.toString()),
                DebugRow("SLM Model", modelUiLabel),
                DebugRow("SLM Prefetch", prefetchUiLabel),
                DebugRow("SLM Compile", compileUiLabel),
            )
        )
    }

    val debugInfoState: State<DebugInfo> = rememberUpdatedState(debugInfo)

    // ---------------------------------------------------------------------
    // Manual upload action (debug)
    // ---------------------------------------------------------------------

    var uploadStatus by remember { mutableStateOf<String?>(null) }
    val uploadStatusState: State<String?> = rememberUpdatedState(uploadStatus)

    val startManualUpload: (String) -> Unit = remember(appContext, scope) {
        { from ->
            scope.launch(Dispatchers.IO) {
                val reason = "manual:$from"
                val gh = runCatching {
                    if (GitHubLogUploadManager.isConfigured()) {
                        GitHubLogUploadManager.uploadRegularBlocking(appContext, reason).getOrNull()
                    } else {
                        null
                    }
                }.onFailure { t ->
                    SafeLog.e(TAG, "manual upload failed (non-fatal)", t)
                }.getOrNull()

                val summary = "gh=" + (gh ?: "fail")
                SafeLog.i(TAG, "manual upload: $summary")

                withContext(Dispatchers.Main) {
                    uploadStatus = summary
                    Toast.makeText(appContext, "Upload: $summary", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Nav3 entries
    // ---------------------------------------------------------------------

    val entries: (NavKey) -> NavEntry<NavKey> = remember(
        nav,
        prompts,
        sessionVm,
        startManualUpload,
        debugInfoState,
        warmup,
        modelDownloader,
        modelSpec
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
                val ms: ModelDownloadController.ModelState by modelDownloader.state.collectAsStateWithLifecycle()
                val ps: PrefetchState by warmup.prefetchState.collectAsStateWithLifecycle()
                val cs: CompileState by warmup.compileState.collectAsStateWithLifecycle()

                val modelBlocking = ms !is ModelDownloadController.ModelState.Ready
                if (modelBlocking) {
                    SlmGateScreen(
                        modelState = ms,
                        prefetchState = ps,
                        compileState = cs,
                        onBack = { nav.pop() },
                        onRetry = {
                            modelDownloader.resetForRetry(reason = "uiRetry")
                            val spec = modelSpec
                            if (spec != null) {
                                runCatching {
                                    modelDownloader.ensureModelOnce(timeoutMs = spec.timeoutMs, reason = "uiRetry")
                                }.onFailure { t ->
                                    SafeLog.e(TAG, "Model ensure failed (uiRetry, non-fatal)", t)
                                }
                            } else {
                                SafeLog.w(TAG, "uiRetry: modelSpec is null (config not ready)")
                            }
                        }
                    )
                } else {
                    // Critical fix: pass the shared controller to avoid state split.
                    SurveyStartScreen(
                        onBegin = { nav.beginQuestions("Q1") },
                        onBack = { nav.pop() },
                        debugInfo = debugInfoState.value,
                        warmupControllerOverride = warmup
                    )
                }
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                val ms: ModelDownloadController.ModelState by modelDownloader.state.collectAsStateWithLifecycle()
                val ps: PrefetchState by warmup.prefetchState.collectAsStateWithLifecycle()
                val cs: CompileState by warmup.compileState.collectAsStateWithLifecycle()

                val modelBlocking = ms !is ModelDownloadController.ModelState.Ready
                val prefetchBusy = isPrefetchInProgress(ps)

                // Important fix:
                // - Do NOT treat Idle as blocking; it can deadlock the UX if compile isn't requested yet.
                val compileBusy =
                    cs is CompileState.WaitingForPrefetch ||
                            cs is CompileState.Compiling

                val isBlocking = modelBlocking || prefetchBusy || compileBusy

                if (isBlocking) {
                    SlmGateScreen(
                        modelState = ms,
                        prefetchState = ps,
                        compileState = cs,
                        onBack = { nav.pop() },
                        onRetry = {
                            modelDownloader.resetForRetry(reason = "uiRetry")
                            val spec = modelSpec
                            if (spec != null) {
                                runCatching {
                                    modelDownloader.ensureModelOnce(timeoutMs = spec.timeoutMs, reason = "uiRetry")
                                }.onFailure { t ->
                                    SafeLog.e(TAG, "Model ensure failed (uiRetry, non-fatal)", t)
                                }
                            } else {
                                SafeLog.w(TAG, "uiRetry: modelSpec is null (config not ready)")
                            }
                        }
                    )
                } else {
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
            ProvideSlmWarmupController(controller = warmup) {
                CompositionLocalProvider(LocalChatStreamBridge provides streamBridge) {
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = entries,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun SlmGateScreen(
    modelState: ModelDownloadController.ModelState,
    prefetchState: PrefetchState,
    compileState: CompileState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val prefetchInProgress = isPrefetchInProgress(prefetchState)
    val compileInProgress = compileState is CompileState.WaitingForPrefetch || compileState is CompileState.Compiling
    val modelInProgress = modelState is ModelDownloadController.ModelState.Checking ||
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
            prefetchInProgress -> prefetchLabelForUi(prefetchState, elapsedMs = prefetchElapsedMs, format = WARMUP_UI_FORMAT_GATE)
            compileState is CompileState.WaitingForPrefetch -> compileLabelForUi(compileState, elapsedMs = compileElapsedMs, format = WARMUP_UI_FORMAT_GATE)
            compileState is CompileState.Compiling -> compileLabelForUi(compileState, elapsedMs = compileElapsedMs, format = WARMUP_UI_FORMAT_GATE)
            else -> WARMUP_UI_FORMAT_GATE.idleLabel
        }
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
                        "Model URL is not configured in SurveyConfig (model_defaults.default_model_url)."
                    is ModelDownloadController.ModelState.Failed ->
                        "Download failed. Check connectivity / token / URL."
                    is ModelDownloadController.ModelState.Cancelled ->
                        "Download cancelled."
                    else -> when (compileState) {
                        is CompileState.WaitingForPrefetch ->
                            "Waiting for prefetch to complete before compilation."
                        is CompileState.Compiling ->
                            "Compiling/initializing the model. UI may stutter briefly."
                        is CompileState.Idle ->
                            "Compilation can run in background. You may proceed."
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

            val showRetry =
                modelState is ModelDownloadController.ModelState.Failed ||
                        modelState is ModelDownloadController.ModelState.Cancelled

            if (showRetry) {
                OutlinedButton(onClick = onRetry) {
                    Text("Retry download")
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
// Warmup UI Formatting
// ---------------------------------------------------------------------

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

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

/**
 * Returns true while the prefetch warmup is actively reading the model file.
 */
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
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                "Downloading ${pct}% (${state.downloaded}/${total}B) ${formatElapsed(state.elapsedMs)}"
            } else {
                "Downloading ${state.downloaded}B ${formatElapsed(state.elapsedMs)}"
            }
        }
        is ModelDownloadController.ModelState.Ready -> "Ready (${state.file.name})"
        is ModelDownloadController.ModelState.Failed -> "Failed (${state.message})"
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
    val name = state.javaClass.simpleName
    val elapsed = formatElapsed(elapsedMs)
    return when (state) {
        is CompileState.WaitingForPrefetch -> "${format.compileWaitingPrefix} $elapsed"
        is CompileState.Compiling -> "${format.compileCompilingPrefix} $elapsed"
        is CompileState.Idle -> format.idleLabel
        else -> "$name $elapsed"
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

private const val TAG = "SurveyAppRoot"
private const val CONFIG_ASSET_NAME = "survey.yaml"