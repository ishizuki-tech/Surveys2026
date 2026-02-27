/*
 * =====================================================================
 *  IshizukiTech LLC — SurveyApp
 *  ---------------------------------------------------------------------
 *  File: DebugLogUploadButton.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.negi.surveys.logging.AppLog
import kotlinx.coroutines.launch

/**
 * Debug UI button that collects a log file and uploads it.
 *
 * Notes:
 * - Always produces a file (even if logcat is unavailable).
 * - Shows a compact status message in the UI.
 * - Does not expose token values.
 */
@Composable
fun DebugLogUploadButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val status = remember { mutableStateOf("Idle") }
    val isBusy = remember { mutableStateOf(false) }

    Column {
        Button(
            onClick = {
                if (isBusy.value) return@Button
                isBusy.value = true
                status.value = "Collecting…"

                scope.launch {
                    val result = runCatching {
                        DebugLogUploader.collectAndUpload(context)
                    }.getOrElse { e ->
                        AppLog.e("DebugLogUploadButton", "collectAndUpload failed: ${e.message}", e)
                        DebugLogUploader.UploadResult(
                            ok = false,
                            destination = "error",
                            message = "Unexpected error: ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }

                    status.value = if (result.ok) {
                        "OK: ${result.message}"
                    } else {
                        "FAIL: ${result.message}"
                    }
                    isBusy.value = false
                }
            }
        ) {
            Text(
                text = if (isBusy.value) "Uploading…" else "Upload Debug Log",
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = status.value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
