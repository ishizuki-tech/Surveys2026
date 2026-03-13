/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Startup Coordinator)
 *  ---------------------------------------------------------------------
 *  File: StartupCoordinator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.content.Context
import android.os.SystemClock
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.config.InstalledSurveyConfigStore
import com.negi.surveys.config.ModelDownloadSpec
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.config.resolveModelDownloadSpec
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController
import java.io.File
import kotlinx.coroutines.delay

/**
 * Coordinates startup preparation that does not belong in the ViewModel itself.
 *
 * Responsibilities:
 * - Wait for the installed process config.
 * - Recover the config from assets when needed.
 * - Resolve startup mode + model requirements.
 * - Build process-scoped repository / warmup / downloader services.
 */
internal class StartupCoordinator(
    appContext: Context,
) {
    private val app = appContext.applicationContext

    /**
     * Prepares the startup inputs needed by the root ViewModel.
     *
     * Branch contract:
     * - Non-on-device:
     *   - no model requirement
     * - On-device + remote-capable:
     *   - startup may use downloader
     *   - existing local model is still surfaced for eager warmup wiring
     * - On-device + local-only:
     *   - startup requires an existing local model
     * - On-device + neither remote nor local:
     *   - fail with ModelUnavailable
     */
    suspend fun prepareStartup(
        repoModeProvider: () -> AppProcessServices.RepoMode,
        modelWiring: StartupModelWiring,
    ): StartupPreparationResult {
        val t0 = SystemClock.elapsedRealtime()
        SafeLog.i(TAG, "Config: waiting for installed config (Application-owned)")

        val installed =
            waitForInstalledConfigOrRecoverOrNull(
                deadlineAt = t0 + INSTALLED_CONFIG_WAIT_MS,
            )

        if (installed == null) {
            val dt = SystemClock.elapsedRealtime() - t0
            SafeLog.e(TAG, "Config: missing after recovery attempt dt=${dt}ms")
            return StartupPreparationResult.Failed(
                safeReason = "InstalledConfigMissing",
                repoMode = repoModeProvider(),
                modelSpec = null,
                modelSpecKey = null,
            )
        }

        val dt = SystemClock.elapsedRealtime() - t0
        SafeLog.i(TAG, "Config: ready in ${dt}ms")

        val modelSpec = installed.resolveModelDownloadSpec()
        val modelSpecKey = modelWiring.buildModelSpecKey(modelSpec)
        val repoMode = repoModeProvider()
        val onDeviceEnabled = repoMode == AppProcessServices.RepoMode.ON_DEVICE

        /**
         * Always probe the configured local model for on-device mode.
         *
         * Why:
         * - A remote-capable startup may still already have a usable local artifact.
         * - Surfacing it here allows eager warmup wiring without waiting for downloader state.
         */
        val initialLocalModelFile =
            if (onDeviceEnabled) {
                modelWiring.resolveExistingLocalModelFileOrNull(modelSpec)
            } else {
                null
            }

        val remoteCapable = hasRemoteDownloadCapability(modelSpec, modelSpecKey)
        val localPresent = initialLocalModelFile != null

        SafeLog.i(
            TAG,
            "Config: startup branch onDevice=$onDeviceEnabled remoteCapable=$remoteCapable localPresent=$localPresent",
        )

        if (onDeviceEnabled && !remoteCapable && !localPresent) {
            SafeLog.e(
                TAG,
                "Config: on-device model unavailable (no remote-capable downloader and no local model)",
            )
            return StartupPreparationResult.Failed(
                safeReason = "ModelUnavailable",
                repoMode = repoMode,
                modelSpec = modelSpec,
                modelSpecKey = null,
            )
        }

        return StartupPreparationResult.Ready(
            prepared = StartupPreparedConfig(
                installedConfig = installed,
                modelSpec = modelSpec,
                modelSpecKey = modelSpecKey,
                repoMode = repoMode,
                onDeviceEnabled = onDeviceEnabled,
                initialLocalModelFile = initialLocalModelFile,
            ),
        )
    }

    /**
     * Builds the process-scoped startup services for the prepared config.
     *
     * Notes:
     * - Downloader enable/disable is explicit.
     * - A missing spec means "disable downloader now", not "reuse previous spec".
     * - This prevents stale remote-download state from surviving a switch to local-only mode.
     */
    fun buildServices(
        repoMode: AppProcessServices.RepoMode,
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ): StartupBuiltServices? {
        return runCatching {
            val repo = AppProcessServices.repository(app, repoMode)
            val warmup = AppProcessServices.warmupController(app, repoMode)

            val downloaderEnabled =
                repoMode == AppProcessServices.RepoMode.ON_DEVICE &&
                        hasRemoteDownloadCapability(modelSpec, modelSpecKey)

            val downloader =
                if (downloaderEnabled) {
                    AppProcessServices.modelDownloader(
                        context = app,
                        spec = requireNotNull(modelSpec),
                    )
                } else {
                    AppProcessServices.clearModelDownloader()
                    null
                }

            SafeLog.i(
                TAG,
                "Services: built mode=$repoMode downloaderEnabled=$downloaderEnabled",
            )

            StartupBuiltServices(
                repository = repo,
                warmup = warmup,
                downloader = downloader,
            )
        }.onFailure { t ->
            SafeLog.e(
                TAG,
                "Services: build failed type=${t::class.java.simpleName}",
            )
        }.getOrNull()
    }

    /**
     * Returns true when startup has enough information to create a downloader.
     */
    private fun hasRemoteDownloadCapability(
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ): Boolean {
        return modelSpec != null && modelSpecKey != null
    }

    /**
     * Polls the installed-config store until a deadline is reached and then
     * performs a best-effort recovery from assets if needed.
     */
    private suspend fun waitForInstalledConfigOrRecoverOrNull(
        deadlineAt: Long,
    ): SurveyConfig? {
        var installed: SurveyConfig? = null

        while (SystemClock.elapsedRealtime() < deadlineAt && installed == null) {
            installed = InstalledSurveyConfigStore.getOrNull()
            if (installed != null) break
            delay(INSTALLED_CONFIG_POLL_MS)
        }

        if (installed != null) return installed

        SafeLog.w(TAG, "Config: install not observed; attempting best-effort recovery")
        return recoverInstalledConfigBestEffort()
    }

    /**
     * Rebuilds the process config from the bundled asset as a last-resort recovery path.
     */
    private fun recoverInstalledConfigBestEffort(): SurveyConfig? {
        return runCatching {
            SurveyConfigLoader.installProcessConfigFromAssetsValidated(
                context = app,
                fileName = CONFIG_ASSET_NAME,
            )
        }.onFailure { t ->
            SafeLog.e(
                TAG,
                "Config: recovery install failed type=${t::class.java.simpleName}",
            )
        }.getOrNull()
    }

    /**
     * Result of startup preparation before process services are activated.
     */
    sealed interface StartupPreparationResult {
        data class Ready(
            val prepared: StartupPreparedConfig,
        ) : StartupPreparationResult

        data class Failed(
            val safeReason: String,
            val repoMode: AppProcessServices.RepoMode,
            val modelSpec: ModelDownloadSpec?,
            val modelSpecKey: ModelSpecKey?,
        ) : StartupPreparationResult
    }

    /**
     * Prepared startup inputs derived from process config + startup policy.
     *
     * Notes:
     * - [initialLocalModelFile] may be present even when [modelSpecKey] is also present.
     * - This allows eager warmup wiring from an already-available local artifact.
     */
    data class StartupPreparedConfig(
        val installedConfig: SurveyConfig,
        val modelSpec: ModelDownloadSpec?,
        val modelSpecKey: ModelSpecKey?,
        val repoMode: AppProcessServices.RepoMode,
        val onDeviceEnabled: Boolean,
        val initialLocalModelFile: File?,
    )

    /**
     * Built process-scoped startup services.
     */
    internal data class StartupBuiltServices(
        val repository: ChatValidation.RepositoryI,
        val warmup: WarmupController,
        val downloader: ModelDownloadController?,
    )

    companion object {
        private const val TAG = "StartupCoordinator"
        private const val CONFIG_ASSET_NAME = "survey.yaml"
        private const val INSTALLED_CONFIG_WAIT_MS: Long = 1_500L
        private const val INSTALLED_CONFIG_POLL_MS: Long = 25L
    }
}