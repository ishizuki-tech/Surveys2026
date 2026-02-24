/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: AppNavKey.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * App-scoped navigation key marker.
 *
 * Why this exists:
 * - Nav3 back stacks are fundamentally typed as NavKey.
 * - In many real apps, you still want a "closed world" of destinations that
 *   belong only to THIS app module / navigation graph.
 * - This marker makes that intent explicit and allows stricter checks in one place.
 *
 * Important:
 * - This is intentionally a marker interface (no members).
 * - Each destination key (e.g., Home, SurveyStart, Question, Review, Export)
 *   should implement AppNavKey to guarantee "app-only" destinations.
 */
sealed interface AppNavKey : NavKey

/**
 * Returns this key as AppNavKey if it belongs to the app-scoped destination set.
 *
 * This is useful when the back stack is typed as NavKey (common in Nav3),
 * but you still want to safely narrow to AppNavKey at the edges.
 */
fun NavKey.asAppNavKeyOrNull(): AppNavKey? = this as? AppNavKey

/**
 * Requires that this NavKey is an AppNavKey; otherwise throws with a detailed message.
 *
 * Use this in places where mixing non-app keys would indicate a programming error,
 * e.g. inside a central AppNavigator or when computing titles for the app shell.
 */
fun NavKey.requireAppNavKey(context: String = "NavKey"): AppNavKey {
    return (this as? AppNavKey)
        ?: error("$context expected AppNavKey, but was ${this::class.java.name}")
}

/**
 * Ensures that every element in the stack is AppNavKey.
 *
 * Recommended usage:
 * - Call this once after creating the stack (or during debug builds),
 *   especially if you have multiple modules or experiments that could
 *   accidentally push foreign NavKeys.
 *
 * Note:
 * - This is a runtime assertion. It adds safety and improves debugging,
 *   but does not replace proper type discipline in key definitions.
 */
fun NavBackStack<NavKey>.assertAllAppKeys(context: String = "NavBackStack") {
    
    /** Fail fast with the first offending key for concise error messages. */
    forEachIndexed { idx, key ->
        if (key !is AppNavKey) {
            error("$context[$idx] is not AppNavKey: ${key::class.java.name}")
        }
    }
}

/**
 * Ensures that every element in the list is AppNavKey.
 *
 * Why:
 * - Some call sites operate on List<NavKey> (e.g., snapshots or debug dumps).
 * - Keeping the assertion shared avoids duplicating loops in AppNavigator and elsewhere.
 */
fun List<NavKey>.assertAllAppKeys(context: String = "NavKeyList") {
    
    /** Fail fast with the first offending key for concise error messages. */
    forEachIndexed { idx, key ->
        if (key !is AppNavKey) {
            error("$context[$idx] is not AppNavKey: ${key::class.java.name}")
        }
    }
}