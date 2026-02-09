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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    /**
     * Guard flag to prevent duplicate navigations caused by rapid multiple taps.
     *
     * Notes:
     * - Use rememberSaveable to keep behavior stable across recompositions/rotations.
     * - We add an automatic unlock timeout as a safety valve in case navigation fails.
     */
    var beginLocked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Log.d(TAG, "SurveyStartScreen: composed")
    }

    /**
     * Auto-unlock if we stay on this screen for too long after "Begin".
     *
     * Notes:
     * - If navigation succeeds, this composable will typically leave composition, so this effect cancels.
     * - If navigation fails or is blocked, we prevent the UI from being stuck in a locked state.
     */
    LaunchedEffect(beginLocked) {
        if (!beginLocked) return@LaunchedEffect
        delay(BEGIN_LOCK_TIMEOUT_MS)
        if (beginLocked) {
            beginLocked = false
            Log.d(TAG, "SurveyStartScreen: begin auto-unlocked after timeout")
        }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Survey Start")

        Text(
            "This step prepares the survey session before opening the first question."
        )

        if (debugInfo != null) {
            Spacer(Modifier.height(6.dp))
            DebugPanel(debugInfo)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "SurveyStartScreen: back clicked")
                    onBack()
                },
                enabled = !beginLocked
            ) {
                Text("Back")
            }

            Button(
                onClick = {
                    if (beginLocked) {
                        Log.d(TAG, "SurveyStartScreen: begin ignored (locked)")
                        return@Button
                    }
                    beginLocked = true
                    Log.d(TAG, "SurveyStartScreen: begin clicked")
                    onBegin()
                },
                enabled = !beginLocked
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
