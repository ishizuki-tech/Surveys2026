/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Startup Config State)
 *  ---------------------------------------------------------------------
 *  File: StartupConfigState.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys.config

/**
 * Installed config readiness state for startup orchestration.
 */
sealed interface StartupConfigState {
    /**
     * The ViewModel is still waiting for Application-installed config.
     */
    data object Loading : StartupConfigState

    /**
     * The process-installed config is available.
     */
    data class Ready(
        val cfg: SurveyConfig,
    ) : StartupConfigState

    /**
     * A safe failure state.
     *
     * The reason must never contain exception.message.
     */
    data class Failed(
        val safeReason: String,
    ) : StartupConfigState
}