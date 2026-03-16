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
 * - Gate "Begin" until warmup is semantically ready when warmup is required.
 *
 * Readiness model:
 * - CompileState alone is not always sufficient.
 * - Startup may already know that warmup is satisfied through a reusable compile stamp.
 * - This screen therefore accepts [warmupSatisfiedHint] from the caller.
 *
 * Architecture:
 * - UI depends only on [WarmupController] when warmup is actually needed.
 * - For questionless graphs, warmup can be skipped entirely.
 * - Caller-supplied readiness hint wins over local compile-state inference.
 */
object SurveyStartScreen {

    @Composable
    operator fun invoke(
        onBegin: () -> Unit,
        onBack: () -> Unit,
        warmupController: WarmupController?,
        requireWarmup: Boolean,
        warmupSatisfiedHint: Boolean = false,
        debugInfo: DebugInfo? = null,
        descriptionText: String = "This step prepares the survey session before opening the first question.",
        beginLabel: String = "Begin Survey",
    ) {
        SurveyStartScreenInternal.Render(
            onBegin = onBegin,
            onBack = onBack,
            warmupController = warmupController,
            requireWarmup = requireWarmup,
            warmupSatisfiedHint = warmupSatisfiedHint,
            debugInfo = debugInfo,
            descriptionText = descriptionText,
            beginLabel = beginLabel,
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
            warmupController: WarmupController?,
            requireWarmup: Boolean,
            warmupSatisfiedHint: Boolean,
            debugInfo: DebugInfo?,
            descriptionText: String,
            beginLabel: String,
        ) {
            val latestOnBegin by rememberUpdatedState(onBegin)
            val latestOnBack by rememberUpdatedState(onBack)
            val latestController by rememberUpdatedState(warmupController)
            val latestRequireWarmup by rememberUpdatedState(requireWarmup)
            val latestWarmupSatisfiedHint by rememberUpdatedState(warmupSatisfiedHint)
            val latestDescriptionText by rememberUpdatedState(descriptionText)
            val latestBeginLabel by rememberUpdatedState(beginLabel)

            val scope = rememberCoroutineScope()

            val compileState: WarmupController.CompileState =
                if (warmupController != null) {
                    val state by warmupController.compileState.collectAsStateWithLifecycle()
                    state
                } else {
                    WarmupController.CompileState.SkippedNotReady(reason = "WarmupControllerMissing")
                }

            val latestCompileState by rememberUpdatedState(compileState)

            val effectiveWarmupReady =
                isWarmupReady(
                    requireWarmup = requireWarmup,
                    warmupSatisfiedHint = warmupSatisfiedHint,
                    state = compileState,
                )

            val compileLogKey =
                remember(compileState, requireWarmup, warmupSatisfiedHint) {
                    buildCompileLogKey(
                        state = compileState,
                        requireWarmup = requireWarmup,
                        warmupSatisfiedHint = warmupSatisfiedHint,
                    )
                }

            var beginLocked by remember { mutableStateOf(false) }
            var beginPendingWarmup by remember { mutableStateOf(false) }

            var pendingWarmupStartMs by remember { mutableStateOf<Long?>(null) }
            var pendingUiNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

            fun requestCompile(reason: String) {
                val controller = latestController ?: run {
                    SafeLog.d(TAG, "compile request skipped: controller=null reason=$reason")
                    return
                }

                val requireWarmupNow = latestRequireWarmup
                val satisfiedNow =
                    isWarmupReady(
                        requireWarmup = requireWarmupNow,
                        warmupSatisfiedHint = latestWarmupSatisfiedHint,
                        state = latestCompileState,
                    )

                if (!requireWarmupNow) {
                    SafeLog.d(TAG, "compile request skipped: warmup not required reason=$reason")
                    return
                }

                if (satisfiedNow) {
                    SafeLog.d(
                        TAG,
                        "compile request skipped: warmup already satisfied " +
                                "reason=$reason state=${latestCompileState.javaClass.simpleName} hint=${latestWarmupSatisfiedHint}",
                    )
                    return
                }

                val st = latestCompileState
                if (
                    st is WarmupController.CompileState.Compiling ||
                    st is WarmupController.CompileState.WaitingForPrefetch
                ) {
                    SafeLog.d(
                        TAG,
                        "compile request skipped: already running reason=$reason state=${st.javaClass.simpleName}",
                    )
                    return
                }

                SafeLog.d(
                    TAG,
                    "compile request: reason=$reason state=${st.javaClass.simpleName} hint=${latestWarmupSatisfiedHint}",
                )

                scope.launch(Dispatchers.IO) {
                    runCatching {
                        controller.requestCompileAfterPrefetch(reason = reason)
                    }.onFailure { t ->
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
                    "begin proceed: source=$source requireWarmup=$latestRequireWarmup " +
                            "state=${st.javaClass.simpleName} hint=${latestWarmupSatisfiedHint} waitedMs=${waitedMs ?: -1}",
                )

                beginPendingWarmup = false
                pendingWarmupStartMs = null

                runCatching { latestOnBegin() }
                    .onFailure { t ->
                        beginLocked = false
                        SafeLog.w(
                            TAG,
                            "begin callback failed (unlocked) type=${t::class.java.simpleName}",
                            t,
                        )
                    }
            }

            /**
             * Log only on meaningful state transitions.
             *
             * Why:
             * - CompileState may update elapsedMs frequently while compiling.
             * - Logging on every object change causes noisy release logs.
             */
            LaunchedEffect(compileLogKey) {
                SafeLog.d(TAG, compileLogKey)
            }

            /**
             * Request compile once after the screen is visible when:
             * - warmup is required
             * - readiness hint is not already satisfied
             * - a controller is available
             *
             * Keys include [warmupController] so the effect can rerun when the controller
             * becomes available after an earlier composition.
             */
            LaunchedEffect(warmupController, requireWarmup, warmupSatisfiedHint) {
                SafeLog.d(
                    TAG,
                    "composed requireWarmup=$requireWarmup warmupSatisfiedHint=$warmupSatisfiedHint controllerPresent=${warmupController != null}",
                )

                if (!requireWarmup) return@LaunchedEffect

                withFrameNanos { /* Wait until the first frame is rendered. */ }
                delay(COMPILE_REQUEST_DELAY_MS)

                val satisfiedNow =
                    isWarmupReady(
                        requireWarmup = latestRequireWarmup,
                        warmupSatisfiedHint = latestWarmupSatisfiedHint,
                        state = latestCompileState,
                    )

                if (!satisfiedNow) {
                    requestCompile(reason = "enterScreen")
                } else {
                    SafeLog.d(
                        TAG,
                        "enterScreen: warmup already satisfied " +
                                "state=${latestCompileState.javaClass.simpleName} hint=${latestWarmupSatisfiedHint}",
                    )
                }
            }

            LaunchedEffect(beginPendingWarmup, pendingWarmupStartMs) {
                if (!beginPendingWarmup) return@LaunchedEffect
                if (pendingWarmupStartMs == null) return@LaunchedEffect

                while (isActive && beginPendingWarmup) {
                    pendingUiNowMs = SystemClock.elapsedRealtime()
                    delay(PENDING_WAIT_TICK_MS)
                }
            }

            LaunchedEffect(beginPendingWarmup, compileState, warmupSatisfiedHint, requireWarmup) {
                if (!beginPendingWarmup) return@LaunchedEffect

                val satisfiedNow =
                    isWarmupReady(
                        requireWarmup = requireWarmup,
                        warmupSatisfiedHint = warmupSatisfiedHint,
                        state = compileState,
                    )

                if (satisfiedNow) {
                    val waitedMs =
                        pendingWarmupStartMs?.let { SystemClock.elapsedRealtime() - it } ?: -1
                    SafeLog.i(
                        TAG,
                        "warmup became ready while pending (auto proceed) waitedMs=$waitedMs hint=$warmupSatisfiedHint",
                    )
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
                Text(latestDescriptionText)

                if (debugInfo != null) {
                    Spacer(Modifier.height(6.dp))
                    DebugPanel(debugInfo = debugInfo)
                }

                val showWarmupStatus =
                    latestRequireWarmup && (beginPendingWarmup || !effectiveWarmupReady)

                if (showWarmupStatus) {
                    Spacer(Modifier.height(6.dp))
                    WarmupStatusPanel(
                        compileState = compileState,
                        warmupSatisfiedHint = warmupSatisfiedHint,
                        onRetry = {
                            val controller = latestController ?: return@WarmupStatusPanel

                            SafeLog.w(
                                TAG,
                                "warmup retry requested (SurveyStart) state=${compileState.javaClass.simpleName}",
                            )

                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    controller.resetForRetry(reason = "uiRetry")
                                }.onFailure { t ->
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

                            if (!latestRequireWarmup) {
                                SafeLog.i(TAG, "click: begin (no warmup required)")
                                proceedBegin(source = "immediateNoWarmup")
                                return@Button
                            }

                            val st = compileState
                            val satisfiedNow =
                                isWarmupReady(
                                    requireWarmup = latestRequireWarmup,
                                    warmupSatisfiedHint = latestWarmupSatisfiedHint,
                                    state = st,
                                )

                            SafeLog.d(
                                TAG,
                                "click: begin state=${st.javaClass.simpleName} " +
                                        "elapsedMs=${st.elapsedMs} hint=${latestWarmupSatisfiedHint} satisfied=$satisfiedNow",
                            )

                            if (satisfiedNow) {
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
                                else -> latestBeginLabel
                            },
                        )
                    }
                }

                if (beginLocked) {
                    Text("Starting…")
                } else if (beginPendingWarmup) {
                    val waitedMs =
                        pendingWarmupStartMs?.let { (pendingUiNowMs - it).coerceAtLeast(0L) }

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
            warmupSatisfiedHint: Boolean,
            onRetry: () -> Unit,
        ) {
            val label =
                when {
                    warmupSatisfiedHint -> "Ready (reused)"
                    compileState is WarmupController.CompileState.Idle -> "Idle"
                    compileState is WarmupController.CompileState.WaitingForPrefetch -> "Waiting for prefetch…"
                    compileState is WarmupController.CompileState.Compiling -> "Compiling…"
                    compileState is WarmupController.CompileState.Compiled -> "Compiled"
                    compileState is WarmupController.CompileState.Cancelled -> "Cancelled"
                    compileState is WarmupController.CompileState.SkippedNotReady -> "Warmup not ready"
                    compileState is WarmupController.CompileState.Failed -> {
                        val msg = compileState.message.replace('\n', ' ').take(FAILED_MSG_MAX)
                        "Failed: $msg"
                    }
                    else -> "Unknown"
                }

            val elapsed = compileState.elapsedMs

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$label  (elapsed=${elapsed}ms)")
                if (
                    compileState is WarmupController.CompileState.Failed ||
                    compileState is WarmupController.CompileState.Cancelled ||
                    compileState is WarmupController.CompileState.SkippedNotReady
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("warmup_retry"),
                    ) {
                        Text("Retry warmup")
                    }
                }
            }
        }

        /**
         * Defines whether the app can proceed to Question flow without risking first-use stalls.
         *
         * Policy:
         * - If warmup is not required, proceed.
         * - Caller-provided readiness hint wins.
         * - Only an observed Compiled state counts as process-local readiness.
         * - SkippedNotReady is NOT treated as ready when warmup is required.
         */
        private fun isWarmupReady(
            requireWarmup: Boolean,
            warmupSatisfiedHint: Boolean,
            state: WarmupController.CompileState,
        ): Boolean {
            if (!requireWarmup) return true
            if (warmupSatisfiedHint) return true

            return when (state) {
                is WarmupController.CompileState.Compiled -> true
                else -> false
            }
        }

        /**
         * Builds a coarse-grained log key to avoid logging on every elapsedMs tick.
         */
        private fun buildCompileLogKey(
            state: WarmupController.CompileState,
            requireWarmup: Boolean,
            warmupSatisfiedHint: Boolean,
        ): String {
            val stateLabel =
                when (state) {
                    is WarmupController.CompileState.Idle -> "Idle"
                    is WarmupController.CompileState.WaitingForPrefetch -> "WaitingForPrefetch"
                    is WarmupController.CompileState.Compiling -> "Compiling"
                    is WarmupController.CompileState.Compiled -> "Compiled"
                    is WarmupController.CompileState.Cancelled -> "Cancelled"
                    is WarmupController.CompileState.SkippedNotReady -> {
                        val reason = state.reason.take(48)
                        "SkippedNotReady:$reason"
                    }
                    is WarmupController.CompileState.Failed -> {
                        val msg = state.message.replace('\n', ' ').take(48)
                        "Failed:$msg"
                    }
                }

            return "compileState=$stateLabel requireWarmup=$requireWarmup hint=$warmupSatisfiedHint"
        }
    }
}