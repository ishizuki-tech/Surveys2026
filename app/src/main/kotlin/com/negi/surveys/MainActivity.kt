/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * Main entry activity for the Nav3 frame prototype.
 *
 * Responsibilities (kept intentionally small):
 * - Configure window behavior (edge-to-edge) so insets are handled consistently.
 * - Provide a stable Material3 theme surface for the Compose tree.
 * - Emit minimal debug traces that help diagnose cold start / process recreation.
 *
 * Notes:
 * - Navigation, state, and business logic live below (SurveyAppRoot and ViewModels).
 * - Avoid putting survey/session state in the Activity; rely on ViewModels instead.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout so the app shell can correctly handle system insets.
        enableEdgeToEdge()

        Log.d(TAG, buildStartupLog(savedInstanceState))

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SurveyAppRoot()
                }
            }
        }
    }
}

private const val TAG = "MainActivity"

/**
 * Builds a concise startup log message.
 *
 * Why:
 * - Helps detect whether we're in a cold start vs recreation (savedInstanceState != null).
 * - Captures device/runtime info useful for debugging.
 */
private fun buildStartupLog(savedInstanceState: Bundle?): String {
    val recreation = savedInstanceState != null
    return "onCreate: sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "recreated=$recreation"
}
