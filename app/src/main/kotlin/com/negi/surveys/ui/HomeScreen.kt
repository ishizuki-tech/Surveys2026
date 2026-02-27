/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: HomeScreen.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "HomeScreen"

/**
 * Home screen (frame-ready).
 *
 * Goals:
 * - Provide clear entry actions for the prototype (Start Survey / Export).
 * - Offer optional debug instrumentation for navigation and environment sanity checks.
 * - Keep the screen UI-only: it should emit intents via callbacks and not mutate nav state.
 *
 * Notes:
 * - Warmup is handled at the app root (SurveyAppRoot) to avoid duplication.
 */
@Composable
fun HomeScreen(
    onStartSurvey: () -> Unit,
    onExport: () -> Unit,
    debugInfo: DebugInfo? = null,
    exportEnabled: Boolean = true
) {
    // Always call the latest callbacks, even if the lambdas change across recompositions.
    val latestOnStartSurvey by rememberUpdatedState(onStartSurvey)
    val latestOnExport by rememberUpdatedState(onExport)

    val scope = rememberCoroutineScope()

    // Simple click guard to prevent double navigation due to rapid taps.
    var clickLocked by remember { mutableStateOf(false) }

    fun runGuardedClick(actionName: String, block: () -> Unit) {
        if (clickLocked) {
            AppLog.w(TAG, "click: ignored (locked) action=$actionName")
            return
        }
        clickLocked = true
        AppLog.i(TAG, "click: $actionName")
        block()

        // Unlock shortly after to avoid accidental double triggers.
        scope.launch {
            delay(350L)
            clickLocked = false
        }
    }

    // Debug-only "composed" trace (PII-safe).
    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            AppLog.d(TAG, "composed")
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
            .testTag("home_root"),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Survey App (Nav3 Frame)",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(12.dp))

        if (debugInfo != null) {
            DebugPanel(debugInfo = debugInfo)
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                runGuardedClick("startSurvey") {
                    latestOnStartSurvey()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("home_start_survey")
        ) {
            Text("Start Survey")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                runGuardedClick("export") {
                    latestOnExport()
                }
            },
            enabled = exportEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("home_export")
        ) {
            Text("Export")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Status: Prototype frame is running.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}