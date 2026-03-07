/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: AppNavigator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.surveys.logging.AppLog

/**
 * Centralized navigation controller for a single Nav3 back stack.
 *
 * Design goals:
 * - Keep UI screens "dumb": screens emit intents; navigator mutates the back stack.
 * - Provide consistent back-stack operations (push/pop/reset) in one place.
 * - Offer optional debug logging to trace navigation flows during development.
 *
 * Notes:
 * - NavBackStack<T> is a MutableList<T>, so operations like add/removeAt/clear are valid.
 * - This navigator assumes app destinations are represented by NavKey implementations
 *   (e.g., Home, SurveyStart, Question, Review, Export).
 * - If you want a "closed world" of app-only destinations, enable [enforceAppKeys] and ensure
 *   all destination keys implement [AppNavKey].
 */
class AppNavigator(
    private val backStack: NavBackStack<NavKey>,
    private val logger: Logger = Logger.Default,
    private val enforceAppKeys: Boolean = false,
) {

    /**
     * Returns the current destination (top of stack).
     *
     * This returns NavKey to avoid unsafe casting. If you want strict typing,
     * enable [enforceAppKeys] and use [currentApp] where appropriate.
     */
    val current: NavKey
        get() {
            ensureRoot()
            maybeAssertAppKeys("current")
            return backStack.last()
        }

    /**
     * Returns the current destination as AppNavKey if possible.
     *
     * This is useful for app-shell UI, such as app bar title rendering.
     */
    val currentApp: AppNavKey?
        get() = current.asAppNavKeyOrNull()

    /**
     * True if the stack has more than one element (i.e., can navigate back).
     */
    fun canPop(): Boolean {
        ensureRoot()
        maybeAssertAppKeys("canPop")
        return backStack.size > 1
    }

    /**
     * Pops one destination from the stack if possible.
     *
     * @return The removed NavKey if popped; null if the stack cannot be popped.
     */
    fun pop(): NavKey? {
        ensureRoot()
        maybeAssertAppKeys("pop(before)")

        if (!canPop()) {
            logger.d("pop: ignored (stack size=${backStack.size})")
            return null
        }

        val removed = backStack.removeAt(backStack.lastIndex)
        logger.d("pop: removed=${removed.debugNameForLog()} -> stack=${backStack.debugStackForLog()}")

        maybeAssertAppKeys("pop(after)")
        return removed
    }

    /**
     * Pushes a destination key onto the stack.
     *
     * Note:
     * - If the stack is empty (invalid), this method restores Home first.
     * - If [enforceAppKeys] is enabled, non-AppNavKey pushes will fail fast.
     */
    fun push(key: NavKey) {
        ensureRoot()
        maybeAssertAppKeys("push(before)")

        if (enforceAppKeys && key !is AppNavKey) {
            error("push() expected AppNavKey, but was ${key::class.java.name}")
        }

        backStack.add(key)
        logger.d("push: added=${key.debugNameForLog()} -> stack=${backStack.debugStackForLog()}")

        maybeAssertAppKeys("push(after)")
    }

    /**
     * Clears the stack and sets Home as the single root destination.
     *
     * This is useful for "restart flow" or after completing a session.
     */
    fun resetToHome() {
        backStack.clear()
        backStack.add(Home)
        logger.d("resetToHome -> stack=${backStack.debugStackForLog()}")
        maybeAssertAppKeys("resetToHome")
    }

    fun goHome() = resetToHome()

    fun startSurvey() = push(SurveyStart)

    fun beginQuestions(firstId: String) = push(Question(firstId))

    fun goQuestion(id: String) = push(Question(id))

    fun goReview() = push(Review)

    fun goExport() = push(Export)

    /**
     * Ensures the back stack is never empty.
     *
     * Why:
     * - Some initialization orders or external mutations may temporarily create an empty stack.
     * - Calling List.last() on an empty list would crash.
     *
     * Policy:
     * - Home is always the root destination.
     */
    private fun ensureRoot() {
        if (backStack.isNotEmpty()) return
        backStack.add(Home)
        logger.d("ensureRoot: stack was empty -> restored Home")
    }

    /**
     * Optionally asserts that every key in the stack is an [AppNavKey].
     *
     * This improves debugging when multiple modules/features could accidentally
     * push foreign NavKeys into the app stack.
     */
    private fun maybeAssertAppKeys(context: String) {
        if (!enforceAppKeys) return
        backStack.assertAllAppKeys(context = "AppNavigator:$context")
    }

    /**
     * Simple logging interface to avoid hard dependency on any specific logging framework.
     */
    interface Logger {
        fun d(msg: String)

        object Default : Logger {
            private const val TAG = "AppNavigator"
            override fun d(msg: String) {
                AppLog.d(TAG, msg)
            }
        }
    }
}

/**
 * Returns a compact, readable identifier for logging.
 *
 * Prefer AppNavKey.debugName() when available; otherwise fall back to class name.
 */
private fun NavKey.debugNameForLog(): String {
    val app = this.asAppNavKeyOrNull()
    return app?.debugName() ?: this::class.java.simpleName
}

/**
 * Returns a readable stack dump for debugging.
 */
private fun NavBackStack<NavKey>.debugStackForLog(): String {
    if (isEmpty()) return "[]"
    return joinToString(prefix = "[", postfix = "]") { it.debugNameForLog() }
}