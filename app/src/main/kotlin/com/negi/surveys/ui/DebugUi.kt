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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small structured debug model used across early bring-up screens.
 *
 * Notes:
 * - Keep this minimal and non-sensitive (no user answers / PII).
 * - Prefer stable identifiers (route name, stack size, build label).
 * - [extras] enables optional key/value rows without changing call sites.
 */
@Immutable
data class DebugInfo(
    val currentRoute: String,
    val backStackSize: Int,
    val buildLabel: String? = null,
    val extras: List<DebugRow> = emptyList()
)

/**
 * One extra debug row.
 *
 * Notes:
 * - Keep values short and non-sensitive.
 */
@Immutable
data class DebugRow(
    val label: String,
    val value: String
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
    modifier: Modifier = Modifier,
    maxCharsPerValue: Int = 96
) {
    val route = remember(debugInfo.currentRoute, maxCharsPerValue) {
        debugInfo.currentRoute
            .trim()
            .ifBlank { "(none)" }
            .ellipsize(maxChars = maxCharsPerValue)
    }

    val build = remember(debugInfo.buildLabel, maxCharsPerValue) {
        debugInfo.buildLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.ellipsize(maxChars = maxCharsPerValue)
    }

    val extras = remember(debugInfo.extras, maxCharsPerValue) {
        debugInfo.extras
            .asSequence()
            .map { row ->
                DebugRow(
                    label = row.label.trim().ifBlank { "(key)" }.ellipsize(32),
                    value = row.value.trim().ifBlank { "(none)" }.ellipsize(maxCharsPerValue)
                )
            }
            .toList()
    }

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

        DebugLine(label = "Current", value = route)
        DebugLine(label = "BackStack", value = debugInfo.backStackSize.toString())

        if (build != null) {
            DebugLine(label = "Build", value = build)
        }

        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            for (row in extras) {
                DebugLine(label = row.label, value = row.value)
            }
        }
    }
}

/**
 * Single debug line renderer.
 *
 * Notes:
 * - Kept small for readability and stability.
 */
@Composable
private fun DebugLine(
    label: String,
    value: String
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall
    )
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
