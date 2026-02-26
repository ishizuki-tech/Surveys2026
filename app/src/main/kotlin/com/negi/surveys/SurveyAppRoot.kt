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
import android.util.Log
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
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "BackHandler: pop requested. stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}")
        }
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
                if (BuildConfig.DEBUG) Log.d("StreamBridge", msg)
                AppLog.d("StreamBridge", msg)
            }
        )
    }

    val streamStats: ChatStreamBridge.StreamStats by streamBridge.stats.collectAsStateWithLifecycle()

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // --- SLM warmup state ---
    val warmupState: SlmWarmup.PrefetchState by SlmWarmup.state.collectAsStateWithLifecycle()

    // Raw label: changes only when state emits (good for logs).
    val warmupRawLabel: String = remember(warmupState) { warmupLabelFor(warmupState) }

    // UI timing (monotonic) so "Initializing ..." updates in real-time AND "Initialized in X" is available.
    val inProgress = warmupState is SlmWarmup.PrefetchState.Running || warmupState is SlmWarmup.PrefetchState.Initializing

    var warmupStartedAtMs by remember { mutableStateOf<Long?>(null) }
    var warmupFinalElapsedMs by remember { mutableStateOf<Long?>(null) }

    var warmupUiNowMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    // Pin start time / finalize elapsed exactly once per run.
    LaunchedEffect(warmupState) {
        val now = android.os.SystemClock.elapsedRealtime()
        when (val s = warmupState) {
            is SlmWarmup.PrefetchState.Idle -> {
                warmupStartedAtMs = null
                warmupFinalElapsedMs = null
            }

            is SlmWarmup.PrefetchState.Running -> {
                if (warmupStartedAtMs == null) {
                    warmupStartedAtMs = (now - s.elapsedMs).coerceAtMost(now)
                }
                warmupFinalElapsedMs = null
            }

            is SlmWarmup.PrefetchState.Initializing -> {
                if (warmupStartedAtMs == null) {
                    warmupStartedAtMs = (now - s.elapsedMs).coerceAtMost(now)
                }
                warmupFinalElapsedMs = null
            }

            is SlmWarmup.PrefetchState.Initialized -> {
                if (warmupFinalElapsedMs == null) {
                    val start = warmupStartedAtMs
                    warmupFinalElapsedMs = if (start != null) (now - start).coerceAtLeast(0L) else null
                }
            }

            is SlmWarmup.PrefetchState.Failed -> {
                if (warmupFinalElapsedMs == null) {
                    val start = warmupStartedAtMs
                    warmupFinalElapsedMs = if (start != null) (now - start).coerceAtLeast(0L) else null
                }
            }

            is SlmWarmup.PrefetchState.Cancelled -> {
                if (warmupFinalElapsedMs == null) {
                    val start = warmupStartedAtMs
                    warmupFinalElapsedMs = if (start != null) (now - start).coerceAtLeast(0L) else null
                }
            }

            is SlmWarmup.PrefetchState.SkippedNotConfigured -> {
                if (warmupFinalElapsedMs == null) {
                    val start = warmupStartedAtMs
                    warmupFinalElapsedMs = if (start != null) (now - start).coerceAtLeast(0L) else null
                }
            }
        }
    }

    // UI ticker: updates ONLY while warmup is in progress (good for DebugRow/Home/Gate screen).
    LaunchedEffect(inProgress) {
        if (!inProgress) return@LaunchedEffect
        while (true) {
            warmupUiNowMs = android.os.SystemClock.elapsedRealtime()
            delay(250L)
        }
    }

    val displayElapsedMs: Long = when {
        inProgress && warmupStartedAtMs != null -> (warmupUiNowMs - warmupStartedAtMs!!).coerceAtLeast(0L)
        warmupFinalElapsedMs != null -> warmupFinalElapsedMs!!
        else -> extractElapsedMs(warmupState) ?: 0L
    }

    val warmupUiLabel: String = remember(warmupState, displayElapsedMs, warmupFinalElapsedMs) {
        warmupLabelForUi(warmupState, displayElapsedMs, warmupFinalElapsedMs)
    }

    // Use updated states so the entryProvider can safely reference changing values without rebuilding.
    val warmupStateState: State<SlmWarmup.PrefetchState> = rememberUpdatedState(warmupState)
    val warmupUiLabelState: State<String> = rememberUpdatedState(warmupUiLabel)

    LaunchedEffect(Unit) {
        // Warmup is idempotent per process.
        SlmWarmup.startWarmupIfConfigured(appContext)
    }

    if (BuildConfig.DEBUG) {
        // Keep the raw log (no spam during the UI ticker).
        LaunchedEffect(warmupRawLabel) {
            Log.d(TAG, "SLM Warmup: $warmupRawLabel")
            AppLog.d(TAG, "SLM Warmup: $warmupRawLabel")
        }

        // NEW: print the "in XXs" version only when warmup is finalized.
        LaunchedEffect(warmupState, warmupFinalElapsedMs) {
            when (warmupState) {
                is SlmWarmup.PrefetchState.Initialized,
                is SlmWarmup.PrefetchState.Failed,
                is SlmWarmup.PrefetchState.Cancelled -> {
                    Log.d(TAG, "SLM Warmup Final: $warmupUiLabel")
                    AppLog.d(TAG, "SLM Warmup Final: $warmupUiLabel")
                }
                else -> Unit
            }
        }
    }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(
            currentKey,
            canPop,
            logs.size,
            exportText.length,
            streamStats.activeSessionId,
            streamStats.droppedEvents,
            warmupRawLabel
        ) {
            Log.d(
                TAG,
                "NavState: size=${backStack.size} canPop=$canPop current=${currentKey.javaClass.simpleName} " +
                        "logs=${logs.size} exportLen=${exportText.length} streamActive=${streamStats.activeSessionId} " +
                        "dropped=${streamStats.droppedEvents} slmWarmup=$warmupRawLabel"
            )
        }
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
        warmupUiLabel,
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
                DebugRow("SLM Warmup", warmupUiLabel),
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
                    } else null
                }.getOrNull()

                val summary = "gh=" + (gh ?: "fail")
                AppLog.i(TAG, "manual upload: $summary")
                if (BuildConfig.DEBUG) Log.d(TAG, "manual upload: $summary")

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
        warmupStateState,
        warmupUiLabelState
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
                SurveyStartScreen(
                    onBegin = { nav.beginQuestions("Q1") },
                    onBack = { nav.pop() },
                    debugInfo = debugInfoState.value
                )
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                val wu = warmupStateState.value
                val isWarmupBlocking = wu is SlmWarmup.PrefetchState.Running || wu is SlmWarmup.PrefetchState.Initializing

                if (isWarmupBlocking) {
                    SlmWarmupGateScreen(
                        state = wu,
                        label = warmupUiLabelState.value,
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
    state: SlmWarmup.PrefetchState,
    label: String,
    onBack: () -> Unit
) {
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
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            val hint = when (state) {
                is SlmWarmup.PrefetchState.Running -> "Downloading model files. This is a one-time cost per device."
                is SlmWarmup.PrefetchState.Initializing -> "Initializing/compiling model. UI may stutter briefly."
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

private fun warmupLabelFor(state: SlmWarmup.PrefetchState): String {
    return when (state) {
        is SlmWarmup.PrefetchState.Idle -> "Idle"

        is SlmWarmup.PrefetchState.Running -> {
            val total = state.total
            if (total != null && total > 0L) {
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                "Downloading ${pct}% (${state.downloaded}/${total}B) ${state.elapsedMs}ms"
            } else {
                "Downloading ${state.downloaded}B ${state.elapsedMs}ms"
            }
        }

        is SlmWarmup.PrefetchState.Initializing -> {
            "Initializing (${state.file.name}) ${state.elapsedMs}ms"
        }

        is SlmWarmup.PrefetchState.Initialized -> {
            "Initialized (${state.file.name})"
        }

        is SlmWarmup.PrefetchState.Failed -> "Failed (${state.message})"
        is SlmWarmup.PrefetchState.Cancelled -> "Cancelled"
        is SlmWarmup.PrefetchState.SkippedNotConfigured -> "Skipped(${state.reason})"
    }
}

private fun extractElapsedMs(state: SlmWarmup.PrefetchState): Long? {
    return when (state) {
        is SlmWarmup.PrefetchState.Running -> state.elapsedMs
        is SlmWarmup.PrefetchState.Initializing -> state.elapsedMs
        else -> null
    }
}

private fun warmupLabelForUi(
    state: SlmWarmup.PrefetchState,
    elapsedMs: Long,
    finalElapsedMs: Long?,
): String {
    fun suffix(prefix: String): String {
        val ms = finalElapsedMs ?: elapsedMs
        return if (ms > 0L) " $prefix ${formatElapsed(ms)}" else ""
    }

    return when (state) {
        is SlmWarmup.PrefetchState.Idle -> "Idle"

        is SlmWarmup.PrefetchState.Running -> {
            val total = state.total
            val elapsed = formatElapsed(elapsedMs)
            if (total != null && total > 0L) {
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                "Downloading ${pct}% (${state.downloaded}/${total}B) $elapsed"
            } else {
                "Downloading ${state.downloaded}B $elapsed"
            }
        }

        is SlmWarmup.PrefetchState.Initializing -> {
            "Initializing (${state.file.name}) ${formatElapsed(elapsedMs)}"
        }

        is SlmWarmup.PrefetchState.Initialized -> {
            "Initialized (${state.file.name})${suffix("in")}"
        }

        is SlmWarmup.PrefetchState.Failed -> "Failed (${state.message})${suffix("after")}"
        is SlmWarmup.PrefetchState.Cancelled -> "Cancelled${suffix("after")}"
        is SlmWarmup.PrefetchState.SkippedNotConfigured -> "Skipped(${state.reason})"
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