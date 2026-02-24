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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import kotlinx.coroutines.delay

private const val TAG = "SurveyStartScreen"

/**
 * SurveyStart screen (frame-ready).
 *
 * Goals:
 * - Provide a clear "pre-flight" step before starting questions.
 * - Prevent accidental double navigation (e.g., rapid taps).
 * - Offer an optional debug panel hook for future ViewModel/config/session wiring.
 *
 * Notes:
 * - Keep this composable UI-only. It emits intents via callbacks.
 * - In later steps, you can inject survey config metadata (name/version/language),
 *   eligibility summary, or a "resume session" option.
 */
@Composable
fun SurveyStartScreen(
    onBegin: () -> Unit,
    onBack: () -> Unit,
    debugInfo: DebugInfo? = null
) {
    val latestOnBegin by rememberUpdatedState(onBegin)
    val latestOnBack by rememberUpdatedState(onBack)

    /**
     * Guard flag to prevent duplicate navigations caused by rapid multiple taps.
     *
     * Note:
     * - This is a transient UI lock; we intentionally do NOT save it across rotations.
     * - An automatic unlock timeout acts as a safety valve in case navigation fails.
     */
    var beginLocked by remember { mutableStateOf(false) }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            AppLog.d(TAG, "composed")
            Log.d(TAG, "composed")
        }
    }

    /**
     * Auto-unlock if we stay on this screen for too long after "Begin".
     * If navigation succeeds, this composable will typically leave composition, so this effect cancels.
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

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
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

                    beginLocked = true
                    AppLog.i(TAG, "click: begin")
                    if (BuildConfig.DEBUG) Log.d(TAG, "click: begin")

                    runCatching { latestOnBegin() }
                        .onFailure { t ->
                            // If begin throws synchronously, unlock immediately to avoid trapping the UI.
                            beginLocked = false
                            AppLog.w(TAG, "begin callback failed (unlocked): ${t.javaClass.simpleName}")
                            if (BuildConfig.DEBUG) Log.w(TAG, "begin callback failed (unlocked)", t)
                        }
                },
                enabled = !beginLocked,
                modifier = Modifier
                    .weight(1f)
                    .testTag("survey_start_begin")
            ) {
                Text("Begin (Q1)")
            }
        }

        if (beginLocked) {
            Text("Starting…")
        }
    }
}

private const val BEGIN_LOCK_TIMEOUT_MS: Long = 2_000L