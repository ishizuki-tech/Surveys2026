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
 *
 * Design:
 * - Blocking must be derived from actual blocking reasons.
 * - A spinner alone must not keep the shell blocked after startup is ready.
 * - Failed states remain blocking, but they must not show a spinner.
 * - A ready state with services marked ready but repository still null is treated
 *   as a service-hole anomaly, not as normal loading.
 */
internal fun AppStartupUiState.toStartupBlockingUi(): StartupBlockingUi {
    val repositoryMissingAfterReady = configState is StartupConfigState.Ready && servicesReady && repository == null

    val title =
        when (configState) {
            is StartupConfigState.Loading -> "Loading application configuration…"

            is StartupConfigState.Failed -> "Startup could not continue."

            is StartupConfigState.Ready -> {
                when {
                    repositoryMissingAfterReady -> "App services need attention."
                    !servicesReady && onDeviceEnabled -> "Preparing on-device services…"
                    !servicesReady -> "Preparing app services…"
                    repository == null -> "Preparing app services…"
                    onDeviceEnabled && modelDownloader == null && modelSpecKey != null ->
                        "Preparing model download services…"
                    onDeviceEnabled && warmup == null -> "Preparing on-device services…"
                    else -> null
                }
            }
        }

    val detail =
        when (val st = configState) {
            is StartupConfigState.Loading -> {
                "Waiting for the Application-installed SurveyConfig."
            }

            is StartupConfigState.Failed -> {
                failedDetailForSafeReason(st.safeReason)
            }

            is StartupConfigState.Ready -> {
                when {
                    repositoryMissingAfterReady -> {
                        "Startup reported services as ready, but the repository is still unavailable. " +
                                "Retry startup to rebuild the root service graph."
                    }

                    !servicesReady && onDeviceEnabled -> {
                        "Process-scoped on-device services are still starting."
                    }

                    !servicesReady -> {
                        "Process-scoped services are still starting."
                    }

                    repository == null -> {
                        "Repository is not ready yet."
                    }

                    onDeviceEnabled && modelDownloader == null && modelSpecKey != null -> {
                        "Model downloader is not ready yet."
                    }

                    onDeviceEnabled && warmup == null -> {
                        "Warmup controller is not ready yet."
                    }

                    else -> null
                }
            }
        }

    val isBlocking = !title.isNullOrBlank() || !detail.isNullOrBlank()
    val showSpinner =
        when {
            !isBlocking -> false
            configState is StartupConfigState.Failed -> false
            repositoryMissingAfterReady -> false
            else -> true
        }

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

/**
 * Maps a safe startup failure reason to user-facing guidance.
 */
private fun failedDetailForSafeReason(reason: String): String {
    return when (reason) {
        "InstalledConfigMissing" -> {
            "The process-scoped configuration was not installed in time. " +
                    "Retry startup to rebuild the app bootstrap path."
        }

        "ModelUnavailable" -> {
            "No usable on-device model is available. " +
                    "A local model file was not found, and no download URL is configured. " +
                    "Add the model or update the config, then retry startup."
        }

        "ServiceInitFailed" -> {
            "Startup could not build the process-scoped service graph. " +
                    "Retry startup to recreate repository, warmup, and model services."
        }

        "ModelStateCollectorFailed" -> {
            "Startup lost the model-state observer while services were running. " +
                    "Retry startup to rebuild the root observers."
        }

        "PrefetchStateCollectorFailed" -> {
            "Startup lost the prefetch-state observer while services were running. " +
                    "Retry startup to rebuild the root observers."
        }

        "CompileStateCollectorFailed" -> {
            "Startup lost the compile-state observer while services were running. " +
                    "Retry startup to rebuild the root observers."
        }

        else -> {
            "Startup stopped in a safe failure state. " +
                    "Retry startup to run configuration and service initialization again. " +
                    "Code: $reason"
        }
    }
}
