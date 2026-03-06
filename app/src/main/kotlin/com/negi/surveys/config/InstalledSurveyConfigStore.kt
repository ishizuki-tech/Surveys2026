/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Installed Survey Config Store)
 *  ---------------------------------------------------------------------
 *  File: InstalledSurveyConfigStore.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local installed config store.
 *
 * Design goals:
 * - Keep the installed SurveyConfig in exactly one dedicated place.
 * - Enforce first-install-wins semantics for production code.
 * - Preserve explicit test-only escape hatches.
 *
 * Notes:
 * - This store is process-local only.
 * - It does not perform any I/O or parsing.
 * - Application should remain the owner of the initial install.
 */
internal object InstalledSurveyConfigStore {

    /**
     * Result of an install attempt.
     */
    internal enum class InstallResult {
        /**
         * The config was installed because the store was empty.
         */
        INSTALLED,

        /**
         * A config was already installed, so the new value was ignored.
         */
        ALREADY_INSTALLED,
    }

    private val installedRef: AtomicReference<SurveyConfig?> =
        AtomicReference(null)

    /**
     * Installs the process config exactly once.
     *
     * Contract:
     * - First successful caller wins.
     * - Later install attempts are ignored.
     * - The stored instance remains stable for the process lifetime,
     *   unless test-only APIs are used.
     */
    internal fun installOnce(cfg: SurveyConfig): InstallResult {
        return if (installedRef.compareAndSet(null, cfg)) {
            InstallResult.INSTALLED
        } else {
            InstallResult.ALREADY_INSTALLED
        }
    }

    /**
     * Returns the installed config if present, otherwise null.
     */
    internal fun getOrNull(): SurveyConfig? = installedRef.get()

    /**
     * Returns the installed config or throws if it has not been installed.
     */
    internal fun requireInstalled(): SurveyConfig {
        return installedRef.get()
            ?: error("InstalledSurveyConfigStore.requireInstalled(): config is not installed")
    }

    /**
     * Replaces the installed config for tests only.
     *
     * Do not call from production code.
     */
    internal fun replaceForTests(cfg: SurveyConfig) {
        installedRef.set(cfg)
    }

    /**
     * Clears the installed config for tests only.
     *
     * Do not call from production code.
     */
    internal fun clearForTests() {
        installedRef.set(null)
    }
}