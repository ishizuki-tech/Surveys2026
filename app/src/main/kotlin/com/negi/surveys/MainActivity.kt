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

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.negi.surveys.logging.SafeLog

/**
 * Main entry activity for the Nav3 frame prototype.
 *
 * Responsibilities:
 * - Configure window behavior (edge-to-edge).
 * - Provide a stable Material3 theme surface for the Compose tree.
 *
 * Notes:
 * - Process-scoped initialization MUST live in Application (SurveyApplication).
 * - Keep Activity free from process bootstrap to avoid duplication on recreation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout so the app shell can correctly handle system insets.
        enableEdgeToEdge()

        SafeLog.i(TAG_MAIN, buildStartupLog(savedInstanceState))

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SurveyAppRoot()
                }
            }
        }
    }

    /**
     * Builds a stable startup log line without leaking any sensitive information.
     */
    private fun buildStartupLog(savedInstanceState: Bundle?): String {
        val recreation = savedInstanceState != null
        val buildLabel =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                    "${BuildConfig.BUILD_TYPE} dbg=${BuildConfig.DEBUG}"
        return "onCreate: uptimeMs=${SystemClock.uptimeMillis()} recreated=$recreation build=$buildLabel"
    }

    private companion object {
        private const val TAG_MAIN = "MainActivity"
    }
}