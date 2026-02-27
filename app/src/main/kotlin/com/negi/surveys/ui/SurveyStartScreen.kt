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

import android.util.Log
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.SlmWarmup
import kotlinx.coroutines.delay

private const val TAG = "SurveyStartScreen"

/**
 * SurveyStart screen (frame-ready).
 *
 * Goals:
 * - Provide a clear "pre-flight" step before starting questions.
 * - Prevent accidental double navigation (e.g., rapid taps).
 * - Gate "Begin" until the SLM compile warmup is ready to avoid UI jank and
 *   reduce the chance of a first-question stall.
 *
 * Notes:
 * - Keep this composable UI-only. It emits intents via callbacks.
 * - Warmup is process-scoped via [SlmWarmup].
 */
@Composable
fun SurveyStartScreen(
    onBegin: () -> Unit,
    onBack: () -> Unit,
    debugInfo: DebugInfo? = null,
) {
    val latestOnBegin by rememberUpdatedState(onBegin)
    val latestOnBack by rememberUpdatedState(onBack)

    val appContext = LocalContext.current.applicationContext

    val compileState by SlmWarmup.compileState.collectAsState()

    /**
     * Guard flag to prevent duplicate navigations caused by rapid multiple taps.
     *
     * Note:
     * - This is a transient UI lock; we intentionally do NOT save it across rotations.
     * - An automatic unlock timeout acts as a safety valve in case navigation fails.
     */
    var beginLocked by remember { mutableStateOf(false) }

    /**
     * User requested "Begin" but the model is not ready yet.
     * We keep the user on this screen and proceed automatically once warmup is ready.
     */
    var beginPendingWarmup by remember { mutableStateOf(false) }

    // Request compile warmup once we have a frame on screen.
    CompileWarmupOnFirstNeedEffect(
        warmupKey = Unit,
        compileWarmup = {
            SlmWarmup.startCompileIfConfigured(appContext)
        },
        delayMsAfterFirstFrame = COMPILE_REQUEST_DELAY_MS,
        onRequested = {
            AppLog.d(TAG, "compile warmup requested")
            if (BuildConfig.DEBUG) Log.d(TAG, "compile warmup requested")
        }
    )

    fun proceedBegin(source: String) {
        beginLocked = true
        AppLog.i(TAG, "begin proceed: source=$source state=${compileState.javaClass.simpleName}")
        if (BuildConfig.DEBUG) Log.d(TAG, "begin proceed: source=$source state=${compileState.javaClass.simpleName}")

        runCatching { latestOnBegin() }
            .onFailure { t ->
                // If begin throws synchronously, unlock immediately to avoid trapping the UI.
                beginLocked = false
                AppLog.w(TAG, "begin callback failed (unlocked): ${t.javaClass.simpleName}")
                if (BuildConfig.DEBUG) Log.w(TAG, "begin callback failed (unlocked)", t)
            }
    }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            AppLog.d(TAG, "composed")
            Log.d(TAG, "composed")
        }
    }

    // If the user requested Begin early, proceed automatically once warmup is ready.
    LaunchedEffect(beginPendingWarmup, compileState) {
        if (!beginPendingWarmup) return@LaunchedEffect
        if (isWarmupReady(compileState)) {
            beginPendingWarmup = false
            proceedBegin(source = "autoAfterWarmup")
        }
    }

    /**
     * Auto-unlock if we stay on this screen for too long after we actually invoked navigation.
     * If navigation succeeds, this composable will typically leave composition, so this effect cancels.
     *
     * Important:
     * - Do NOT apply this timeout while waiting for warmup.
     */
    LaunchedEffect(beginLocked) {
        if (!beginLocked) return@LaunchedEffect
        delay(BEGIN_LOCK_TIMEOUT_MS)
        if (beginLocked) {
            beginLocked = false
            AppLog.w(TAG, "begin auto-unlocked after timeout")
            if (BuildConfig.DEBUG) Log.d(TAG, "begin auto-unlocked after timeout")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            /**
             * App-level TopBar consumes TOP statusBars inset.
             * Apply only Horizontal + Bottom safeDrawing here to avoid double insets.
             */
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

        // Warmup status (show only when it matters, to keep the UI clean).
        val showWarmupStatus = beginPendingWarmup || !isWarmupReady(compileState)
        if (showWarmupStatus) {
            Spacer(Modifier.height(6.dp))
            WarmupStatusPanel(
                compileState = compileState,
                onRetry = {
                    AppLog.w(TAG, "warmup retry requested")
                    if (BuildConfig.DEBUG) Log.d(TAG, "warmup retry requested")
                    SlmWarmup.resetForRetry(reason = "uiRetry")
                    SlmWarmup.startCompileIfConfigured(appContext)
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
                        // Cancel "pending begin" and return.
                        beginPendingWarmup = false
                        AppLog.d(TAG, "begin pending cancelled by back")
                        if (BuildConfig.DEBUG) Log.d(TAG, "begin pending cancelled by back")
                    }

                    AppLog.d(TAG, "click: back")
                    if (BuildConfig.DEBUG) Log.d(TAG, "click: back")
                    runCatching { latestOnBack() }
                        .onFailure { t ->
                            AppLog.w(TAG, "back callback failed (non-fatal): ${t.javaClass.simpleName}")
                            if (BuildConfig.DEBUG) Log.w(TAG, "back callback failed (non-fatal)", t)
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "begin ignored (locked)")
                        return@Button
                    }
                    if (beginPendingWarmup) {
                        AppLog.w(TAG, "begin ignored (pendingWarmup)")
                        if (BuildConfig.DEBUG) Log.d(TAG, "begin ignored (pendingWarmup)")
                        return@Button
                    }

                    if (isWarmupReady(compileState)) {
                        AppLog.i(TAG, "click: begin")
                        if (BuildConfig.DEBUG) Log.d(TAG, "click: begin")
                        proceedBegin(source = "immediate")
                        return@Button
                    }

                    // Not ready yet: request warmup (idempotent) and wait on this screen.
                    beginPendingWarmup = true
                    AppLog.i(TAG, "click: begin (waiting for warmup)")
                    if (BuildConfig.DEBUG) Log.d(TAG, "click: begin (waiting for warmup)")
                    SlmWarmup.startCompileIfConfigured(appContext)
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
    compileState: SlmWarmup.CompileState,
    onRetry: () -> Unit,
) {
    val text = when (compileState) {
        is SlmWarmup.CompileState.Idle -> "AI warmup: idle"
        is SlmWarmup.CompileState.WaitingForPrefetch ->
            "AI warmup: waiting for prefetch (${formatElapsed(compileState.elapsedMs)})"
        is SlmWarmup.CompileState.Compiling ->
            "AI warmup: compiling (${formatElapsed(compileState.elapsedMs)})"
        is SlmWarmup.CompileState.Compiled ->
            "AI warmup: ready (${formatElapsed(compileState.elapsedMs)})"
        is SlmWarmup.CompileState.Cancelled ->
            "AI warmup: cancelled (${formatElapsed(compileState.elapsedMs)})"
        is SlmWarmup.CompileState.Failed ->
            "AI warmup: failed (${compileState.message})"
        is SlmWarmup.CompileState.SkippedNotConfigured ->
            "AI warmup: skipped (${compileState.reason})"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text)
        if (compileState is SlmWarmup.CompileState.Failed || compileState is SlmWarmup.CompileState.Cancelled) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("warmup_retry")) {
                Text("Retry warmup")
            }
        }
    }
}

private fun isWarmupReady(state: SlmWarmup.CompileState): Boolean {
    return when (state) {
        is SlmWarmup.CompileState.Compiled,
        is SlmWarmup.CompileState.SkippedNotConfigured -> true
        else -> false
    }
}

private fun formatElapsed(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000.0
    return String.format("%.1fs", sec)
}

private const val BEGIN_LOCK_TIMEOUT_MS: Long = 2_000L
private const val COMPILE_REQUEST_DELAY_MS: Long = 250L