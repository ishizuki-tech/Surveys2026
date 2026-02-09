/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: DebugUi.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small structured debug model used across early bring-up screens.
 *
 * Notes:
 * - Keep this minimal and non-sensitive (no user answers / PII).
 * - Prefer stable identifiers (route name, stack size, build label).
 */
@Immutable
data class DebugInfo(
    val currentRoute: String,
    val backStackSize: Int,
    val buildLabel: String? = null
)

/**
 * Compact debug panel for development-time visibility.
 *
 * Notes:
 * - This UI intentionally avoids fancy styling to remain robust and readable.
 * - You can later replace this with a proper diagnostics screen.
 */
@Composable
fun DebugPanel(
    debugInfo: DebugInfo,
    modifier: Modifier = Modifier
) {
    val route = debugInfo.currentRoute
        .ifBlank { "(none)" }
        .ellipsize(maxChars = 80)

    val build = debugInfo.buildLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.ellipsize(maxChars = 80)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Text(
            text = "Debug",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text("Current: $route")
        Text("BackStack: ${debugInfo.backStackSize}")
        if (build != null) {
            Text("Build: $build")
        }
    }
}

/**
 * Truncates a string to [maxChars] with a trailing ellipsis.
 *
 * This is used only for debug UI readability.
 */
private fun String.ellipsize(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    if (maxChars == 1) return "…"
    return take(maxChars - 1) + "…"
}
