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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.negi.surveys.BuildConfig as AppBuildConfig
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.GitHubLogUploadManager
import com.negi.surveys.logging.SupabaseLogUploadManager
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.ui.ChatQuestionScreen
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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
        Log.d(TAG, "BackHandler: pop requested. stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}")
        AppLog.d(TAG, "BackHandler: pop requested. stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}")
        nav.pop()
    }

    val prompts: Map<String, String> = remember {
        linkedMapOf(
            "Q1" to "Question prompt for Q1 (placeholder)",
            "Q2" to "Question prompt for Q2 (placeholder)"
        )
    }

    val logsState = sessionVm.logs.collectAsStateWithLifecycle()
    val logs = logsState.value

    val exportTextState = sessionVm.exportJson.collectAsStateWithLifecycle()
    val exportText = exportTextState.value

    val streamBridge: ChatStreamBridge = remember {
        ChatStreamBridge(
            logger = {
                Log.d("StreamBridge", it)
                AppLog.d("StreamBridge", it)
            }
        )
    }

    val streamStats: ChatStreamBridge.StreamStats by streamBridge.stats.collectAsStateWithLifecycle()

    if (AppBuildConfig.DEBUG) {
        LaunchedEffect(
            currentKey,
            canPop,
            logs.size,
            exportText.length,
            streamStats.activeSessionId,
            streamStats.droppedEvents
        ) {
            Log.d(
                TAG,
                "NavState: size=${backStack.size} canPop=$canPop current=${currentKey.javaClass.simpleName} " +
                        "logs=${logs.size} exportLen=${exportText.length} streamActive=${streamStats.activeSessionId} " +
                        "dropped=${streamStats.droppedEvents}"
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
        streamStats.ignoredCancel
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
                DebugRow("StreamAgeMs", streamStats.lastEventAgeMs.toString())
            )
        )
    }

    val latestDebugInfo by rememberUpdatedState(debugInfo)

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    val latestUploadStatus by rememberUpdatedState(uploadStatus)

    val startManualUpload: (String) -> Unit = remember(appContext, scope) {
        { from ->
            scope.launch(Dispatchers.IO) {
                val reason = "manual:$from"

                val gh = runCatching {
                    if (GitHubLogUploadManager.isConfigured()) {
                        GitHubLogUploadManager.uploadRegularBlocking(appContext, reason).getOrNull()
                    } else null
                }.getOrNull()

                val sb = runCatching {
                    if (SupabaseLogUploadManager.isConfigured()) {
                        SupabaseLogUploadManager.uploadRegularBlocking(appContext, reason).getOrNull()
                    } else null
                }.getOrNull()

                val summary = buildString {
                    append("gh=").append(gh ?: "fail").append(" sb=").append(sb ?: "fail")
                }

                AppLog.i(TAG, "manual upload: $summary")
                Log.d(TAG, "manual upload: $summary")

                withContext(Dispatchers.Main) {
                    uploadStatus = summary
                    Toast.makeText(appContext, "Upload: $summary", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val entries: (NavKey) -> NavEntry<NavKey> = remember(nav, prompts, sessionVm) {
        entryProvider {
            entry<Home> {
                HomeScreen(
                    onStartSurvey = { nav.startSurvey() },
                    onExport = { nav.goExport() },
                    debugInfo = latestDebugInfo
                )
            }

            entry<SurveyStart> {
                SurveyStartScreen(
                    onBegin = { nav.beginQuestions("Q1") },
                    onBack = { nav.pop() },
                    debugInfo = latestDebugInfo
                )
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

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

            entry<Review> {
                val reviewLogsState = sessionVm.logs.collectAsStateWithLifecycle()
                val reviewLogs = reviewLogsState.value

                ReviewScreen(
                    logs = reviewLogs,
                    onExport = { nav.goExport() },
                    onBack = { nav.pop() },
                    onUploadLogs = { startManualUpload("review") },
                    uploadStatusLine = latestUploadStatus
                )
            }

            entry<Export> {
                val exportState = sessionVm.exportJson.collectAsStateWithLifecycle()
                val payload = exportState.value

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
        val name = AppBuildConfig.VERSION_NAME
        val code = AppBuildConfig.VERSION_CODE
        val type = AppBuildConfig.BUILD_TYPE
        val dbg = AppBuildConfig.DEBUG
        "v$name ($code) $type dbg=$dbg"
    }.getOrElse { "debug" }
}

private const val TAG = "SurveyAppRoot"