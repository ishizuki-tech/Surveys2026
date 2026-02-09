/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: NavKeys.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.nav

import kotlinx.serialization.Serializable

/**
 * App destination keys for Navigation 3 (Nav3).
 *
 * Design notes:
 * - Nav3 back stacks are lists of NavKey instances. These keys represent destinations.
 * - Keys must be stable and ideally serializable to support process death / state restoration.
 * - Keep keys small and immutable. Put large payloads in ViewModels/repositories instead.
 *
 * Practical debugging:
 * - Prefer central helpers (e.g., appBarTitle(), debugName()) over scattering `when` blocks
 *   across multiple files.
 */

/** Home / entry destination. */
@Serializable
object Home : AppNavKey

/** Survey entry screen (pre-flight / instructions / begin button). */
@Serializable
object SurveyStart : AppNavKey

/**
 * A question destination.
 *
 * IMPORTANT:
 * - Keep `id` stable and short (e.g., "Q1", "Q2", "ELIG_01").
 * - Do NOT embed large question text or answers here; use ViewModels/state holders.
 */
@Serializable
data class Question(val id: String) : AppNavKey {
    init {
        require(id.isNotBlank()) { "Question.id must not be blank." }
    }
}

/** Review screen (summary of answers before exporting). */
@Serializable
object Review : AppNavKey

/** Export screen (JSON preview / save / share). */
@Serializable
object Export : AppNavKey

// ---------------------------------------------------------------------
// Optional: Common IDs / helpers to reduce "stringly-typed" code.
// ---------------------------------------------------------------------

/**
 * Canonical question IDs used by the current prototype flow.
 *
 * Notes:
 * - This is intentionally minimal. Expand only when you formalize the survey graph.
 * - Having constants avoids typos ("Q1" vs "Ql") and makes refactors safer.
 */
object QuestionIds {
    const val Q1: String = "Q1"
    const val Q2: String = "Q2"
}

/**
 * Returns an app-bar friendly title for each destination.
 *
 * Notes:
 * - Keeping this next to the keys avoids duplicated `when` blocks across the app shell.
 * - You can later localize this (e.g., via string resources) if needed.
 */
fun AppNavKey.appBarTitle(): String = when (this) {
    Home -> "Home"
    SurveyStart -> "Survey Start"
    is Question -> "Question: $id"
    Review -> "Review"
    Export -> "Export"
}

/**
 * Returns a compact debug string for logging / traces.
 *
 * Example:
 * - "Home"
 * - "Question(id=Q1)"
 */
fun AppNavKey.debugName(): String = when (this) {
    Home -> "Home"
    SurveyStart -> "SurveyStart"
    is Question -> "Question(id=$id)"
    Review -> "Review"
    Export -> "Export"
}
