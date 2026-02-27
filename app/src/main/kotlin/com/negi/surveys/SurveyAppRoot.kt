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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.GitHubLogUploadManager
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.slm.SlmWarmup
import com.negi.surveys.ui.ChatQuestionScreen
import com.negi.surveys.ui.CompileWarmupOnFirstNeedEffect
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.StartupPrefetchEffect
import com.negi.surveys.ui.SurveyStartScreen
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SurveyAppRoot(modifier: Modifier = Modifier) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)
    val nav = remember(backStack) { AppNavigator(backStack) }

    val sessionVm: SurveySessionViewModel = viewModel()

    val currentKey: NavKey by remember(backStack) {
        derivedStateOf { backStack.lastOrNull() ?: Home }
    }
    val canPop: Boolean by remember(backStack) {
        derivedStateOf { backStack.size > 1 }
    }

    BackHandler(enabled = canPop) {
        AppLog.d(TAG, "BackHandler: pop requested. stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}")
        nav.pop()
    }

    val prompts: Map<String, String> = remember {
        linkedMapOf(
            "Q1" to "Question prompt for Q1 (placeholder)",
            "Q2" to "Question prompt for Q2 (placeholder)"
        )
    }

    val logs by sessionVm.logs.collectAsStateWithLifecycle()
    val exportText by sessionVm.exportJson.collectAsStateWithLifecycle()

    val streamBridge: ChatStreamBridge = remember {
        ChatStreamBridge(
            logger = { msg ->
                AppLog.d("StreamBridge", msg)
            }
        )
    }

    val streamStats: ChatStreamBridge.StreamStats by streamBridge.stats.collectAsStateWithLifecycle()

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // --- Split warmup states ---
    val prefetchState: SlmWarmup.PrefetchState by SlmWarmup.prefetchState.collectAsStateWithLifecycle()
    val compileState: SlmWarmup.CompileState by SlmWarmup.compileState.collectAsStateWithLifecycle()

    // --- Log labels (state-only) ---
    val prefetchLogLabel: String = remember(prefetchState) { prefetchLabelForLog(prefetchState) }
    val compileLogLabel: String = remember(compileState) { compileLabelForLog(compileState) }

    // --- UI labels (ticking) for Home debug display ---
    val (prefetchUiLabel, compileUiLabel) = rememberWarmupUiLabels(
        prefetchState = prefetchState,
        compileState = compileState,
        tickIntervalMs = 500L,
        format = WARMUP_UI_FORMAT_HOME
    )

    /**
     * Warmup request after the very first frame.
     *
     * Policy:
     * - Start IO-only prefetch early.
     * - As soon as prefetch completes (terminal), immediately request compile.
     *
     * Rationale:
     * - Keep behavior independent from individual screens.
     * - Reduce the chance of heavy compile colliding with SurveyStart/Question transitions.
     */
    StartupPrefetchEffect(
        prefetch = {
            // NOTE: positional args to avoid mismatching parameter names.
            SlmWarmup.requestCompileAfterPrefetch(appContext, "root")
        },
        delayMsAfterFirstFrame = 150L,
        onRequested = {
            AppLog.d(TAG, "SLM Warmup requested (prefetch -> compile) (startup)")
        }
    )

    LaunchedEffect(prefetchLogLabel) {
        AppLog.d(TAG, "SLM Prefetch: $prefetchLogLabel")
    }

    LaunchedEffect(compileLogLabel) {
        AppLog.d(TAG, "SLM Compile: $compileLogLabel")
    }

    LaunchedEffect(
        currentKey,
        canPop,
        logs.size,
        exportText.length,
        streamStats.activeSessionId,
        streamStats.droppedEvents,
        prefetchLogLabel,
        compileLogLabel
    ) {
        AppLog.d(
            TAG,
            "NavState: size=${backStack.size} canPop=$canPop current=${currentKey.javaClass.simpleName} " +
                    "logs=${logs.size} exportLen=${exportText.length} streamActive=${streamStats.activeSessionId} " +
                    "dropped=${streamStats.droppedEvents} prefetch=$prefetchLogLabel compile=$compileLogLabel"
        )
    }

    val buildLabel = remember { buildLabelSafe() }

    val debugInfo: DebugInfo = remember(
        currentKey,
        backStack.size,
        logs.size,
        exportText.length,
        buildLabel,
        streamStats.activeSessionId,
        streamStats.droppedEvents,
        streamStats.ignoredDelta,
        streamStats.ignoredEnd,
        streamStats.ignoredError,
        streamStats.ignoredCancel,
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
                DebugRow("Logs", logs.size.toString()),
                DebugRow("ExportLen", exportText.length.toString()),
                DebugRow("StreamActive", streamStats.activeSessionId.toString()),
                DebugRow("StreamDropped", streamStats.droppedEvents.toString()),
                DebugRow("StreamIgnored", ignoredTotal.toString()),
                DebugRow("StreamLastKind", (streamStats.lastEventKind ?: "-")),
                DebugRow("StreamLastSid", streamStats.lastEventSessionId.toString()),
                DebugRow("StreamAgeMs", streamStats.lastEventAgeMs.toString()),
                DebugRow("SLM Prefetch", prefetchUiLabel),
                DebugRow("SLM Compile", compileUiLabel),
            )
        )
    }

    val debugInfoState: State<DebugInfo> = rememberUpdatedState(debugInfo)

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
                }.getOrNull()

                val summary = "gh=" + (gh ?: "fail")
                AppLog.i(TAG, "manual upload: $summary")

                withContext(Dispatchers.Main) {
                    uploadStatus = summary
                    Toast.makeText(appContext, "Upload: $summary", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val entries: (NavKey) -> NavEntry<NavKey> = remember(
        nav,
        prompts,
        sessionVm,
        startManualUpload,
        debugInfoState,
        appContext
    ) {
        entryProvider {
            entry<Home> {
                HomeScreen(
                    onStartSurvey = {
                        // Keep it light. Warmup is managed at startup in SurveyAppRoot.
                        nav.startSurvey()
                    },
                    onExport = { nav.goExport() },
                    debugInfo = debugInfoState.value
                )
            }

            entry<SurveyStart> {
                CompileWarmupOnFirstNeedEffect(
                    warmupKey = "SurveyStart",
                    delayMsAfterFirstFrame = 250L,
                    onRequested = {
                        AppLog.d(TAG, "SLM Compile requested (surveyStart)")
                    },
                    compileWarmup = { SlmWarmup.startCompileIfConfigured(appContext) }
                )

                SurveyStartScreen(
                    onBegin = {
                        nav.beginQuestions("Q1")
                    },
                    onBack = { nav.pop() },
                    debugInfo = debugInfoState.value
                )
            }

            entry<Question> { key ->
                CompileWarmupOnFirstNeedEffect(
                    warmupKey = key.id,
                    delayMsAfterFirstFrame = 150L,
                    onRequested = {
                        AppLog.d(TAG, "SLM Compile requested (question=${key.id})")
                    },
                    compileWarmup = { SlmWarmup.startCompileIfConfigured(appContext) }
                )

                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                val ps: SlmWarmup.PrefetchState by SlmWarmup.prefetchState.collectAsStateWithLifecycle()
                val cs: SlmWarmup.CompileState by SlmWarmup.compileState.collectAsStateWithLifecycle()

                val isBlocking = ps is SlmWarmup.PrefetchState.Running ||
                        cs is SlmWarmup.CompileState.Idle ||
                        cs is SlmWarmup.CompileState.WaitingForPrefetch ||
                        cs is SlmWarmup.CompileState.Compiling

                if (isBlocking) {
                    SlmWarmupGateScreen(
                        prefetchState = ps,
                        compileState = cs,
                        onBack = { nav.pop() }
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

@Composable
private fun SlmWarmupGateScreen(
    prefetchState: SlmWarmup.PrefetchState,
    compileState: SlmWarmup.CompileState,
    onBack: () -> Unit
) {
    val labelText = rememberWarmupPrimaryUiLabel(
        prefetchState = prefetchState,
        compileState = compileState,
        tickIntervalMs = 250L,
        format = WARMUP_UI_FORMAT_GATE
    )

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
                text = labelText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            val hint = when {
                prefetchState is SlmWarmup.PrefetchState.Running ->
                    "Prefetching model file (IO only). This is a one-time cost per device."
                compileState is SlmWarmup.CompileState.WaitingForPrefetch ->
                    "Waiting for prefetch to complete before compilation."
                compileState is SlmWarmup.CompileState.Compiling ->
                    "Compiling/initializing the model. UI may stutter briefly."
                compileState is SlmWarmup.CompileState.Idle ->
                    "Starting compilation soon (after the first frame)."
                else -> ""
            }

            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
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
// Warmup UI Formatting (Home vs Gate)
// ---------------------------------------------------------------------

private data class WarmupUiFormat(
    val idleLabel: String,
    val prefetchRunningPrefix: String,
    val compileWaitingPrefix: String,
    val compileCompilingPrefix: String,
    val showFileNameInCompileWaiting: Boolean,
    val showFileNameInCompiling: Boolean,
)

private val WARMUP_UI_FORMAT_HOME = WarmupUiFormat(
    idleLabel = "Idle",
    prefetchRunningPrefix = "Running",
    compileWaitingPrefix = "WaitingForPrefetch",
    compileCompilingPrefix = "Compiling",
    showFileNameInCompileWaiting = true,
    showFileNameInCompiling = true,
)

private val WARMUP_UI_FORMAT_GATE = WarmupUiFormat(
    idleLabel = "Preparing…",
    prefetchRunningPrefix = "Prefetch",
    compileWaitingPrefix = "Compile waiting…",
    compileCompilingPrefix = "Compiling",
    showFileNameInCompileWaiting = false,
    showFileNameInCompiling = true,
)

// ---------------------------------------------------------------------
// Label Builders (Split: LOG vs UI)
// ---------------------------------------------------------------------

private fun prefetchLabelForLog(state: SlmWarmup.PrefetchState): String {
    return when (state) {
        is SlmWarmup.PrefetchState.Idle -> "Idle"
        is SlmWarmup.PrefetchState.Running -> {
            val total = state.total
            if (total != null && total > 0L) {
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                "Running ${pct}% (${state.downloaded}/${total}B) ${state.elapsedMs}ms"
            } else {
                "Running ${state.downloaded}B ${state.elapsedMs}ms"
            }
        }
        is SlmWarmup.PrefetchState.Prefetched -> "Prefetched (${state.file.name}) ${state.elapsedMs}ms"
        is SlmWarmup.PrefetchState.Failed -> "Failed (${state.message}) ${state.elapsedMs}ms"
        is SlmWarmup.PrefetchState.Cancelled -> "Cancelled ${state.elapsedMs}ms"
        is SlmWarmup.PrefetchState.SkippedNotConfigured -> "Skipped(${state.reason})"
    }
}

private fun compileLabelForLog(state: SlmWarmup.CompileState): String {
    return when (state) {
        is SlmWarmup.CompileState.Idle -> "Idle"
        is SlmWarmup.CompileState.WaitingForPrefetch ->
            "WaitingForPrefetch (${state.file.name}) ${state.elapsedMs}ms"
        is SlmWarmup.CompileState.Compiling ->
            "Compiling (${state.file.name}) ${state.elapsedMs}ms"
        is SlmWarmup.CompileState.Compiled ->
            "Compiled (${state.file.name}) ${state.elapsedMs}ms"
        is SlmWarmup.CompileState.Failed -> "Failed (${state.message}) ${state.elapsedMs}ms"
        is SlmWarmup.CompileState.Cancelled -> "Cancelled ${state.elapsedMs}ms"
        is SlmWarmup.CompileState.SkippedNotConfigured -> "Skipped(${state.reason})"
    }
}

private fun prefetchLabelForUi(
    state: SlmWarmup.PrefetchState,
    nowMs: Long,
    format: WarmupUiFormat,
): String {
    return when (state) {
        is SlmWarmup.PrefetchState.Running -> {
            val total = state.total
            val elapsed = formatElapsed(nowMs - state.startedAtMs)
            val prefix = format.prefetchRunningPrefix
            if (total != null && total > 0L) {
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                "$prefix ${pct}% (${state.downloaded}/${total}B) $elapsed"
            } else {
                "$prefix ${state.downloaded}B $elapsed"
            }
        }
        is SlmWarmup.PrefetchState.Idle -> format.idleLabel
        else -> prefetchLabelForLog(state)
    }
}

private fun compileLabelForUi(
    state: SlmWarmup.CompileState,
    nowMs: Long,
    format: WarmupUiFormat,
): String {
    return when (state) {
        is SlmWarmup.CompileState.WaitingForPrefetch -> {
            val elapsed = formatElapsed(nowMs - state.requestedAtMs)
            val prefix = format.compileWaitingPrefix
            if (format.showFileNameInCompileWaiting) {
                "$prefix (${state.file.name}) $elapsed"
            } else {
                "$prefix $elapsed"
            }
        }
        is SlmWarmup.CompileState.Compiling -> {
            val elapsed = formatElapsed(nowMs - state.startedAtMs)
            val prefix = format.compileCompilingPrefix
            if (format.showFileNameInCompiling) {
                "$prefix (${state.file.name}) $elapsed"
            } else {
                "$prefix $elapsed"
            }
        }
        is SlmWarmup.CompileState.Idle -> format.idleLabel
        else -> compileLabelForLog(state)
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
        while (coroutineContext.isActive) {
            uiNowMs = SystemClock.elapsedRealtime()
            delay(tickIntervalMs)
        }
    }

    return uiNowMs
}

@Composable
private fun rememberWarmupUiLabels(
    prefetchState: SlmWarmup.PrefetchState,
    compileState: SlmWarmup.CompileState,
    tickIntervalMs: Long,
    format: WarmupUiFormat,
): Pair<String, String> {
    val prefetchInProgress = prefetchState is SlmWarmup.PrefetchState.Running
    val compileInProgress =
        compileState is SlmWarmup.CompileState.WaitingForPrefetch ||
                compileState is SlmWarmup.CompileState.Compiling

    val inProgress = prefetchInProgress || compileInProgress
    val nowMs = rememberWarmupUiNowMs(inProgress = inProgress, tickIntervalMs = tickIntervalMs)

    val prefetchLabel = remember(prefetchState, nowMs, format) {
        prefetchLabelForUi(prefetchState, nowMs = nowMs, format = format)
    }
    val compileLabel = remember(compileState, nowMs, format) {
        compileLabelForUi(compileState, nowMs = nowMs, format = format)
    }

    return prefetchLabel to compileLabel
}

@Composable
private fun rememberWarmupPrimaryUiLabel(
    prefetchState: SlmWarmup.PrefetchState,
    compileState: SlmWarmup.CompileState,
    tickIntervalMs: Long,
    format: WarmupUiFormat,
): String {
    val prefetchInProgress = prefetchState is SlmWarmup.PrefetchState.Running
    val compileInProgress =
        compileState is SlmWarmup.CompileState.WaitingForPrefetch ||
                compileState is SlmWarmup.CompileState.Compiling

    val inProgress = prefetchInProgress || compileInProgress
    val nowMs = rememberWarmupUiNowMs(inProgress = inProgress, tickIntervalMs = tickIntervalMs)

    return remember(prefetchState, compileState, nowMs, format) {
        when {
            prefetchState is SlmWarmup.PrefetchState.Running ->
                prefetchLabelForUi(prefetchState, nowMs = nowMs, format = format)

            compileState is SlmWarmup.CompileState.WaitingForPrefetch ->
                compileLabelForUi(compileState, nowMs = nowMs, format = format)

            compileState is SlmWarmup.CompileState.Compiling ->
                compileLabelForUi(compileState, nowMs = nowMs, format = format)

            else -> format.idleLabel
        }
    }
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