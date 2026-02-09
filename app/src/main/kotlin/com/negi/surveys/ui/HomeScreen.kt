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

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
 * - The app shell (TopAppBar) and Nav3 back stack are managed outside this composable.
 * - In later steps, you can add "Resume last session" and "Settings" without changing
 *   the basic event contract.
 */
@Composable
fun HomeScreen(
    onStartSurvey: () -> Unit,
    onExport: () -> Unit,
    // Optional debug info to display on the home screen.
    // Supply these from the app shell if you want (e.g., stack size, current key name).
    debugInfo: DebugInfo? = null
) {
    // Debug trace: helps verify that callbacks are invoked and recompositions are sane.
    LaunchedEffect(Unit) {
        Log.d(TAG, "HomeScreen: composed")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // English comment:
            // - safeDrawing avoids content being overlapped by system bars (status/navigation, cutouts).
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
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

        // Primary actions.
        Button(
            onClick = {
                Log.d(TAG, "HomeScreen: onStartSurvey clicked")
                onStartSurvey()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Survey")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                Log.d(TAG, "HomeScreen: onExport clicked")
                onExport()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export")
        }

        Spacer(Modifier.height(24.dp))

        // Reserved area for future expansion:
        // - Resume session
        // - Settings
        // - Diagnostics
        Text(
            text = "Status: Prototype frame is running.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
