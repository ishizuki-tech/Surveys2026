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

import android.os.SystemClock
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.warmup.WarmupController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SurveyStart screen (frame-ready).
 *
 * Technical goals:
 * - Provide a deterministic pre-flight step before entering the Question flow.
 * - Prevent accidental double navigation caused by rapid taps or recomposition.
 * - Gate "Begin" until compile warmup is in a ready terminal state to reduce first-question jank.
 *
 * Architecture:
 * - UI depends ONLY on [WarmupController] (facade).
 */
object SurveyStartScreen {

    @Composable
    operator fun invoke(
        onBegin: () -> Unit,
        onBack: () -> Unit,
        warmupController: WarmupController,
        debugInfo: DebugInfo? = null,
    ) {
        SurveyStartScreenInternal.Render(
            onBegin = onBegin,
            onBack = onBack,
            warmupController = warmupController,
            debugInfo = debugInfo,
        )
    }
    private object SurveyStartScreenInternal {

        private const val TAG = "SurveyStartScreen"
        private const val BEGIN_LOCK_TIMEOUT_MS: Long = 2_000L
        private const val COMPILE_REQUEST_DELAY_MS: Long = 250L
        private const val FAILED_MSG_MAX = 160
        private const val PENDING_WAIT_TICK_MS: Long = 250L

        @Composable
        fun Render(
            onBegin: () -> Unit,
            onBack: () -> Unit,
            warmupController: WarmupController,
            debugInfo: DebugInfo?,
        ) {
            val latestOnBegin by rememberUpdatedState(onBegin)
            val latestOnBack by rememberUpdatedState(onBack)
            val latestController by rememberUpdatedState(warmupController)

            val scope = rememberCoroutineScope()

            val compileState: WarmupController.CompileState by
            warmupController.compileState.collectAsStateWithLifecycle()

            val latestCompileState by rememberUpdatedState(compileState)

            var beginLocked by remember { mutableStateOf(false) }
            var beginPendingWarmup by remember { mutableStateOf(false) }

            // Tracks how long the user has been waiting since pressing Begin while not ready.
            var pendingWarmupStartMs by remember { mutableStateOf<Long?>(null) }

            // UI tick for showing increasing wait time while pending.
            var pendingUiNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

            fun requestCompile(reason: String) {
                val st = latestCompileState
                if (st is WarmupController.CompileState.Compiling ||
                    st is WarmupController.CompileState.WaitingForPrefetch
                ) {
                    SafeLog.d(
                        TAG,
                        "compile request skipped: already running reason=$reason state=${st.javaClass.simpleName}",
                    )
                    return
                }

                SafeLog.d(TAG, "compile request: reason=$reason state=${st.javaClass.simpleName}")
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        // Use the unified pipeline API (prefetch -> compile) to stay consistent with SurveyAppRoot.
                        latestController.requestCompileAfterPrefetch(reason = reason)
                    }.onFailure { t ->
                        // IMPORTANT: Do not log t.message (may contain sensitive info).
                        SafeLog.w(
                            TAG,
                            "requestCompileAfterPrefetch failed (non-fatal) type=${t::class.java.simpleName}",
                            t,
                        )
                    }
                }
            }

            fun proceedBegin(source: String) {
                beginLocked = true
                val st = latestCompileState
                val waitedMs = pendingWarmupStartMs?.let { pendingUiNowMs - it }

                SafeLog.i(
                    TAG,
                    "begin proceed: source=$source state=${st.javaClass.simpleName} waitedMs=${waitedMs ?: -1}",
                )

                // Clear pending before navigation to avoid re-entrancy surprises.
                beginPendingWarmup = false
                pendingWarmupStartMs = null

                runCatching { latestOnBegin() }
                    .onFailure { t ->
                        beginLocked = false
                        // IMPORTANT: Do not log t.message (may contain sensitive info).
                        SafeLog.w(
                            TAG,
                            "begin callback failed (unlocked) type=${t::class.java.simpleName}",
                            t,
                        )
                    }
            }

            LaunchedEffect(compileState) {
                SafeLog.d(
                    TAG,
                    "compileState: ${compileState.javaClass.simpleName} elapsedMs=${compileState.elapsedMs}",
                )
            }

            LaunchedEffect(Unit) {
                SafeLog.d(TAG, "composed")

                // Best-effort: ask for compile shortly after the first frame to reduce "Begin wait" time.
                // This should remain lightweight; the controller is expected to be idempotent.
                withFrameNanos { /* first frame */ }
                delay(COMPILE_REQUEST_DELAY_MS)

                if (!isWarmupReady(latestCompileState)) {
                    requestCompile(reason = "enterScreen")
                } else {
                    SafeLog.d(TAG, "enterScreen: warmup already ready state=${latestCompileState.javaClass.simpleName}")
                }
            }

            // Drive a small UI tick while user is waiting, so "Preparing... (xxxms)" actually increases.
            LaunchedEffect(beginPendingWarmup, pendingWarmupStartMs) {
                if (!beginPendingWarmup) return@LaunchedEffect
                if (pendingWarmupStartMs == null) return@LaunchedEffect

                while (isActive && beginPendingWarmup) {
                    pendingUiNowMs = SystemClock.elapsedRealtime()
                    delay(PENDING_WAIT_TICK_MS)
                }
            }

            LaunchedEffect(beginPendingWarmup, compileState) {
                if (!beginPendingWarmup) return@LaunchedEffect

                if (isWarmupReady(compileState)) {
                    val waitedMs = pendingWarmupStartMs?.let { SystemClock.elapsedRealtime() - it } ?: -1
                    SafeLog.i(TAG, "warmup became ready while pending (auto proceed) waitedMs=$waitedMs")
                    proceedBegin(source = "autoAfterWarmup")
                }
            }

            LaunchedEffect(beginLocked, beginPendingWarmup) {
                if (!beginLocked) return@LaunchedEffect
                if (beginPendingWarmup) return@LaunchedEffect

                delay(BEGIN_LOCK_TIMEOUT_MS)
                if (beginLocked) {
                    beginLocked = false
                    SafeLog.w(TAG, "begin auto-unlocked after timeout")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(16.dp)
                    .testTag("survey_start_root"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            SafeLog.w(TAG, "warmup retry requested (SurveyStart) state=${compileState.javaClass.simpleName}")
                            scope.launch(Dispatchers.IO) {
                                runCatching { latestController.resetForRetry(reason = "uiRetry") }
                                    .onFailure { t ->
                                        // IMPORTANT: Do not log t.message (may contain sensitive info).
                                        SafeLog.w(
                                            TAG,
                                            "resetForRetry failed (non-fatal) type=${t::class.java.simpleName}",
                                            t,
                                        )
                                    }
                                requestCompile(reason = "uiRetry")
                            }
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (beginPendingWarmup) {
                                beginPendingWarmup = false
                                pendingWarmupStartMs = null
                                SafeLog.d(TAG, "begin pending cancelled by back")
                            }

                            SafeLog.d(TAG, "click: back")
                            runCatching { latestOnBack() }
                                .onFailure { t ->
                                    // IMPORTANT: Do not log t.message (may contain sensitive info).
                                    SafeLog.w(
                                        TAG,
                                        "back callback failed (non-fatal) type=${t::class.java.simpleName}",
                                        t,
                                    )
                                }
                        },
                        enabled = !beginLocked,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("survey_start_back"),
                    ) {
                        Text("Back")
                    }

                    Button(
                        onClick = {
                            if (beginLocked) {
                                SafeLog.w(TAG, "begin ignored (locked)")
                                return@Button
                            }
                            if (beginPendingWarmup) {
                                SafeLog.w(TAG, "begin ignored (pendingWarmup)")
                                return@Button
                            }

                            val st = compileState
                            SafeLog.d(TAG, "click: begin state=${st.javaClass.simpleName} elapsedMs=${st.elapsedMs}")

                            if (isWarmupReady(st)) {
                                SafeLog.i(TAG, "click: begin (ready)")
                                proceedBegin(source = "immediate")
                                return@Button
                            }

                            beginPendingWarmup = true
                            pendingWarmupStartMs = SystemClock.elapsedRealtime()
                            pendingUiNowMs = SystemClock.elapsedRealtime()
                            SafeLog.i(TAG, "click: begin (waiting for warmup)")
                            requestCompile(reason = "beginClicked")
                        },
                        enabled = !beginLocked && !beginPendingWarmup,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("survey_start_begin"),
                    ) {
                        Text(
                            when {
                                beginPendingWarmup -> "Preparing…"
                                else -> "Begin (Q1)"
                            },
                        )
                    }
                }

                if (beginLocked) {
                    Text("Starting…")
                } else if (beginPendingWarmup) {
                    val waitedMs = pendingWarmupStartMs?.let { (pendingUiNowMs - it).coerceAtLeast(0L) }
                    Text(
                        if (waitedMs != null) {
                            "Preparing the AI model… (${waitedMs}ms)"
                        } else {
                            "Preparing the AI model… (this may take a while on first run)"
                        },
                    )
                }
            }
        }

        @Composable
        private fun WarmupStatusPanel(
            compileState: WarmupController.CompileState,
            onRetry: () -> Unit,
        ) {
            val label = when (compileState) {
                is WarmupController.CompileState.Idle -> "Idle"
                is WarmupController.CompileState.WaitingForPrefetch -> "Waiting for prefetch…"
                is WarmupController.CompileState.Compiling -> "Compiling…"
                is WarmupController.CompileState.Compiled -> "Compiled"
                is WarmupController.CompileState.Cancelled -> "Cancelled"
                is WarmupController.CompileState.SkippedNotReady -> "Not configured"
                is WarmupController.CompileState.Failed -> {
                    val msg = compileState.message.replace('\n', ' ').take(FAILED_MSG_MAX)
                    "Failed: $msg"
                }
            }

            val elapsed = compileState.elapsedMs
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$label  (elapsed=${elapsed}ms)")
                if (compileState is WarmupController.CompileState.Failed ||
                    compileState is WarmupController.CompileState.Cancelled
                ) {
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
         * - SkippedNotReady => treat as ready so the UI isn't hard-blocked on devices without a model.
         */
        private fun isWarmupReady(state: WarmupController.CompileState): Boolean {
            return when (state) {
                is WarmupController.CompileState.Compiled,
                is WarmupController.CompileState.SkippedNotReady -> true
                else -> false
            }
        }
    }
}