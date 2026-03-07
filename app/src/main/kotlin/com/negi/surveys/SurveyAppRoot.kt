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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.config.SurveyConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SurveyAppRoot {

    const val TAG: String = "SurveyAppRoot"

    /**
     * Allows calling `SurveyAppRoot(...)` even though this is an object.
     *
     * Why:
     * - Keeps call-sites unchanged.
     * - Avoids the confusing `SurveyAppRoot.SurveyAppRoot()` pattern.
     */
    @Composable
    operator fun invoke(modifier: Modifier = Modifier) {
        Render(modifier = modifier)
    }

    @Composable
    fun Render(modifier: Modifier) {
        val appContext = LocalContext.current.applicationContext
        val scope = rememberCoroutineScope()

        val backStack = rememberNavBackStack(Home)
        val nav = remember(backStack) { AppNavigator(backStack) }

        val currentKey by remember(backStack) {
            derivedStateOf { backStack.lastOrNull() ?: Home }
        }
        val canPop by remember(backStack) {
            derivedStateOf { backStack.size > 1 }
        }

        BackHandler(enabled = canPop) {
            SafeLog.d(
                TAG,
                "BackHandler: pop requested stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}",
            )
            nav.pop()
        }

        LaunchedEffect(currentKey) {
            SafeLog.d(
                TAG,
                "Nav: current=${currentKey.javaClass.simpleName} stackSize=${backStack.size}",
            )
        }

        val sessionVm: SurveySessionViewModel = viewModel()
        val logs by sessionVm.logs.collectAsStateWithLifecycle()
        val exportText by sessionVm.exportJson.collectAsStateWithLifecycle()

        /**
         * Keep updated State references so NavEntry composables do NOT re-collect flows.
         *
         * Important:
         * - Do not map/copy lists here.
         * - Do not cast in destination screens.
         */
        val logsState = rememberUpdatedState(logs)
        val exportTextState = rememberUpdatedState(exportText)

        val streamBridge =
            remember {
                ChatStreamBridge(
                    logger = { msg ->
                        SafeLog.d("StreamBridge", "eventLen=${msg.length}")
                    },
                )
            }
        val streamStats by streamBridge.stats.collectAsStateWithLifecycle()

        val startupVm: AppStartupViewModel = viewModel()
        val startupUi by startupVm.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            withFrameNanos {
                /* Wait until the first frame is rendered. */
            }
            startupVm.onFirstFrameRendered()
        }

        val surveyConfig: SurveyConfig? =
            remember(startupUi.configState) {
                (startupUi.configState as? StartupConfigState.Ready)?.cfg
            }

        val repoMode = startupUi.repoMode
        val onDeviceEnabled = startupUi.onDeviceEnabled
        val startupServicesReady = startupUi.servicesReady

        val repo: ChatValidation.RepositoryI? = startupUi.repository
        val warmup = startupUi.warmup
        val modelDownloader = startupUi.modelDownloader

        val rootModelState = startupUi.modelState
        val rootPrefetchState = startupUi.prefetchState
        val rootCompileState = startupUi.compileState

        val rootModelStateState = rememberUpdatedState(rootModelState)
        val rootPrefetchStateState = rememberUpdatedState(rootPrefetchState)
        val rootCompileStateState = rememberUpdatedState(rootCompileState)

        val launchRetryAll: (String) -> Unit =
            remember(startupVm) {
                { fromRaw ->
                    startupVm.retryAll(fromRaw)
                }
            }

        LaunchedEffect(repoMode, onDeviceEnabled) {
            SafeLog.i(TAG, "RepoMode: $repoMode onDeviceEnabled=$onDeviceEnabled")
        }

        LaunchedEffect(modelDownloader, startupServicesReady, onDeviceEnabled) {
            SafeLog.i(
                TAG,
                "StartupServices: ready=$startupServicesReady " +
                        "onDevice=$onDeviceEnabled downloaderPresent=${modelDownloader != null}",
            )
        }

        LogStateTransitions(
            modelState = rootModelState,
            prefetchState = rootPrefetchState,
            compileState = rootCompileState,
        )

        val (prefetchUiLabel, compileUiLabel) =
            rememberWarmupUiLabels(
                prefetchState = rootPrefetchState,
                compileState = rootCompileState,
                tickIntervalMs = 500L,
                format = WARMUP_UI_FORMAT_HOME,
            )

        val modelUiLabel =
            remember(rootModelState) {
                modelLabelForUi(rootModelState)
            }

        val buildLabel = remember { buildLabelSafe() }
        val cfgLabel = remember(startupUi.configState) { startupUi.configState.toDebugLabel() }
        val repoIdLabel = remember(repo) { repo?.let { System.identityHashCode(it).toString() } ?: "null" }
        val repoTypeLabel = remember(repo) { repo?.javaClass?.simpleName ?: "null" }

        val debugInfo =
            remember(
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
                startupServicesReady,
                repoIdLabel,
                repoTypeLabel,
            ) {
                val ignoredTotal =
                    streamStats.ignoredDelta +
                            streamStats.ignoredEnd +
                            streamStats.ignoredError +
                            streamStats.ignoredCancel

                DebugInfo(
                    currentRoute = titleFor(currentKey),
                    backStackSize = backStack.size,
                    buildLabel = buildLabel,
                    extras =
                        listOf(
                            DebugRow("SDK", Build.VERSION.SDK_INT.toString()),
                            DebugRow("Device", "${Build.MANUFACTURER}/${Build.MODEL}"),
                            DebugRow("Config", cfgLabel),
                            DebugRow("RepoMode", repoMode.name),
                            DebugRow("OnDevice", onDeviceEnabled.toString()),
                            DebugRow("StartupReady", startupServicesReady.toString()),
                            DebugRow("Logs", logs.size.toString()),
                            DebugRow("ExportLen", exportText.length.toString()),
                            DebugRow("StreamActive", streamStats.activeSessionId.toString()),
                            DebugRow("StreamDropped", streamStats.droppedEvents.toString()),
                            DebugRow("StreamIgnored", ignoredTotal.toString()),
                            DebugRow("SLM Model", modelUiLabel),
                            DebugRow("SLM Prefetch", prefetchUiLabel),
                            DebugRow("SLM Compile", compileUiLabel),
                            DebugRow("RepoId", repoIdLabel),
                            DebugRow("RepoType", repoTypeLabel),
                        ),
                )
            }

        val debugInfoState = rememberUpdatedState(debugInfo)

        var uploadStatus by remember { mutableStateOf<String?>(null) }
        val uploadStatusState = rememberUpdatedState(uploadStatus)

        val startManualUpload: (String) -> Unit =
            remember(appContext, scope) {
                { fromRaw ->
                    val from = sanitizeLabel(fromRaw)
                    scope.launch(Dispatchers.IO) {
                        val reason = "manual"
                        val gh =
                            runCatching {
                                if (GitHubLogUploadManager.isConfigured()) {
                                    GitHubLogUploadManager.uploadRegularBlocking(appContext, reason).getOrNull()
                                } else {
                                    null
                                }
                            }.onFailure { t ->
                                SafeLog.e(
                                    TAG,
                                    "manual upload failed (non-fatal) type=${t.javaClass.simpleName}",
                                )
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

        val entries =
            rememberSurveyNavEntries(
                nav = nav,
                surveyConfig = surveyConfig,
                sessionVm = sessionVm,
                logsState = logsState,
                exportTextState = exportTextState,
                startManualUpload = startManualUpload,
                debugInfoState = debugInfoState,
                warmup = warmup,
                launchRetryAll = launchRetryAll,
                uploadStatusState = uploadStatusState,
                onDeviceEnabled = onDeviceEnabled,
                rootModelStateState = rootModelStateState,
                rootPrefetchStateState = rootPrefetchStateState,
                rootCompileStateState = rootCompileStateState,
            )

        val startupBlockingUi =
            remember(startupUi) {
                startupUi.toStartupBlockingUi()
            }

        /**
         * Offer retry only for safe failure states or for a post-startup service hole.
         *
         * Notes:
         * - During normal loading we intentionally do not surface retry.
         * - When startup claims services are ready but repository is still null,
         *   the app is in an anomalous blocking state and retry is meaningful.
         */
        val shouldOfferBlockingRetry =
            remember(
                startupUi.configState,
                startupServicesReady,
                repo,
                startupBlockingUi.showSpinner,
            ) {
                when {
                    startupUi.configState is StartupConfigState.Failed -> true
                    startupUi.configState is StartupConfigState.Ready &&
                            startupServicesReady &&
                            repo == null &&
                            !startupBlockingUi.showSpinner -> true
                    else -> false
                }
            }

        val onBlockingRetry: (() -> Unit)? =
            if (shouldOfferBlockingRetry) {
                { launchRetryAll("blockingUi") }
            } else {
                null
            }

        SurveyAppShell(
            modifier = modifier,
            title = titleFor(currentKey),
            canPop = canPop,
            onBack = { nav.pop() },
            backStack = backStack,
            entryProvider = entries,
            streamBridge = streamBridge,
            repository = repo,
            blockingTitle = startupBlockingUi.title,
            blockingDetail = startupBlockingUi.detail,
            showBlockingSpinner = startupBlockingUi.showSpinner,
            onBlockingRetry = onBlockingRetry,
        )
    }

    private fun titleFor(key: Any): String {
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
        }.getOrElse {
            "debug"
        }
    }
}