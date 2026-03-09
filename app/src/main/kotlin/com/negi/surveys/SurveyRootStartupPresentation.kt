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

import com.negi.surveys.config.StartupConfigState

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
    return SurveyRootStartupPresentationSupport.toStartupBlockingUi(this)
}

/**
 * Produces a compact debug label for startup config state.
 */
internal fun StartupConfigState.toDebugLabel(): String {
    return SurveyRootStartupPresentationSupport.toDebugLabel(this)
}

/**
 * File-local support helpers for root startup presentation.
 */
private object SurveyRootStartupPresentationSupport {

    /**
     * Internal blocking reason model used to derive title, detail, and spinner policy.
     */
    private sealed interface BlockingReason {
        data object ConfigLoading : BlockingReason
        data class StartupFailed(val safeReason: String) : BlockingReason
        data object RepositoryMissingAfterReady : BlockingReason
        data object ModelDownloaderMissing : BlockingReason
        data object WarmupMissing : BlockingReason
        data object RepositoryNotReady : BlockingReason
        data object OnDeviceServicesStarting : BlockingReason
        data object AppServicesStarting : BlockingReason
        data object None : BlockingReason
    }

    fun toStartupBlockingUi(state: AppStartupUiState): StartupBlockingUi {
        val reason = resolveBlockingReason(state)
        return StartupBlockingUi(
            title = titleFor(reason),
            detail = detailFor(reason),
            showSpinner = showSpinnerFor(reason),
        )
    }

    fun toDebugLabel(state: StartupConfigState): String {
        return when (state) {
            is StartupConfigState.Loading -> "Loading"
            is StartupConfigState.Ready -> "Ready"
            is StartupConfigState.Failed -> "Failed (${state.safeReason})"
        }
    }

    /**
     * Resolves the most specific current blocking reason.
     *
     * Order matters:
     * - Specific anomalies and missing dependencies must win over broad "still starting" states.
     * - This prevents generic branches from swallowing more actionable details.
     */
    private fun resolveBlockingReason(state: AppStartupUiState): BlockingReason {
        val repositoryMissingAfterReady =
            state.configState is StartupConfigState.Ready &&
                    state.servicesReady &&
                    state.repository == null

        return when (val configState = state.configState) {
            is StartupConfigState.Loading -> BlockingReason.ConfigLoading

            is StartupConfigState.Failed -> BlockingReason.StartupFailed(configState.safeReason)

            is StartupConfigState.Ready -> {
                when {
                    repositoryMissingAfterReady -> BlockingReason.RepositoryMissingAfterReady
                    state.onDeviceEnabled &&
                            state.modelDownloader == null &&
                            state.modelSpecKey != null -> BlockingReason.ModelDownloaderMissing
                    state.onDeviceEnabled && state.warmup == null -> BlockingReason.WarmupMissing
                    state.repository == null -> BlockingReason.RepositoryNotReady
                    !state.servicesReady && state.onDeviceEnabled -> BlockingReason.OnDeviceServicesStarting
                    !state.servicesReady -> BlockingReason.AppServicesStarting
                    else -> BlockingReason.None
                }
            }
        }
    }

    private fun titleFor(reason: BlockingReason): String? {
        return when (reason) {
            is BlockingReason.ConfigLoading -> "Loading application configuration…"
            is BlockingReason.StartupFailed -> "Startup could not continue."
            is BlockingReason.RepositoryMissingAfterReady -> "App services need attention."
            is BlockingReason.ModelDownloaderMissing -> "Preparing model download services…"
            is BlockingReason.WarmupMissing -> "Preparing on-device services…"
            is BlockingReason.RepositoryNotReady -> "Preparing app services…"
            is BlockingReason.OnDeviceServicesStarting -> "Preparing on-device services…"
            is BlockingReason.AppServicesStarting -> "Preparing app services…"
            is BlockingReason.None -> null
        }
    }

    private fun detailFor(reason: BlockingReason): String? {
        return when (reason) {
            is BlockingReason.ConfigLoading -> {
                "Waiting for the Application-installed SurveyConfig."
            }

            is BlockingReason.StartupFailed -> {
                failedDetailForSafeReason(reason.safeReason)
            }

            is BlockingReason.RepositoryMissingAfterReady -> {
                "Startup reported services as ready, but the repository is still unavailable. " +
                        "Retry startup to rebuild the root service graph."
            }

            is BlockingReason.ModelDownloaderMissing -> {
                "Model downloader is not ready yet."
            }

            is BlockingReason.WarmupMissing -> {
                "Warmup controller is not ready yet."
            }

            is BlockingReason.RepositoryNotReady -> {
                "Repository is not ready yet."
            }

            is BlockingReason.OnDeviceServicesStarting -> {
                "Process-scoped on-device services are still starting."
            }

            is BlockingReason.AppServicesStarting -> {
                "Process-scoped services are still starting."
            }

            is BlockingReason.None -> null
        }
    }

    private fun showSpinnerFor(reason: BlockingReason): Boolean {
        return when (reason) {
            is BlockingReason.None -> false
            is BlockingReason.StartupFailed -> false
            is BlockingReason.RepositoryMissingAfterReady -> false
            else -> true
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
}