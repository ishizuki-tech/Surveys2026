/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SurveyStartScreen.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.SlmWarmupController
import com.negi.surveys.utils.CompileState
import com.negi.surveys.utils.WarmupController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SurveyStartScreen"
private const val BEGIN_LOCK_TIMEOUT_MS: Long = 2_000L
private const val COMPILE_REQUEST_DELAY_MS: Long = 250L
private const val FAILED_MSG_MAX = 160

/**
 * SurveyStart screen (frame-ready).
 *
 * Technical goals:
 * - Provide a deterministic pre-flight step before entering the Question flow.
 * - Prevent accidental double navigation caused by rapid taps or recomposition.
 * - Gate "Begin" until compile warmup is in a ready terminal state to reduce first-question jank.
 *
 * Architecture:
 * - UI depends ONLY on [WarmupController] (facade). The default implementation is [SlmWarmupController].
 * - Prefer passing a shared controller via [warmupControllerOverride] to avoid state split.
 *
 * Concurrency model:
 * - "Begin" is protected by:
 *   - beginLocked: short-lived lock after invoking navigation.
 *   - beginPendingWarmup: indicates the user requested begin while warmup was not ready.
 * - A timeout unlock is included as a safety valve if navigation fails synchronously.
 */
@Composable
fun SurveyStartScreen(
    onBegin: () -> Unit,
    onBack: () -> Unit,
    debugInfo: DebugInfo? = null,
    warmupControllerOverride: WarmupController? = null,
) {
    val latestOnBegin by rememberUpdatedState(onBegin)
    val latestOnBack by rememberUpdatedState(onBack)

    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val controller: WarmupController = remember(appContext, warmupControllerOverride) {
        warmupControllerOverride ?: SlmWarmupController(
            context = appContext,
            logger = { AppLog.d(TAG, it) }
        )
    }

    val compileState: CompileState by controller.compileState.collectAsStateWithLifecycle()
    val latestCompileState by rememberUpdatedState(compileState)

    var beginLocked by remember { mutableStateOf(false) }
    var beginPendingWarmup by remember { mutableStateOf(false) }

    fun requestCompile(reason: String) {
        val st = latestCompileState
        if (st is CompileState.Compiling || st is CompileState.WaitingForPrefetch) {
            AppLog.d(TAG, "compile request skipped: already running reason=$reason state=${st.javaClass.simpleName}")
            return
        }

        AppLog.d(TAG, "compile request: reason=$reason state=${st.javaClass.simpleName}")
        scope.launch {
            runCatching {
                controller.compileOnce()
            }.onFailure { t ->
                AppLog.w(TAG, "compileOnce failed (non-fatal): ${t.javaClass.simpleName}(${t.message})", t)
            }
        }
    }

    fun proceedBegin(source: String) {
        beginLocked = true
        AppLog.i(TAG, "begin proceed: source=$source state=${latestCompileState.javaClass.simpleName}")

        runCatching { latestOnBegin() }
            .onFailure { t ->
                beginLocked = false
                AppLog.w(TAG, "begin callback failed (unlocked): ${t.javaClass.simpleName}(${t.message})", t)
            }
    }

    LaunchedEffect(compileState) {
        AppLog.d(TAG, "compileState: ${compileState.javaClass.simpleName} elapsedMs=${compileState.elapsedMs}")
    }

    LaunchedEffect(Unit) {
        AppLog.d(TAG, "composed")

        // Best-effort: ask for compile shortly after the first frame to reduce "Begin wait" time.
        // This should remain lightweight; the controller is expected to be idempotent.
        withFrameNanos { /* first frame */ }
        delay(COMPILE_REQUEST_DELAY_MS)

        if (!isWarmupReady(latestCompileState)) {
            requestCompile(reason = "enterScreen")
        }
    }

    LaunchedEffect(beginPendingWarmup, compileState) {
        if (!beginPendingWarmup) return@LaunchedEffect
        if (isWarmupReady(compileState)) {
            beginPendingWarmup = false
            proceedBegin(source = "autoAfterWarmup")
        }
    }

    LaunchedEffect(beginLocked, beginPendingWarmup) {
        if (!beginLocked) return@LaunchedEffect
        if (beginPendingWarmup) return@LaunchedEffect

        delay(BEGIN_LOCK_TIMEOUT_MS)
        if (beginLocked) {
            beginLocked = false
            AppLog.w(TAG, "begin auto-unlocked after timeout")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .padding(16.dp)
            .testTag("survey_start_root"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Survey Start")
        Text("This step prepares the survey session before opening the first question.")

        if (debugInfo != null) {
            Spacer(Modifier.height(6.dp))
            DebugPanel(debugInfo = debugInfo)
        }

        val showWarmupStatus = beginPendingWarmup || !isWarmupReady(compileState)
        if (showWarmupStatus) {
            Spacer(Modifier.height(6.dp))
            WarmupStatusPanel(
                compileState = compileState,
                onRetry = {
                    AppLog.w(TAG, "warmup retry requested (SurveyStart)")
                    scope.launch {
                        runCatching { controller.resetForRetry(reason = "uiRetry") }
                            .onFailure { t ->
                                AppLog.w(
                                    TAG,
                                    "resetForRetry failed (non-fatal): ${t.javaClass.simpleName}(${t.message})",
                                    t
                                )
                            }
                        requestCompile(reason = "uiRetry")
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (beginPendingWarmup) {
                        beginPendingWarmup = false
                        AppLog.d(TAG, "begin pending cancelled by back")
                    }

                    AppLog.d(TAG, "click: back")
                    runCatching { latestOnBack() }
                        .onFailure { t ->
                            AppLog.w(
                                TAG,
                                "back callback failed (non-fatal): ${t.javaClass.simpleName}(${t.message})",
                                t
                            )
                        }
                },
                enabled = !beginLocked,
                modifier = Modifier
                    .weight(1f)
                    .testTag("survey_start_back")
            ) {
                Text("Back")
            }

            Button(
                onClick = {
                    if (beginLocked) {
                        AppLog.w(TAG, "begin ignored (locked)")
                        return@Button
                    }
                    if (beginPendingWarmup) {
                        AppLog.w(TAG, "begin ignored (pendingWarmup)")
                        return@Button
                    }

                    if (isWarmupReady(compileState)) {
                        AppLog.i(TAG, "click: begin (ready)")
                        proceedBegin(source = "immediate")
                        return@Button
                    }

                    beginPendingWarmup = true
                    AppLog.i(TAG, "click: begin (waiting for warmup)")
                    requestCompile(reason = "beginClicked")
                },
                enabled = !beginLocked && !beginPendingWarmup,
                modifier = Modifier
                    .weight(1f)
                    .testTag("survey_start_begin")
            ) {
                Text(
                    when {
                        beginPendingWarmup -> "Preparing…"
                        else -> "Begin (Q1)"
                    }
                )
            }
        }

        if (beginLocked) {
            Text("Starting…")
        } else if (beginPendingWarmup) {
            Text("Preparing the AI model… (this may take a while on first run)")
        }
    }
}

@Composable
private fun WarmupStatusPanel(
    compileState: CompileState,
    onRetry: () -> Unit,
) {
    val label = when (compileState) {
        is CompileState.Idle -> "Idle"
        is CompileState.WaitingForPrefetch -> "Waiting for prefetch…"
        is CompileState.Compiling -> "Compiling…"
        is CompileState.Compiled -> "Compiled"
        is CompileState.Cancelled -> "Cancelled"
        is CompileState.SkippedNotConfigured -> "Not configured"
        is CompileState.Failed -> {
            val msg = compileState.message.replace('\n', ' ').take(FAILED_MSG_MAX)
            "Failed: $msg"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
        if (compileState is CompileState.Failed || compileState is CompileState.Cancelled) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("warmup_retry")) {
                Text("Retry warmup")
            }
        }
    }
}

/**
 * Defines whether the app can proceed to Question flow without risking first-use stalls.
 *
 * Policy:
 * - Compiled => ready.
 * - SkippedNotConfigured => treat as ready so the UI isn't hard-blocked on devices without a model.
 */
private fun isWarmupReady(state: CompileState): Boolean {
    return when (state) {
        is CompileState.Compiled,
        is CompileState.SkippedNotConfigured -> true
        else -> false
    }
}