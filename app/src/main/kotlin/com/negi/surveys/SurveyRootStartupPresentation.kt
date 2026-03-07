/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Root Startup Presentation)
 *  ---------------------------------------------------------------------
 *  File: SurveyRootStartupPresentation.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

/**
 * Startup blocking UI model consumed by the root shell.
 */
internal data class StartupBlockingUi(
    val title: String?,
    val detail: String?,
    val showSpinner: Boolean,
)

/**
 * Converts the startup orchestration state into a shell-level blocking model.
 */
internal fun AppStartupUiState.toStartupBlockingUi(): StartupBlockingUi {
    val title = when (configState) {
        is StartupConfigState.Loading -> "Loading application configuration…"
        is StartupConfigState.Failed -> "Startup configuration is unavailable."
        is StartupConfigState.Ready -> {
            when {
                !servicesReady && onDeviceEnabled -> "Preparing on-device services…"
                !servicesReady -> "Preparing app services…"
                repository == null -> "Preparing app services…"
                onDeviceEnabled && modelDownloader == null -> "Preparing on-device services…"
                onDeviceEnabled && warmup == null -> "Preparing on-device services…"
                else -> null
            }
        }
    }

    val detail = when (val st = configState) {
        is StartupConfigState.Loading ->
            "Waiting for the Application-installed SurveyConfig."

        is StartupConfigState.Failed ->
            "Safe reason: ${st.safeReason}"

        is StartupConfigState.Ready -> {
            when {
                !servicesReady ->
                    "Process-scoped services are still starting."

                repository == null ->
                    "Repository is not ready yet."

                onDeviceEnabled && modelDownloader == null ->
                    "Model downloader is not ready yet."

                onDeviceEnabled && warmup == null ->
                    "Warmup controller is not ready yet."

                else -> null
            }
        }
    }

    val showSpinner = configState !is StartupConfigState.Failed

    return StartupBlockingUi(
        title = title,
        detail = detail,
        showSpinner = showSpinner,
    )
}

/**
 * Produces a compact debug label for startup config state.
 */
internal fun StartupConfigState.toDebugLabel(): String {
    return when (this) {
        is StartupConfigState.Loading -> "Loading"
        is StartupConfigState.Ready -> "Ready"
        is StartupConfigState.Failed -> "Failed ($safeReason)"
    }
}