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
 * - Wait for the installed process config prepared by Application bootstrap.
 * - Resolve startup mode + model requirements.
 * - Prefer a ready local model over remote download when both are available.
 * - Build process-scoped repository / warmup / downloader services.
 *
 * Non-responsibilities:
 * - Installing or recovering the process config from assets.
 * - Owning retry policy.
 * - Mutating root UI state.
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
        val repoMode = repoModeProvider()
        val t0 = SystemClock.elapsedRealtime()
        SafeLog.i(TAG, "Config: waiting for installed config (Application-owned)")

        val installed =
            waitForInstalledConfigOrNull(
                deadlineAt = t0 + INSTALLED_CONFIG_WAIT_MS,
            )

        if (installed == null) {
            val dt = SystemClock.elapsedRealtime() - t0
            SafeLog.e(TAG, "Config: missing before deadline dt=${dt}ms")
            return StartupPreparationResult.Failed(
                safeReason = "InstalledConfigMissing",
                repoMode = repoMode,
                modelSpec = null,
                modelSpecKey = null,
            )
        }

        val dt = SystemClock.elapsedRealtime() - t0
        SafeLog.i(TAG, "Config: ready in ${dt}ms")

        val declaredModelSpec = installed.resolveModelDownloadSpec()
        val declaredRemoteModelSpecKey = modelWiring.buildModelSpecKey(declaredModelSpec)
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
                remoteDownloadEligible = remoteDownloadEligible,
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
     * - Partial build failures clear the startup graph before returning failure.
     */
    fun buildServices(
        repoMode: AppProcessServices.RepoMode,
        modelSpec: ModelDownloadSpec?,
        modelSpecKey: ModelSpecKey?,
    ): StartupBuildServicesResult {
        val repo =
            runCatching {
                AppProcessServices.repository(app, repoMode)
            }.onFailure { t ->
                SafeLog.e(
                    TAG,
                    "Services: repository init failed type=${t::class.java.simpleName}",
                )
            }.getOrNull() ?: return buildFailure("RepoInitFailed")

        val warmup =
            runCatching {
                AppProcessServices.warmupController(app, repoMode)
            }.onFailure { t ->
                SafeLog.e(
                    TAG,
                    "Services: warmup init failed type=${t::class.java.simpleName}",
                )
            }.getOrNull() ?: return buildFailure("WarmupInitFailed")

        val downloader =
            if (
                repoMode == AppProcessServices.RepoMode.ON_DEVICE &&
                modelSpec != null &&
                modelSpecKey != null
            ) {
                runCatching {
                    AppProcessServices.modelDownloader(app, spec = modelSpec)
                }.onFailure { t ->
                    SafeLog.e(
                        TAG,
                        "Services: downloader init failed type=${t::class.java.simpleName}",
                    )
                }.getOrNull() ?: return buildFailure("DownloaderInitFailed")
            } else {
                AppProcessServices.clearModelDownloader()
                null
            }

        SafeLog.i(
            TAG,
            "Services: built mode=$repoMode downloaderEnabled=${downloader != null}",
        )

        return StartupBuildServicesResult.Ready(
            services =
                StartupBuiltServices(
                    repository = repo,
                    warmup = warmup,
                    downloader = downloader,
                ),
        )
    }

    /**
     * Polls the installed-config store until a deadline is reached.
     *
     * Notes:
     * - Config installation remains Application-owned.
     * - This coordinator intentionally does not re-install from assets.
     */
    private suspend fun waitForInstalledConfigOrNull(
        deadlineAt: Long,
    ): SurveyConfig? {
        var installed: SurveyConfig? = null

        while (SystemClock.elapsedRealtime() < deadlineAt && installed == null) {
            installed = InstalledSurveyConfigStore.getOrNull()
            if (installed != null) break
            delay(INSTALLED_CONFIG_POLL_MS)
        }

        if (installed == null) {
            SafeLog.w(TAG, "Config: install not observed before deadline")
        }

        return installed
    }

    private fun buildFailure(
        safeReason: String,
    ): StartupBuildServicesResult.Failed {
        AppProcessServices.clearStartupGraphForRebuild(
            reason = safeReason,
            clearWarmupInputs = false,
            clearDownloader = true,
            clearRepository = true,
            clearWarmup = true,
        )
        return StartupBuildServicesResult.Failed(safeReason = safeReason)
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
     * Result of building the process-scoped startup services.
     */
    sealed interface StartupBuildServicesResult {
        data class Ready(
            val services: StartupBuiltServices,
        ) : StartupBuildServicesResult

        data class Failed(
            val safeReason: String,
        ) : StartupBuildServicesResult
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
        val remoteDownloadEligible: Boolean,
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
        private const val INSTALLED_CONFIG_WAIT_MS: Long = 1_500L
        private const val INSTALLED_CONFIG_POLL_MS: Long = 25L
    }
}
