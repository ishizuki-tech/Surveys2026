/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Startup UI State)
 *  ---------------------------------------------------------------------
 *  File: AppStartupUiState.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.StartupConfigState
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController

/**
 * Root startup UI state exposed to Compose.
 */
data class AppStartupUiState(
    val configState: StartupConfigState = StartupConfigState.Loading,
    val modelSpec: ModelDownloadSpec? = null,
    val modelSpecKey: ModelSpecKey? = null,
    val repoMode: AppProcessServices.RepoMode = AppProcessServices.RepoMode.ON_DEVICE,
    val onDeviceEnabled: Boolean = true,
    val servicesReady: Boolean = false,
    val repository: ChatValidation.RepositoryI? = null,
    val warmup: WarmupController? = null,
    val modelDownloader: ModelDownloadController? = null,
    val modelState: ModelDownloadController.ModelState =
        ModelDownloadController.ModelState.Idle(elapsedMs = 0L),
    val prefetchState: WarmupController.PrefetchState = WarmupController.PrefetchState.Idle,
    val compileState: WarmupController.CompileState = WarmupController.CompileState.Idle,
)