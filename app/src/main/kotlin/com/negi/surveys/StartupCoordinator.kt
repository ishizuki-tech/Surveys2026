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
 * - Prefer a ready local model over remote download when both are available.
 * - Build process-scoped repository / warmup / downloader services.
 */
internal class StartupCoordinator(
    appContext: Context,
) {
    private val app = appContext.applicationContext

    /**
     * Prepares the startup inputs needed by the root ViewModel.
     *
     * Startup selection policy:
     * - If a usable local model already exists, prefer local-ready startup.
     * - Otherwise, if remote download is possible, use downloader startup.
     * - Otherwise, fail as model unavailable.
     *
     * Important:
     * - [modelSpecKey] in the returned prepared config represents the selected
     *   startup path, not merely "remote is theoretically possible".
     * - When local-ready startup wins, [modelSpecKey] is intentionally null so
     *   downstream code does not create a downloader for this boot path.
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

        val declaredModelSpec = installed.resolveModelDownloadSpec()
        val declaredRemoteModelSpecKey = modelWiring.buildModelSpecKey(declaredModelSpec)
        val repoMode = repoModeProvider()
        val onDeviceEnabled = repoMode == AppProcessServices.RepoMode.ON_DEVICE

        val initialLocalModelFile =
            if (onDeviceEnabled) {
                modelWiring.resolveExistingLocalModelFileOrNull(declaredModelSpec)
            } else {
                null
            }

        val remoteDownloadEligible =
            onDeviceEnabled && declaredRemoteModelSpecKey != null

        val preferLocalReadyStartup =
            onDeviceEnabled && initialLocalModelFile != null

        val selectedModelSpecKey =
            if (preferLocalReadyStartup) {
                null
            } else {
                declaredRemoteModelSpecKey
            }

        SafeLog.i(
            TAG,
            "Config: startup branch " +
                    "onDevice=$onDeviceEnabled " +
                    "remoteCapable=$remoteDownloadEligible " +
                    "localPresent=${initialLocalModelFile != null} " +
                    "preferLocalReady=$preferLocalReadyStartup",
        )

        if (onDeviceEnabled && !preferLocalReadyStartup && !remoteDownloadEligible) {
            SafeLog.e(
                TAG,
                "Config: on-device model unavailable (no usable local model and no remote download path)",
            )
            return StartupPreparationResult.Failed(
                safeReason = "ModelUnavailable",
                repoMode = repoMode,
                modelSpec = declaredModelSpec,
                modelSpecKey = null,
            )
        }

        return StartupPreparationResult.Ready(
            prepared = StartupPreparedConfig(
                installedConfig = installed,
                modelSpec = declaredModelSpec,
                modelSpecKey = selectedModelSpecKey,
                repoMode = repoMode,
                onDeviceEnabled = onDeviceEnabled,
                initialLocalModelFile = initialLocalModelFile,
                startupModelSource = when {
                    !onDeviceEnabled -> StartupModelSource.NONE
                    preferLocalReadyStartup -> StartupModelSource.LOCAL_READY
                    remoteDownloadEligible -> StartupModelSource.REMOTE_DOWNLOAD
                    else -> StartupModelSource.NONE
                },
            ),
        )
    }

    /**
     * Builds the process-scoped startup services for the prepared config.
     *
     * Notes:
     * - Downloader enable/disable is explicit.
     * - A missing [modelSpecKey] here means "do not create downloader for this startup path".
     * - Local-ready startup intentionally passes null [modelSpecKey] to keep the
     *   boot path downloader-free even when remote download would also be possible.
     */
    fun buildServices(
        repoMode: AppProcessServices.RepoMode,
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ): StartupBuiltServices? {
        return runCatching {
            val repo = AppProcessServices.repository(app, repoMode)
            val warmup = AppProcessServices.warmupController(app, repoMode)

            val downloader =
                if (
                    repoMode == AppProcessServices.RepoMode.ON_DEVICE &&
                    modelSpec != null &&
                    modelSpecKey != null
                ) {
                    AppProcessServices.modelDownloader(app, spec = modelSpec)
                } else {
                    AppProcessServices.clearModelDownloader()
                    null
                }

            SafeLog.i(
                TAG,
                "Services: built mode=$repoMode downloaderEnabled=${downloader != null}",
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
     * Selected startup model source.
     */
    enum class StartupModelSource {
        NONE,
        LOCAL_READY,
        REMOTE_DOWNLOAD,
    }

    /**
     * Prepared startup inputs derived from process config + startup policy.
     */
    data class StartupPreparedConfig(
        val installedConfig: SurveyConfig,
        val modelSpec: ModelDownloadSpec?,
        val modelSpecKey: ModelSpecKey?,
        val repoMode: AppProcessServices.RepoMode,
        val onDeviceEnabled: Boolean,
        val initialLocalModelFile: File?,
        val startupModelSource: StartupModelSource,
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