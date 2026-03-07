/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Root Nav Entries)
 *  ---------------------------------------------------------------------
 *  File: SurveyRootEntries.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.ReviewQuestionLog
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
import com.negi.surveys.ui.chat.ChatQuestionScreen
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController

/**
 * Builds the root navigation entries without storing extra flow collectors inside destinations.
 */
@Composable
internal fun rememberSurveyNavEntries(
    nav: AppNavigator,
    prompts: Map<String, String>,
    sessionVm: SurveySessionViewModel,
    logsState: State<List<ReviewQuestionLog>>,
    exportTextState: State<String>,
    startManualUpload: (String) -> Unit,
    debugInfoState: State<DebugInfo>,
    warmup: WarmupController?,
    launchRetryAll: (String) -> Unit,
    uploadStatusState: State<String?>,
    onDeviceEnabled: Boolean,
    rootModelStateState: State<ModelDownloadController.ModelState>,
    rootPrefetchStateState: State<WarmupController.PrefetchState>,
    rootCompileStateState: State<WarmupController.CompileState>,
): (NavKey) -> NavEntry<NavKey> {
    return remember(
        nav,
        prompts,
        sessionVm,
        logsState,
        exportTextState,
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
                    debugInfo = debugInfoState.value,
                )
            }

            entry<SurveyStart> {
                val readyWarmup = warmup
                if (readyWarmup == null) {
                    SurveyAppShell.BlockingBody(
                        title = "Preparing app services…",
                        detail = "Warmup service is not ready yet.",
                        showSpinner = true,
                    )
                } else {
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
                            warmupController = readyWarmup,
                            debugInfo = debugInfoState.value,
                        )
                    }
                }
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                GateOrLatchedContent(
                    enabled = onDeviceEnabled,
                    policy = GatePolicy.MODEL_PREFETCH_COMPILE,
                    latchKey = key.id,
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
                        onBack = { nav.pop() },
                    )
                }
            }

            entry<Review> {
                ReviewScreen(
                    logs = logsState.value,
                    onExport = { nav.goExport() },
                    onBack = { nav.pop() },
                    onUploadLogs = { startManualUpload("review") },
                    uploadStatusLine = uploadStatusState.value,
                )
            }

            entry<Export> {
                ExportScreen(
                    exportText = exportTextState.value,
                    onBack = { nav.pop() },
                )
            }
        }
    }
}